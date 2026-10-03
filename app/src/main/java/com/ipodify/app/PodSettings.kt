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

    private lateinit var prefs: SharedPreferences

    private val _lockScreen = MutableStateFlow(true)

    /**
     * While the iPod is open, locking the phone shows it full screen over the
     * lock screen until the phone is unlocked. Off: the system lock screen.
     */
    val lockScreen: StateFlow<Boolean> = _lockScreen.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _lockScreen.value = prefs.getBoolean(KEY_LOCK_SCREEN, true)
    }

    fun setLockScreen(value: Boolean) {
        _lockScreen.value = value
        prefs.edit().putBoolean(KEY_LOCK_SCREEN, value).apply()
    }
}
