package com.poolguide.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

enum class EditTool(val label: String) {
    MOVE("Move"), ADD("Add"), DELETE("Delete"), CUE("Set cue"), KIND("Type")
}

/**
 * Full-screen overlay. Draws the detected table, balls and the recommended shot.
 * In edit mode it becomes touchable so the player can fix detection by hand.
 */
@SuppressLint("ViewConstructor")
class AimView(
    ctx: Context,
    private val state: GuideState,
    private val onBallsEdited: () -> Unit,
    private val onTableEdited: () -> Unit,
    private val onSelectionChanged: () -> Unit,
) : View(ctx) {

    var editMode = false
        set(v) { field = v; invalidate() }
    var tool = EditTool.MOVE
        set(v) { field = v; invalidate() }
    var guidesVisible = true
        set(v) { field = v; invalidate() }

    private val dp = resources.displayMetrics.density
    private val loc = IntArray(2)

    private fun stroke(color: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; this.color = color; strokeWidth = w * dp; strokeCap = Paint.Cap.ROUND
    }
    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }

    private val tablePaint = stroke(Color.argb(200, 0, 229, 255), 1.5f)
    private val pocketPaint = stroke(Color.argb(220, 255, 214, 0), 1.5f)
    private val pocketHi = stroke(Color.rgb(255, 214, 0), 4f)
    private val cuePathPaint = stroke(Color.WHITE, 3f)
    private val cuePathShadow = stroke(Color.argb(160, 0, 0, 0), 6f)
    private val aimExtPaint = stroke(Color.argb(170, 255, 255, 255), 1.5f).apply {
        pathEffect = DashPathEffect(floatArrayOf(10 * dp, 8 * dp), 0f)
    }
    private val objPaint = stroke(Color.rgb(255, 214, 0), 3f)
    private val objShadow = stroke(Color.argb(160, 0, 0, 0), 6f)
    private val ghostPaint = stroke(Color.WHITE, 2f).apply {
        pathEffect = DashPathEffect(floatArrayOf(6 * dp, 5 * dp), 0f)
    }
    private val tangentPaint = stroke(Color.argb(200, 0, 229, 255), 2f).apply {
        pathEffect = DashPathEffect(floatArrayOf(4 * dp, 6 * dp), 0f)
    }
    private val altPaint = stroke(Color.argb(90, 255, 255, 255), 1.5f)
    private val handlePaint = fill(Color.argb(230, 0, 229, 255))
    private val handleRing = stroke(Color.WHITE, 2f)
    private val dimPaint = fill(Color.argb(60, 0, 0, 0))
    private val cardBg = fill(Color.argb(215, 18, 22, 28))
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14 * dp }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 255, 255, 255); textSize = 11 * dp }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 11 * dp; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val barBg = fill(Color.argb(120, 255, 255, 255))
    private val barFill = fill(Color.rgb(255, 214, 0))
    private val barFillHot = fill(Color.rgb(255, 82, 82))

    private fun kindColor(k: BallKind) = when (k) {
        BallKind.CUE -> Color.WHITE
        BallKind.EIGHT -> Color.rgb(160, 160, 160)
        BallKind.SOLID -> Color.rgb(255, 145, 0)
        BallKind.STRIPE -> Color.rgb(186, 104, 255)
        BallKind.UNKNOWN -> Color.rgb(120, 220, 120)
    }
    private fun kindLabel(k: BallKind) = when (k) {
        BallKind.CUE -> "C"; BallKind.EIGHT -> "8"; BallKind.SOLID -> "S"; BallKind.STRIPE -> "T"; BallKind.UNKNOWN -> "?"
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        if (editMode) canvas.drawRect(0f, 0f, width.toFloat() + loc[0], height.toFloat() + loc[1], dimPaint)
        if (guidesVisible || editMode) drawScene(canvas)
        canvas.restore()
        if (guidesVisible || editMode) drawCard(canvas)
    }

    private fun drawScene(c: Canvas) {
        val t = state.table ?: return
        val r = state.radius.toFloat().coerceAtLeast(4f)
        c.drawRect(t.left.toFloat(), t.top.toFloat(), t.right.toFloat(), t.bottom.toFloat(), tablePaint)

        val shot = if (state.moving && !editMode) null else state.currentShot
        t.pockets.forEachIndexed { i, p ->
            val hi = shot?.pocketIndex == i || state.forcedPocket == i
            c.drawCircle(p.pos.x.toFloat(), p.pos.y.toFloat(), t.pocketRadius.toFloat(), if (hi) pocketHi else pocketPaint)
        }

        // Faint alternatives
        if (!editMode && shot != null && Prefs(context).showAlternatives) {
            state.shots.take(4).forEachIndexed { i, s ->
                if (i != state.shotIndex) polyline(c, s.objectPath, altPaint)
            }
        }

        val forcedId = state.forcedTargetId()
        for (b in state.balls) {
            val p = kindColor(b.kind)
            val ring = stroke(p, if (b.id == shot?.targetId || b.id == forcedId) 3f else 1.5f)
            c.drawCircle(b.pos.x.toFloat(), b.pos.y.toFloat(), r, ring)
            if (editMode || b.id == forcedId) {
                c.drawText(kindLabel(b.kind), b.pos.x.toFloat(), (b.pos.y - r - 4 * dp).toFloat(), labelText)
            }
            if (b.id == forcedId) {
                c.drawCircle(b.pos.x.toFloat(), b.pos.y.toFloat(), r + 5 * dp, stroke(Color.rgb(255, 214, 0), 1.5f))
            }
        }

        if (shot != null && !editMode) {
            // Extended aim line through the aim point: lines up with the game's own aiming guide.
            val start = shot.cuePath.first()
            val aim = shot.aimPoint
            val dir = (aim - start).norm()
            val far = aim + dir * t.longSide * 0.35
            c.drawLine(aim.x.toFloat(), aim.y.toFloat(), far.x.toFloat(), far.y.toFloat(), aimExtPaint)

            polyline(c, shot.cuePath, cuePathShadow); polyline(c, shot.cuePath, cuePathPaint)
            polyline(c, shot.objectPath, objShadow); polyline(c, shot.objectPath, objPaint)
            if (shot.tangentPath.size >= 2) polyline(c, shot.tangentPath, tangentPaint)
            c.drawCircle(shot.ghost.x.toFloat(), shot.ghost.y.toFloat(), r, ghostPaint)
            c.drawCircle(shot.ghost.x.toFloat(), shot.ghost.y.toFloat(), 2.5f * dp, fill(Color.WHITE))
            // Rail contact markers
            if (shot.cuePath.size == 3) marker(c, shot.cuePath[1])
            if (shot.objectPath.size == 3) marker(c, shot.objectPath[1])
        }

        if (editMode) {
            for (h in handles(t)) {
                c.drawCircle(h.x.toFloat(), h.y.toFloat(), 9 * dp, handlePaint)
                c.drawCircle(h.x.toFloat(), h.y.toFloat(), 9 * dp, handleRing)
            }
        }
    }

    private fun marker(c: Canvas, p: Vec) {
        c.drawCircle(p.x.toFloat(), p.y.toFloat(), 5 * dp, fill(Color.rgb(255, 82, 82)))
    }

    private fun polyline(c: Canvas, pts: List<Vec>, paint: Paint) {
        if (pts.size < 2) return
        val path = Path()
        path.moveTo(pts[0].x.toFloat(), pts[0].y.toFloat())
        for (i in 1 until pts.size) path.lineTo(pts[i].x.toFloat(), pts[i].y.toFloat())
        c.drawPath(path, paint)
    }

    /** Info card in the top-centre of the screen. */
    private fun drawCard(c: Canvas) {
        val lines = ArrayList<String>()
        var power = -1.0
        val shot = state.currentShot
        when {
            editMode -> {
                lines += "EDIT · ${tool.label}"
                lines += when (tool) {
                    EditTool.MOVE -> "Drag balls, pockets or the blue table handles. Tap a ball/pocket to force it."
                    EditTool.ADD -> "Tap the table to add a ball"
                    EditTool.DELETE -> "Tap a ball to remove it"
                    EditTool.CUE -> "Tap the cue ball"
                    EditTool.KIND -> "Tap a ball to cycle: solid → stripe → 8 → unknown"
                }
            }
            state.moving -> lines += "Balls moving…"
            shot != null -> {
                val type = when (shot.type) {
                    ShotType.DIRECT -> "Direct"; ShotType.BANK -> "Bank"; ShotType.KICK -> "Kick"
                }
                lines += "Shot ${state.shotIndex + 1}/${state.shots.size} · $type · ${shot.difficulty}"
                val extra = if (shot.power > 1.0) "  (beyond max!)" else ""
                lines += "Power ${shot.powerPct}%$extra · Cut ${shot.cutDeg.toInt()}°" +
                    if (shot.scratchRisk) " · scratch risk" else ""
                power = shot.power
            }
            else -> lines += state.message
        }
        val pad = 10 * dp
        val w = min(width - 20 * dp, max(lines.maxOf { textPaint.measureText(it) }, 220 * dp) + pad * 2)
        val lineH = 19 * dp
        val h = pad * 2 + lines.size * lineH + if (power >= 0) 14 * dp else 0f
        val left = (width - w) / 2
        val top = 8 * dp
        c.drawRoundRect(RectF(left, top, left + w, top + h), 12 * dp, 12 * dp, cardBg)
        lines.forEachIndexed { i, s ->
            c.drawText(s, left + pad, top + pad + (i + 1) * lineH - 5 * dp, if (i == 0) textPaint else smallText)
        }
        if (power >= 0) {
            val by = top + pad + lines.size * lineH + 2 * dp
            val bw = w - pad * 2
            c.drawRoundRect(RectF(left + pad, by, left + pad + bw, by + 7 * dp), 4 * dp, 4 * dp, barBg)
            val f = power.coerceIn(0.0, 1.0).toFloat()
            c.drawRoundRect(RectF(left + pad, by, left + pad + bw * f, by + 7 * dp), 4 * dp, 4 * dp,
                if (power > 0.9) barFillHot else barFill)
        }
    }

    // ------------------------------------------------------------------ editing

    private fun handles(t: TableGeom) = listOf(
        Vec(t.left, t.top), Vec(t.right, t.top), Vec(t.left, t.bottom), Vec(t.right, t.bottom),
        Vec((t.left + t.right) / 2, t.top), Vec((t.left + t.right) / 2, t.bottom),
        Vec(t.left, (t.top + t.bottom) / 2), Vec(t.right, (t.top + t.bottom) / 2),
    )

    private sealed class Drag {
        data class Handle(val i: Int) : Drag()
        data class PocketD(val i: Int) : Drag()
        data class BallD(val id: Int) : Drag()
    }

    private var drag: Drag? = null
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!editMode) return false
        getLocationOnScreen(loc)
        val p = Vec((e.x + loc[0]).toDouble(), (e.y + loc[1]).toDouble())
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; moved = false
                drag = if (tool == EditTool.MOVE) hitTest(p) else null
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.hypot(e.x - downX, e.y - downY) > 6 * dp) moved = true
                if (moved) applyDrag(p)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) tap(p)
                else when (drag) {
                    is Drag.BallD -> onBallsEdited()
                    is Drag.Handle, is Drag.PocketD -> onTableEdited()
                    null -> {}
                }
                drag = null
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> { drag = null; return true }
        }
        return true
    }

    private fun hitTest(p: Vec): Drag? {
        val t = state.table
        val touch = 26 * dp
        if (t != null) {
            handles(t).forEachIndexed { i, h -> if (h.dist(p) < touch) return Drag.Handle(i) }
        }
        nearestBall(p)?.let { return Drag.BallD(it.id) }
        if (t != null) {
            t.pockets.forEachIndexed { i, pk -> if (pk.pos.dist(p) < max(t.pocketRadius, touch.toDouble())) return Drag.PocketD(i) }
        }
        return null
    }

    private fun nearestBall(p: Vec): Ball? {
        val reach = max(state.radius * 1.3, 22.0 * dp)
        return state.balls.filter { it.pos.dist(p) < reach }.minByOrNull { it.pos.dist(p) }
    }

    private fun applyDrag(p: Vec) {
        val t = state.table
        when (val d = drag) {
            is Drag.Handle -> if (t != null) {
                when (d.i) {
                    0 -> { t.left = p.x; t.top = p.y }
                    1 -> { t.right = p.x; t.top = p.y }
                    2 -> { t.left = p.x; t.bottom = p.y }
                    3 -> { t.right = p.x; t.bottom = p.y }
                    4 -> t.top = p.y
                    5 -> t.bottom = p.y
                    6 -> t.left = p.x
                    7 -> t.right = p.x
                }
                t.normalize()
                t.resetPockets()
            }
            is Drag.PocketD -> if (t != null) t.pockets[d.i].pos = p
            is Drag.BallD -> state.balls.firstOrNull { it.id == d.id }?.pos = p
            null -> {}
        }
        invalidate()
    }

    private fun tap(p: Vec) {
        val t = state.table
        val b = nearestBall(p)
        when (tool) {
            EditTool.MOVE -> {
                if (b != null && b.kind != BallKind.CUE) {
                    state.forcedTargetPos = if (state.forcedTargetId() == b.id) null else b.pos
                    onSelectionChanged()
                } else if (t != null) {
                    val i = t.pockets.indexOfFirst { it.pos.dist(p) < max(t.pocketRadius * 1.3, 24.0 * dp) }
                    if (i >= 0) {
                        state.forcedPocket = if (state.forcedPocket == i) -1 else i
                        onSelectionChanged()
                    }
                }
            }
            EditTool.ADD -> {
                if (t != null && p.x > t.left && p.x < t.right && p.y > t.top && p.y < t.bottom) {
                    state.balls.add(Ball(state.nextId++, p, BallKind.UNKNOWN))
                    if (state.radius <= 0) state.radius = t.shortSide * 0.035
                    onBallsEdited()
                }
            }
            EditTool.DELETE -> if (b != null) { state.balls.remove(b); onBallsEdited() }
            EditTool.CUE -> if (b != null) {
                state.balls.forEach { if (it.kind == BallKind.CUE) it.kind = BallKind.UNKNOWN }
                b.kind = BallKind.CUE
                onBallsEdited()
            }
            EditTool.KIND -> if (b != null) {
                b.kind = when (b.kind) {
                    BallKind.SOLID -> BallKind.STRIPE
                    BallKind.STRIPE -> BallKind.EIGHT
                    BallKind.EIGHT -> BallKind.UNKNOWN
                    BallKind.UNKNOWN, BallKind.CUE -> BallKind.SOLID
                }
                onBallsEdited()
            }
        }
        invalidate()
    }
}
