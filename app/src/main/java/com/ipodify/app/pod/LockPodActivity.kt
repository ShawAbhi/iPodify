package com.ipodify.app.pod

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.ipodify.app.media.NowPlaying
import com.ipodify.app.media.PodController
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ipodify.app.ui.IPodifyTheme
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/**
 * The iPod as the lock screen: the same iPod the PIP shows, filling the
 * screen, with the wheel, menus and Cover Flow all working.
 *
 * Shown only while the PIP is open (it is [FloatingPodService] that starts it,
 * as the screen turns off) and only with "iPod lock screen" on in iPodify.
 * It is *shown when locked*, so it covers the keyguard without unlocking
 * anything: swiping up from the bottom asks the system to dismiss the
 * keyguard — PIN, pattern or fingerprint as usual — and only then gets out of
 * the way. The PIP's own windows are hidden while the keyguard is up, so
 * there is only ever one iPod on screen.
 *
 * It finishes itself on unlock, when the PIP closes, or when it finds itself
 * up with no keyguard to cover (woken inside the lock delay, or unlocked by
 * fingerprint from the dark screen).
 */
class LockPodActivity : ComponentActivity() {

    companion object {
        private var current: WeakReference<LockPodActivity>? = null

        /** Starts the lock screen. Called by the service as the screen turns off. */
        fun show(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(context, LockPodActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                    ),
                )
            }
        }

        /** Takes the lock screen down if it is up. */
        fun dismiss() {
            current?.get()?.let { if (!it.isFinishing) it.finishQuietly() }
            current = null
        }
    }

    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            // Never wakes the screen itself: it waits for the user to.
            setTurnScreenOn(false)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        enableEdgeToEdge()

        setContent {
            IPodifyTheme {
                LockPod(onUnlock = ::requestUnlock)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Up with the screen on but nothing locked: nothing to cover.
        if (powerManager.isInteractive && !keyguardManager.isKeyguardLocked) finishQuietly()
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    private fun requestUnlock() {
        if (!keyguardManager.isKeyguardLocked) {
            finishQuietly()
            return
        }
        keyguardManager.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = finishQuietly()
                // Cancelled or failed: stay up, the iPod still usable.
            },
        )
    }

    private fun finishQuietly() {
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}

/**
 * The iPod, as large as the screen allows at its own proportions, on a
 * background of its own body colour so the whole screen reads as the device;
 * and a home indicator along the bottom to swipe up on.
 */
@Composable
private fun LockPod(onUnlock: () -> Unit) {
    val playerState by NowPlaying.state.collectAsState()
    val controller: PodController = NowPlaying
    val finish = rememberPodFinish()

    // The swipe lifts the iPod a little and lets it spring back if let go short.
    val lift = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 110.dp.toPx() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(finish.bodyTop, finish.bodyBottom))),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp)
                .padding(top = 8.dp, bottom = UNLOCK_STRIP),
            contentAlignment = Alignment.Center,
        ) {
            // Fit the iPod's proportions inside what is left of the screen.
            val ratio = POD_DESIGN_WIDTH / POD_DESIGN_HEIGHT
            val width = min(maxWidth, maxHeight * ratio)
            Box(
                Modifier
                    .size(width, width / ratio)
                    .offset { IntOffset(0, (lift.value * 0.35f).roundToInt()) },
            ) {
                ClassipodApp(controller, playerState)
            }
        }

        // The unlock strip, below the iPod so its gestures never meet the wheel's.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(UNLOCK_STRIP)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            scope.launch { lift.snapTo((lift.value + dy).coerceAtMost(0f)) }
                        },
                        onDragEnd = {
                            if (-lift.value > threshold) onUnlock()
                            scope.launch { lift.animateTo(0f, spring(dampingRatio = 0.7f)) }
                        },
                        onDragCancel = {
                            scope.launch { lift.animateTo(0f, spring(dampingRatio = 0.7f)) }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .graphicsLayer { alpha = 1f + lift.value / (threshold * 2f) }
                    .size(width = 134.dp, height = 5.dp)
                    .background(finish.label.copy(alpha = 0.55f), CircleShape),
            )
            Text(
                "Swipe up to unlock",
                color = finish.label.copy(alpha = 0.5f),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
            )
        }
    }
}

/** The strip at the bottom of the lock screen that takes the unlock swipe. */
private val UNLOCK_STRIP = 44.dp
