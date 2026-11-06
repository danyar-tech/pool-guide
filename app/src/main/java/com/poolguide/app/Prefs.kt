package com.poolguide.app

import android.content.Context
import android.content.SharedPreferences

class Prefs(ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences("poolguide", Context.MODE_PRIVATE)

    var group: Group
        get() = runCatching { Group.valueOf(sp.getString("group", "ANY")!!) }.getOrDefault(Group.ANY)
        set(v) = sp.edit().putString("group", v.name).apply()

    var allowBank: Boolean
        get() = sp.getBoolean("bank", true)
        set(v) = sp.edit().putBoolean("bank", v).apply()

    var allowKick: Boolean
        get() = sp.getBoolean("kick", true)
        set(v) = sp.edit().putBoolean("kick", v).apply()

    /** Milliseconds between live scans. */
    var liveInterval: Int
        get() = sp.getInt("interval", 800)
        set(v) = sp.edit().putInt("interval", v).apply()

    /** Cue ball roll distance at 100% power, in table lengths. */
    var maxPowerTables: Float
        get() = sp.getFloat("maxPower", 5f)
        set(v) = sp.edit().putFloat("maxPower", v).apply()

    /** Extra roll past the pocket, in table lengths. */
    var firmness: Float
        get() = sp.getFloat("firmness", 0.2f)
        set(v) = sp.edit().putFloat("firmness", v).apply()

    var powerCurve: PowerCurve
        get() = runCatching { PowerCurve.valueOf(sp.getString("curve", "SPEED")!!) }.getOrDefault(PowerCurve.SPEED)
        set(v) = sp.edit().putString("curve", v.name).apply()

    /** Ball radius in screen pixels; 0 = automatic. */
    var ballRadius: Float
        get() = sp.getFloat("ballRadius", 0f)
        set(v) = sp.edit().putFloat("ballRadius", v).apply()

    var lockTable: Boolean
        get() = sp.getBoolean("lockTable", false)
        set(v) = sp.edit().putBoolean("lockTable", v).apply()

    /** Hide the guide lines for a moment while capturing so they are not mistaken for balls. */
    var hideOnCapture: Boolean
        get() = sp.getBoolean("hideOnCapture", true)
        set(v) = sp.edit().putBoolean("hideOnCapture", v).apply()

    var showAlternatives: Boolean
        get() = sp.getBoolean("alts", true)
        set(v) = sp.edit().putBoolean("alts", v).apply()

    fun table(w: Int, h: Int): TableGeom? = TableGeom.decode(sp.getString("table_${w}x$h", null))
    fun saveTable(w: Int, h: Int, t: TableGeom?) {
        if (t == null) sp.edit().remove("table_${w}x$h").apply()
        else sp.edit().putString("table_${w}x$h", t.encode()).apply()
    }
    fun clearTables() {
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith("table_") }.forEach { e.remove(it) }
        e.apply()
    }

    fun plannerConfig(forcedTarget: Int, forcedPocket: Int) = PlannerConfig(
        group = group,
        allowBank = allowBank,
        allowKick = allowKick,
        maxPowerTables = maxPowerTables.toDouble(),
        firmnessTables = firmness.toDouble(),
        powerCurve = powerCurve,
        forcedTargetId = forcedTarget,
        forcedPocket = forcedPocket,
    )
}

/** Mutable state shared between the capture pipeline and the overlay views (main thread only). */
class GuideState {
    var table: TableGeom? = null
    var balls: MutableList<Ball> = mutableListOf()
    var radius: Double = 0.0
    var shots: List<Shot> = emptyList()
    var shotIndex: Int = 0
    var moving: Boolean = false
    /** Player-picked target ball, remembered by position because ids change between frames. */
    var forcedTargetPos: Vec? = null
    var forcedPocket: Int = -1
    /** True after manual ball edits: live scanning stops overwriting the balls until the next Scan. */
    var ballsManual: Boolean = false
    var message: String = "Tap Scan to detect the table"
    var nextId: Int = 1000

    val currentShot: Shot? get() = shots.getOrNull(shotIndex)

    fun forcedTargetId(): Int {
        val p = forcedTargetPos ?: return -1
        val b = balls.filter { it.kind != BallKind.CUE }.minByOrNull { it.pos.dist(p) } ?: return -1
        return if (b.pos.dist(p) < radius * 2.5) b.id else -1
    }
}
