package com.ipodify.app.pod

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.random.Random

/*
 * The iPod classic (6th gen) body, rebuilt from the "iPod Mockup (Community)"
 * Figma file layer by layer rather than exported from it.
 *
 * Every measurement here is in that file's own units — the body is 377 × 626
 * (plus [SCREEN_EXTRA] of extra screen height)
 * — so the drawing code reads like the layer list: [POD_DESIGN_WIDTH] units
 * across, scaled to whatever size the iPod is actually drawn at. The two
 * finishes are the file's two frames: "Grey" for dark mode and "White" for
 * light mode.
 */

/** The design's body width, in design units. Everything else is measured against it. */
internal const val POD_DESIGN_WIDTH = 377f

/**
 * How much taller the screen is than in the mockup, in design units, so the
 * playlist shows six full rows as it did before the redesign. The body grows
 * by the same amount and everything below the screen moves down with it; the
 * width and every other measurement stay exactly as designed.
 */
internal const val SCREEN_EXTRA = 40f

/** The design's body height, in design units. */
internal const val POD_DESIGN_HEIGHT = 626f + SCREEN_EXTRA


/** One finish of the body: every colour that differs between the Grey and White frames. */
@Immutable
internal data class PodFinish(
    /** Body fill, top to bottom. */
    val bodyTop: Color,
    val bodyBottom: Color,
    /** Screen glass, and the bezel stroke drawn inside its edge (if any). */
    val screen: Color,
    val screenBezel: Color?,
    /** Whether the diagonal glare on the screen is shown (only the Grey frame has it). */
    val screenGlare: Boolean,
    /** Click wheel fill, and its 1-unit inside stroke (if any). */
    val wheel: Color,
    val wheelStroke: Color?,
    /** Centre button fill, top to bottom, and its 1-unit outside stroke. */
    val centerTop: Color,
    val centerBottom: Color,
    val centerStroke: Color,
    /** MENU, ⏮, ⏭ and ⏯ — drawn at 90% opacity. */
    val label: Color,
    /** Opacity of the dark bands down the body's left and right edges. */
    val sideShadowAlpha: Float,
) {
    companion object {
        /** The "Grey" frame: dark mode. */
        val Grey = PodFinish(
            bodyTop = Color(0xFF939295),
            bodyBottom = Color(0xFF262527),
            screen = Color(0xFF121212),
            screenBezel = null,
            screenGlare = false, // No glare, to match the White finish.
            wheel = Color(0xFF212122),
            wheelStroke = null,
            centerTop = Color(0xFF282829),
            centerBottom = Color(0xFF676467),
            centerStroke = Color.Black,
            label = Color.White,
            sideShadowAlpha = 0.5f,
        )

        /** The "White" frame: light mode. */
        val White = PodFinish(
            bodyTop = Color(0xFFF2F2F2),
            bodyBottom = Color(0xFFADADAD),
            screen = Color(0xFF6D6D6D),
            screenBezel = Color(0xFF2F2F2F),
            screenGlare = false,
            wheel = Color.White,
            wheelStroke = Color(0xFFAEADAD),
            centerTop = Color(0xFFB1B1B0),
            centerBottom = Color(0xFFE1E1E1),
            centerStroke = Color(0xFFAEADAD),
            label = Color(0xFF8793A0),
            sideShadowAlpha = 0.3f,
        )
    }
}

/** Grey when the system is dark, White when it is light. */
@Composable
internal fun rememberPodFinish(): PodFinish =
    if (isSystemInDarkTheme()) PodFinish.Grey else PodFinish.White

// ── Layout, in design units ──────────────────────────────────────────────

internal const val POD_CORNER_UNITS = 38f

internal const val SCREEN_X = 30f
internal const val SCREEN_Y = 24f
internal const val SCREEN_W = 318f
internal const val SCREEN_H = 242f + SCREEN_EXTRA
internal const val SCREEN_CORNER = 8f
/** The White frame's bezel stroke; the display sits inside it on both finishes. */
internal const val SCREEN_BEZEL = 6f

internal const val WHEEL_X = 74f
internal const val WHEEL_Y = 327f + SCREEN_EXTRA
internal const val WHEEL_SIZE = 230f

/** The centre button, relative to the wheel. */
internal const val CENTER_OFFSET = 74f
internal const val CENTER_SIZE = 82f

// ── Noise ────────────────────────────────────────────────────────────────

/**
 * The grain both the body and the centre button carry: a tile of random grey,
 * repeated, laid over at 20% in soft light — the file's "noise" layers,
 * generated here instead of shipped as an image.
 */
internal object PodNoise {
    private const val TILE_PX = 128

