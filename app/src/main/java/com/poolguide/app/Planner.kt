package com.poolguide.app

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

enum class Group { ANY, SOLIDS, STRIPES }
enum class PowerCurve { SPEED, DISTANCE }
enum class ShotType { DIRECT, BANK, KICK }

data class PlannerConfig(
    val group: Group = Group.ANY,
    val allowBank: Boolean = true,
    val allowKick: Boolean = true,
    val maxCutDeg: Double = 82.0,
    /** How far the cue ball rolls at 100% power, measured in table lengths. Game specific. */
    val maxPowerTables: Double = 5.0,
    /** Extra roll the object ball should still have when it reaches the pocket, in table lengths. */
    val firmnessTables: Double = 0.2,
    val powerCurve: PowerCurve = PowerCurve.SPEED,
    val railRestitution: Double = 0.75,
    val ballRestitution: Double = 0.93,
    /** Ball id the player picked by hand, or -1 for automatic. */
    val forcedTargetId: Int = -1,
    /** Pocket index the player picked by hand, or -1 for automatic. */
    val forcedPocket: Int = -1,
)

data class Shot(
    val type: ShotType,
    val targetId: Int,
    val pocketIndex: Int,
    /** Cue ball path: start, optional rail contact, ghost ball position. */
    val cuePath: List<Vec>,
    /** Object ball path: start, optional rail contact, pocket. */
    val objectPath: List<Vec>,
    val ghost: Vec,
    /** Point to aim the cue stick through (ghost ball centre, or the rail point for kicks). */
    val aimPoint: Vec,
    val cutDeg: Double,
    /** Required power as a fraction of max power (can exceed 1 when the shot is out of range). */
    val power: Double,
    /** Higher is easier. Roughly the cue-aim error (radians) the shot tolerates. */
    val score: Double,
    /** Cue ball path after contact (stun / tangent line). */
    val tangentPath: List<Vec>,
    val scratchRisk: Boolean,
) {
    val powerPct: Int get() = (power * 100).toInt().coerceIn(1, 100)
    val difficulty: String
        get() = when {
            score > 0.020 -> "Easy"
            score > 0.009 -> "Medium"
            score > 0.004 -> "Hard"
            else -> "Very hard"
        }
}

/**
 * Finds and ranks pocketing shots using ghost-ball geometry, mirror-image banks and kicks,
 * clearance checks against every other ball, and a friction model for power.
 */
class Planner(private val cfg: PlannerConfig) {

    private enum class Rail { LEFT, RIGHT, TOP, BOTTOM }

    fun plan(scene: Scene): List<Shot> {
        val cue = scene.cue ?: return emptyList()
        val r = scene.ballRadius
        if (r <= 0.0) return emptyList()
        val targets = pickTargets(scene)
        val out = ArrayList<Shot>()
        for (t in targets) {
            scene.table.pockets.forEachIndexed { pi, pocket ->
                if (cfg.forcedPocket >= 0 && cfg.forcedPocket != pi) return@forEachIndexed
                direct(scene, cue, t, pi, pocket)?.let { out += it }
                if (cfg.allowBank) out += banks(scene, cue, t, pi, pocket)
                if (cfg.allowKick) out += kicks(scene, cue, t, pi, pocket)
            }
        }
        out.sortByDescending { it.score }
        return out.take(15)
    }

    // ---------------------------------------------------------------- targets

    fun pickTargets(scene: Scene): List<Ball> {
        val nonCue = scene.balls.filter { it.kind != BallKind.CUE }
        if (cfg.forcedTargetId >= 0) {
            return nonCue.filter { it.id == cfg.forcedTargetId }
        }
        val solids = nonCue.filter { it.kind == BallKind.SOLID }
        val stripes = nonCue.filter { it.kind == BallKind.STRIPE }
        val unknown = nonCue.filter { it.kind == BallKind.UNKNOWN }
        val eight = nonCue.filter { it.kind == BallKind.EIGHT }
        val group = when (cfg.group) {
            Group.SOLIDS -> solids
            Group.STRIPES -> stripes
            Group.ANY -> solids + stripes
        }
        val list = group + unknown
        return if (list.isEmpty()) eight else list
    }

    // ---------------------------------------------------------------- shot types

