package com.mcsoftware.paneldash

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sunrise/sunset (classic sunrise equation, ~1 min accurate) so the panel can switch
 * light/dark on its own. All times are handled in UTC, so the device timezone never matters.
 */
object SunTimes {

    private fun rad(d: Double) = d * PI / 180.0
    private fun deg(r: Double) = r * 180.0 / PI

    /** Sun's ecliptic longitude at (approximate) day number t. */
    private fun sunLongitude(t: Double): Double {
        val m = 0.9856 * t - 3.289
        var l = m + 1.916 * sin(rad(m)) + 0.020 * sin(rad(2 * m)) + 282.634
        l = ((l % 360) + 360) % 360
        return l
    }

    /** Raw cos(hour angle) for the day; >1 = sun never rises, <-1 = never sets. */
    private fun cosHourAngle(yDay: Int, lat: Double, lon: Double): Double {
        val lngHour = lon / 15.0
        val t = yDay + (6.0 - lngHour) / 24.0
        val l = sunLongitude(t)
        val sinDec = 0.39782 * sin(rad(l))
        val cosDec = cos(asin(sinDec))
        return (cos(rad(90.833)) - sinDec * sin(rad(lat))) / (cosDec * cos(rad(lat)))
    }

    /** Sunrise (rising=true) / sunset as fractional UTC hours on day `yDay`. */
    private fun eventHour(yDay: Int, lat: Double, lon: Double, rising: Boolean): Double {
        val lngHour = lon / 15.0
        val t = yDay + (if (rising) (6.0 - lngHour) / 24.0 else (18.0 - lngHour) / 24.0)
        val l = sunLongitude(t)
        var ra = deg(atan(0.91764 * tan(rad(l))))
        ra = ((ra % 360) + 360) % 360
        ra += (floor(l / 90) * 90 - floor(ra / 90) * 90)
        ra /= 15.0
        val sinDec = 0.39782 * sin(rad(l))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(rad(90.833)) - sinDec * sin(rad(lat))) / (cosDec * cos(rad(lat)))
        val h = if (rising) (360.0 - deg(acos(cosH))) / 15.0 else deg(acos(cosH)) / 15.0
        val localMean = h + ra - 0.06571 * t - 6.622
        return (((localMean - lngHour) % 24) + 24) % 24
    }

    /** True when it is night (before sunrise or after sunset) at (lat, lon) right now. */
    fun isNight(nowEpochMs: Long, lat: Double, lon: Double): Boolean {
        val day = LocalDate.now(ZoneOffset.UTC).dayOfYear
        val cosH = cosHourAngle(day, lat, lon)
        if (cosH >= 1.0) return true    // polar night: sun never rises
        if (cosH <= -1.0) return false  // midnight sun: sun never sets
        val rise = eventHour(day, lat, lon, true)
        val set = eventHour(day, lat, lon, false)
        val nowH = (nowEpochMs % 86_400_000L) / 3_600_000.0
        // Normally day = [rise, set). For longitudes where sunset falls after UTC
        // midnight (set < rise, e.g. the Americas), the window wraps: day = [rise, 24) u [0, set).
        return if (set >= rise) (nowH < rise || nowH >= set)
               else (nowH >= set && nowH < rise)
    }
}

/**
 * Ambient light (lux) from the device's own light sensor — the panel reads the room
 * instead of guessing from the clock. The NSPanel Pro has a TYPE_LIGHT sensor on the
 * front face (dark room ~0-2 lx, room lights on ~700 lx).
 */
object LightSensorStore {

    @Volatile
    var lastLux: Float = -1f
        private set

    private var listener: SensorEventListener? = null

    fun available(ctx: Context): Boolean {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        return sm.getDefaultSensor(Sensor.TYPE_LIGHT) != null
    }

    /** Register (idempotent) for light readings; safe to call repeatedly. */
    fun start(ctx: Context) {
        if (listener != null) return
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (e.values.isNotEmpty()) lastLux = e.values[0]
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        listener = l
    }
}

/**
 * IP-based location for the day/night switch. Resolved once and cached on-device for
 * 30 days; tries several providers. Set options.latitude/longitude to skip IP lookup.
 */
object GeoStore {
    private const val MAX_AGE_MS = 30L * 24 * 3600 * 1000
    private const val PREF_LAT = "geo_lat"
    private const val PREF_LON = "geo_lon"
    private const val PREF_TS = "geo_ts"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun resolve(ctx: Context, overrideLat: Double?, overrideLon: Double?): Pair<Double, Double>? {
        if (overrideLat != null && overrideLon != null) return overrideLat to overrideLon
        val prefs = ctx.getSharedPreferences("paneldash", Context.MODE_PRIVATE)
        val lat = prefs.getFloat(PREF_LAT, Float.NaN)
        val lon = prefs.getFloat(PREF_LON, Float.NaN)
        val ts = prefs.getLong(PREF_TS, 0)
        val have = !lat.isNaN() && !lon.isNaN()
        if (have && System.currentTimeMillis() - ts < MAX_AGE_MS) return lat.toDouble() to lon.toDouble()
        val fetched = fetch()
        if (fetched != null) {
            prefs.edit()
                .putFloat(PREF_LAT, fetched.first.toFloat())
                .putFloat(PREF_LON, fetched.second.toFloat())
                .putLong(PREF_TS, System.currentTimeMillis())
                .apply()
            return fetched
        }
        // Offline but we still have an old fix: coarse location beats guessing.
        return if (have) lat.toDouble() to lon.toDouble() else null
    }

    private suspend fun fetch(): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        for (url in listOf(
            "http://ip-api.com/json/",   // plain HTTP: panel allows cleartext traffic
            "https://ipapi.co/json/",
            "https://ipwho.is/",
        )) {
            try {
                val req = Request.Builder().url(url).header("User-Agent", "PanelDash").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: return@use
                    val o = JSONObject(body)
                    val la = o.optDouble("lat", o.optDouble("latitude", Double.NaN))
                    val lo = o.optDouble("lon", o.optDouble("longitude", Double.NaN))
                    if (!la.isNaN() && !lo.isNaN() &&
                        la in -90.0..90.0 && lo in -180.0..180.0
                    ) return@withContext la to lo
                }
            } catch (_: Exception) {
                // try next provider
            }
        }
        null
    }
}
