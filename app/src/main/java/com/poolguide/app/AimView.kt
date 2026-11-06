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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class EditTool(val label: String) {
    MOVE("Move"), ADD("Add"), DELETE("Delete"), CUE("Mark cue"), KIND("Type"), PLACE_CUE("Place cue"), AIM("Aim")
}

/**
 * Full-screen overlay. Draws the table, balls and either the suggested shot (Best mode) or the traced
 * shot for the current stick direction (Predict mode). In edit mode it becomes touchable for manual fixes.
 */
@SuppressLint("ViewConstructor")
class AimView(
    ctx: Context,
    private val state: GuideState,
    private val onBallsEdited: () -> Unit,
    private val onTableEdited: () -> Unit,
    private val onSelectionChanged: () -> Unit,
    private val onRadiusEdited: (Double) -> Unit,
    private val onCueEdited: () -> Unit,
    private val onAimEdited: () -> Unit,
) : View(ctx) {

    var editMode = false
        set(v) { field = v; invalidate() }
    var tool = EditTool.MOVE
        set(v) { field = v; invalidate() }
    var guidesVisible = true
        set(v) { field = v; invalidate() }

    private val prefs = Prefs(ctx)
    private val dp = resources.displayMetrics.density
    private val loc = IntArray(2)
    /** Centre of the ball-size ring shown in edit mode (screen px). */
    private var sizer: Vec? = null

    private fun stroke(color: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; this.color = color; strokeWidth = w * dp; strokeCap = Paint.Cap.ROUND
    }
    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
    private fun dashed(p: Paint, on: Float, off: Float) = p.apply { pathEffect = DashPathEffect(floatArrayOf(on * dp, off * dp), 0f) }

    private val yellow = Color.rgb(255, 214, 0)
    private val cyan = Color.argb(220, 0, 229, 255)
    private val green = Color.rgb(80, 230, 120)
    private val red = Color.rgb(255, 82, 82)
    private val tablePaint = stroke(Color.argb(200, 0, 229, 255), 1.5f)
    private val tableEditPaint = stroke(Color.argb(255, 0, 229, 255), 2.5f)
    private val pocketPaint = stroke(Color.argb(220, 255, 214, 0), 1.5f)
    private val pocketHi = stroke(yellow, 4f)
    private val pocketPot = stroke(green, 5f)
    private val cuePathPaint = stroke(Color.WHITE, 3f)
    private val shadowPaint = stroke(Color.argb(160, 0, 0, 0), 6f)
    private val aimExtPaint = dashed(stroke(Color.argb(170, 255, 255, 255), 1.5f), 10f, 8f)
    private val objPaint = stroke(yellow, 3f)
    private val objPotPaint = stroke(green, 3.5f)
    private val objMissPaint = stroke(Color.argb(230, 255, 140, 60), 3f)
    private val ghostPaint = dashed(stroke(Color.WHITE, 2f), 6f, 5f)
    private val tangentPaint = dashed(stroke(cyan, 2f), 4f, 6f)
    private val altPaint = stroke(Color.argb(90, 255, 255, 255), 1.5f)
    private val handlePaint = fill(Color.argb(230, 0, 229, 255))
    private val handleRing = stroke(Color.WHITE, 2f)
    private val sizerPaint = stroke(Color.rgb(255, 145, 0), 2.5f)
    private val sizerHandle = fill(Color.rgb(255, 145, 0))
    private val dimPaint = fill(Color.argb(60, 0, 0, 0))
    private val cardBg = fill(Color.argb(215, 18, 22, 28))
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14 * dp }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 255, 255, 255); textSize = 11 * dp }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 11 * dp; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val barBg = fill(Color.argb(120, 255, 255, 255))
    private val barFill = fill(yellow)
    private val barFillHot = fill(red)

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

    private fun radius() = if (state.radius > 0) state.radius else (state.table?.shortSide ?: 600.0) * 0.035

    /** Cue ball position used for prediction (manual pin or detected). */
    fun cuePos(): Vec? = if (prefs.cueManual) state.manualCue ?: state.balls.firstOrNull { it.kind == BallKind.CUE }?.pos
        else state.balls.firstOrNull { it.kind == BallKind.CUE }?.pos

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
        val r = radius().toFloat()
        c.drawRect(t.left.toFloat(), t.top.toFloat(), t.right.toFloat(), t.bottom.toFloat(), if (editMode) tableEditPaint else tablePaint)

        val predict = prefs.predictMode
        val shot = if (predict || (state.moving && !editMode)) null else state.currentShot
        val pred = if (predict && !state.moving) state.prediction else null
        t.pockets.forEachIndexed { i, p ->
            val paint = when {
                pred != null && pred.objectPocket == i -> pocketPot
                shot?.pocketIndex == i || state.forcedPocket == i -> pocketHi
                else -> pocketPaint
            }
            c.drawCircle(p.pos.x.toFloat(), p.pos.y.toFloat(), t.pocketRadius.toFloat(), paint)
        }

        if (!editMode && shot != null && prefs.showAlternatives) {
            state.shots.take(4).forEachIndexed { i, s -> if (i != state.shotIndex) polyline(c, s.objectPath, altPaint) }
        }

        val forcedId = state.forcedTargetId()
        for (b in state.balls) {
            if (prefs.cueManual && b.kind == BallKind.CUE && state.manualCue != null) continue
            val hi = b.id == shot?.targetId || b.id == forcedId || b.id == pred?.hitBallId
            c.drawCircle(b.pos.x.toFloat(), b.pos.y.toFloat(), r, stroke(kindColor(b.kind), if (hi) 3f else 1.5f))
            if (editMode || b.id == forcedId) {
                c.drawText(kindLabel(b.kind), b.pos.x.toFloat(), (b.pos.y - r - 4 * dp).toFloat(), labelText)
            }
            if (b.id == forcedId) c.drawCircle(b.pos.x.toFloat(), b.pos.y.toFloat(), r + 5 * dp, stroke(yellow, 1.5f))
        }
        // Hand-placed cue ball
        if (prefs.cueManual) state.manualCue?.let { m ->
            c.drawCircle(m.x.toFloat(), m.y.toFloat(), r, stroke(Color.WHITE, 3f))
            c.drawLine((m.x - r * 0.5).toFloat(), m.y.toFloat(), (m.x + r * 0.5).toFloat(), m.y.toFloat(), stroke(Color.WHITE, 1.5f))
            c.drawLine(m.x.toFloat(), (m.y - r * 0.5).toFloat(), m.x.toFloat(), (m.y + r * 0.5).toFloat(), stroke(Color.WHITE, 1.5f))
            c.drawText("CUE", m.x.toFloat(), (m.y - r - 4 * dp).toFloat(), labelText)
        }

        if (!editMode && shot != null) drawShot(c, shot, t, r)
        if (pred != null && (!editMode || tool == EditTool.AIM)) drawPrediction(c, pred, r)
        if (editMode && tool == EditTool.AIM) {
            // show the raw direction while aiming
            val cue = cuePos()
            val ang = state.aimAngle(prefs.stickManual)
            if (cue != null && ang != null) {
                val far = cue + Vec(cos(ang), sin(ang)) * t.longSide
                c.drawLine(cue.x.toFloat(), cue.y.toFloat(), far.x.toFloat(), far.y.toFloat(), aimExtPaint)
            }
        }

        if (editMode) {
            for (h in handles(t)) {
                c.drawCircle(h.x.toFloat(), h.y.toFloat(), 10 * dp, handlePaint)
                c.drawCircle(h.x.toFloat(), h.y.toFloat(), 10 * dp, handleRing)
            }
            // ball size ring: drag its centre onto a ball, drag the orange dot to match the ball's edge
            val sc = sizer ?: t.center.also { sizer = it }
            c.drawCircle(sc.x.toFloat(), sc.y.toFloat(), r, sizerPaint)
            c.drawCircle((sc.x + r).toFloat(), sc.y.toFloat(), 8 * dp, sizerHandle)
            c.drawCircle(sc.x.toFloat(), sc.y.toFloat(), 3 * dp, sizerHandle)
            c.drawText("ball size", sc.x.toFloat(), (sc.y + r + 14 * dp).toFloat(), labelText)
        }
    }

    private fun drawShot(c: Canvas, shot: Shot, t: TableGeom, r: Float) {
        val start = shot.cuePath.first()
        val aim = shot.aimPoint
        val dir = (aim - start).norm()
        val far = aim + dir * t.longSide * 0.35
        c.drawLine(aim.x.toFloat(), aim.y.toFloat(), far.x.toFloat(), far.y.toFloat(), aimExtPaint)
        polyline(c, shot.cuePath, shadowPaint); polyline(c, shot.cuePath, cuePathPaint)
        polyline(c, shot.objectPath, shadowPaint); polyline(c, shot.objectPath, objPaint)
        if (shot.tangentPath.size >= 2) polyline(c, shot.tangentPath, tangentPaint)
        c.drawCircle(shot.ghost.x.toFloat(), shot.ghost.y.toFloat(), r, ghostPaint)
        c.drawCircle(shot.ghost.x.toFloat(), shot.ghost.y.toFloat(), 2.5f * dp, fill(Color.WHITE))
        if (shot.cuePath.size == 3) marker(c, shot.cuePath[1])
        if (shot.objectPath.size == 3) marker(c, shot.objectPath[1])
    }

    private fun drawPrediction(c: Canvas, p: Prediction, r: Float) {
        polyline(c, p.cuePath, shadowPaint); polyline(c, p.cuePath, cuePathPaint)
        for (i in 1 until p.cuePath.size - 1) marker(c, p.cuePath[i])
        p.contact?.let {
            c.drawCircle(it.x.toFloat(), it.y.toFloat(), r, ghostPaint)
            c.drawCircle(it.x.toFloat(), it.y.toFloat(), 2.5f * dp, fill(Color.WHITE))
        }
        if (p.objectPath.size >= 2) {
            polyline(c, p.objectPath, shadowPaint)
            polyline(c, p.objectPath, if (p.objectPocket >= 0) objPotPaint else objMissPaint)
            for (i in 1 until p.objectPath.size - 1) marker(c, p.objectPath[i])
        }
        if (p.cueAfterPath.size >= 2) polyline(c, p.cueAfterPath, tangentPaint)
        if (p.contact == null && p.cuePath.isNotEmpty()) {
            // nothing hit: show where the cue ball ends
            val e = p.cuePath.last()
            c.drawCircle(e.x.toFloat(), e.y.toFloat(), r, ghostPaint)
        }
    }

    private fun marker(c: Canvas, p: Vec) {
        c.drawCircle(p.x.toFloat(), p.y.toFloat(), 5 * dp, fill(red))
    }

    private fun polyline(c: Canvas, pts: List<Vec>, paint: Paint) {
        if (pts.size < 2) return
        val path = Path()
        path.moveTo(pts[0].x.toFloat(), pts[0].y.toFloat())
        for (i in 1 until pts.size) path.lineTo(pts[i].x.toFloat(), pts[i].y.toFloat())
        c.drawPath(path, paint)
    }

    private fun pocketName(i: Int): String {
        val t = state.table ?: return "pocket"
        val p = t.pockets.getOrNull(i) ?: return "pocket"
        val c = t.center
        val v = if (p.pos.y < c.y) "top" else "bottom"
        val h = if (p.pos.x < c.x) "left" else "right"
        return if (p.side) (if (t.horizontal) "$v middle" else "$h middle") else "$v $h"
    }

    /** Info card in the top-centre of the screen. */
    private fun drawCard(c: Canvas) {
        val lines = ArrayList<String>()
        var power = -1.0
        val shot = state.currentShot
        val pred = state.prediction
        when {
            editMode -> {
                lines += "EDIT · ${tool.label}"
                lines += when (tool) {
                    EditTool.MOVE -> "Drag blue dots to the cushion edges. Orange ring = ball size. Drag balls/pockets; tap one to force it."
                    EditTool.ADD -> "Tap the table to add a ball"
                    EditTool.DELETE -> "Tap a ball to remove it"
                    EditTool.CUE -> "Tap the ball that is the cue ball"
                    EditTool.KIND -> "Tap a ball to cycle: solid → stripe → 8 → unknown"
                    EditTool.PLACE_CUE -> "Tap or drag to place the cue ball by hand"
                    EditTool.AIM -> "Drag anywhere: the stick points from the cue ball toward your finger"
                }
            }
            state.table == null -> { lines += "No table yet"; lines += "Tap Edit and drag the blue dots to the table's cushions" }
            state.moving -> lines += "Balls moving…"
            prefs.predictMode -> {
                val src = when {
                    prefs.stickManual && state.manualAngle != null -> "manual"
                    state.detectedAngle != null -> if (state.detectedFromStick) "from cue stick" else "from aim line"
                    else -> null
                }
                if (pred == null) {
                    lines += "Predict"
                    lines += when {
                        cuePos() == null -> "Cue ball not found — Cue: Man, then Edit → Place cue"
                        src == null -> "Stick not found — switch Stick to Man and aim with Edit → Aim or ◀ ▶"
                        else -> state.message
                    }
                } else {
                    val head = when {
                        pred.contact == null && pred.scratch -> "Scratch — cue ball goes in!"
                        pred.contact == null -> "Misses every ball"
                        pred.objectPocket >= 0 && pred.scratch -> "Pots in ${pocketName(pred.objectPocket)} — but scratch!"
                        pred.objectPocket >= 0 -> "POTS in ${pocketName(pred.objectPocket)}"
                        pred.objectHitsBallId >= 0 -> "Misses — object ball hits another ball"
                        else -> "Misses — object ball hits the cushion"
                    }
                    lines += head
                    val sb = StringBuilder("Stick $src")
                    if (pred.contact != null) sb.append(" · Cut ${pred.cutDeg.toInt()}°")
                    if (pred.power >= 0) { sb.append(" · Power ${(pred.power * 100).toInt().coerceIn(1, 100)}%"); power = pred.power }
                    lines += sb.toString()
                }
            }
            shot != null -> {
                val type = when (shot.type) { ShotType.DIRECT -> "Direct"; ShotType.BANK -> "Bank"; ShotType.KICK -> "Kick" }
                lines += "Shot ${state.shotIndex + 1}/${state.shots.size} · $type · ${shot.difficulty}"
                val extra = if (shot.power > 1.0) "  (beyond max!)" else ""
                lines += "Power ${shot.powerPct}%$extra · Cut ${shot.cutDeg.toInt()}°" + if (shot.scratchRisk) " · scratch risk" else ""
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
            c.drawRoundRect(RectF(left + pad, by, left + pad + bw * f, by + 7 * dp), 4 * dp, 4 * dp, if (power > 0.9) barFillHot else barFill)
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
        object SizerMove : Drag()
        object SizerRadius : Drag()
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
                if (tool == EditTool.AIM) setAim(p)
                if (tool == EditTool.PLACE_CUE) { state.manualCue = p; invalidate() }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.hypot(e.x - downX, e.y - downY) > 6 * dp) moved = true
                when (tool) {
                    EditTool.AIM -> setAim(p)
                    EditTool.PLACE_CUE -> { state.manualCue = p; invalidate() }
                    else -> if (moved) applyDrag(p)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                when (tool) {
                    EditTool.AIM -> { setAim(p); onAimEdited() }
                    EditTool.PLACE_CUE -> { state.manualCue = p; onCueEdited() }
                    else -> if (!moved) tap(p) else when (drag) {
                        is Drag.BallD -> onBallsEdited()
                        is Drag.Handle, is Drag.PocketD -> onTableEdited()
                        is Drag.SizerRadius -> onRadiusEdited(state.radius)
                        is Drag.SizerMove, null -> {}
                    }
                }
                drag = null
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> { drag = null; return true }
        }
        return true
    }

    private fun setAim(p: Vec) {
        val cue = cuePos() ?: return
        if (p.dist(cue) < 8 * dp) return
        state.manualAngle = atan2(p.y - cue.y, p.x - cue.x)
        onAimEdited()
        invalidate()
    }

    private fun hitTest(p: Vec): Drag? {
        val t = state.table
        val touch = 26 * dp
        val r = radius()
        sizer?.let { sc ->
            if (Vec(sc.x + r, sc.y).dist(p) < touch) return Drag.SizerRadius
        }
        if (t != null) handles(t).forEachIndexed { i, h -> if (h.dist(p) < touch) return Drag.Handle(i) }
        nearestBall(p)?.let { return Drag.BallD(it.id) }
        sizer?.let { sc -> if (sc.dist(p) < max(r, touch.toDouble())) return Drag.SizerMove }
        if (t != null) t.pockets.forEachIndexed { i, pk -> if (pk.pos.dist(p) < max(t.pocketRadius, touch.toDouble())) return Drag.PocketD(i) }
        return null
    }

    private fun nearestBall(p: Vec): Ball? {
        val reach = max(radius() * 1.3, 22.0 * dp)
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
            is Drag.SizerMove -> sizer = p
            is Drag.SizerRadius -> sizer?.let { sc -> state.radius = max(4.0, p.x - sc.x) }
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
                    if (state.radius <= 0) state.radius = radius()
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
            EditTool.PLACE_CUE, EditTool.AIM -> {}
        }
        invalidate()
    }
}