    private fun direct(scene: Scene, cue: Ball, t: Ball, pi: Int, pocket: Pocket): Shot? {
        val r = scene.ballRadius
        val objDir = (pocket.pos - t.pos).norm()
        val ghost = t.pos - objDir * (2 * r)
        if (!scene.insidePlayable(ghost, r * 0.25)) return null
        val incoming = ghost - cue.pos
        if (incoming.len() < r * 0.5) return null
        val cut = Vec.angle(incoming, objDir)
        if (cut > Math.toRadians(cfg.maxCutDeg)) return null
        if (!clear(scene, cue.pos, ghost, setOf(cue.id, t.id))) return null
        if (!clear(scene, t.pos, pocket.pos, setOf(cue.id, t.id))) return null
        val accept = pocketAcceptance(scene, pocket, objDir) ?: return null

        val dObj = t.pos.dist(pocket.pos)
        val dCue = incoming.len()
        val vObj = objectStartSpeed(scene, listOf(dObj))
        val power = cueStartPower(scene, listOf(dCue), vObj, cut)
        val tangent = tangentPath(scene, ghost, incoming.norm(), objDir)
        val scratch = scratchOnPath(scene, tangent, cut)
        val score = scoreOf(r, dCue, dObj, cut, accept) *
            (if (scratch) 0.6 else 1.0) * kindWeight(t) * powerWeight(power)
        return Shot(ShotType.DIRECT, t.id, pi, listOf(cue.pos, ghost), listOf(t.pos, pocket.pos),
            ghost, ghost, Math.toDegrees(cut), power, score, tangent, scratch)
    }

    private fun banks(scene: Scene, cue: Ball, t: Ball, pi: Int, pocket: Pocket): List<Shot> {
        val r = scene.ballRadius
        val res = ArrayList<Shot>()
        for (rail in Rail.values()) {
            if (pocketOnRail(scene, pocket, rail)) continue
            val mirrored = mirror(scene, pocket.pos, rail)
            val bank = railHit(scene, t.pos, mirrored, rail) ?: continue
            if (nearPocket(scene, bank, r * 2.5)) continue
            val objDir = (bank - t.pos).norm()
            val ghost = t.pos - objDir * (2 * r)
            if (!scene.insidePlayable(ghost, r * 0.25)) continue
            val incoming = ghost - cue.pos
            if (incoming.len() < r * 0.5) continue
            val cut = Vec.angle(incoming, objDir)
            if (cut > Math.toRadians(cfg.maxCutDeg - 10)) continue
            val ex = setOf(cue.id, t.id)
            if (!clear(scene, cue.pos, ghost, ex)) continue
            if (!clear(scene, t.pos, bank, ex)) continue
            if (!clear(scene, bank, pocket.pos, ex)) continue
            val finalDir = (pocket.pos - bank).norm()
            val accept = pocketAcceptance(scene, pocket, finalDir) ?: continue

            val d1 = t.pos.dist(bank)
            val d2 = bank.dist(pocket.pos)
            val dCue = incoming.len()
            val vObj = objectStartSpeed(scene, listOf(d1, d2))
            val power = cueStartPower(scene, listOf(dCue), vObj, cut)
            val tangent = tangentPath(scene, ghost, incoming.norm(), objDir)
            val scratch = scratchOnPath(scene, tangent, cut)
            // Banks are less predictable (rail rebound, throw), so the effective distance is inflated.
            val score = scoreOf(r, dCue, (d1 + d2) * 1.35, cut, accept) * 0.55 *
                (if (scratch) 0.6 else 1.0) * kindWeight(t) * powerWeight(power)
            res += Shot(ShotType.BANK, t.id, pi, listOf(cue.pos, ghost), listOf(t.pos, bank, pocket.pos),
                ghost, ghost, Math.toDegrees(cut), power, score, tangent, scratch)
        }
        return res
    }

