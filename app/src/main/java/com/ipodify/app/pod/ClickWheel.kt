package com.ipodify.app.pod

import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.ipodify.app.R
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The click wheel and its centre button, drawn after the Figma file's
 * "control" group: a 230-unit wheel, an 82-unit gradient centre button with a
 * hairline and grain, MENU across the top and the three transport glyphs
 * built from the same triangles and bars the file uses.
 *
 * It fills whatever size it is given and scales its drawing to match; every
 * position below is in the file's units, relative to the wheel.
 *
 * Interaction is unchanged: drag round the ring to scroll (one tick every
 * 15°), and tap MENU, ⏮, ⏭, ⏯ or the centre.
 */
@Composable
fun ClickWheel(
    onScroll: (Int) -> Boolean,
    onClickMenu: () -> Unit,
    onClickPlayPause: () -> Unit,
    onClickNext: () -> Unit,
    onClickPrev: () -> Unit,
    onClickCenter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val finish = rememberPodFinish()
    val context = LocalContext.current
    // Played as alarm vibrations so they always get through — see [PodHaptics].
    val hapticTick = { PodHaptics.tick(context) }
    val hapticClick = { PodHaptics.click(context) }

    val currentOnScroll by rememberUpdatedState(onScroll)
    val currentOnMenu by rememberUpdatedState(onClickMenu)
    val currentOnPlayPause by rememberUpdatedState(onClickPlayPause)
    val currentOnNext by rememberUpdatedState(onClickNext)
    val currentOnPrev by rememberUpdatedState(onClickPrev)
    val currentOnCenter by rememberUpdatedState(onClickCenter)
    var lastAngle by remember { mutableFloatStateOf(0f) }
    var accumulatedAngle by remember { mutableFloatStateOf(0f) }

    val textMeasurer = rememberTextMeasurer()
    val menuFont = remember { FontFamily(Font(DeviceFontFamilyName("sans-serif"), FontWeight.Bold)) }

    Box(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val c = Offset(size.width / 2f, size.height / 2f)
                            lastAngle = angleOf(offset - c)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val currentAngle = angleOf(change.position - c)
                            var delta = currentAngle - lastAngle
                            if (delta > 180) delta -= 360
                            if (delta < -180) delta += 360
                            accumulatedAngle += delta
                            lastAngle = currentAngle
                            if (abs(accumulatedAngle) > DEGREES_PER_TICK) {
                                val ticks = (accumulatedAngle / DEGREES_PER_TICK).toInt()
                                accumulatedAngle -= ticks * DEGREES_PER_TICK
                                if (currentOnScroll(ticks)) hapticTick()
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        val unit = size.width / WHEEL_SIZE
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val d = tap - c
                        val distance = hypot(d.x, d.y)
                        if (distance > WHEEL_SIZE / 2f * unit) return@detectTapGestures
                        hapticClick()
                        when {
                            distance <= CENTER_SIZE / 2f * unit -> currentOnCenter()
                            // The four buttons each own a quarter of the ring.
                            abs(d.y) >= abs(d.x) && d.y < 0 -> currentOnMenu()
                            abs(d.y) >= abs(d.x) -> currentOnPlayPause()
                            d.x < 0 -> currentOnPrev()
                            else -> currentOnNext()
                        }
                    }
                },
        ) {
            val unit = size.width / WHEEL_SIZE
            drawWheel(finish, unit)
            // MENU: 16-unit bold type, 12 units below the wheel's top edge.
            val menu = textMeasurer.measure(
                text = "MENU",
                style = TextStyle(
                    color = finish.label.copy(alpha = 0.9f),
                    fontSize = (16f * unit / density / fontScale).sp,
                    fontFamily = menuFont,
                    fontWeight = FontWeight.Bold,
                ),
            )
            drawText(
                textLayoutResult = menu,
                topLeft = Offset((size.width - menu.size.width) / 2f, 12f * unit),
            )
            drawTransportGlyphs(finish, unit)
        }
    }
}

private const val DEGREES_PER_TICK = 15f

private fun angleOf(offset: Offset): Float =
    Math.toDegrees(atan2(offset.y.toDouble(), offset.x.toDouble())).toFloat()

/** The ring and the centre button, in wheel-local pixels. */
private fun DrawScope.drawWheel(finish: PodFinish, unit: Float) {
    val radius = WHEEL_SIZE / 2f * unit
    drawCircle(color = finish.wheel, radius = radius)
    finish.wheelStroke?.let { stroke ->
        // 1 unit, inside the edge.
        drawCircle(color = stroke, radius = radius - unit / 2f, style = Stroke(width = unit))
    }

    val centerRadius = CENTER_SIZE / 2f * unit
    val centerTop = center.y - centerRadius
    drawCircle(
        brush = Brush.verticalGradient(
            listOf(finish.centerTop, finish.centerBottom),
            startY = centerTop,
            endY = centerTop + 2 * centerRadius,
        ),
        radius = centerRadius,
    )
    with(PodNoise) {
        drawNoise(
            topLeft = Offset(center.x - centerRadius, centerTop),
            size = Size(2 * centerRadius, 2 * centerRadius),
            corner = centerRadius,
        )
    }
    // 1 unit, outside the edge.
    drawCircle(
        color = finish.centerStroke,
        radius = centerRadius + unit / 2f,
        style = Stroke(width = unit),
    )
}

/**
 * ⏯ ⏭ ⏮, built as in the file: ⏯ is a 12-unit triangle and two 2 × 10 bars,
 * ⏭ two overlapping 10-unit triangles and a bar, and ⏮ the same mirrored.
 */
private fun DrawScope.drawTransportGlyphs(finish: PodFinish, unit: Float) {
    val color = finish.label.copy(alpha = 0.9f)
    fun triangle(size: Float) = Path().apply {
        moveTo(size, size / 2f)
        lineTo(0f, size)
        lineTo(0f, 0f)
        close()
    }

    // Play/pause: 26 × 12 at (102, 205).
    translate(102f * unit, 205f * unit) {
        scale(unit, unit, pivot = Offset.Zero) {
            drawPath(triangle(12f), color)
            drawRect(color, topLeft = Offset(18f, 1f), size = Size(2f, 10f))
            drawRect(color, topLeft = Offset(24f, 1f), size = Size(2f, 10f))
        }
    }

    fun DrawScope.skip() {
        drawPath(triangle(10f), color)
        translate(9f, 0f) { drawPath(triangle(10f), color) }
        drawRect(color, topLeft = Offset(18f, 0f), size = Size(2f, 10f))
    }
    // Next: 20 × 10 at (200, 110).
    translate(200f * unit, 110f * unit) {
        scale(unit, unit, pivot = Offset.Zero) { skip() }
    }
    // Previous: the same, flipped, at (10, 110) — so its right edge is at 30.
    translate(30f * unit, 110f * unit) {
        scale(-unit, unit, pivot = Offset.Zero) { skip() }
    }
}
