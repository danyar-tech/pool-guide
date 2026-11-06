package com.poolguide.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

@SuppressLint("SetTextI18n")
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var overlayStatus: TextView
    private lateinit var startBtn: Button
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.statusBarColor = Color.rgb(14, 59, 46)
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val ok = Settings.canDrawOverlays(this)
        overlayStatus.text = if (ok) "✓ Overlay permission granted" else "✗ Overlay permission needed"
        overlayStatus.setTextColor(if (ok) Color.rgb(120, 220, 120) else Color.rgb(255, 120, 120))
        startBtn.text = if (OverlayService.running) "Restart guide" else "Start guide"
    }

    // ------------------------------------------------------------------ actions

    private fun requestOverlay() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow \"display over other apps\" first", Toast.LENGTH_LONG).show()
            requestOverlay()
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 42)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 42) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen capture is needed to see the balls", Toast.LENGTH_LONG).show()
            return
        }
        val i = Intent(this, OverlayService::class.java)
            .putExtra(OverlayService.EXTRA_CODE, resultCode)
            .putExtra(OverlayService.EXTRA_DATA, data)
        startForegroundService(i)
        Toast.makeText(this, "Guide started — open your game", Toast.LENGTH_SHORT).show()
        moveTaskToBack(true)
    }

    private fun stop() {
        startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
        window.decorView.postDelayed({ refresh() }, 300)
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (18 * dp).toInt()
            setPadding(p, p, p, p * 2)
            setBackgroundColor(Color.rgb(16, 20, 24))
        }
        root.addView(text("Pool Guide", 26f, bold = true, color = Color.rgb(255, 214, 0)))
        root.addView(text("Aim and power guidelines drawn over your pool game. " +
            "It detects the table, pockets and balls, then shows the easiest pot with the power to use.", 14f))

        root.addView(header("1 · Permissions"))
        overlayStatus = text("", 14f)
        root.addView(overlayStatus)
        root.addView(button("Allow display over other apps") { requestOverlay() })

        root.addView(header("2 · Run"))
        startBtn = button("Start guide") { start() }
        root.addView(startBtn)
        root.addView(button("Stop guide") { stop() })
        root.addView(text("When Android asks what to share, choose \"Entire screen\". " +
            "Then open your game and tap Scan on the floating panel.", 13f, dim = true))

        root.addView(header("Mode"))
        root.addView(check("Predict mode: trace the shot for the current stick direction (off = suggest the best shot)",
            prefs.predictMode) { prefs.predictMode = it })
        root.addView(check("Set the stick direction by hand (off = read it from the game's cue stick / aim line)",
            prefs.stickManual) { prefs.stickManual = it })
        root.addView(check("Place the cue ball by hand (off = detect it)", prefs.cueManual) { prefs.cueManual = it })

        root.addView(header("Shot selection"))
        val groups = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        Group.values().forEach { g ->
            groups.addView(RadioButton(this).apply {
                text = when (g) { Group.ANY -> "Any"; Group.SOLIDS -> "Solids"; Group.STRIPES -> "Stripes" }
                setTextColor(Color.WHITE)
                id = View.generateViewId()
                isChecked = prefs.group == g
                setOnCheckedChangeListener { _, c -> if (c) prefs.group = g }
            })
        }
        root.addView(groups)
        root.addView(check("Suggest bank shots (object ball off a cushion)", prefs.allowBank) { prefs.allowBank = it })
        root.addView(check("Suggest kick shots (cue ball off a cushion)", prefs.allowKick) { prefs.allowKick = it })
        root.addView(check("Show faint alternative shots", prefs.showAlternatives) { prefs.showAlternatives = it })

        root.addView(header("Power calibration"))
        root.addView(text("At 100% power in your game, roughly how many table lengths does the cue ball roll " +
            "before stopping? Hit the cue ball at full power on an empty table once and count.", 13f, dim = true))
        root.addView(slider("Full power = %.1f table lengths", 10, 120, (prefs.maxPowerTables * 10).toInt()) { v ->
            prefs.maxPowerTables = v / 10f; v / 10f
        })
        root.addView(slider("Firmness: ball keeps %.2f table lengths of roll at the pocket", 0, 100,
            (prefs.firmness * 100).toInt()) { v -> prefs.firmness = v / 100f; v / 100f })
        val curve = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        listOf(PowerCurve.SPEED to "Power bar sets ball speed (most games)",
            PowerCurve.DISTANCE to "Power bar sets roll distance").forEach { (c, label) ->
            curve.addView(RadioButton(this).apply {
                text = label; setTextColor(Color.WHITE); id = View.generateViewId()
                isChecked = prefs.powerCurve == c
                setOnCheckedChangeListener { _, ch -> if (ch) prefs.powerCurve = c }
            })
        }
        root.addView(curve)

        root.addView(header("Detection"))
        root.addView(slider("Live scan every %.0f ms", 250, 2000, prefs.liveInterval) { v ->
            prefs.liveInterval = v; v.toFloat()
        })
        root.addView(check("Find the table automatically (off = you set it by hand in Edit — recommended)", prefs.autoTable) {
            prefs.autoTable = it
        })
        root.addView(slider("Ball radius: %.0f px (0 = automatic; easier: orange ring in Edit)", 0, 80, prefs.ballRadius.toInt()) { v ->
            prefs.ballRadius = v.toFloat(); v.toFloat()
        })
        root.addView(check("Lock table position (stop re-detecting it on Scan)", prefs.lockTable) { prefs.lockTable = it })
        root.addView(check("Hide guide lines for a moment while capturing (recommended)", prefs.hideOnCapture) {
            prefs.hideOnCapture = it
        })
        root.addView(button("Forget saved table positions") {
            prefs.clearTables()
            Toast.makeText(this, "Saved tables cleared — tap Scan in the game", Toast.LENGTH_SHORT).show()
        })

        root.addView(header("Floating panel"))
        root.addView(text(
            "First time: the guide opens in Edit. Drag the 8 blue dots onto the inner edges of the cushions, " +
            "drag the orange ring onto a ball and drag its orange dot to the ball's edge, then tap Done.\n\n" +
            "Live — keep scanning automatically\n" +
            "Scan — detect the balls now\n" +
            "Best shot / Predict — suggest the easiest pot, or trace the shot your stick is aiming at\n" +
            "Next — cycle through other good shots (Best shot mode)\n" +
            "Any/Solids/Stripes — which balls are yours\n" +
            "Cue: Auto/Man — detect the cue ball or place it yourself (Edit → Place cue)\n" +
            "Stick: Auto/Man — read the stick direction from the game, or set it yourself " +
            "(Edit → Aim to drag it, ◀ ▶ to fine-tune: tap = 0.05°, hold = faster)\n" +
            "Edit — set the table and ball size, drag/add/delete balls, mark the cue ball, change a ball's type, " +
            "or tap a ball/pocket to force that shot\n" +
            "Hide — hide the lines\n" +
            "Tap the ⠿ title to collapse the panel, drag it to move it. Keep it off the table.\n\n" +
            "On screen: white line = cue ball path, dashed circle = where the cue ball must be at contact " +
            "(ghost ball), yellow line = object ball path, blue dots = where the cue ball goes after contact, " +
            "red dot = cushion contact point. In Predict mode the object ball line turns green when it goes in.", 13f, dim = true))

        return ScrollView(this).apply { addView(root); setBackgroundColor(Color.rgb(16, 20, 24)) }
    }

    private fun text(s: String, size: Float, bold: Boolean = false, color: Int = Color.WHITE, dim: Boolean = false) =
        TextView(this).apply {
            text = s; textSize = size
            setTextColor(if (dim) Color.argb(170, 255, 255, 255) else color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
        }

    private fun header(s: String) = text(s, 17f, bold = true, color = Color.rgb(0, 229, 255)).apply {
        setPadding(0, (20 * dp).toInt(), 0, (6 * dp).toInt())
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; setTextColor(Color.BLACK); gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(Color.rgb(255, 214, 0)); cornerRadius = 10 * dp }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * dp).toInt() }
        setOnClickListener { onClick() }
    }

    private fun check(s: String, value: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = s; isChecked = value; setTextColor(Color.WHITE)
        setOnCheckedChangeListener { _, c -> onChange(c) }
    }

    /** Label (String.format with one float) + seek bar. [apply] stores the value and returns what to display. */
    private fun slider(fmt: String, min: Int, max: Int, value: Int, apply: (Int) -> Float): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val label = text("", 14f)
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
        }
        label.text = String.format(fmt, apply(value.coerceIn(min, max)))
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                label.text = String.format(fmt, apply(p + min))
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        box.addView(label); box.addView(bar)
        return box
    }
}
