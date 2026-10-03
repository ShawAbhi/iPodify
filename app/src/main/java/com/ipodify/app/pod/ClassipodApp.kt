package com.ipodify.app.pod

import android.media.AudioManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ipodify.app.media.PodController
import com.ipodify.app.media.PlayerState

@Composable
fun ClassipodApp(
    controller: PodController?,
    playerState: PlayerState
) {
    val context = LocalContext.current
    val menuState = remember { PodMenuState() }

    // Ensure we start with Playlist and update immediately
    LaunchedEffect(playerState.queue) {
        menuState.title.value = "Playlist"
        
        val queueItems = playerState.queue.mapIndexed { index, song ->
            MenuItem(
                title = song.title,
                hasArrow = false,
                subtitle = song.artist,
                artworkUrl = song.thumbnailUrl,
            ) {
                controller?.seekToDefaultPosition(index)
                controller?.play()
                menuState.isNowPlaying.value = true
            }
        }
        val newItems = queueItems.ifEmpty { listOf(MenuItem("Empty", false) {}) }
        
        // Opening on a fresh queue, start the flow at the song that's playing.
        val firstFill = menuState.items.isEmpty() || menuState.items.singleOrNull()?.title == "Empty"
        if (firstFill && queueItems.isNotEmpty()) {
            menuState.selectedIndex.value = playerState.queueIndex
        }

        // We are no longer using nested menus, so just set the items directly
        menuState.items.clear()
        menuState.items.addAll(newItems)
        
        // Keep selection within bounds
        menuState.selectedIndex.value = menuState.selectedIndex.value.coerceIn(0, (newItems.size - 1).coerceAtLeast(0))
    }

    LaunchedEffect(playerState.queueIndex, playerState.queue) {
        menuState.playingIndex.value = if (playerState.queue.isEmpty()) -1 else playerState.queueIndex
    }

    val finish = rememberPodFinish()

    // The iPod classic body from the Figma mockup, drawn at whatever size the
    // window gives it. Everything inside is positioned in the design's own
    // units (377 across), so [unit] is all that changes with the size.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val unit = maxWidth / POD_DESIGN_WIDTH
        val bodyShape = RoundedCornerShape(unit * POD_CORNER_UNITS)

        Box(
            modifier = Modifier
                .fillMaxSize()
                // The design has no drop shadow; this one lifts the iPod off
                // whatever app it floats over.
                .shadow(12.dp, bodyShape)
                .clip(bodyShape)
                .drawBehind { drawPodBody(finish, unit.toPx()) },
        ) {
            // Screen: 318 × [SCREEN_H] glass at (30, 24), with the display inset by
            // the bezel and, on the Grey finish, the glare over it.
            Box(
                modifier = Modifier
                    .offset(unit * SCREEN_X, unit * SCREEN_Y)
                    .size(unit * SCREEN_W, unit * SCREEN_H)
                    .clip(RoundedCornerShape(unit * SCREEN_CORNER))
                    .background(finish.screen)
                    .then(
                        finish.screenBezel?.let { bezel ->
                            Modifier.border(unit * SCREEN_BEZEL, bezel, RoundedCornerShape(unit * SCREEN_CORNER))
                        } ?: Modifier,
                    )
                    .drawWithContent {
                        drawContent()
                        if (finish.screenGlare) drawScreenGlare(unit.toPx())
                    }
                    .padding(unit * SCREEN_BEZEL),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // No CRT overlay: it was redrawn over the whole display on
                        // every frame of Cover Flow, for a look the dark screen no
                        // longer needs.
                        .clip(RoundedCornerShape(unit * 3f)),
                ) {
                    PodScreen(controller, playerState, menuState)
                }
            }

            // Click wheel: 230 units at (74, [WHEEL_Y]).
            ClickWheel(
                modifier = Modifier
                    .offset(unit * WHEEL_X, unit * WHEEL_Y)
                    .size(unit * WHEEL_SIZE),

                    onScroll = { ticks -> 
                        if (menuState.isNowPlaying.value) {
                            if (menuState.isScrubbingMode.value) {
                                val duration = playerState.durationMs
                                if (duration > 0) {
                                    val currentBase = menuState.scrubPositionMs.value ?: playerState.position.positionMs
                                    // 2% of the song per tick, or at least 5 seconds
                                    val seekAmountMs = maxOf(5000L, (duration * 0.02).toLong()) * ticks 
                                    val newPos = (currentBase + seekAmountMs).coerceIn(0L, duration)
                                    menuState.scrubPositionMs.value = newPos
                                    true
                                } else {
                                    false
                                }
                            } else {
                                val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
                                val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                val newVol = (currentVol + ticks).coerceIn(0, maxVol)
                                // Shown even when already at the end stop, so a turn
                                // that does nothing still says why.
                                menuState.volume.value = if (maxVol > 0) newVol / maxVol.toFloat() else 0f
                                menuState.volumeTouches.value++
                                if (currentVol != newVol) {
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                    true
                                } else {
                                    false
                                }
                            }
                        } else {
                            menuState.scroll(ticks)
                        }
                    },
                    onClickMenu = {
                        menuState.isNowPlaying.value = !menuState.isNowPlaying.value
                        if (menuState.isScrubbingMode.value) {
                            menuState.scrubPositionMs.value?.let { finalPos -> controller?.seekTo(finalPos) }
                            controller?.play()
                        }
                        menuState.isScrubbingMode.value = false
                        menuState.scrubPositionMs.value = null
                    },
                    onClickPlayPause = {
                        if (playerState.isPlaying) controller?.pause() else controller?.play()
                    },
                    onClickNext = { controller?.seekToNext() },
                    onClickPrev = { controller?.seekToPrevious() },
                    onClickCenter = {
                        if (!menuState.isNowPlaying.value && menuState.items.isNotEmpty()) {
                            menuState.items[menuState.selectedIndex.value].onClick()
                        } else if (menuState.isNowPlaying.value) {
                            menuState.isScrubbingMode.value = !menuState.isScrubbingMode.value
                            if (menuState.isScrubbingMode.value) {
                                controller?.pause()
                                menuState.scrubPositionMs.value = playerState.position.positionMs
                            } else {
                                // Exiting scrub mode: perform the final definitive seek
                                menuState.scrubPositionMs.value?.let { finalPos -> controller?.seekTo(finalPos) }
                                controller?.play()
                                menuState.scrubPositionMs.value = null
                            }
                        }
                    }
            )

            // The edge shadows sit over everything, blurred, inside the body's
            // clip — the file's "shadows" group.
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(unit * EDGE_SHADOW_BLUR, BlurredEdgeTreatment.Unbounded),
            ) {
                drawPodEdgeShadows(finish, unit.toPx())
            }
        }
    }
}

/** The shadows' 20-unit Figma layer blur, as a Compose blur radius of roughly the same spread. */
private const val EDGE_SHADOW_BLUR = 18f
