package com.ipodify.app

import android.app.Application
import com.ipodify.app.media.NowPlaying

class IPodifyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PodSettings.init(this)
        NowPlaying.ensureConnected(this)
    }
}
