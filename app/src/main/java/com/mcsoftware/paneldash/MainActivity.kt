package com.mcsoftware.paneldash

import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File

class MainActivity : ComponentActivity() {

    private val dash = Dashboard()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersive()

        val prefs = getSharedPreferences("paneldash", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("asked_storage", false)) {
            prefs.edit().putBoolean("asked_storage", true).apply()
            try {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
            } catch (e: Exception) {
                // ignore - falls back to the app-folder config location
            }
        }

        setContent {
            val ctx = this@MainActivity

            // Theme switching. Default to night until a mode resolves, so nothing
            // flashes white on boot.
            //   daynight: sensor  -> the panel's own light sensor (ambient lux)
            //   daynight: auto    -> sun times (IP-located), sunrise/sunset
            //   daynight: off     -> always the single `theme`
            var isNight by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
            LaunchedEffect(dash.config) {
                val cfg = dash.config
                val mode = cfg?.options?.dayNight ?: "off"
                if (cfg == null || mode == "off") {
                    isNight = false
                    return@LaunchedEffect
                }

                if (mode == "sensor" && LightSensorStore.available(ctx)) {
                    LightSensorStore.start(ctx)
                    // Hysteresis + debounce: cross dark_below_lux to go dark, light_above_lux
                    // to go light, and require the new level to hold for ~8s. Passing shadows
                    // and a hand waving at the panel never flip the theme.
                    val darkAt = cfg.options.darkBelowLux
                    val lightAt = cfg.options.lightAboveLux
                    var night = isNight
                    var candidate: Boolean? = null
                    var candidateSince = 0L
                    while (true) {
                        val lux = LightSensorStore.lastLux
                        if (lux >= 0f) {
                            val want = when {
                                night && lux >= lightAt -> false   // clearly lit -> day
                                !night && lux <= darkAt -> true    // clearly dark -> night
                                else -> night                      // in-between: stay
                            }
                            if (want != night) {
                                if (candidate != want) {
                                    candidate = want
                                    candidateSince = System.currentTimeMillis()
                                } else if (System.currentTimeMillis() - candidateSince >= 8_000) {
                                    night = want
                                    candidate = null
                                }
                            } else {
                                candidate = null
                            }
                            isNight = night
                        }
                        delay(2_000)
                    }
                }

                // Fallback (no sensor, or mode: auto): sun times at the IP location.
                while (true) {
                    val loc = GeoStore.resolve(ctx, cfg.options.latitude, cfg.options.longitude)
                    isNight = if (loc == null) {
                        // No location yet (first boot offline): fall back to device clock,
                        // 07:00-19:00 = day. Better than nothing until the IP lookup lands.
                        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                        h < 7 || h >= 19
                    } else SunTimes.isNight(System.currentTimeMillis(), loc.first, loc.second)
                    delay(60_000)
                }
            }

            // Doorbell chime: one short bell on the panel speaker per ring.
            LaunchedEffect(dash.alertNonce) {
                val db = dash.config?.doorbell ?: return@LaunchedEffect
                if (dash.alertNonce > 0 && db.sound) playChime(ctx, db.volume)
            }

            // Backlight manager: combines auto-brightness (ambient lux, sqrt ramp)
            // with wake-on-approach dimming (proximity). One writer for the window's
            // screenBrightness avoids the two features fighting each other.
            LaunchedEffect(dash.config) {
                val cfgB = dash.config ?: return@LaunchedEffect
                val hasAuto = cfgB.options.autoBrightness && LightSensorStore.available(ctx)
                val prox = cfgB.proximity?.takeIf { it.enabled }
                val hasProx = prox != null && ProximityStore.available(ctx)
                if (!hasAuto && !hasProx) {
                    // Restore the system default if both features are off.
                    runOnUiThread {
                        window.attributes = window.attributes.apply { screenBrightness = -1f }
                    }
                    return@LaunchedEffect
                }
                val px = if (hasProx) prox else null
                if (hasAuto) LightSensorStore.start(ctx)
                if (px != null) ProximityStore.start(ctx, px)

                val minB = cfgB.options.brightnessMin.coerceIn(1, 255)
                val maxB = cfgB.options.brightnessMax.coerceIn(minB, 255)
                val darkAt = cfgB.options.darkBelowLux.coerceAtLeast(0.5f)
                val lightAt = cfgB.options.lightAboveLux.coerceAtLeast(darkAt + 0.5f)
                var lastB = -2f
                while (true) {
                    val away = px != null && ProximityStore.isAway(px.awaySeconds)
                    val lux = LightSensorStore.lastLux
                    val b = when {
                        away -> (px ?: return@LaunchedEffect).dimBrightness
                        hasAuto && lux >= 0f -> {
                            val frac = kotlin.math.sqrt(
                                ((lux - darkAt) / (lightAt - darkAt)).coerceIn(0f, 1f),
                            )
                            (minB + (maxB - minB) * frac) / 255f
                        }
                        // No auto-brightness and not away: don't touch the backlight.
                        else -> -1f
                    }
                    if (kotlin.math.abs(b - lastB) > 0.01f) {
                        lastB = b
                        runOnUiThread {
                            window.attributes = window.attributes.apply { screenBrightness = b }
                        }
                    }
                    delay(2_000)
                }
            }

            LaunchedEffect(Unit) {
                val f = ensureConfig(ctx)
                dash.configPath = f.absolutePath
                try {
                    dash.config = ConfigParser.parse(f.readText())
                } catch (e: Exception) {
                    dash.status = "Config error: " + e.message
                }
            }

            LaunchedEffect(Unit) {
                var lastSig = ""
                while (true) {
                    val f = currentConfigFile(ctx)
                    if (f == null) {
                        delay(2000)
                        continue
                    }
                    val sig = f.absolutePath + "|" + f.lastModified() + "|" + f.length()
                    if (sig != lastSig) {
                        lastSig = sig
                        try {
                            val parsed = ConfigParser.parse(f.readText())
                            dash.configPath = f.absolutePath
                            if (parsed != dash.config) dash.config = parsed
                            if (dash.status.startsWith("Config error")) dash.status = ""
                        } catch (e: Exception) {
                            dash.status = "Config error: " + e.message
                        }
                    }
                    delay(1500)
                }
            }

            // Idle return: after `idle_return_seconds` with no touches, drift back
            // to the main screen (first page; any camera peek is closed). 0 disables.
            LaunchedEffect(dash.config) {
                val secs = dash.config?.options?.idleReturnSeconds ?: 0
                if (secs <= 0) return@LaunchedEffect
                while (true) {
                    delay(5_000)
                    val idleMs = System.currentTimeMillis() - dash.lastInteractionAt
                    if (idleMs >= secs * 1000L) {
                        val drifted = dash.currentPage != 0 || dash.cameraVisible != null
                        dash.currentPage = 0
                        dash.cameraVisible = null
                        if (drifted) dash.bumpInteraction()
                    }
                }
            }

            val cfg = dash.config
            LaunchedEffect(cfg) {
                if (cfg != null) {
                    requestedOrientation = when (cfg.options.orientation) {
                        "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                    runStream(dash, cfg)
                }
            }

            if (cfg == null) {
                Surface(color = Color(0xFF0E1116), modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().padding(24.dp)) {
                        Text(
                            dash.status.ifEmpty { "Loading..." },
                            color = Color(0xFFEAF0F6),
                            fontSize = 16.sp,
                        )
                    }
                }
            } else {
                val theme = if (cfg.options.dayNight == "off") cfg.theme else cfg.themeFor(isNight)
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        primary = theme.accentColor,
                        background = theme.bgColor,
                        surface = theme.surfaceColor,
                        onSurface = theme.textColor,
                    ),
                ) {
                    Surface(color = theme.bgColor, modifier = Modifier.fillMaxSize()) {
                      Box(
                          Modifier
                              .fillMaxSize()
                              // Any touch counts as activity: bumps the proximity timer so
                              // a dimmed panel wakes the moment it is touched.
                              .pointerInput(Unit) {
                                  awaitPointerEventScope {
                                      while (true) {
                                          awaitPointerEvent()
                                          ProximityStore.bump()
                                          // Any touch is also an "operation" for the
                                          // idle-return timer.
                                          dash.bumpInteraction()
                                      }
                                  }
                              },
                      ) {
                        // Device chassis: brushed-metal backdrop + vignette behind content,
                        // corner screws over it. Sells the "physical panel" look.
                        val metalLight = androidx.compose.ui.graphics.lerp(theme.surfaceColor, Color.White, 0.14f)
                        val metalDark = androidx.compose.ui.graphics.lerp(theme.surfaceColor, Color.Black, 0.55f)
                        Box(
                            Modifier
                                .matchParentSize()
                                .drawBehind {
                                    drawRect(theme.bgColor)
                                    val lightMode = isLightTheme(theme)
                                    // fine horizontal brushed streaks
                                    val streak = if (lightMode) Color.Black else Color.White
                                    val a1 = if (lightMode) 0.020f else 0.013f
                                    val a2 = if (lightMode) 0.008f else 0.005f
                                    val step = 3.dp.toPx()
                                    var y = 0f
                                    var i = 0
                                    while (y < size.height) {
                                        drawLine(
                                            streak.copy(alpha = if (i % 2 == 0) a1 else a2),
                                            androidx.compose.ui.geometry.Offset(0f, y),
                                            androidx.compose.ui.geometry.Offset(size.width, y),
                                            strokeWidth = 1.2f,
                                        )
                                        y += step
                                        i++
                                    }
                                    // vignette: corners fall into shadow (softer in light mode)
                                    drawRect(
                                        brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                            listOf(Color.Transparent, Color.Black.copy(alpha = if (lightMode) 0.14f else 0.42f)),
                                            center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f),
                                            radius = size.width * 0.75f,
                                        ),
                                    )
                                    // glass edge bevel
                                    drawRect(
                                        color = (if (lightMode) Color.Black else Color.White).copy(alpha = if (lightMode) 0.08f else 0.05f),
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
                                    )
                                },
                        )
                        Column(Modifier.fillMaxSize()) {
                            if (dash.status.isNotEmpty()) {
                                Text(
                                    dash.status,
                                    color = Color(0xFFFFCC80),
                                    fontSize = 12.sp,
                                    maxLines = 3,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF3A2A12))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                            // Always-visible info bar (temp / humidity / window...),
                            // configured once at the top level so every page shows it.
                            cfg.header?.let { header ->
                                RenderNode(header, theme, dash, Modifier.fillMaxWidth())
                            }
                            val pages = cfg.pages
                            val idx = dash.currentPage.coerceIn(0, pages.lastIndex)
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                RenderNode(pages[idx].node, theme, dash, Modifier.fillMaxSize())
                            }
                            if (pages.size > 1) {
                                PageNav(pages, idx, theme) { dash.currentPage = it }
                            }
                        }
                        Box(
                            Modifier
                                .matchParentSize()
                                .drawBehind { drawPanelScrews(metalLight, metalDark) },
                        )
                        // Doorbell: full-screen live-camera alert, above all chrome.
                        cfg.doorbell?.let { db ->
                            if (dash.alertVisible) DoorbellOverlay(db, dash, theme)
                        }
                        // Camera peek: full-screen live view from a `type: camera` tile.
                        dash.cameraVisible?.let { camId ->
                            CameraPeekOverlay(camId, dash.cameraTitle, dash, theme)
                        }
                      }
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    @Suppress("DEPRECATION")
    private fun applyImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    /**
     * Live streaming loop: Home Assistant's WebSocket API pushes every state change
     * (including attribute diffs like the blind position), so the panel updates
     * within ~100 ms and the radio does one long-lived connection instead of a
     * batch of HTTP requests every few seconds. Reconnects with backoff.
     */
    private suspend fun runStream(dash: Dashboard, cfg: PanelConfig) {
        val token = resolveToken(cfg)
        dash.client = HaClient(cfg.server.url, token)
        val tokenMissing = token.isBlank() || token.contains("PASTE")
        if (tokenMissing) {
            dash.status = "Set your Home Assistant token in " + dash.configPath
        }

        var failures = 0
        while (true) {
            val end = runStreamOnce(cfg.server.url, token, dash)
            when (end) {
                StreamEnd.AuthFailed -> {
                    dash.status =
                        if (tokenMissing) "Set your Home Assistant token in " + dash.configPath
                        else "Home Assistant rejected the token - update it in " + dash.configPath
                    failures = 0
                    // Token may be fixed on the panel; retry slowly.
                    delay(30_000)
                }

                StreamEnd.Disconnected -> {
                    failures++
                    if (failures >= 2) {
                        dash.status = "Can't reach Home Assistant at " + cfg.server.url
                    }
                    // 2s, 4s, 8s, 16s, then every 30s.
                    val wait = if (failures <= 4) 2_000L shl (failures - 1) else 30_000L
                    delay(wait)
                }
            }
        }
    }
}

private fun resolveToken(cfg: PanelConfig): String {
    cfg.server.tokenFile?.let { path ->
        try {
            val f = File(path)
            if (f.exists() && f.canRead()) return f.readText().trim()
        } catch (e: Exception) {
            // fall through to inline token
        }
    }
    return cfg.server.token
}

private fun candidateFiles(ctx: Context): List<File> {
    val list = mutableListOf<File>()
    list += File("/sdcard/paneldash/config.yaml")
    ctx.getExternalFilesDir(null)?.let { list += File(it, "config.yaml") }
    list += File(ctx.filesDir, "config.yaml")
    return list
}

private fun currentConfigFile(ctx: Context): File? =
    candidateFiles(ctx).firstOrNull { it.exists() && it.canRead() }

private fun ensureConfig(ctx: Context): File {
    currentConfigFile(ctx)?.let { return it }
    val target = ctx.getExternalFilesDir(null)?.let { File(it, "config.yaml") }
        ?: File(ctx.filesDir, "config.yaml")
    target.parentFile?.mkdirs()
    val sample = try {
        ctx.assets.open("default_config.yaml").bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        "root:\n  type: vstack\n  children: []\n"
    }
    target.writeText(sample)
    return target
}
