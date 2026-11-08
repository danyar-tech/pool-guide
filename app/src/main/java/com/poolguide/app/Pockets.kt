package com.poolguide.app

import kotlin.math.abs
import kotlin.math.min

/**
 * Pocket mouths. Each pocket has two cushion "jaws" (the nose points where the cushion ends) on the cushion
 * line. A ball only drops if its centre line passes between the jaws with at least one ball radius of
 * clearance from each, so the ball size decides which angles still go in. A corner pocket can't be hit
 * straight along the cushion line at the corner, and a side pocket closes up at shallow angles.
 */
object PocketGeom {
    /** Distance from the corner to each jaw along the cushion, in ball radii (before the user's scale). */
    const val CORNER_JAW = 3.0
    /** Half the side pocket opening, in ball radii (before the user's scale). */
    const val SIDE_JAW = 2.3

    fun jaws(t: TableGeom, i: Int, r: Double, scale: Double): Pair<Vec, Vec> {
        val p = t.pockets[i]
        val c = t.center
        return if (!p.side) {
            val right = p.pos.x > c.x; val bottom = p.pos.y > c.y
            val kx = if (right) t.right else t.left
            val ky = if (bottom) t.bottom else t.top
            val sx = if (right) 1.0 else -1.0
            val sy = if (bottom) 1.0 else -1.0
            val cw = CORNER_JAW * r * scale
            Vec(kx - sx * cw, ky) to Vec(kx, ky - sy * cw)
        } else {
            val sw = SIDE_JAW * r * scale
            if (t.horizontal) {
                val y = if (p.pos.y > c.y) t.bottom else t.top
                Vec(p.pos.x - sw, y) to Vec(p.pos.x + sw, y)
            } else {
                val x = if (p.pos.x > c.x) t.right else t.left
                Vec(x, p.pos.y - sw) to Vec(x, p.pos.y + sw)
            }
        }
    }

    /**
     * @param aim point on the mouth to send the ball's centre through
     * @param margin spare clearance (px) past the closer jaw: how far the line can be off and still drop
     * @param drop a point just inside the pocket, for drawing
     */
    data class Entry(val aim: Vec, val margin: Double, val drop: Vec)

    /**
     * Best straight entry from [start] through the mouth a-b. The line that keeps the most clearance from both
     * jaws is the bisector of the angle a-start-b, which meets a-b at the point dividing it as |SA| : |SB|.
     */
    fun entry(start: Vec, a: Vec, b: Vec, r: Double): Entry? {
        val sa = a.dist(start); val sb = b.dist(start)
        if (sa < 1e-6 || sb < 1e-6) return null
        val q = a + (b - a) * (sa / (sa + sb))
        val u = (q - start).norm()
        if (u.len() < 0.5) return null
        val ca = (a - start).cross(u); val cb = (b - start).cross(u)
        if (ca * cb > 0) return null // both jaws on one side: the mouth isn't ahead
        if ((q - start).dot(u) <= 0) return null
        val margin = min(abs(ca), abs(cb)) - r
        // A ball frozen on the cushion can still run down the rail into a corner, so allow ~0 margin.
        if (margin < -0.02 * r) return null
        return Entry(q, margin, q + u * (r * 1.3))
    }

    /** Clearance (px, minus one radius) for a ball travelling from [start] along [dir] through the mouth a-b, or null if it does not pass between the jaws. */
    fun clearance(start: Vec, dir: Vec, a: Vec, b: Vec, r: Double): Double? {
        val ca = (a - start).cross(dir); val cb = (b - start).cross(dir)
        if (ca * cb > 0) return null
        if ((a - start).dot(dir) <= 0 && (b - start).dot(dir) <= 0) return null
        return min(abs(ca), abs(cb)) - r
    }

    /** Distance along [dir] from [start] to the mouth line a-b (where the ball centre crosses it). */
    fun distanceToMouth(start: Vec, dir: Vec, a: Vec, b: Vec): Double {
        val n = (b - a).perp().norm()
        val denom = dir.dot(n)
        if (abs(denom) < 1e-9) return Double.MAX_VALUE
        return (a - start).dot(n) / denom
    }
}
