package com.poolguide.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/** Small draggable panel with the in-game controls. */
@SuppressLint("ViewConstructor", "SetTextI18n")
class ControlPanel(
    ctx: Context,
    private val wm: WindowManager,
    private val params: WindowManager.LayoutParams,
) : LinearLayout(ctx) {

    private val dp = resources.displayMetrics.density
    val status = TextView(ctx)
    private val handle = TextView(ctx)
    private val normalRow = LinearLayout(ctx)
    private val editRow = LinearLayout(ctx)
    private val rows = LinearLayout(ctx)
    private var collapsed = false

    val btnLive = button("Live")
    val btnScan = button("Scan")
    val btnNext = button("Next")
    val btnGroup = button("Any")
    val btnEdit = button("Edit")
    val btnHide = button("Hide")
    val btnStop = button("✕")

    val toolButtons = EditTool.values().associateWith { button(it.label) }
    val btnRedetect = button("Find table")
    val btnClear = button("Clear pick")
    val btnDone = button("Done")

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(Color.argb(225, 18, 22, 28)); cornerRadius = 14 * dp
            setStroke((1 * dp).toInt(), Color.argb(90, 255, 255, 255))
        }
        val pad = (6 * dp).toInt()
        setPadding(pad, pad, pad, pad)

        val top = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        handle.text = "⠿ Pool Guide"
        handle.setTextColor(Color.rgb(255, 214, 0))
        handle.textSize = 13f
        handle.typeface = Typeface.DEFAULT_BOLD
        handle.setPadding(pad, pad, pad * 2, pad)
        status.setTextColor(Color.argb(220, 255, 255, 255))
        status.textSize = 12f
        status.maxLines = 1
        top.addView(handle)
        top.addView(status)
        addView(top)

        normalRow.orientation = HORIZONTAL
        listOf(btnLive, btnScan, btnNext, btnGroup, btnEdit, btnHide, btnStop).forEach { normalRow.addView(it) }
        editRow.orientation = HORIZONTAL
        toolButtons.values.forEach { editRow.addView(it) }
        listOf(btnRedetect, btnClear, btnDone).forEach { editRow.addView(it) }
        editRow.visibility = GONE

        rows.orientation = VERTICAL
        rows.addView(normalRow)
        rows.addView(editRow)
        val scroll = HorizontalScrollView(ctx)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.addView(rows)
        addView(scroll)

        setupDrag()
    }

    private fun button(text: String) = TextView(context).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        val h = (10 * dp).toInt(); val v = (7 * dp).toInt()
        setPadding(h, v, h, v)
        background = GradientDrawable().apply { setColor(Color.argb(70, 255, 255, 255)); cornerRadius = 9 * dp }
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            val m = (3 * dp).toInt(); setMargins(m, m, m, m)
        }
        isClickable = true
    }

    fun setActive(b: TextView, active: Boolean) {
        (b.background as GradientDrawable).setColor(
            if (active) Color.argb(230, 255, 214, 0) else Color.argb(70, 255, 255, 255))
        b.setTextColor(if (active) Color.BLACK else Color.WHITE)
    }

    fun showEdit(edit: Boolean) {
        normalRow.visibility = if (edit) GONE else VISIBLE
        editRow.visibility = if (edit) VISIBLE else GONE
        if (edit && collapsed) toggleCollapsed()
    }

    private fun toggleCollapsed() {
        collapsed = !collapsed
        (rows.parent as View).visibility = if (collapsed) GONE else VISIBLE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        var sx = 0f; var sy = 0f; var px = 0; var py = 0; var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; px = params.x; py = params.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 8 * dp) moved = true
                    if (moved) {
                        params.x = (px + dx).toInt(); params.y = (py + dy).toInt()
                        runCatching { wm.updateViewLayout(this, params) }
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) toggleCollapsed()
            }
            true
        }
    }
}
