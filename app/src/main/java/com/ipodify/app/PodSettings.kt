package com.ipodify.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** iPodify's settings, kept in SharedPreferences. Initialised in [IPodifyApp]. */
object PodSettings {

    private const val PREFS = "ipodify"
    private const val KEY_LOCK_SCREEN = "lock_screen"
    private const val KEY_AUTO_SHOW = "auto_show"
    private const val KEY_BUBBLE_RIGHT = "bubble_right"
    private const val KEY_BUBBLE_Y = "bubble_y"

    private lateinit var prefs: SharedPreferences

    private val _lockScreen = MutableStateFlow(true)
    private val _autoShow = MutableStateFlow(true)

    /**
     * While the iPod is open, locking the phone shows it full screen over the
     * lock screen until the phone is unlocked. Off: the system lock screen.
     */
    val lockScreen: StateFlow<Boolean> = _lockScreen.asStateFlow()

    /**
     * The bubble appears by itself, tucked half into the screen edge, whenever
     * music starts playing in any app.
     */
    val autoShow: StateFlow<Boolean> = _autoShow.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _lockScreen.value = prefs.getBoolean(KEY_LOCK_SCREEN, true)
        _autoShow.value = prefs.getBoolean(KEY_AUTO_SHOW, true)
    }

    fun setLockScreen(value: Boolean) {
        _lockScreen.value = value
        prefs.edit().putBoolean(KEY_LOCK_SCREEN, value).apply()
    }

    fun setAutoShow(value: Boolean) {
        _autoShow.value = value
        prefs.edit().putBoolean(KEY_AUTO_SHOW, value).apply()
    }

    /** Which side the bubble was last dropped on. Defaults to the right. */
    val bubbleOnRight: Boolean get() = prefs.getBoolean(KEY_BUBBLE_RIGHT, true)

    /**
     * How far down the screen the bubble was last dropped, from 0 (top of the
     * usable area) to 1 (bottom) — a fraction, so it survives rotation.
     */
    val bubbleY: Float get() = prefs.getFloat(KEY_BUBBLE_Y, 0.25f)

    fun setBubbleSpot(onRight: Boolean, y: Float) {
        prefs.edit()
            .putBoolean(KEY_BUBBLE_RIGHT, onRight)
            .putFloat(KEY_BUBBLE_Y, y.coerceIn(0f, 1f))
            .apply()
    }
}