    private fun kicks(scene: Scene, cue: Ball, t: Ball, pi: Int, pocket: Pocket): List<Shot> {
        val r = scene.ballRadius
        val res = ArrayList<Shot>()
        val objDir = (pocket.pos - t.pos).norm()
        val ghost = t.pos - objDir * (2 * r)
        if (!scene.insidePlayable(ghost, r * 0.25)) return res
        val ex = setOf(cue.id, t.id)
        if (!clear(scene, t.pos, pocket.pos, ex)) return res
        val accept = pocketAcceptance(scene, pocket, objDir) ?: return res
        for (rail in Rail.values()) {
            val mirrored = mirror(scene, ghost, rail)
            val hit = railHit(scene, cue.pos, mirrored, rail) ?: continue
            if (nearPocket(scene, hit, r * 2.5)) continue
            val incoming = ghost - hit
            if (incoming.len() < r * 2) continue
            val cut = Vec.angle(incoming, objDir)
            if (cut > Math.toRadians(cfg.maxCutDeg - 15)) continue
            if (!clear(scene, cue.pos, hit, ex)) continue
            if (!clear(scene, hit, ghost, ex)) continue
            val d1 = cue.pos.dist(hit)
            val d2 = hit.dist(ghost)
            val dObj = t.pos.dist(pocket.pos)
            val vObj = objectStartSpeed(scene, listOf(dObj))
            val power = cueStartPower(scene, listOf(d1, d2), vObj, cut)
            val tangent = tangentPath(scene, ghost, incoming.norm(), objDir)
            val scratch = scratchOnPath(scene, tangent, cut)
            val score = scoreOf(r, (d1 + d2) * 1.4, dObj, cut, accept) * 0.45 *
                (if (scratch) 0.6 else 1.0) * kindWeight(t) * powerWeight(power)
            res += Shot(ShotType.KICK, t.id, pi, listOf(cue.pos, hit, ghost), listOf(t.pos, pocket.pos),
                ghost, hit, Math.toDegrees(cut), power, score, tangent, scratch)
        }
        return res
    }

    // ---------------------------------------------------------------- geometry helpers

    /** True when a ball of radius r travelling from a to b touches no other ball. */
    fun clear(scene: Scene, a: Vec, b: Vec, exclude: Set<Int>): Boolean {
        val minD = 2 * scene.ballRadius * 0.97
        for (o in scene.balls) {
            if (o.id in exclude) continue
            if (Vec.distPointSegment(o.pos, a, b) < minD) return false
        }
        return true
    }

    private fun railCoord(scene: Scene, rail: Rail) = when (rail) {
        Rail.LEFT -> scene.minX()
        Rail.RIGHT -> scene.maxX()
        Rail.TOP -> scene.minY()
        Rail.BOTTOM -> scene.maxY()
    }

    private fun mirror(scene: Scene, p: Vec, rail: Rail): Vec {
        val c = railCoord(scene, rail)
        return when (rail) {
            Rail.LEFT, Rail.RIGHT -> Vec(2 * c - p.x, p.y)
            Rail.TOP, Rail.BOTTOM -> Vec(p.x, 2 * c - p.y)
        }
    }

    private fun railHit(scene: Scene, a: Vec, mirroredB: Vec, rail: Rail): Vec? {
        val c = railCoord(scene, rail)
        val d = mirroredB - a
        val t = when (rail) {
            Rail.LEFT, Rail.RIGHT -> if (absD(d.x) < 1e-9) return null else (c - a.x) / d.x
            Rail.TOP, Rail.BOTTOM -> if (absD(d.y) < 1e-9) return null else (c - a.y) / d.y
        }
        if (t <= 0.02 || t >= 0.98) return null
        val p = a + d * t
        val ok = when (rail) {
            Rail.LEFT, Rail.RIGHT -> p.y > scene.minY() && p.y < scene.maxY()
            Rail.TOP, Rail.BOTTOM -> p.x > scene.minX() && p.x < scene.maxX()
        }
        return if (ok) p else null
    }

    private fun pocketOnRail(scene: Scene, pocket: Pocket, rail: Rail): Boolean {
        val tb = scene.table
        val tol = scene.ballRadius * 3 + tb.pocketRadius
        return when (rail) {
            Rail.LEFT -> absD(pocket.pos.x - tb.left) < tol
            Rail.RIGHT -> absD(pocket.pos.x - tb.right) < tol
            Rail.TOP -> absD(pocket.pos.y - tb.top) < tol
            Rail.BOTTOM -> absD(pocket.pos.y - tb.bottom) < tol
        }
    }

    private fun nearPocket(scene: Scene, p: Vec, d: Double) =
        scene.table.pockets.any { it.pos.dist(p) < d + scene.table.pocketRadius }

    /** Effective half-width of the pocket opening for this approach direction, or null if it can't go in. */
    fun pocketAcceptance(scene: Scene, pocket: Pocket, approach: Vec): Double? {
        val tb = scene.table
        val c = tb.center
        val normal = if (pocket.side) {
            if (tb.horizontal) Vec(0.0, sign(pocket.pos.y - c.y)) else Vec(sign(pocket.pos.x - c.x), 0.0)
        } else {
            Vec(sign(pocket.pos.x - c.x), sign(pocket.pos.y - c.y)).norm()
        }
        val dev = Vec.angle(approach, normal)
        val factor = if (pocket.side) {
            if (dev > Math.toRadians(62.0)) return null
            cos(dev)
        } else {
            if (dev > Math.toRadians(80.0)) return null
            cos(max(0.0, dev - PI / 4)) // anything within 45 deg of the diagonal is full width
        }
        val half = max(tb.pocketRadius, scene.ballRadius * 1.2) * 0.85 * factor
        return if (half < scene.ballRadius * 0.12) null else half
    }