    val tile: ImageBitmap by lazy {
        val random = Random(377626)
        val pixels = IntArray(TILE_PX * TILE_PX) {
            val v = random.nextInt(256)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        Bitmap.createBitmap(pixels, TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    val brush: ShaderBrush by lazy {
        ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    }

    /**
     * Soft light needs API 29. Before that, a plain overlay at a much lower
     * opacity gives a similar grain without greying the colour underneath.
     */
    private val softLight = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun DrawScope.drawNoise(topLeft: Offset = Offset.Zero, size: Size = this.size, corner: Float = 0f) {
        drawRoundRect(
            brush = brush,
            topLeft = topLeft,
            size = size,
            cornerRadius = CornerRadius(corner, corner),
            alpha = if (softLight) 0.2f else 0.05f,
            blendMode = if (softLight) BlendMode.Softlight else BlendMode.SrcOver,
        )
    }
}

// ── Body ─────────────────────────────────────────────────────────────────

/**
 * The body fill and its grain: the frame's vertical gradient and its noise
 * layer. [unit] is pixels per design unit.
 */
internal fun DrawScope.drawPodBody(finish: PodFinish, unit: Float) {
    val corner = POD_CORNER_UNITS * unit
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(finish.bodyTop, finish.bodyBottom)),
        cornerRadius = CornerRadius(corner, corner),
    )
    with(PodNoise) { drawNoise(corner = corner) }
}

/**
 * The "shadows" group: four soft dark bands along the top, the sides and the
 * bottom that give the metal its rounded edge. In the file each is a gradient
 * rectangle with a 20-unit layer blur, clipped by the body; the caller blurs
 * the layer this is drawn into.
 */
internal fun DrawScope.drawPodEdgeShadows(finish: PodFinish, unit: Float) {
    fun u(v: Float) = v * unit

    // Top: 318 × 20 at (30, −10), solid for its upper half, fading out below.
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color.Black, Color.Transparent),
            startY = u(0f),
            endY = u(10f),
        ),
        topLeft = Offset(u(30f), u(-10f)),
        size = Size(u(318f), u(20f)),
        cornerRadius = CornerRadius(u(20f)),
        alpha = 0.4f,
    )
    // Left: 45 × 600 at (−23, 26), solid up to the body's edge, fading inward.
    drawRoundRect(
        brush = Brush.horizontalGradient(
            listOf(Color.Black, Color.Transparent),
            startX = u(0f),
            endX = u(22f),
        ),
        topLeft = Offset(u(-23f), u(26f)),
        size = Size(u(45f), u(600f + SCREEN_EXTRA)),
        cornerRadius = CornerRadius(u(22.5f)),
        alpha = finish.sideShadowAlpha,
    )
    // Right: 41 × 600 at (356, 26), the mirror of the left.
    drawRoundRect(
        brush = Brush.horizontalGradient(
            listOf(Color.Black, Color.Transparent),
            startX = u(POD_DESIGN_WIDTH),
            endX = u(356f),
        ),
        topLeft = Offset(u(356f), u(26f)),
        size = Size(u(41f), u(600f + SCREEN_EXTRA)),
        cornerRadius = CornerRadius(u(20.5f)),
        alpha = finish.sideShadowAlpha,
    )
    // Bottom: 377 × 80, 40 units up from the bottom edge, solid below the
    // body's edge, fading upward.
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color.Black, Color.Transparent),
            startY = u(POD_DESIGN_HEIGHT),
            endY = u(POD_DESIGN_HEIGHT - 40f),
        ),
        topLeft = Offset(u(0f), u(POD_DESIGN_HEIGHT - 40f)),
        size = Size(u(POD_DESIGN_WIDTH), u(80f)),
        cornerRadius = CornerRadius(u(20f)),
        alpha = 0.6f,
    )
}

/**
 * The glare across the top right of the Grey screen: a quadrilateral from the
 * screen's top edge down its right side, white at half strength fading out by
 * half the screen's height. Drawn in screen-local pixels.
 */
internal fun DrawScope.drawScreenGlare(unit: Float) {
    fun u(v: Float) = v * unit
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(u(162f), 0f)
        lineTo(u(SCREEN_W), 0f)
        lineTo(u(SCREEN_W), u(SCREEN_H))
        // Same slope as the mockup's glare over its 242-unit screen.
        lineTo(u(162f + 95.69f * SCREEN_H / 242f), u(SCREEN_H))
        close()
    }
    drawPath(
        path = path,
        brush = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.5f), Color(0xFFC4C4C4).copy(alpha = 0f)),
            startY = 0f,
            endY = u(SCREEN_H / 2f),
        ),
    )
}

