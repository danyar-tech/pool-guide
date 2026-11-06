package com.poolguide.app

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 2D vector in screen pixels. Pure Kotlin so the planner can be unit tested on the JVM. */
data class Vec(val x: Double, val y: Double) {
    operator fun plus(o: Vec) = Vec(x + o.x, y + o.y)
    operator fun minus(o: Vec) = Vec(x - o.x, y - o.y)
    operator fun times(s: Double) = Vec(x * s, y * s)
    operator fun unaryMinus() = Vec(-x, -y)
    fun dot(o: Vec) = x * o.x + y * o.y
    fun cross(o: Vec) = x * o.y - y * o.x
    fun len() = hypot(x, y)
    fun dist(o: Vec) = hypot(x - o.x, y - o.y)
    fun norm(): Vec {
        val l = len()
        return if (l < 1e-9) Vec(0.0, 0.0) else Vec(x / l, y / l)
    }
    fun perp() = Vec(-y, x)

    companion object {
        /** Angle in radians between two vectors (0..PI). */
        fun angle(a: Vec, b: Vec): Double {
            val la = a.len()
            val lb = b.len()
            if (la < 1e-9 || lb < 1e-9) return 0.0
            return acos((a.dot(b) / (la * lb)).coerceIn(-1.0, 1.0))
        }

        /** Shortest distance from point p to the segment a-b. */
        fun distPointSegment(p: Vec, a: Vec, b: Vec): Double {
            val ab = b - a
            val l2 = ab.dot(ab)
            if (l2 < 1e-12) return p.dist(a)
            val t = ((p - a).dot(ab) / l2).coerceIn(0.0, 1.0)
            return p.dist(a + ab * t)
        }
    }
}

enum class BallKind { CUE, EIGHT, SOLID, STRIPE, UNKNOWN }

data class Ball(
    val id: Int,
    var pos: Vec,
    var kind: BallKind = BallKind.UNKNOWN,
    /** Dominant colour hue 0..180 (OpenCV scale) or -1 when unknown. */
    var hue: Float = -1f,
)

data class Pocket(var pos: Vec, val side: Boolean)

/**
 * Axis-aligned playing area. [left]/[top]/[right]/[bottom] are the inner cushion edges
 * (where a ball's surface stops), in screen pixels.
 */
data class TableGeom(
    var left: Double,
    var top: Double,
    var right: Double,
    var bottom: Double,
    val pockets: MutableList<Pocket> = mutableListOf(),
    var pocketRadius: Double = 0.0,
) {
    val width get() = right - left
    val height get() = bottom - top
    val longSide get() = max(width, height)
    val shortSide get() = min(width, height)
    val horizontal get() = width >= height
    val center get() = Vec((left + right) / 2, (top + bottom) / 2)

    fun copyDeep() = TableGeom(left, top, right, bottom,
        pockets.map { Pocket(it.pos, it.side) }.toMutableList(), pocketRadius)

    /** Put the six pockets at their standard positions for the current rectangle. */
    fun resetPockets() {
        pockets.clear()
        pockets += Pocket(Vec(left, top), false)
        pockets += Pocket(Vec(right, top), false)
        pockets += Pocket(Vec(left, bottom), false)
        pockets += Pocket(Vec(right, bottom), false)
        if (horizontal) {
            pockets += Pocket(Vec((left + right) / 2, top), true)
            pockets += Pocket(Vec((left + right) / 2, bottom), true)
        } else {
            pockets += Pocket(Vec(left, (top + bottom) / 2), true)
            pockets += Pocket(Vec(right, (top + bottom) / 2), true)
        }
        if (pocketRadius <= 0.0) pocketRadius = shortSide * 0.045
    }

    fun normalize() {
        if (left > right) { val t = left; left = right; right = t }
        if (top > bottom) { val t = top; top = bottom; bottom = t }
    }

    fun encode(): String {
        val p = pockets.joinToString("|") { "${it.pos.x},${it.pos.y},${if (it.side) 1 else 0}" }
        return "$left,$top,$right,$bottom,$pocketRadius;$p"
    }

    companion object {
        fun decode(s: String?): TableGeom? {
            if (s.isNullOrBlank()) return null
            return try {
                val parts = s.split(";")
                val r = parts[0].split(",").map { it.toDouble() }
                val t = TableGeom(r[0], r[1], r[2], r[3], mutableListOf(), r[4])
                if (parts.size > 1 && parts[1].isNotBlank()) {
                    parts[1].split("|").forEach {
                        val q = it.split(",")
                        t.pockets += Pocket(Vec(q[0].toDouble(), q[1].toDouble()), q[2] == "1")
                    }
                }
                if (t.pockets.size != 6) t.resetPockets()
                t
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** Everything the planner needs about the current table state. */
data class Scene(
    val table: TableGeom,
    val balls: List<Ball>,
    val ballRadius: Double,
) {
    val cue: Ball? get() = balls.firstOrNull { it.kind == BallKind.CUE }

    /** Area a ball centre can occupy. */
    fun minX() = table.left + ballRadius
    fun maxX() = table.right - ballRadius
    fun minY() = table.top + ballRadius
    fun maxY() = table.bottom - ballRadius

    fun insidePlayable(p: Vec, tol: Double): Boolean =
        p.x >= minX() - tol && p.x <= maxX() + tol && p.y >= minY() - tol && p.y <= maxY() + tol
}

fun clampD(v: Double, lo: Double, hi: Double) = if (v < lo) lo else if (v > hi) hi else v
fun sq(v: Double) = v * v
fun safeSqrt(v: Double) = sqrt(max(0.0, v))
fun absD(v: Double) = abs(v)
