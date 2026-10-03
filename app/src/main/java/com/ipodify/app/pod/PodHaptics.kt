package com.ipodify.app.pod

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Haptics for the iPod overlay that always play.
 *
 * View haptics don't fire inside an overlay window, and a plain vibration is
 * classed as touch feedback — which the system drops when touch vibration is
 * turned down or off, in Do Not Disturb, or in battery saver. Played as an
 * *alarm* vibration instead, the wheel's ticks and clicks get through
 * regardless of those settings, like the real click wheel's always-on clicker.
 */
internal object PodHaptics {

    /** A wheel detent, a magnet catch. */
    fun tick(context: Context) = play(context, VibrationEffect.EFFECT_TICK, 10)

    /** A button press. */
    fun click(context: Context) = play(context, VibrationEffect.EFFECT_CLICK, 20)

    /** Something final: dismissing, auto-minimising. */
    fun heavy(context: Context) = play(context, VibrationEffect.EFFECT_HEAVY_CLICK, 30)

    private fun play(context: Context, predefined: Int, fallbackMs: Long) {
        val vibrator = vibratorOf(context) ?: return
        if (!vibrator.hasVibrator()) return
        try {
            val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(predefined)
            } else {
                VibrationEffect.createOneShot(fallbackMs, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(
                    effect,
                    VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build(),
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, ALARM_AUDIO_ATTRIBUTES)
            }
        } catch (_: Exception) {
            // A vibrator that refuses is not worth crashing the iPod over.
        }
    }

    private fun vibratorOf(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private val ALARM_AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
}
