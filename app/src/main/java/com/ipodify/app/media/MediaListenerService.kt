package com.ipodify.app.media

import android.content.ComponentName
import android.service.notification.NotificationListenerService

/**
 * Notification access, which is what Android requires before an app may read
 * other apps' media sessions. iPodify reads no notifications: this service
 * exists only so [NowPlaying] can list the sessions and drive them.
 */
class MediaListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        NowPlaying.connect(this, ComponentName(this, MediaListenerService::class.java))
    }

    override fun onListenerDisconnected() {
        NowPlaying.disconnect()
        super.onListenerDisconnected()
    }
}
