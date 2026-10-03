package com.ipodify.app.media

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

/**
 * One track, as the iPod sees it — whichever app is actually playing it.
 *
 * [thumbnailUrl] is whatever Coil can load the artwork from: usually a
 * [android.graphics.Bitmap] handed over in the session's metadata, sometimes a
 * URI string. Named after the field the iPod screens were written against.
 */
@Immutable
data class Song(
    /** The session's queue id when there is one, else a key built from the metadata. */
    val id: String,
    val title: String,
    val artist: String,
    val albumName: String? = null,
    val thumbnailUrl: Any? = null,
)

/**
 * The playhead. Its identity never changes, so carrying it in [PlayerState]
 * costs nothing; only what reads [positionMs] redraws as it ticks.
 */
@Stable
class PlaybackPosition internal constructor() {
    var positionMs by mutableLongStateOf(0L)
        internal set
}

/** What is playing now, in whatever app, and what comes after it. */
@Immutable
data class PlayerState(
    val song: Song? = null,
    val isPlaying: Boolean = false,
    val position: PlaybackPosition = PlaybackPosition(),
    val durationMs: Long = 0L,
    /**
     * The playing app's queue, when it shares one; otherwise just [song], so
     * Cover Flow always has the current track to show.
     */
    val queue: List<Song> = emptyList(),
    val queueIndex: Int = 0,
    /** The playing app's name, e.g. "Spotify"; null when nothing is playing. */
    val appName: String? = null,
)

/**
 * The transport the iPod drives. Implemented by [NowPlaying], which forwards
 * every call to the media session of the app that is playing.
 */
interface PodController {
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekToNext()
    fun seekToPrevious()

    /** Jumps to the [index]th item of [PlayerState.queue] and starts it. */
    fun seekToDefaultPosition(index: Int)
}
