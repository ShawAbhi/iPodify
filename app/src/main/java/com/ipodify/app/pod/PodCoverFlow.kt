package com.ipodify.app.pod

import androidx.compose.ui.text.font.DeviceFontFamilyName
import android.os.Build
import coil3.toBitmap
import coil3.request.allowHardware
import coil3.compose.AsyncImagePainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.runtime.collectAsState
import android.graphics.Shader
import android.graphics.PorterDuff
import android.graphics.ComposeShader
import android.graphics.BitmapShader
import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawWithCache
import coil3.compose.rememberAsyncImagePainter
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import coil3.request.ImageRequest
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.ipodify.app.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * The playlist as Cover Flow: the selected song's artwork faces you in the
 * middle, its neighbours fan out to either side turned towards it, and every
 * cover stands on a glossy floor with its reflection beneath.
 *
 * The modern touches over the 2007 original: the backdrop is the selected
 * cover itself, blurred and dimmed, so the whole screen takes on its colour;
 * covers have softly rounded corners, a sheen and a shadow; and the flow
 * glides on a spring rather than stepping, so spinning the wheel fast streams
 * covers past and letting go settles with a little give.
 *
 * Driven entirely by [PodMenuState.selectedIndex] — the click wheel scrolls it
 * and the centre button plays the selected song, as with the list it replaces.
 */
