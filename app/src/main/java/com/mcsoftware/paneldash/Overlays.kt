package com.mcsoftware.paneldash

import android.content.Context
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Full-screen doorbell alert: title + countdown, live camera view (1 snap/s,
 * server-resized to keep the panel's slow SoC happy), and a dismiss button.
 * A second ring while visible extends the timer (alertNonce changes -> effects
 * restart). Auto-dismisses after cfg.seconds.
 */
@Composable
fun DoorbellOverlay(cfg: DoorbellCfg, dash: Dashboard, theme: ThemeCfg) {
    val scale = theme.fontScale.coerceIn(0.5f, 3f)
    val corner = RoundedCornerShape(theme.radius.coerceIn(0, 40).dp)
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var remaining by remember(dash.alertNonce) { mutableStateOf(cfg.seconds) }

    // Countdown -> auto-dismiss. Restarted on every ring (nonce).
    LaunchedEffect(dash.alertNonce) {
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
        dash.alertVisible = false
    }

    // Camera loop: fetch + decode one snapshot per second while visible.
    LaunchedEffect(dash.alertNonce) {
        val cam = cfg.camera ?: return@LaunchedEffect
        while (true) {
            val client = dash.client
            if (client != null) {
                val bytes = client.getCameraSnapshot(cam, 480)
                if (bytes != null) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bmp ->
                        frame = bmp.asImageBitmap()
                    }
                }
            }
            delay(1000)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xE6000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { dash.alertVisible = false },
    ) {
        Column(
            Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "\u25CF",
                    color = Color(0xFFFF4A4A),
                    fontSize = (20f * scale).sp,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    "  " + cfg.title.uppercase(),
                    color = Color.White,
                    fontSize = (20f * scale).sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${remaining}s",
                    color = Color(0xFFB9C2CC),
                    fontSize = (14f * scale).sp,
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(corner)
                    .background(Color(0xFF101418))
                    .border(1.dp, Color(0xFF2A3340), corner),
                contentAlignment = Alignment.Center,
            ) {
                val f = frame
                if (f != null) {
                    Image(
                        bitmap = f,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        "Connecting to camera\u2026",
                        color = Color(0xFF8A94A0),
                        fontSize = (14f * scale).sp,
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(corner)
                    .background(theme.accentColor)
                    .clickable { dash.alertVisible = false },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "DISMISS",
                    color = lerp(theme.accentColor, Color.Black, 0.88f),
                    fontSize = (18f * scale).sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}

/**
 * Play the bundled doorbell chime once on the panel speaker. Uses a MediaPlayer
 * with USAGE_ALARM attributes so the chime plays even when the media stream is
 * muted, at its own gain (cfg.volume) independent of the system volume.
 */
fun playChime(ctx: Context, volume: Float) {
    try {
        val mp = MediaPlayer()
        ctx.assets.openFd("chime.wav").use { afd ->
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        }
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        mp.setVolume(volume, volume)
        mp.setOnCompletionListener { it.release() }
        mp.prepare()
        mp.start()
    } catch (e: Exception) {
        // Never let audio trouble take the alert down.
        android.util.Log.w("PanelDashChime", "chime failed: " + (e.message ?: e.javaClass.simpleName))
    }
}

/**
 * Full-screen live camera view opened by `type: camera` tiles. Same calm
 * language as the doorbell overlay: dark scrim, rounded frame, one big
 * CLOSE bar; tapping the backdrop also closes it.
 */
@Composable
fun CameraPeekOverlay(cameraId: String, title: String, dash: Dashboard, theme: ThemeCfg) {
    val scale = theme.fontScale.coerceIn(0.5f, 3f)
    val corner = RoundedCornerShape(theme.radius.coerceIn(0, 40).dp)
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(cameraId) {
        while (true) {
            val client = dash.client
            if (client != null) {
                val bytes = client.getCameraSnapshot(cameraId, 480)
                if (bytes != null) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bmp ->
                        frame = bmp.asImageBitmap()
                    }
                }
            }
            delay(1000)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xE6000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { dash.cameraVisible = null },
    ) {
        Column(
            Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title.uppercase(),
                    color = Color.White,
                    fontSize = (20f * scale).sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "LIVE",
                    color = Color(0xFFFF4A4A),
                    fontSize = (13f * scale).sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(corner)
                    .background(Color(0xFF101418))
                    .border(1.dp, Color(0xFF2A3340), corner),
                contentAlignment = Alignment.Center,
            ) {
                val f = frame
                if (f != null) {
                    Image(
                        bitmap = f,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        "Connecting to camera\u2026",
                        color = Color(0xFF8A94A0),
                        fontSize = (14f * scale).sp,
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(corner)
                    .background(theme.accentColor)
                    .clickable { dash.cameraVisible = null },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "CLOSE",
                    color = lerp(theme.accentColor, Color.Black, 0.88f),
                    fontSize = (18f * scale).sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}
