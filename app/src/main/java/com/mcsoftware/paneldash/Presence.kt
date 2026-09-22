package com.mcsoftware.paneldash

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.abs

/**
 * Proximity/presence tracking for wake-on-approach.
 *
 * The NSPanel Pro exposes a TYPE_PROXIMITY sensor; its raw readings sit at a
 * stable baseline when nobody is near (observed ~1950-1960 idle) and move when
 * something approaches. Two detection models are cheap to support at once:
 *
 *  - absolute: reading >= nearValue (matches the stock "wake-on-wave" threshold of 2000)
 *  - delta:    reading moved >= nearDelta away from the slowly-adapting baseline
 *              (catches sensors that go DOWN when covered, like phone proximity sensors)
 *
 * Either fires "activity". After `awaySeconds` with no activity the screen dims.
 * Activity is also bumped by touches ([bump]) and by the doorbell alert.
 */
object ProximityStore {

    private const val TAG = "PanelDashProx"

    @Volatile
    var lastValue: Float = -1f
        private set

    @Volatile
    private var baseline = 1950f

    @Volatile
    private var lastActivity = 0L

    @Volatile
    private var enabled = false

    private var listener: SensorEventListener? = null
    private var lastLogAt = 0L

    fun available(ctx: Context): Boolean {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        return sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null
    }

    /** Register (idempotent) for proximity readings; safe to call on every config reload. */
    fun start(ctx: Context, cfg: ProximityCfg) {
        enabled = true
        if (lastActivity == 0L) lastActivity = System.currentTimeMillis()
        if (listener != null) return
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val v = if (e.values.isNotEmpty()) e.values[0] else return
                lastValue = v
                // Unconditional slow adaptation (time constant ~100 s at 5 Hz):
                // converges to the idle reading mean and cannot run away. An
                // adapt-only-when-calm version freezes the baseline the moment
                // noise drifts past the delta band, after which everything
                // misfires as "activity" forever - so this one adapts always.
                // A wave/sample spike moves it by ~0.1 - effectively invisible.
                baseline += (v - baseline) * 0.002f
                val near = v >= cfg.nearValue || abs(v - baseline) >= cfg.nearDelta
                if (near) {
                    lastActivity = System.currentTimeMillis()
                    val now = System.currentTimeMillis()
                    if (now - lastLogAt > 5_000) {
                        lastLogAt = now
                        Log.i(TAG, "activity: v=$v baseline=$baseline")
                    }
                }
            }

            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        listener = l
        Log.i(TAG, "proximity listener started (near=${cfg.nearValue}, delta=${cfg.nearDelta})")
    }

    /** Mark activity now (touch, doorbell, config reload). */
    fun bump() {
        lastActivity = System.currentTimeMillis()
    }

    /** True when nothing has happened for [awaySeconds] — dim the screen. */
    fun isAway(awaySeconds: Int): Boolean =
        enabled && System.currentTimeMillis() - lastActivity > awaySeconds * 1000L

    /** Snapshot for logging/debug: "v=1955 baseline=1950". */
    fun describe(): String = "v=$lastValue baseline=$baseline"
}
