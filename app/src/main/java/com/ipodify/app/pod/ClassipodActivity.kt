package com.ipodify.app.pod

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * An invisible trampoline that gets the "Display over other apps" grant the
 * Classipod window needs, then starts it. Reached through
 * [FloatingPodService.start] whenever the grant is missing.
 *
 * Asks exactly once per launch. Coming back from the system screen without
 * granting it finishes with a message instead of sending the user straight
 * back there.
 */
class ClassipodActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())

    private val overlaySettings =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // The system screen gives no result code worth reading, so the
            // grant itself is checked. Some devices report it a beat late, so
            // a miss gets one short second look before it counts as a refusal.
            if (!startIfAllowed()) {
                handler.postDelayed({
                    if (!startIfAllowed()) {
                        Toast.makeText(
                            this,
                            "The iPod needs \"Display over other apps\" to float over your screen",
                            Toast.LENGTH_LONG,
                        ).show()
                        finish()
                    }
                }, RECHECK_DELAY_MS)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated mid-request (rotation, process death): the pending result
        // is still delivered to the callback above, so don't ask a second time.
        if (savedInstanceState != null) return
        if (startIfAllowed()) return

        Toast.makeText(
            this,
            "Allow \"Display over other apps\" for the iPod",
            Toast.LENGTH_LONG,
        ).show()
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { overlaySettings.launch(intent) }.onFailure {
            // Some devices hide the per-app screen; fall back to the list.
            runCatching {
                overlaySettings.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }.onFailure { finish() }
        }
    }

    /** Starts the window and finishes if the grant is there; false otherwise. */
    private fun startIfAllowed(): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        startService(Intent(this, FloatingPodService::class.java))
        finish()
        return true
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val RECHECK_DELAY_MS = 500L
    }
}