    /** Cue ball direction after contact for a stun shot (90 degree rule), traced to the first rail and one rebound. */
    private fun tangentPath(scene: Scene, ghost: Vec, incoming: Vec, objDir: Vec): List<Vec> {
        val along = objDir * incoming.dot(objDir)
        val tan = (incoming - along)
        if (tan.len() < 0.08) return emptyList() // nearly straight: cue ball stops (stun)
        val dir = tan.norm()
        val hit = rayToRail(scene, ghost, dir) ?: return emptyList()
        val reflected = when {
            absD(hit.x - scene.minX()) < 1e-6 || absD(hit.x - scene.maxX()) < 1e-6 -> Vec(-dir.x, dir.y)
            else -> Vec(dir.x, -dir.y)
        }
        val leg = scene.table.shortSide * 0.35
        val second = hit + reflected * leg
        return listOf(ghost, hit, Vec(clampD(second.x, scene.minX(), scene.maxX()),
            clampD(second.y, scene.minY(), scene.maxY())))
    }

    private fun rayToRail(scene: Scene, o: Vec, d: Vec): Vec? {
        var best = Double.MAX_VALUE
        if (d.x > 1e-9) best = min(best, (scene.maxX() - o.x) / d.x)
        if (d.x < -1e-9) best = min(best, (scene.minX() - o.x) / d.x)
        if (d.y > 1e-9) best = min(best, (scene.maxY() - o.y) / d.y)
        if (d.y < -1e-9) best = min(best, (scene.minY() - o.y) / d.y)
        if (best == Double.MAX_VALUE || best < 0) return null
        return o + d * best
    }

    private fun scratchOnPath(scene: Scene, path: List<Vec>, cut: Double): Boolean {
        if (path.size < 2 || cut < Math.toRadians(4.0)) return false
        val rad = scene.table.pocketRadius + scene.ballRadius * 0.5
        return scene.table.pockets.any { Vec.distPointSegment(it.pos, path[0], path[1]) < rad }
    }

    // ---------------------------------------------------------------- scoring & power

    /**
     * Cue-aim error the shot tolerates (radians). The object ball may leave at an angle error of
     * atan(halfWidth / distance); a cue aim error e moves the contact point by dCue*e, which turns
     * the object ball by dCue*e / (2R cos(cut)).
     */
    private fun scoreOf(r: Double, dCue: Double, dObj: Double, cut: Double, halfWidth: Double): Double {
        val objTol = atan(halfWidth / max(dObj, r))
        val c = max(cos(cut), 0.08)
        return objTol * 2 * r * c / max(dCue, r)
    }

    private fun kindWeight(b: Ball) = if (b.kind == BallKind.UNKNOWN) 0.9 else 1.0

    private fun powerWeight(p: Double) = when {
        p > 1.0 -> 0.25
        p > 0.9 -> 0.75
        else -> 1.0
    }

    /** Friction model: deceleration a = 1, so v^2 = 2 * distance. Returns object ball start speed. */
    fun objectStartSpeed(scene: Scene, legs: List<Double>): Double {
        val extra = cfg.firmnessTables * scene.table.longSide
        // Work backwards from the pocket: last leg plus the extra roll, then each rail costs speed.
        var v2 = 2 * (legs.last() + extra)
        for (i in legs.size - 2 downTo 0) {
            v2 = v2 / sq(cfg.railRestitution) + 2 * legs[i]
        }
        return safeSqrt(v2)
    }

    fun cueStartPower(scene: Scene, cueLegs: List<Double>, vObj: Double, cut: Double): Double {
        val c = max(cos(cut), 0.12)
        val vContact = vObj / (c * cfg.ballRestitution)
        var v2 = sq(vContact) + 2 * cueLegs.last()
        for (i in cueLegs.size - 2 downTo 0) {
            v2 = v2 / sq(cfg.railRestitution) + 2 * cueLegs[i]
        }
        val vMax2 = 2 * cfg.maxPowerTables * scene.table.longSide
        return when (cfg.powerCurve) {
            PowerCurve.SPEED -> safeSqrt(v2 / vMax2)
            PowerCurve.DISTANCE -> v2 / vMax2
        }
    }
}
