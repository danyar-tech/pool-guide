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
)

/**
 * Traces a shot for a given cue ball position and stick direction: cushion rebounds (angle in = angle out),
 * first ball contact, object ball direction along the line of centres, cue ball tangent line, pockets.
 */
class Predictor(private val cfg: PlannerConfig) {

    private class Trace(val points: List<Vec>, val hit: Ball?, val pocket: Int, val endDir: Vec)

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
            after, scratch, Math.toDegrees(cut), power)
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
            val tNext = min(tBall, tRail)
            // pocket: ball centre passes inside the pocket mouth before the next event
            var tPocket = Double.MAX_VALUE
            var pocketIdx = -1
            val capture = max(tb.pocketRadius * 0.95, r * 1.1)
            tb.pockets.forEachIndexed { i, pk ->
                val m = pk.pos - pos
                val tc = clampD(m.dot(d), 0.0, tNext)
                val closest = pos + d * tc
                if (closest.dist(pk.pos) < capture) {
                    // entry point into the capture circle
                    val off = (pk.pos - pos)
                    val along = off.dot(d)
                    val perp2 = off.dot(off) - along * along
                    val tin = max(0.0, along - sqrt(max(0.0, capture * capture - perp2)))
                    if (tin < tPocket) { tPocket = tin; pocketIdx = i }
                }
            }
            val t = minOf(tBall, tRail, tPocket)
            if (t >= remaining) {
                pts += pos + d * remaining
                return Trace(pts, null, -1, d)
            }
            pos += d * t
            pts += pos
            remaining -= t
            when {
                t == tPocket -> return Trace(pts, null, pocketIdx, d)
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
