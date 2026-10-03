package com.ipodify.app.pod

import androidx.compose.ui.text.font.DeviceFontFamilyName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.ContextCompat
import com.ipodify.app.media.PodController
import com.ipodify.app.R
import com.ipodify.app.media.PlayerState

/**
 * The iPod's display: a slim status bar over whichever screen is showing —
 * Cover Flow or Now Playing — which slide across like the original's menus.
 *
 * Everything is sized off the display's width ([u], a 300th of it), so it
 * scales with the PIP window like the body around it.
 */
@Composable
fun PodScreen(
    controller: PodController?,
    playerState: PlayerState,
    currentMenu: PodMenuState
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(PodScreenBlack),
    ) {
        val u = maxWidth / 300f
        Column(Modifier.fillMaxSize()) {
            PodStatusBar(
                title = when {
                    !currentMenu.isNowPlaying.value -> currentMenu.title.value
                    currentMenu.isScrubbingMode.value -> "Scrubbing"
                    else -> "Now Playing"
                },
                isPlaying = playerState.isPlaying,
                u = u,
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = currentMenu.isNowPlaying.value,
                    transitionSpec = {
                        // Into Now Playing slides in from the right, as going
                        // deeper did on the iPod; MENU slides back.
                        val forward = targetState
                        val spec = tween<IntOffset>(280)
                        (slideInHorizontally(spec) { w -> if (forward) w else -w } + fadeIn(tween(200)))
                            .togetherWith(
                                slideOutHorizontally(spec) { w -> if (forward) -w / 3 else w / 3 } +
                                    fadeOut(tween(200)),
                            )
                    },
                    label = "podScreen",
                ) { nowPlaying ->
                    if (nowPlaying) {
                        PodNowPlaying(playerState, currentMenu)
                    } else {
                        PodMenuList(currentMenu)
                    }
                }
            }
        }
    }
}

/**
 * The bar across the top: play state on the left, the screen's title in the
 * middle, the phone's real battery on the right. Dark glass with a hairline
 * under it, in place of the old metallic-blue strip.
 */
@Composable
private fun PodStatusBar(title: String, isPlaying: Boolean, u: Dp) {
    val density = LocalDensity.current
    val font = remember { FontFamily(Font(DeviceFontFamilyName("sans-serif"), FontWeight.SemiBold)) }
    val battery = rememberBattery()

    Box(
        Modifier
            .fillMaxWidth()
            .height(u * 24f)
            .background(
                Brush.verticalGradient(listOf(Color(0xFF26262E), Color(0xFF16161B))),
            ),
    ) {
        // The hairline under the bar.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(with(density) { 1f.toDp() })
                .background(Color.White.copy(alpha = 0.10f)),
        )
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = u * 10f),
        ) {
        Canvas(
            Modifier
                .align(Alignment.CenterStart)
                .size(u * 9f),
        ) { drawPlayState(isPlaying, Color.White.copy(alpha = 0.9f)) }

        AnimatedContent(
            targetState = title,
            transitionSpec = { fadeIn(tween(180)).togetherWith(fadeOut(tween(120))) },
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = u * 34f),
            label = "podTitle",
        ) { text ->
            Text(
                text = text,
                style = TextStyle(
                    color = Color.White,
                    fontFamily = font,
                    fontSize = with(density) { (u * 12.5f).toSp() },
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Canvas(
            Modifier
                .align(Alignment.CenterEnd)
                .size(u * 21f, u * 10f),
        ) { drawBattery(battery.level, battery.charging) }
        }
    }
}

/** ▶ while playing, ‖ while paused, filling the canvas. */
private fun DrawScope.drawPlayState(isPlaying: Boolean, color: Color) {
    val w = size.width
    val h = size.height
    if (isPlaying) {
        val path = Path().apply {
            moveTo(w * 0.12f, 0f)
            lineTo(w, h / 2f)
            lineTo(w * 0.12f, h)
            close()
        }
        drawPath(path, color)
    } else {
        val bar = w * 0.30f
        val r = CornerRadius(bar * 0.3f)
        drawRoundRect(color, Offset(w * 0.10f, 0f), Size(bar, h), r)
        drawRoundRect(color, Offset(w * 0.60f, 0f), Size(bar, h), r)
    }
}

/**
 * A rounded battery with a nub, filled to [level]: green while charging, red
 * at 20% and below, white otherwise.
 */
private fun DrawScope.drawBattery(level: Float, charging: Boolean) {
    val nubW = size.width * 0.08f
    val bodyW = size.width - nubW - size.width * 0.04f
    val h = size.height
    val stroke = h * 0.10f
    val radius = CornerRadius(h * 0.28f)
    val outline = Color.White.copy(alpha = 0.55f)

    drawRoundRect(
        color = outline,
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(bodyW - stroke, h - stroke),
        cornerRadius = radius,
        style = Stroke(stroke),
    )
    drawRoundRect(
        color = outline,
        topLeft = Offset(size.width - nubW, h * 0.32f),
        size = Size(nubW, h * 0.36f),
        cornerRadius = CornerRadius(nubW / 2f),
    )
    val fill = when {
        charging -> Color(0xFF34C759)
        level <= 0.2f -> Color(0xFFFF453A)
        else -> Color.White
    }
    val inset = stroke * 2f
    drawRoundRect(
        color = fill,
        topLeft = Offset(inset, inset),
        size = Size(((bodyW - 2 * inset) * level.coerceIn(0.04f, 1f)), h - 2 * inset),
        cornerRadius = CornerRadius(h * 0.16f),
    )
}

private class BatteryState(level: Float, charging: Boolean) {
    var level by mutableStateOf(level)
    var charging by mutableStateOf(charging)
}

/** The phone's battery, kept current from the sticky battery broadcast. */
@Composable
private fun rememberBattery(): BatteryState {
    val context = LocalContext.current
    val state = remember { BatteryState(1f, false) }
    DisposableEffect(context) {
        fun read(intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) state.level = level / scale.toFloat()
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            state.charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = read(intent)
        }
        // Sticky: registering hands back the current state straight away.
        read(
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            ),
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return state
}
