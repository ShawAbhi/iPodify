package com.ipodify.app

import android.app.Application
import android.os.SystemClock
import com.ipodify.app.media.NowPlaying
import com.ipodify.app.pod.FloatingPodService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class IPodifyApp : Application() {

    private val scope = MainScope()

    override fun onCreate() {
        super.onCreate()
        PodSettings.init(this)
        NowPlaying.ensureConnected(this)
        showBubbleWhenMusicStarts()
    }

    /**
     * Brings the bubble up, tucked into its edge, the moment any app starts
     * playing — unless it is switched off ([PodSettings.autoShow]) or already
     * up.
     *
     * Only a real start counts: after a stop, playback must have stayed
     * stopped for [RESTART_GAP_MS]. Many apps report a moment of "paused"
     * between tracks, and without the gap a bubble the user had just closed
     * would pop back up at the next song.
     */
    private fun showBubbleWhenMusicStarts() {
        var wasPlaying = false
        var stoppedAt: Long? = null // Null: hasn't played since launch.
        scope.launch {
            NowPlaying.state
                .map { it.isPlaying }
                .distinctUntilChanged()
                .collect { playing ->
                    val now = SystemClock.elapsedRealtime()
                    if (!playing) {
                        if (wasPlaying) stoppedAt = now
                    } else {
                        val gapOk = stoppedAt.let { it == null || now - it >= RESTART_GAP_MS }
                        if (gapOk && PodSettings.autoShow.value) {
                            FloatingPodService.showForMusic(this@IPodifyApp)
                        }
                    }
                    wasPlaying = playing
                }
        }
    }

    private companion object {
        const val RESTART_GAP_MS = 2_000L
    }
}