@Composable
fun PodCoverFlow(menuState: PodMenuState) {
    val items = menuState.items
    val selected = menuState.selectedIndex.value.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val playing = menuState.playingIndex.value

    // Where the flow is, in items: fractional while it glides between two.
    val position = remember { Animatable(selected.toFloat()) }
    LaunchedEffect(selected) {
        position.animateTo(
            selected.toFloat(),
            spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
        )
    }

    val titleFont = remember { FontFamily(Font(DeviceFontFamilyName("sans-serif"), FontWeight.SemiBold)) }
    val bodyFont = remember { FontFamily(Font(DeviceFontFamilyName("sans-serif"), FontWeight.Medium)) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(PodScreenBlack)
            .clipToBounds(),
    ) {
        val density = LocalDensity.current
        // Sized off the screen, so the flow scales with the iPod.
        val cover: Dp = min(maxHeight.value * 0.50f, maxWidth.value * 0.46f).dp
        val coverPx = with(density) { cover.toPx() }
        val coverTop = maxHeight * 0.09f

        // Behind the covers, which sit at negative z so the nearest is on top.
        Box(Modifier.fillMaxSize().zIndex(-100f)) {
            PodArtBackdrop(items.getOrNull(selected)?.artworkUrl)
        }

        // Performance: [position] moves every frame while the flow glides, so it
        // is never read during composition. Composition only changes when the
        // nearest whole cover does ([nearest]); every frame after that only
        // re-places and re-draws the covers that are already there — position,
        // turn, scale, stacking order, dim and shadow are all read in the
        // placement and draw phases below.
        val nearest by remember { derivedStateOf { position.value.roundToInt() } }
        val first = max(0, nearest - VISIBLE_EACH_SIDE - 1)
        val last = min(items.size - 1, nearest + VISIBLE_EACH_SIDE + 1)
        val camera = CAMERA_COVERS * coverPx / 72f
        for (index in first..last) key(index) {
            val item = items[index]
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = coverTop)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            val d = index - position.value
                            val near = min(abs(d), 1f)
                            val far = max(abs(d) - 1f, 0f)
                            // Stacking set at placement, so it follows the glide
                            // without recomposing: the nearest cover is on top.
                            placeable.placeWithLayer(0, 0, zIndex = -abs(d)) {
                                translationX = sign(d) *
                                    (coverPx * CENTER_GAP * near + coverPx * SIDE_STEP * far)
                                // Outer edges come towards you, inner edges recede.
                                rotationY = -d.coerceIn(-1f, 1f) * SIDE_ANGLE
                                val s = lerp(1f, SIDE_SCALE, near)
                                scaleX = s
                                scaleY = s
                                // The camera distance is in the renderer's 72-per-inch
                                // units, not pixels, so it is set from the cover's size
                                // in px: [CAMERA_COVERS] covers back gives gentle
                                // perspective on any density instead of a fish-eye.
                                cameraDistance = camera
                                // Pivot on the cover itself, not cover + reflection.
                                transformOrigin = TransformOrigin(0.5f, 1f / (1f + REFLECTION))
                                alpha = (VISIBLE_EACH_SIDE + 0.5f - abs(d)).coerceIn(0f, 1f)
                            }
                        }
                    },
            ) {
                CoverWithReflection(
                    url = item.artworkUrl,
                    side = cover,
                    // Side covers sit back in the dark a little.
                    dim = {
                        val d = abs(index - position.value)
                        min(d, 1f) * 0.38f + max(d - 1f, 0f) * 0.06f
                    },
                )
            }
        }

        // Title and artist, under the middle cover.
        val current = items.getOrNull(selected)
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = cover * 0.16f)
                .padding(bottom = cover * 0.10f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val titleSize = with(density) { (cover * 0.125f).toSp() }
            val bodySize = with(density) { (cover * 0.10f).toSp() }
            // Plain text, no crossfade: a fast spin changes it many times a
            // second, and each crossfade would compose and draw two copies.
            val item = current
            run {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (item != null && selected == playing) {
                            NowPlayingDot(cover * 0.05f)
                            Spacer(Modifier.width(cover * 0.04f))
                        }
                        Text(
                            text = item?.title ?: "",
                            style = TextStyle(
                                color = Color.White,
                                fontFamily = titleFont,
                                fontSize = titleSize,
                                textAlign = TextAlign.Center,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = item?.subtitle ?: "",
                        style = TextStyle(
                            color = Color.White.copy(alpha = 0.62f),
                            fontFamily = bodyFont,
                            fontSize = bodySize,
                            textAlign = TextAlign.Center,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (items.size > 1) {
                Spacer(Modifier.height(cover * 0.06f))
                Text(
                    text = "${selected + 1} of ${items.size}",
                    style = TextStyle(
                        color = Color.White.copy(alpha = 0.38f),
                        fontFamily = bodyFont,
                        fontSize = with(density) { (cover * 0.08f).toSp() },
                    ),
                )
            }
        }
    }
}

/**
 * The selected cover, blown up, blurred and dimmed behind everything, with a
 * dark floor fading in below the covers.
 *
 * Performance: while the selection is moving the backdrop stays dark — it
 * fades out at the first tick and only comes back once the flow has rested on
 * a cover for [BACKDROP_SETTLE_MS] and that cover's tiny backdrop image has
 * loaded. While hidden, its layer has zero alpha and isn't drawn at all, so
 * scrolling never pays for the blur. Below Android 12 there is no blur, so it
 * is simply dimmed further.
 */
@Composable
internal fun PodArtBackdrop(url: Any?) {
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val context = LocalContext.current

    var shownUrl by remember { mutableStateOf<Any?>(null) }
    var loadedUrl by remember { mutableStateOf<Any?>(null) }
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(url) {
        if (reveal.value > 0f) reveal.animateTo(0f, tween(BACKDROP_FADE_OUT_MS))
        delay(BACKDROP_SETTLE_MS)
        shownUrl = url
        if (url == null) return@LaunchedEffect
        // Fade in only once there is something to show.
        snapshotFlow { loadedUrl }.first { it == url }
        reveal.animateTo(1f, tween(BACKDROP_FADE_IN_MS))
    }

    val art = shownUrl
    if (art != null) {
        // Decoded tiny: it is blurred to nothing anyway, and upscaling a small
        // image is half the blur for free, so the real blur can be light.
        val request = remember(art) {
            ImageRequest.Builder(context)
                .data(art)
                .size(BACKDROP_ART_PX)
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.Low,
            onSuccess = { loadedUrl = art },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Read here, not in composition: the fade only redraws.
                    alpha = reveal.value * if (canBlur) 0.85f else 0.35f
                    scaleX = 1.5f
                    scaleY = 1.5f
                }
                .then(
                    if (canBlur) Modifier.blur(14.dp, BlurredEdgeTreatment.Rectangle) else Modifier,
                ),
        )
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(BACKDROP_FLOOR),
    )
}

private val BACKDROP_FLOOR = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.30f),
    0.55f to Color.Black.copy(alpha = 0.55f),
    1f to Color.Black.copy(alpha = 0.92f),
)

