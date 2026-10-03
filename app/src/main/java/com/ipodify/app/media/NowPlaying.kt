package com.ipodify.app.media

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Whatever is playing on the phone, from any app — Spotify, YouTube Music,
 * Apple Music, SoundCloud, a podcast app — and the remote to drive it.
 *
 * Android publishes every player's media session (the thing its notification
 * and lock-screen controls talk to), and an app holding notification access
 * may read them: [MediaListenerService] is that access. From the sessions this
 * follows the one that is playing — or, when nothing is, the one that played
 * last — and mirrors its track, artwork, progress, play state and, when the app
 * shares it, its queue into [state]. Every [PodController] call is sent back to
 * that session's transport controls.
 *
 * How much comes through is up to each app: all of them give the current track
 * and the transport; not all of them share their queue (or artwork for the
 * tracks in it), in which case the queue is just the current track.
 *
 * Main thread only.
 */
object NowPlaying : PodController {

    private const val TAG = "iPodify"

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _connected = MutableStateFlow(false)
    /** True while the session list can be read: notification access is granted and live. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val position = PlaybackPosition()
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var appContext: Context? = null
    private var manager: MediaSessionManager? = null
    private var listenerComponent: ComponentName? = null

    /** Every live session, each with the callback watching it. */
    private val watched = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()

    /** The session [state] mirrors. */
    private var active: MediaController? = null
    private var activeQueue: List<MediaSession.QueueItem> = emptyList()
    private var ticker: Job? = null
    /** The followed track's length, for clamping the playhead. */
    private var durationMs = 0L

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        watch(controllers.orEmpty())
    }

    // ── Access ───────────────────────────────────────────────────────────

    /** Whether the user has granted notification access to [MediaListenerService]. */
    fun hasAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /**
     * Connects if access is granted and nothing is connected yet. Safe to call
     * often (on every resume). If the listener was killed while access stayed
     * granted, asks the system to bind it again.
     */
    fun ensureConnected(context: Context) {
        if (manager != null || !hasAccess(context)) return
        val component = ComponentName(context, MediaListenerService::class.java)
        connect(context, component)
        if (manager == null) {
            runCatching { NotificationListenerService.requestRebind(component) }
        }
    }

    /** Called by [MediaListenerService] once the system has bound it. */
    internal fun connect(context: Context, component: ComponentName) {
        val app = context.applicationContext
        val sessions = app.getSystemService(MediaSessionManager::class.java) ?: return
        try {
            val current = sessions.getActiveSessions(component)
            sessions.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            appContext = app
            manager = sessions
            listenerComponent = component
            _connected.value = true
            watch(current)
        } catch (e: SecurityException) {
            // Access was revoked, or the listener isn't bound yet.
            Log.w(TAG, "No access to media sessions: ${e.message}")
        }
    }

    /** Called when the listener is unbound: notification access revoked, or the app stopped. */
    internal fun disconnect() {
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        manager = null
        listenerComponent = null
        watch(emptyList())
        _connected.value = false
    }

    // ── Following the sessions ──────────────────────────────────────────

    private fun watch(controllers: List<MediaController>) {
        val tokens = controllers.map { it.sessionToken }.toSet()
        // Stop watching sessions that have gone.
        watched.keys.filter { it !in tokens }.forEach { token ->
            watched.remove(token)?.let { (controller, callback) ->
                runCatching { controller.unregisterCallback(callback) }
            }
        }
        // Start watching new ones. Any of them changing can change which one
        // is "the" player — the user pressing play in another app, say.
        controllers.forEach { controller ->
            if (controller.sessionToken !in watched) {
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) = evaluate()
                    override fun onMetadataChanged(metadata: MediaMetadata?) = evaluate()
                    override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) = evaluate()
                    override fun onSessionDestroyed() {
                        watched.remove(controller.sessionToken)
                        evaluate()
                    }
                }
                controller.registerCallback(callback, handler)
                watched[controller.sessionToken] = controller to callback
            }
        }
        evaluate()
    }

    /** Picks the session to follow and publishes it. */
    private fun evaluate() {
        val controllers = watched.values.map { it.first }
        val playing = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val keep = active?.takeIf { current -> controllers.any { it.sessionToken == current.sessionToken } }
        active = playing ?: keep ?: controllers.firstOrNull()
        publish()
    }

    private fun publish() {
        val controller = active
        if (controller == null) {
            activeQueue = emptyList()
            ticker?.cancel()
            position.positionMs = 0L
            _state.value = PlayerState(position = position)
            return
        }
        val metadata = controller.metadata
        val playback = controller.playbackState
        val isPlaying = playback?.state == PlaybackState.STATE_PLAYING ||
            playback?.state == PlaybackState.STATE_BUFFERING
        val current = metadata?.toSong()

        activeQueue = controller.queue.orEmpty()
        val queueSongs = activeQueue.map { it.toSong() }
        val activeId = playback?.activeQueueItemId ?: MediaSession.QueueItem.UNKNOWN_ID.toLong()
        var index = activeQueue.indexOfFirst { it.queueId == activeId }
        if (index < 0 && current != null) {
            index = queueSongs.indexOfFirst { it.title == current.title && it.artist == current.artist }
        }

        val queue: List<Song>
        val queueIndex: Int
        if (queueSongs.isEmpty() || index < 0) {
            // No usable queue: Cover Flow shows the one track there is.
            queue = listOfNotNull(current)
            queueIndex = 0
        } else {
            // The playing item takes the richer metadata (artwork above all,
            // which queues often leave out).
            queue = queueSongs.toMutableList().also { list ->
                if (current != null) {
                    val item = list[index]
                    list[index] = item.copy(thumbnailUrl = current.thumbnailUrl ?: item.thumbnailUrl)
                }
            }
            queueIndex = index
        }

        durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
        updatePosition(playback)
        _state.value = PlayerState(
            song = queue.getOrNull(queueIndex) ?: current,
            isPlaying = isPlaying,
            position = position,
            durationMs = durationMs,
            queue = queue,
            queueIndex = queueIndex,
            appName = appLabel(controller.packageName),
        )

        ticker?.cancel()
        if (isPlaying) {
            ticker = scope.launch {
                while (isActive) {
                    delay(500)
                    updatePosition(active?.playbackState)
                }
            }
        }
    }

    /** The playhead now: the last reported position, run forward at the playback speed. */
    private fun updatePosition(playback: PlaybackState?) {
        if (playback == null) {
            position.positionMs = 0L
            return
        }
        var ms = playback.position
        if (playback.state == PlaybackState.STATE_PLAYING && playback.lastPositionUpdateTime > 0) {
            val elapsed = SystemClock.elapsedRealtime() - playback.lastPositionUpdateTime
            ms += (elapsed * playback.playbackSpeed).toLong()
        }
        position.positionMs = if (durationMs > 0) ms.coerceIn(0L, durationMs) else ms.coerceAtLeast(0L)
    }

    private val labels = mutableMapOf<String, String>()

    private fun appLabel(packageName: String): String = labels.getOrPut(packageName) {
        val pm = appContext?.packageManager ?: return@getOrPut packageName
        try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    // ── Transport ────────────────────────────────────────────────────────

    private val controls get() = active?.transportControls

    override fun play() { controls?.play() }
    override fun pause() { controls?.pause() }
    override fun seekTo(positionMs: Long) { controls?.seekTo(positionMs.coerceAtLeast(0L)) }
    override fun seekToNext() { controls?.skipToNext() }
    override fun seekToPrevious() { controls?.skipToPrevious() }

    override fun seekToDefaultPosition(index: Int) {
        val item = activeQueue.getOrNull(index) ?: return
        controls?.skipToQueueItem(item.queueId)
        controls?.play()
    }
}

