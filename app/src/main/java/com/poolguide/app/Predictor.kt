package com.poolguide.app

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class Prediction(
    /** Cue ball: start, cushion contacts, then its centre at contact (or where it stops). */
    val cuePath: List<Vec>,
    val hitBallId: Int,
    /** Cue ball centre at the moment of contact (ghost ball), or null when nothing is hit. */
    val contact: Vec?,
    /** Object ball path after contact: start, cushion contacts, end. */
    val objectPath: List<Vec>,
    /** Pocket index the object ball drops into, or -1. */
    val objectPocket: Int,
    /** Ball the object ball runs into, or -1. */
    val objectHitsBallId: Int,
    /** Cue ball path after contact (stun / tangent line). */
    val cueAfterPath: List<Vec>,
    val scratch: Boolean,
    val cutDeg: Double,
    /** Suggested power fraction when the object ball is potted, otherwise -1. */
    val power: Double,
    /** The object ball is heading into a pocket but clips the jaw (rattles out). */
    val objectJaw: Boolean = false,
)

/**
 * Traces a shot for a given cue ball position and stick direction: cushion rebounds (angle in = angle out),
 * first ball contact, object ball direction along the line of centres, cue ball tangent line, pockets.
 */
class Predictor(private val cfg: PlannerConfig) {

    private class Trace(val points: List<Vec>, val hit: Ball?, val pocket: Int, val endDir: Vec, val jaw: Boolean = false)

    fun predict(scene: Scene, cueId: Int, start: Vec, dir: Vec): Prediction {
        val r = scene.ballRadius
        val d0 = dir.norm()
        val longSide = scene.table.longSide
        val cue = trace(scene, start, d0, setOf(cueId), maxRails = 4, maxLen = longSide * 4)
        if (cue.hit == null) {
            return Prediction(cue.points, -1, null, emptyList(), -1, -1, emptyList(), cue.pocket >= 0, 0.0, -1.0)
        }
        val contact = cue.points.last()
        val target = cue.hit
        val objDir = (target.pos - contact).norm()
        val incoming = cue.endDir
        val cut = Vec.angle(incoming, objDir)
        val obj = trace(scene, target.pos, objDir, setOf(cueId, target.id), maxRails = 3, maxLen = longSide * 3)

        // Stun shot: cue ball leaves along the tangent line.
        val tan = incoming - objDir * incoming.dot(objDir)
        var after: List<Vec> = emptyList()
        var scratch = false
        if (tan.len() > 0.05) {
            val t = trace(scene, contact, tan.norm(), setOf(cueId, target.id), maxRails = 1,
                maxLen = longSide * 0.6 * tan.len())
            after = t.points
            scratch = t.pocket >= 0
        }

        var power = -1.0
        if (obj.pocket >= 0) {
            val planner = Planner(cfg)
            val objLegs = legs(obj.points)
            val cueLegs = legs(cue.points)
            if (objLegs.isNotEmpty() && cueLegs.isNotEmpty()) {
                power = planner.cueStartPower(scene, cueLegs, planner.objectStartSpeed(scene, objLegs), cut)
            }
        }
        return Prediction(cue.points, target.id, contact, obj.points, obj.pocket, obj.hit?.id ?: -1,
            after, scratch, Math.toDegrees(cut), power, obj.jaw)
    }

    private fun legs(pts: List<Vec>) = (1 until pts.size).map { pts[it - 1].dist(pts[it]) }.filter { it > 1e-6 }

    private fun trace(scene: Scene, start: Vec, dir: Vec, ignore: Set<Int>, maxRails: Int, maxLen: Double): Trace {
        val r = scene.ballRadius
        val tb = scene.table
        val pts = arrayListOf(start)
        var pos = start
        var d = dir
        var remaining = maxLen
        var rails = 0
        while (true) {
            // first ball on the path
            var tBall = Double.MAX_VALUE
            var hit: Ball? = null
            for (b in scene.balls) {
                if (b.id in ignore) continue
                val m = pos - b.pos
                val bb = m.dot(d)
                val c = m.dot(m) - 4 * r * r
                if (c < 0) continue // already overlapping: ignore
                val disc = bb * bb - c
                if (disc < 0) continue
                val t = -bb - sqrt(disc)
                if (t > 1e-6 && t < tBall) { tBall = t; hit = b }
            }
            // cushion
            var tRail = Double.MAX_VALUE
            var vertical = false
            if (d.x > 1e-9) { val t = (scene.maxX() - pos.x) / d.x; if (t < tRail) { tRail = t; vertical = true } }
            if (d.x < -1e-9) { val t = (scene.minX() - pos.x) / d.x; if (t < tRail) { tRail = t; vertical = true } }
            if (d.y > 1e-9) { val t = (scene.maxY() - pos.y) / d.y; if (t < tRail) { tRail = t; vertical = false } }
            if (d.y < -1e-9) { val t = (scene.minY() - pos.y) / d.y; if (t < tRail) { tRail = t; vertical = false } }
            tRail = max(tRail, 0.0)
            // Pockets: the centre line must pass between the two jaws with a radius of clearance.
            var tPocket = Double.MAX_VALUE
            var pocketIdx = -1
            var tJaw = Double.MAX_VALUE
            for (i in tb.pockets.indices) {
                val (ja, jb) = PocketGeom.jaws(tb, i, r, cfg.pocketScale)
                val cl = PocketGeom.clearance(pos, d, ja, jb, r) ?: continue
                val tm = PocketGeom.distanceToMouth(pos, d, ja, jb)
                if (tm <= 0 || tm == Double.MAX_VALUE) continue
                // the mouth must be right where this path reaches the cushion line, not past another cushion
                if (tm > tRail + 4.5 * r) continue
                if (cl >= -0.02 * r) {
                    if (tm < tPocket) { tPocket = tm; pocketIdx = i }
                } else {
                    // between the jaws but too tight: it hits a jaw just before the mouth
                    val tj = max(0.0, tm - r)
                    if (tj < tJaw) tJaw = tj
                }
            }
            if (pocketIdx >= 0 && tPocket < tBall) {
                // Going in: the cushion line is open here, so ignore the rail crossing at the mouth.
                if (tPocket >= remaining) { pts += pos + d * remaining; return Trace(pts, null, -1, d) }
                pts += pos + d * (tPocket + r * 1.3)
                return Trace(pts, null, pocketIdx, d)
            }
            if (tJaw < tBall && tJaw <= tRail + r * 2 && tJaw < remaining) {
                pts += pos + d * min(tJaw, tRail)
                return Trace(pts, null, -1, d, jaw = true)
            }
            val t = minOf(tBall, tRail)
            if (t >= remaining) {
                pts += pos + d * remaining
                return Trace(pts, null, -1, d)
            }
            pos += d * t
            pts += pos
            remaining -= t
            when {
                t == tBall -> return Trace(pts, hit, -1, d)
                else -> {
                    rails++
                    if (rails > maxRails) return Trace(pts, null, -1, d)
                    d = if (vertical) Vec(-d.x, d.y) else Vec(d.x, -d.y)
                    // nudge off the cushion so the next step does not re-hit it
                    pos += d * 1e-3
                }
            }
        }
    }
}
