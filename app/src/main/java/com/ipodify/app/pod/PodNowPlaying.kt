package com.ipodify.app.pod

import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.ipodify.app.R
import com.ipodify.app.media.PlayerState
import kotlinx.coroutines.delay

/**
 * Now Playing, in the iPod classic's layout brought up to date: the artwork on
 * the left standing on its reflection, the song, artist and album beside it,
 * and the progress bar along the bottom — all over the cover blurred into the
 * background, as Cover Flow does.
 *
 * Turning the wheel here changes the volume, and the progress bar gives way to
 * a volume bar while it does. In scrubbing mode (centre button) the bar thickens,
 * turns the accent colour and grows a knob.
 */
@Composable
fun PodNowPlaying(playerState: PlayerState, menuState: PodMenuState) {
    val song = playerState.song
    val fonts = rememberPodFonts()

    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val u = maxWidth / 300f
        val density = LocalDensity.current
        fun Float.usp(): TextUnit = with(density) { (u * this@usp).toSp() }

        PodArtBackdrop(song?.thumbnailUrl)

        if (song == null) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(u * 46f)
                        .border(u * 1.5f, Color.White.copy(alpha = 0.25f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("♪", style = TextStyle(color = Color.White.copy(alpha = 0.5f), fontSize = 22f.usp()))
                }
                Spacer(Modifier.height(u * 10f))
                Text(
                    "Not Playing",
                    style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontFamily = fonts.medium, fontSize = 13f.usp()),
                )
            }
            return@BoxWithConstraints
        }

        Column(Modifier.fillMaxSize()) {
            // Artwork and details.
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = u * 16f, end = u * 14f, top = u * 14f),
            ) {
                val art = u * 112f
                Crossfade(targetState = song.thumbnailUrl, animationSpec = tween(300), label = "npArt") { url ->
                    CoverWithReflection(url = url, side = art, dim = { 0f })
                }
                Spacer(Modifier.width(u * 14f))
                AnimatedContent(
                    targetState = song,
                    transitionSpec = { fadeIn(tween(220)).togetherWith(fadeOut(tween(160))) },
                    modifier = Modifier.weight(1f).height(art),
                    label = "npText",
                ) { s ->
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                        val queueTotal = playerState.queue.size.coerceAtLeast(1)
                        Text(
                            text = "${playerState.queueIndex + 1} of $queueTotal".uppercase(),
                            style = TextStyle(
                                color = Color.White.copy(alpha = 0.45f),
                                fontFamily = fonts.semibold,
                                fontSize = 9f.usp(),
                                letterSpacing = 9f.usp() * 0.08f,
                            ),
                        )
                        Spacer(Modifier.height(u * 6f))
                        MarqueeText(s.title, TextStyle(Color.White, 15f.usp(), fontFamily = fonts.semibold))
                        Spacer(Modifier.height(u * 3f))
                        MarqueeText(
                            s.artist.ifBlank { "Unknown Artist" },
                            TextStyle(Color.White.copy(alpha = 0.72f), 12f.usp(), fontFamily = fonts.medium),
                        )
                        Spacer(Modifier.height(u * 2f))
                        MarqueeText(
                            s.albumName ?: "",
                            TextStyle(Color.White.copy(alpha = 0.48f), 11f.usp(), fontFamily = fonts.medium),
                        )
                    }
                }
            }

            // Progress, or volume while the wheel is turning.
            var showVolume by remember { mutableStateOf(false) }
            LaunchedEffect(menuState.volumeTouches.value) {
                if (menuState.volumeTouches.value == 0) return@LaunchedEffect
                showVolume = true
                delay(VOLUME_SHOWN_MS)
                showVolume = false
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = u * 16f)
                    .padding(bottom = u * 12f)
                    .height(u * 34f),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Crossfade(
                    targetState = showVolume && !menuState.isScrubbingMode.value,
                    animationSpec = tween(180),
                    label = "npBar",
                ) { volume ->
                    if (volume) {
                        VolumeBar(menuState.volume.value, u)
                    } else {
                        val position = menuState.scrubPositionMs.value ?: playerState.position.positionMs
                        ProgressBar(
                            positionMs = position,
                            durationMs = playerState.durationMs,
                            scrubbing = menuState.isScrubbingMode.value,
                            u = u,
                            timeStyle = TextStyle(
                                color = Color.White.copy(alpha = 0.6f),
                                fontFamily = fonts.medium,
                                fontSize = 10f.usp(),
                                // Even-width digits, so the times don't jitter.
                                fontFeatureSettings = "tnum",
                            ),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarqueeText(text: String, style: TextStyle) {
    if (text.isEmpty()) return
    Text(
        text = text,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        // Long titles scroll after a beat, as on the iPod, instead of cutting off.
        modifier = Modifier.basicMarquee(initialDelayMillis = 1500, velocity = 28.dp),
    )
}

@Composable
private fun ProgressBar(positionMs: Long, durationMs: Long, scrubbing: Boolean, u: Dp, timeStyle: TextStyle) {
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shown by animateFloatAsState(fraction, spring(stiffness = 600f), label = "progress")
    val track by animateDpAsState(if (scrubbing) u * 7f else u * 4f, label = "track")
    val knob by animateFloatAsState(if (scrubbing) 1f else 0f, label = "knob")

    Column(Modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(u * 12f),
        ) {
            val h = track.toPx()
            val y = (size.height - h) / 2f
            val r = CornerRadius(h / 2f)
            drawRoundRect(Color.White.copy(alpha = 0.18f), Offset(0f, y), Size(size.width, h), r)
            val fill = if (scrubbing) PodAccent else Color.White
            drawRoundRect(fill, Offset(0f, y), Size(size.width * shown, h), r)
            if (knob > 0f) {
                val c = Offset(size.width * shown, size.height / 2f)
                drawCircle(PodAccent.copy(alpha = 0.30f * knob), radius = size.height * 0.62f * knob, center = c)
                drawCircle(Color.White, radius = size.height * 0.42f * knob, center = c)
            }
        }
        Spacer(Modifier.height(u * 3f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(positionMs), style = timeStyle)
            val remaining = (durationMs - positionMs).coerceAtLeast(0)
            Text("-" + formatMs(remaining), style = timeStyle)
        }
    }
}

/** A quiet speaker, the level, and a loud speaker. */
@Composable
private fun VolumeBar(level: Float, u: Dp) {
    val shown by animateFloatAsState(level, spring(stiffness = 700f), label = "volume")
    Row(
        Modifier
            .fillMaxWidth()
            .height(u * 22f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(u * 12f)) { drawSpeaker(waves = 0) }
        Spacer(Modifier.width(u * 8f))
        Canvas(
            Modifier
                .weight(1f)
                .height(u * 6f),
        ) {
            val r = CornerRadius(size.height / 2f)
            drawRoundRect(Color.White.copy(alpha = 0.18f), cornerRadius = r)
            drawRoundRect(PodAccent, size = Size(size.width * shown, size.height), cornerRadius = r)
        }
        Spacer(Modifier.width(u * 8f))
        Canvas(Modifier.size(u * 14f)) { drawSpeaker(waves = 2) }
    }
}

/** A speaker glyph with [waves] sound waves, filling the canvas. */
private fun DrawScope.drawSpeaker(waves: Int) {
    val color = Color.White.copy(alpha = 0.75f)
    val h = size.height
    val body = Path().apply {
        moveTo(0f, h * 0.36f)
        lineTo(h * 0.22f, h * 0.36f)
        lineTo(h * 0.48f, h * 0.12f)
        lineTo(h * 0.48f, h * 0.88f)
        lineTo(h * 0.22f, h * 0.64f)
        lineTo(0f, h * 0.64f)
        close()
    }
    drawPath(body, color)
    for (i in 1..waves) {
        val radius = h * (0.16f + 0.17f * i)
        drawArc(
            color = color,
            startAngle = -45f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(h * 0.40f - radius, h / 2f - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = h * 0.09f),
        )
    }
}

private class PodFonts(val medium: FontFamily, val semibold: FontFamily)

@Composable
private fun rememberPodFonts() = remember {
    PodFonts(
        medium = FontFamily(Font(DeviceFontFamilyName("sans-serif"), FontWeight.Medium)),
        semibold = FontFamily(Font(DeviceFontFamilyName("sans-serif"), FontWeight.SemiBold)),
    )
}

private const val VOLUME_SHOWN_MS = 1500L

private fun formatMs(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