// ── Mapping ──────────────────────────────────────────────────────────────

private fun MediaMetadata.toSong(): Song {
    val title = text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: text(MediaMetadata.METADATA_KEY_TITLE) ?: ""
    val artist = text(MediaMetadata.METADATA_KEY_ARTIST)
        ?: text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        ?: text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        ?: ""
    return Song(
        id = "now:$title|$artist",
        title = title,
        artist = artist,
        albumName = text(MediaMetadata.METADATA_KEY_ALBUM),
        thumbnailUrl = artwork(),
    )
}

private fun MediaMetadata.text(key: String): String? =
    getText(key)?.toString()?.takeIf { it.isNotBlank() }

/** The best artwork the metadata carries: a bitmap if there is one, else a loadable URI. */
private fun MediaMetadata.artwork(): Any? {
    val bitmap: Bitmap? = getBitmap(MediaMetadata.METADATA_KEY_ART)
        ?: getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        ?: getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    if (bitmap != null) return bitmap
    return listOf(
        MediaMetadata.METADATA_KEY_ART_URI,
        MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
        MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
    ).firstNotNullOfOrNull { key -> getString(key)?.takeIf { it.isLoadableUri() } }
}

private fun MediaSession.QueueItem.toSong(): Song {
    val d = description
    return Song(
        id = "q:$queueId",
        title = d.title?.toString().orEmpty(),
        artist = d.subtitle?.toString().orEmpty(),
        albumName = d.description?.toString()?.takeIf { it.isNotBlank() },
        thumbnailUrl = d.iconBitmap ?: d.iconUri?.toString()?.takeIf { it.isLoadableUri() },
    )
}

/** Only what Coil can open from here: other apps' private schemes ("spotify:image:…") are not. */
private fun String.isLoadableUri(): Boolean =
    startsWith("http://") || startsWith("https://") || startsWith("content://") ||
        startsWith("android.resource://") || startsWith("file://")