/**
 * One cover standing on its reflection, drawn as a single node from a single
 * image.
 *
 * Performance: the reflection is faked. Rather than a second image, flipped and
 * masked in an offscreen layer, the same painter is drawn again upside down
 * below the cover and a gradient into the screen's black is laid over it —
 * two draws of an image that is already decoded, no extra load, no offscreen
 * buffer. The rounded clip, sheen and fade are built once per size; per frame
 * only [dim] is read, in the draw phase, so a glide redraws covers without
 * recomposing them. There is no drop shadow: shadows re-render as they change,
 * and on the dark screen it didn't show.
 */
@Composable
internal fun CoverWithReflection(url: Any?, side: Dp, dim: () -> Float) {
    val context = LocalContext.current
    val sidePx = with(LocalDensity.current) { side.roundToPx() }.coerceAtLeast(1)
    val request = remember(url, sidePx) {
        // Decoded at exactly the size it is drawn: no bigger bitmap than needed.
        url?.let {
            ImageRequest.Builder(context)
                .data(it)
                .size(sidePx)
                // A software bitmap, so the reflection can sample it in a shader.
                .allowHardware(false)
                .build()
        }
    }
    val painter = rememberAsyncImagePainter(request)
    // The decoded artwork, once it is in: the reflection draws from it directly.
    val painterState by painter.state.collectAsState()
    val bitmap = (painterState as? AsyncImagePainter.State.Success)?.result?.image
    val artwork = remember(bitmap) { bitmap?.toBitmap() }

    Spacer(
        Modifier
            .size(side, side * (1f + REFLECTION_GAP + REFLECTION))
            .drawWithCache {
                val s = size.width
                val coverSize = Size(s, s)
                val clip = Path().apply {
                    addRoundRect(RoundRect(Rect(Offset.Zero, coverSize), CornerRadius(s * 0.035f)))
                }
                val reflectionTop = s * (1f + REFLECTION_GAP)
                val reflectionSize = Size(s, s * REFLECTION)
                val placeholder = Brush.linearGradient(
                    listOf(Color(0xFF3A3A44), Color(0xFF17171C)),
                    end = Offset(s, s),
                )
                val sheen = Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.16f),
                    0.45f to Color.White.copy(alpha = 0.04f),
                    0.46f to Color.Transparent,
                    start = Offset.Zero,
                    end = Offset(s, s),
                )
                // The reflection: the artwork flipped about the gap and faded
                // out by a transparency mask, all in one shader — a single draw
                // with a true see-through fade, so it melts into whatever is
                // behind it instead of ending in a black box. No second image
                // load and no offscreen layer.
                val reflection = artwork?.let { reflectionBrush(it, s, reflectionTop, reflectionSize.height) }
                onDrawBehind {
                    val amount = dim().coerceIn(0f, 0.8f)
                    clipPath(clip) {
                        drawRect(placeholder, size = coverSize)
                        drawCropped(painter, s)
                        drawRect(sheen, size = coverSize)
                        if (amount > 0f) drawRect(Color.Black.copy(alpha = amount), size = coverSize)
                    }
                    if (reflection != null) {
                        // Dimmed by alpha rather than a black overlay, which
                        // would show as a box where the reflection has faded.
                        drawRect(
                            reflection,
                            topLeft = Offset(0f, reflectionTop),
                            size = reflectionSize,
                            alpha = 1f - amount,
                        )
                    }
                }
            },
    )
}

