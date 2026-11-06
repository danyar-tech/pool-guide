package com.poolguide.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat

class OverlayService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.poolguide.app.STOP"
        private const val CHANNEL = "guide"
        @Volatile var running = false
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var screenW = 0
    private var screenH = 0
    private var dpi = 0

    private val vision = Vision()
    private val ballVision = BallVision()
    private val tracker = Tracker()
    private var stickMisses = 0
    private val state = GuideState()
    private var aim: AimView? = null
    private var aimParams: WindowManager.LayoutParams? = null
    private var panel: ControlPanel? = null

    private var live = true
    private var editing = false
    private var busy = false
    private var lastFrame: ByteArray? = null
    private var lastFrameW = 0
    private var lastFrameH = 0
    private var cvReady = false

    // ------------------------------------------------------------------ lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        workerThread = HandlerThread("vision").also { it.start() }
        worker = Handler(workerThread.looper)
        cvReady = runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        teardownCapture()
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try { mpm.getMediaProjection(code, data) } catch (e: Exception) { null }
        if (mp == null) { toast("Screen capture was not allowed"); stopSelf(); return START_NOT_STICKY }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)
        running = true
        if (!cvReady) toast("OpenCV failed to load — use Edit to place balls by hand")
        setupCapture()
        if (aim == null) setupOverlays()
        loadTable()
        if (state.table == null && !prefs.autoTable) main.postDelayed({ setEditing(true) }, 300)
        main.removeCallbacks(liveLoop)
        main.postDelayed(liveLoop, 600)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        teardownCapture()
        projection?.stop()
        projection = null
        aim?.let { runCatching { wm.removeView(it) } }
        panel?.let { runCatching { wm.removeView(it) } }
        aim = null; panel = null
        workerThread.quitSafely()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation: resize the capture to the new screen size and load that orientation's table.
        main.postDelayed({
            val (w, h, _) = screenSize()
            if (w != screenW || h != screenH) {
                screenW = w; screenH = h
                val old = reader
                reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
                display?.resize(w, h, dpi)
                display?.surface = reader?.surface
                old?.close()
                lastFrame = null
                loadTable()
            }
        }, 400)
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Pool Guide", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Pool Guide is running")
            .setContentText("Overlay active. Tap to open settings.")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    // ------------------------------------------------------------------ capture

    private fun screenSize(): Triple<Int, Int, Int> {
        val dm = getSystemService(DisplayManager::class.java)
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") d.getRealMetrics(m)
        return Triple(m.widthPixels, m.heightPixels, m.densityDpi)
    }

    private fun setupCapture() {
        val (w, h, d) = screenSize()
        screenW = w; screenH = h; dpi = d
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        display = projection?.createVirtualDisplay("poolguide", w, h, d,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, main)
    }

    private fun teardownCapture() {
        display?.release(); display = null
        reader?.close(); reader = null
    }

    private val liveLoop = object : Runnable {
        override fun run() {
            val canScan = state.table != null || prefs.autoTable
            if (live && !editing && !busy && canScan) capture(detectTable = state.table == null)
            val interval = prefs.liveInterval.toLong().coerceAtLeast(250)
            main.postDelayed(this, if (prefs.predictMode) minOf(interval, 450L) else interval)
        }
    }

    /** Hide the guide lines, grab a clean frame, show them again, then analyse off the main thread. */
    private fun capture(detectTable: Boolean, single: Boolean = false, autoSize: Boolean = false) {
        if (reader == null || busy) return
        busy = true
        val a = aim
        val hide = prefs.hideOnCapture && a != null && a.guidesVisible
        if (hide) a!!.visibility = View.INVISIBLE
        val hideTime = System.nanoTime()
        var held: Image? = null
        var attempt = 0
        val grab = object : Runnable {
            override fun run() {
                val img = runCatching { reader?.acquireLatestImage() }.getOrNull()
                if (img != null) { held?.close(); held = img }
                val fresh = held != null && held!!.timestamp >= hideTime + 8_000_000L
                if (!fresh && attempt++ < 6) { main.postDelayed(this, 40); return }
                var bytes: ByteArray? = null; var w = 0; var h = 0
                held?.let {
                    try {
                        w = it.width; h = it.height
                        bytes = copyImage(it)
                    } catch (e: Exception) { bytes = null }
                    it.close()
                }
                held = null
                if (hide) a!!.visibility = View.VISIBLE
                if (bytes != null) { lastFrame = bytes; lastFrameW = w; lastFrameH = h }
                val frame = bytes ?: lastFrame
                if (frame == null) {
                    busy = false
                    state.message = "No screen image yet — try Scan again"
                    aim?.invalidate()
                    return
                }
                val fw = if (bytes != null) w else lastFrameW
                val fh = if (bytes != null) h else lastFrameH
                val job = Job(
                    table = state.table?.copyDeep(),
                    detectTable = detectTable && prefs.autoTable || (detectTable && single && state.table == null),
                    forceTable = detectTable && single && prefs.autoTable,
                    radius = if (prefs.ballRadius > 0) prefs.ballRadius.toDouble() else state.radius,
                    autoSize = autoSize,
                    manualBalls = state.ballsManual,
                    cue = if (prefs.cueManual) state.manualCue else null,
                    wantStick = prefs.predictMode && !prefs.stickManual,
                    single = single,
                )
                worker.post { analyse(frame, fw, fh, job) }
            }
        }
        main.postDelayed(grab, if (hide) 90L else 10L)
    }

    private fun copyImage(img: Image): ByteArray {
        val plane = img.planes[0]
        val buf = plane.buffer
        val w = img.width; val h = img.height
        val rowStride = plane.rowStride
        val out = ByteArray(w * h * 4)
        for (y in 0 until h) {
            buf.position(y * rowStride)
            buf.get(out, y * w * 4, w * 4)
        }
        return out
    }

    // ------------------------------------------------------------------ analysis (worker thread)

    /** Snapshot of everything the worker needs, taken on the main thread. */
    private class Job(
        val table: TableGeom?, val detectTable: Boolean, val forceTable: Boolean, val radius: Double,
        val autoSize: Boolean, val manualBalls: Boolean, val cue: Vec?, val wantStick: Boolean, val single: Boolean,
    )

    private class Result(
        val table: TableGeom?, val tableDetected: Boolean, val balls: List<Ball>?, val radius: Double,
        val radiusEstimated: Boolean, val stick: StickReading?, val stickTried: Boolean, val msg: String?,
    )

    private fun analyse(frame: ByteArray, w: Int, h: Int, job: Job) {
        var table = job.table
        var tableDetected = false
        var balls: List<Ball>? = null
        var radius = job.radius
        var estimated = false
        var stick: StickReading? = null
        var msg: String? = null
        try {
            if (!cvReady) throw IllegalStateException("OpenCV not loaded")
            val mat = Mat(h, w, CvType.CV_8UC4)
            mat.put(0, 0, frame)
            if (table == null || job.forceTable || (job.detectTable && !prefs.lockTable)) {
                val t = vision.detectTable(mat)
                if (t != null) { table = t; tableDetected = true }
                else if (table == null) msg = "Table not found — tap Edit and drag the blue dots"
            }
            if (table != null) {
                if (radius <= 0 || job.autoSize) {
                    val est = ballVision.estimateRadius(mat, table)
                    if (est != null) { radius = est; estimated = true }
                    else if (radius <= 0) radius = table.longSide / 55
                }
                val prep = ballVision.prepare(mat, table, radius)
                if (prep != null) {
                    if (!job.manualBalls) balls = ballVision.detectIn(prep, table, radius).balls
                    if (job.wantStick) {
                        val cue = job.cue ?: balls?.firstOrNull { it.kind == BallKind.CUE }?.pos
                        if (cue != null) stick = ballVision.detectStick(prep, cue)
                    }
                }
            }
            mat.release()
        } catch (e: Throwable) {
            msg = "Detection error: ${e.message}"
        }
        val res = Result(table, tableDetected, balls, radius, estimated, stick, job.wantStick, msg)
        main.post { applyResult(res, job.single) }
    }

    private fun applyResult(res: Result, single: Boolean) {
        busy = false
        if (aim == null) return
        if (res.table != null && (state.table == null || res.tableDetected)) {
            state.table = res.table
            prefs.saveTable(screenW, screenH, res.table)
        }
        if (res.radius > 0) {
            state.radius = res.radius
            if (res.radiusEstimated && prefs.ballRadius <= 0f) toast("Ball size set automatically — adjust the orange ring in Edit if it looks wrong")
        }
        if (res.balls != null && !state.ballsManual) {
            if (single) tracker.reset()
            state.balls = tracker.update(res.balls, state.radius).toMutableList()
            state.moving = tracker.moving && !single
        }
        if (res.stickTried) {
            val s = res.stick
            if (s != null) {
                val prev = state.detectedAngle
                val diff = if (prev == null) 9.0 else kotlin.math.abs(angleDiff(prev, s.angle))
                state.detectedAngle = if (prev != null && diff < Math.toRadians(0.4)) prev + angleDiff(s.angle, prev) * 0.5 else s.angle
                state.detectedFromStick = s.fromStick
                stickMisses = 0
            } else if (++stickMisses > 2) {
                state.detectedAngle = null
            }
        }
        if (res.msg != null) state.message = res.msg
        replan(keepIndex = true)
    }

    /** Signed smallest difference a - b in radians. */
    private fun angleDiff(a: Double, b: Double): Double {
        var d = (a - b) % (2 * Math.PI)
        if (d > Math.PI) d -= 2 * Math.PI
        if (d < -Math.PI) d += 2 * Math.PI
        return d
    }

    /** Balls used for planning: the detected ones, with the hand-placed cue ball swapped in when cue mode is manual. */
    private fun planningBalls(): List<Ball> {
        val m = state.manualCue
        if (!prefs.cueManual || m == null) return state.balls
        val r = state.radius
        val rest = state.balls.filter { it.kind != BallKind.CUE && it.pos.dist(m) > r * 1.2 }
        return rest + Ball(-7, m, BallKind.CUE)
    }

    private fun replan(keepIndex: Boolean = false) {
        val t = state.table
        val balls = planningBalls()
        val cue = balls.firstOrNull { it.kind == BallKind.CUE }
        val cfg = prefs.plannerConfig(state.forcedTargetId(), state.forcedPocket)
        state.prediction = null
        if (prefs.predictMode) {
            state.shots = emptyList()
            val ang = state.aimAngle(prefs.stickManual)
            if (t != null && cue != null && ang != null && state.radius > 0) {
                state.prediction = Predictor(cfg).predict(Scene(t, balls, state.radius), cue.id, cue.pos,
                    Vec(kotlin.math.cos(ang), kotlin.math.sin(ang)))
            }
        } else {
            state.shots = if (t != null && cue != null && state.radius > 0) {
                Planner(cfg).plan(Scene(t, balls, state.radius))
            } else emptyList()
            if (!keepIndex || state.shotIndex >= state.shots.size) state.shotIndex = 0
        }
        if (t != null && (state.shots.isEmpty() || prefs.predictMode)) {
            state.message = when {
                balls.isEmpty() -> "No balls found — tap Scan, or Edit → Add"
                cue == null -> "Cue ball not found — Edit → Mark cue, or Cue: Man"
                prefs.predictMode -> "Waiting for the stick…"
                else -> "No clear pot — play safe (or allow banks/kicks in settings)"
            }
        }
        updatePanel()
        aim?.invalidate()
    }

    // ------------------------------------------------------------------ overlays

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun setupOverlays() {
        val ap = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(), passThroughFlags(), PixelFormat.TRANSLUCENT)
        ap.gravity = Gravity.TOP or Gravity.START
        // Android 12+ only lets touches pass through an overlay whose opacity is at most 0.8.
        ap.alpha = 0.8f
        if (Build.VERSION.SDK_INT >= 28) {
            ap.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val view = AimView(this, state,
            onBallsEdited = { state.ballsManual = true; state.moving = false; replan() },
            onTableEdited = { state.table?.let { prefs.saveTable(screenW, screenH, it) }; replan() },
            onSelectionChanged = { replan() },
            onRadiusEdited = { r -> prefs.ballRadius = r.toFloat(); replan() },
            onCueEdited = { if (!prefs.cueManual) prefs.cueManual = true; replan() },
            onAimEdited = { if (!prefs.stickManual) prefs.stickManual = true; replan() })
        wm.addView(view, ap)
        aim = view; aimParams = ap

        val pp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        pp.gravity = Gravity.TOP or Gravity.START
        pp.x = prefs.sp.getInt("panelX", 40)
        pp.y = prefs.sp.getInt("panelY", 200)
        val p = ControlPanel(this, wm, pp)
        wm.addView(p, pp)
        panel = p
        wirePanel(p, pp)
        updatePanel()
    }

    private fun passThroughFlags() =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    private fun wirePanel(p: ControlPanel, pp: WindowManager.LayoutParams) {
        p.btnLive.setOnClickListener { live = !live; updatePanel() }
        p.btnScan.setOnClickListener {
            state.ballsManual = false
            state.forcedTargetPos = null
            state.forcedPocket = -1
            capture(detectTable = true, single = true)
        }
        p.btnMode.setOnClickListener { prefs.predictMode = !prefs.predictMode; replan() }
        p.btnNext.setOnClickListener {
            if (state.shots.isNotEmpty()) state.shotIndex = (state.shotIndex + 1) % state.shots.size
            aim?.invalidate(); updatePanel()
        }
        p.btnGroup.setOnClickListener {
            prefs.group = when (prefs.group) { Group.ANY -> Group.SOLIDS; Group.SOLIDS -> Group.STRIPES; Group.STRIPES -> Group.ANY }
            replan()
        }
        p.btnCue.setOnClickListener {
            prefs.cueManual = !prefs.cueManual
            if (prefs.cueManual) {
                if (state.manualCue == null) {
                    state.manualCue = state.balls.firstOrNull { it.kind == BallKind.CUE }?.pos ?: state.table?.center
                }
                setEditing(true)
                aim?.tool = EditTool.PLACE_CUE
                updatePanel()
            }
            replan()
        }
        p.btnStick.setOnClickListener {
            prefs.stickManual = !prefs.stickManual
            if (prefs.stickManual && state.manualAngle == null) state.manualAngle = state.detectedAngle ?: 0.0
            replan()
        }
        // Fine aim: 0.05 degree per tap, faster when held.
        fun rotate(sign: Int, count: Int) {
            val stepDeg = if (count < 6) 0.05 else if (count < 25) 0.25 else 1.0
            val base = state.manualAngle ?: state.detectedAngle ?: 0.0
            state.manualAngle = base + sign * Math.toRadians(stepDeg)
            replan()
        }
        p.setRepeat(p.btnRotL) { rotate(-1, it) }
        p.setRepeat(p.btnRotR) { rotate(1, it) }
        p.btnEdit.setOnClickListener { setEditing(true) }
        p.btnHide.setOnClickListener {
            aim?.let { it.guidesVisible = !it.guidesVisible }
            updatePanel()
        }
        p.btnStop.setOnClickListener {
            prefs.sp.edit().putInt("panelX", pp.x).putInt("panelY", pp.y).apply()
            stopSelf()
        }
        p.toolButtons.forEach { (tool, b) -> b.setOnClickListener { aim?.tool = tool; updatePanel() } }
        p.btnRedetect.setOnClickListener {
            prefs.autoTable = true
            setEditing(false)
            capture(detectTable = true, single = true)
            toast("Finding the table automatically… (turn this off again in settings to keep it manual)")
        }
        p.btnAutoSize.setOnClickListener {
            prefs.ballRadius = 0f
            setEditing(false)
            capture(detectTable = false, single = true, autoSize = true)
        }
        p.btnClear.setOnClickListener { state.forcedTargetPos = null; state.forcedPocket = -1; replan() }
        p.btnDone.setOnClickListener {
            state.table?.let { prefs.saveTable(screenW, screenH, it) }
            prefs.sp.edit().putInt("panelX", pp.x).putInt("panelY", pp.y).apply()
            setEditing(false)
            if (!state.ballsManual) capture(detectTable = false, single = true)
        }
    }

    private fun setEditing(on: Boolean) {
        editing = on
        val a = aim ?: return
        val ap = aimParams ?: return
        a.editMode = on
        if (on) {
            a.tool = EditTool.MOVE
            ap.flags = ap.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            ap.alpha = 1f
            if (state.table == null) {
                // A starting rectangle for the player to drag onto the cushions.
                val t = TableGeom(screenW * 0.12, screenH * 0.2, screenW * 0.88, screenH * 0.8)
                t.resetPockets()
                state.table = t
            }
        } else {
            ap.flags = passThroughFlags()
            ap.alpha = 0.8f
        }
        runCatching { wm.updateViewLayout(a, ap) }
        panel?.showEdit(on)
        replan(keepIndex = true)
    }

    private fun updatePanel() {
        val p = panel ?: return
        p.setActive(p.btnLive, live)
        p.btnLive.text = if (live) "Live ●" else "Live ○"
        p.setModeButtons(prefs.predictMode, prefs.stickManual)
        p.setActive(p.btnMode, prefs.predictMode)
        p.btnGroup.text = when (prefs.group) { Group.ANY -> "Any"; Group.SOLIDS -> "Solids"; Group.STRIPES -> "Stripes" }
        p.btnCue.text = if (prefs.cueManual) "Cue: Man" else "Cue: Auto"
        p.setActive(p.btnCue, prefs.cueManual)
        p.setActive(p.btnStick, prefs.stickManual)
        p.btnHide.text = if (aim?.guidesVisible == false) "Show" else "Hide"
        p.toolButtons.forEach { (tool, b) -> p.setActive(b, aim?.tool == tool) }
        val n = state.balls.size
        p.status.text = when {
            editing -> "editing"
            state.table == null -> "no table"
            state.ballsManual -> "$n balls (manual)"
            else -> "$n balls"
        }
    }

    private fun loadTable() {
        prefs.table(screenW, screenH)?.let { state.table = it }
        if (prefs.ballRadius > 0) state.radius = prefs.ballRadius.toDouble()
        aim?.invalidate()
    }

    private fun toast(s: String) = main.post { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }
}