/**
 * The reflection of a cover [side] px square: [artwork] centre-cropped as the
 * cover is, mirrored so its bottom edge meets the cover's at [top], and masked
 * from [REFLECTION_START_ALPHA] at the top to fully transparent [height] below.
 */
private fun reflectionBrush(artwork: Bitmap, side: Float, top: Float, height: Float): ShaderBrush {
    val image = BitmapShader(artwork, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    val scale = max(side / artwork.width, side / artwork.height)
    val w = artwork.width * scale
    val h = artwork.height * scale
    // Cover: y = (side - h) / 2 + v·scale. Mirrored about the line halfway
    // between the cover's bottom (side) and the reflection's top (top).
    image.setLocalMatrix(
        android.graphics.Matrix().apply {
            setScale(scale, -scale)
            postTranslate((side - w) / 2f, side + top - (side - h) / 2f)
        },
    )
    val mask = android.graphics.LinearGradient(
        0f, top, 0f, top + height,
        intArrayOf(
            Color.Black.copy(alpha = REFLECTION_START_ALPHA).toArgb(),
            Color.Black.copy(alpha = REFLECTION_START_ALPHA * 0.35f).toArgb(),
            Color.Transparent.toArgb(),
        ),
        floatArrayOf(0f, 0.45f, 1f),
        Shader.TileMode.CLAMP,
    )
    // DST_IN: the image (dst) kept only as far as the mask (src) is opaque.
    return ShaderBrush(ComposeShader(image, mask, PorterDuff.Mode.DST_IN))
}

/** Draws [painter] centre-cropped into a [side] square at the origin. */
private fun DrawScope.drawCropped(painter: Painter, side: Float) {
    val intrinsic = painter.intrinsicSize
    if (intrinsic.isUnspecified || intrinsic.width <= 0f || intrinsic.height <= 0f) {
        with(painter) { draw(Size(side, side)) }
        return
    }
    val scale = max(side / intrinsic.width, side / intrinsic.height)
    val w = intrinsic.width * scale
    val h = intrinsic.height * scale
    translate((side - w) / 2f, (side - h) / 2f) {
        with(painter) { draw(Size(w, h)) }
    }
}

/** A small glowing dot marking the song that is playing now. */
@Composable
internal fun NowPlayingDot(size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(PodAccent),
    )
}

/** The screen's one accent colour: the playing dot, scrubbing, the volume bar. */
internal val PodAccent = Color(0xFF5AC8FA)

/** The screen's base colour, under the blurred artwork. */
internal val PodScreenBlack = Color(0xFF0B0B0F)

/** How long the selection must rest before the backdrop comes back. */
private const val BACKDROP_SETTLE_MS = 260L

/** How quickly the backdrop goes dark when scrolling starts. */
private const val BACKDROP_FADE_OUT_MS = 120

/** How gently it comes back on the cover the flow stopped at. */
private const val BACKDROP_FADE_IN_MS = 450

/** The gap between a cover and its reflection, as a fraction of its height. */
private const val REFLECTION_GAP = 0.008f

/** How strongly the reflection shows where it meets the cover, 0–1. */
private const val REFLECTION_START_ALPHA = 0.42f

/** The backdrop's decode size: tiny, as it is blurred and scaled up. */
private const val BACKDROP_ART_PX = 96

/** How many covers are drawn either side of the middle. */
private const val VISIBLE_EACH_SIDE = 4

/** Distance from the middle cover's centre to its neighbour's, in covers. */
private const val CENTER_GAP = 0.78f

/** Distance between successive side covers, in covers. */
private const val SIDE_STEP = 0.24f

/** How far back the perspective camera sits, in cover widths. */
private const val CAMERA_COVERS = 3.2f

/** How far side covers turn, in degrees. */
private const val SIDE_ANGLE = 58f

/** Side covers' size relative to the middle one. */
private const val SIDE_SCALE = 0.84f

/** How much of each cover's reflection shows, as a fraction of its height. */
private const val REFLECTION = 0.45f
