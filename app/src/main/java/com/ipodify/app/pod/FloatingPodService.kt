package com.ipodify.app.pod

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import com.ipodify.app.PodSettings
import androidx.core.content.ContextCompat
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration as ComposeViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ipodify.app.R
import com.ipodify.app.media.NowPlaying
import com.ipodify.app.media.PodController
import androidx.compose.runtime.collectAsState
import com.ipodify.app.ui.IPodifyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The Classipod picture-in-picture: a chat-head bubble and the iPod it opens.
 *
 * It behaves like a Messenger chat head:
 *  - **Tap the bubble** to open the iPod. The bubble springs to the iPod's top
 *    right corner and the iPod grows out of it. Tap again to close; the iPod
 *    shrinks back into the bubble and the bubble returns to its edge.
 *  - **Drag the bubble** anywhere. On release it is flung to the nearest side
 *    with a bouncy spring that keeps the finger's momentum. Dragging it while
 *    the iPod is open folds the iPod back into it first.
 *  - **Drag it to the bottom** to close everything. A close target slides up;
 *    near it the bubble is pulled in magnetically and sticks until the finger
 *    pulls away again. Releasing while stuck, or flinging into the target,
 *    closes the PIP and stops the service.
 *  - **Drag the iPod's body** to move the open iPod and its bubble together.
 *  - **Drag a bottom corner** of the open iPod to resize it, keeping its
 *    proportions. Shrunk past [AUTO_MINIMIZE_SCALE] it minimises itself, and
 *    reopens at the size it had before that drag began.
 *
 * It is three overlay windows rather than one, so each can be sized and moved
 * on its own without the open iPod resizing a shared window mid-animation:
 *  - the **close target**, hidden (zero alpha, untouchable) while nothing is dragged;
 *  - the **iPod**, hidden the same way while closed;
 *  - the **bubble**, added last so it is drawn on top of both.
 * A hidden window gets window alpha 0 rather than just transparent content:
 * from Android 12, an untouchable overlay that is not see-through still
 * blocks touches to the apps under it.
 *
 * Positions are driven by Compose [Animatable]s on [AndroidUiDispatcher.Main],
 * so every move is a spring the next touch can interrupt without a jump.
 *
 * Always start it through [start]: the windows need the "Display over other
 * apps" grant, and [start] asks for it through [ClassipodActivity] when it is
 * missing.
 */
class FloatingPodService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    companion object {
        private const val TAG = "iPodify"

        /** Stops the service when sent as the start intent's action. */
        const val ACTION_STOP = "com.ipodify.app.STOP"

        /** True while the PIP is on screen; the in-app launcher hides itself on it. */
        val isRunning = mutableStateOf(false)

        /**
         * Opens the PIP, asking for the overlay permission first if it has not
         * been granted. Safe to call from any UI click handler.
         */
        fun start(context: Context) {
            if (Settings.canDrawOverlays(context)) {
                context.startService(Intent(context, FloatingPodService::class.java))
            } else {
                context.startActivity(
                    Intent(context, ClassipodActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        /** Closes the PIP and stops the service. */
        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingPodService::class.java))
        }
    }

    // ── Owners for the ComposeViews ──────────────────────────────────────

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    // ── Windows ──────────────────────────────────────────────────────────

    /**
     * The context the windows are created with. From Android 11 a service
     * should add overlay windows through a window context, which carries the
     * right metrics and configuration (rotation, density) for its display.
     */
    private lateinit var uiContext: Context
    private lateinit var windowManager: WindowManager

    private var bubbleView: ComposeView? = null
    private var podView: View? = null
    private var targetView: ComposeView? = null

    /**
     * Stands in for the iPod while it is resized from the bottom-left corner;
     * see [startStage]. Hidden (zero alpha, untouchable) the rest of the time.
     */
    private var stageView: ComposeView? = null
    private lateinit var stageParams: WindowManager.LayoutParams
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var podParams: WindowManager.LayoutParams
    private lateinit var targetParams: WindowManager.LayoutParams

    private val scope = CoroutineScope(SupervisorJob() + AndroidUiDispatcher.Main)

    // ── Motion state ─────────────────────────────────────────────────────

    /** The bubble's top-left corner on screen, in px. The iPod hangs off it. */
    private val bubblePos = Animatable(Offset.Zero, Offset.VectorConverter, POSITION_THRESHOLD)

    /** The bubble's scale: pops in on start, squashes on press, shrinks on dismiss. */
    private val bubbleScale = Animatable(0f)

    /** 0 = iPod closed (shrunk into the bubble), 1 = fully open. */
    private val podProgress = Animatable(0f)

    /** The iPod's size, as a multiple of its default size. Its aspect ratio never changes. */
    private val podScale = mutableFloatStateOf(1f)

    /**
     * The view the iPod is composed in, inside the iPod window. Its size and
     * offset within the window are what place and scale the iPod on screen —
     * see [setPodContent] for why those are View layout params and not
     * Compose state.
     */
    private lateinit var podContent: ComposeView

    /** Where [podContent] starts within the iPod window, in px. */
    private var podContentLeft = 0f

    /** True while a corner is being dragged, so the grips can highlight. */
    private val resizeActive = mutableStateOf(false)

    private val targetShown = mutableStateOf(false)
    private val targetHot = mutableStateOf(false)

    /** How far the close target leans toward the bubble as it gets near. */
    private val targetPull = mutableStateOf(Offset.Zero)

    private var expanded = false
    private var podWindowShown = false
    private var dismissing = false

    /** Where the bubble rests while the iPod is closed. */
    private var restPos = Offset.Zero

    /** Whether that rest is half buried in the screen edge. */
    private var restBuried = false

    /** The bubble position the iPod window currently hangs off. */
    private var podAnchor = Offset.Zero

    /** Whether the iPod window follows the bubble frame by frame (only while dragging the iPod). */
    private var podTracksBubble = false

    private var motionJob: Job? = null
    private var targetHideJob: Job? = null

    // Touch tracking for the bubble, in raw screen coordinates so the window
    // moving under the finger doesn't skew the deltas.
    private var downRaw = Offset.Zero
    private var downPos = Offset.Zero
    private var dragging = false
    private var magnetized = false
    private var catchUpUntil = 0L
    private var velocityTracker: VelocityTracker? = null

    private val touchSlop by lazy { ViewConfiguration.get(uiContext).scaledTouchSlop }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        // Not worth recreating after the process dies: the PIP would come
        // back with nothing having asked for it.
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()

        // Last line of defence: every launch path is meant to go through
        // [start], but an overlay without the grant throws from addView.
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "FloatingPodService started without overlay permission; stopping")
            stopSelf()
            return
        }

        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        uiContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            this
        }
        windowManager = uiContext.getSystemService(WINDOW_SERVICE) as WindowManager

        try {
            addWindows()
        } catch (e: Exception) {
            // Permission revoked between the check and here, or an OEM refusing
            // the window type. Either way there is nothing to show.
            Log.w(TAG, "Could not add the Classipod windows: ${e.message}")
            stopSelf()
            return
        }
        isRunning.value = true
        // In case the notification listener hasn't bound yet this run.
        NowPlaying.ensureConnected(this)

        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screenReceiverRegistered = true

        // Start on the right edge, a quarter of the way down, and pop in.
        val area = area()
        restPos = clampRest(Offset(area.right, area.top + (area.bottom - area.top) * 0.25f))
        snap(restPos)
        scope.launch {
            bubbleScale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 400f))
        }
    }

    // ── Lock screen ──────────────────────────────────────────────────────

    /**
     * Puts up [LockPodActivity] — the iPod, full screen — as the screen turns
     * off while the PIP is open, unless it is switched off in Settings
     * ([PodSettings.lockScreen]); takes it down once the phone is unlocked.
     *
     * Started at screen-off rather than screen-on, so it is already in place
     * when the phone wakes, with no flash of the bare keyguard first. Starting
     * an activity from the background is allowed here because the app holds
     * "Display over other apps" and has a visible window: the PIP itself.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF ->
                    if (!dismissing && PodSettings.lockScreen.value) {
                        LockPodActivity.show(this@FloatingPodService)
                    }
                Intent.ACTION_USER_PRESENT -> LockPodActivity.dismiss()
            }
        }
    }
    private var screenReceiverRegistered = false

    private fun addWindows() {
        // Added in this order so the bubble is drawn over the iPod, and both
        // over the close target.
        targetParams = overlayParams(dpInt(TARGET_WINDOW_DP), dpInt(TARGET_WINDOW_DP), touchable = false)
            .apply { alpha = 0f }
        targetView = composeView {
            DismissTarget(
                shown = targetShown.value,
                hot = targetHot.value,
                pull = targetPull.value,
            )
        }
        windowManager.addView(targetView, targetParams)

        podParams = overlayParams(
            dpInt(POD_WIDTH_DP + 2 * POD_SHADOW_DP),
            dpInt(POD_HEIGHT_DP + 2 * POD_SHADOW_DP),
            touchable = false,
        ).apply { alpha = 0f }
        // Wrapped in a view that sees each touch before Compose does, so a
        // touch on a bottom corner can be taken over for resizing in raw
        // screen coordinates.
        podView = PodWindowView(uiContext).apply {
            setViewTreeLifecycleOwner(this@FloatingPodService)
            setViewTreeViewModelStoreOwner(this@FloatingPodService)
            setViewTreeSavedStateRegistryOwner(this@FloatingPodService)
            podContent = ComposeView(uiContext).apply {
                setContent {
                    IPodifyTheme {
                        PodPanel(
                            progress = { podProgress.value },
                            resizing = resizeActive.value,
                            onLayer = { podLayer = it },
                            onDrag = ::onPodDrag,
                            onDragEnd = ::onPodDragEnd,
                        )
                    }
                }
            }
            addView(podContent, FrameLayout.LayoutParams(podParams.width, podParams.height))
        }
        windowManager.addView(podView, podParams)

        stageParams = overlayParams(1, 1, touchable = false).apply { alpha = 0f }
        stageView = composeView {
            ResizeStage(
                image = stageImage.value,
                scale = { podScale.floatValue },
                progress = { podProgress.value },
            )
        }
        windowManager.addView(stageView, stageParams)

        bubbleParams = overlayParams(
            dpInt(BUBBLE_DP + 2 * BUBBLE_MARGIN_DP),
            dpInt(BUBBLE_DP + 2 * BUBBLE_MARGIN_DP),
            touchable = true,
        )
        bubbleView = composeView {
            Bubble(scale = { bubbleScale.value })
        }.apply {
            contentDescription = "iPod"
            setOnTouchListener(::onBubbleTouch)
            // Taps are routed through performClick so TalkBack can toggle too.
            setOnClickListener { toggle() }
        }
        windowManager.addView(bubbleView, bubbleParams)
    }

    // ── Bubble touch handling ────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun onBubbleTouch(view: View, event: MotionEvent): Boolean {
        if (dismissing) return true
        val raw = Offset(event.rawX, event.rawY)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                track(event)
                downRaw = raw
                // Catch the bubble mid-flight, wherever it is.
                motionJob?.cancel()
                downPos = bubblePos.value
                dragging = false
                press(true)
            }
            MotionEvent.ACTION_MOVE -> {
                track(event)
                val delta = raw - downRaw
                if (!dragging && delta.getDistance() > touchSlop) {
                    dragging = true
                    onDragStart()
                }
                if (dragging) onDragTo(downPos + delta)
            }
            MotionEvent.ACTION_UP -> {
                track(event)
                press(false)
                if (dragging) {
                    val tracker = velocityTracker
                    tracker?.computeCurrentVelocity(1000)
                    onDragEnd(Offset(tracker?.xVelocity ?: 0f, tracker?.yVelocity ?: 0f))
                } else {
                    view.performClick()
                }
                velocityTracker?.recycle()
                velocityTracker = null
            }
            MotionEvent.ACTION_CANCEL -> {
                press(false)
                if (dragging) onDragEnd(Offset.Zero)
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return true
    }

    /** Feeds the velocity tracker raw coordinates, since the window itself moves. */
    private fun track(event: MotionEvent) {
        val copy = MotionEvent.obtain(event)
        copy.setLocation(event.rawX, event.rawY)
        velocityTracker?.addMovement(copy)
        copy.recycle()
    }

    private fun onDragStart() {
        // Dragging the bubble always means "move the bubble", so an open iPod
        // folds back into it and follows it until the drag ends.
        if (expanded) collapse(returnToRest = false)
        magnetized = false
        catchUpUntil = 0L
        showTarget()
    }

    private fun onDragTo(free: Offset) {
        val half = dp(BUBBLE_DP) / 2f
        val center = free + Offset(half, half)
        val targetCenter = targetCenter()
        val toTarget = center - targetCenter
        val distance = toTarget.getDistance()

        // The target leans toward a bubble that is getting close.
        targetPull.value = if (!magnetized && distance < dp(PULL_RANGE_DP)) {
            val limit = dp(MAX_PULL_DP)
            Offset(
                (toTarget.x * PULL_FACTOR).coerceIn(-limit, limit),
                (toTarget.y * PULL_FACTOR).coerceIn(-limit, limit),
            )
        } else {
            Offset.Zero
        }

        if (!magnetized && distance < dp(MAGNET_CAPTURE_DP)) {
            // Caught: snap into the target with a bounce and stay there.
            magnetized = true
            targetHot.value = true
            targetPull.value = Offset.Zero
            tick()
            springTo(magnetPos(), MAGNET_SPRING)
            return
        }
        if (magnetized) {
            if (distance < dp(MAGNET_RELEASE_DP)) return
            // Pulled free: spring back under the finger rather than jumping.
            magnetized = false
            targetHot.value = false
            catchUpUntil = System.currentTimeMillis() + CATCH_UP_MS
        }

        val clamped = clampDrag(free)
        if (System.currentTimeMillis() < catchUpUntil) {
            springTo(clamped, CATCH_UP_SPRING)
        } else {
            motionJob?.cancel()
            snap(clamped)
        }
    }

    private fun onDragEnd(velocity: Offset) {
        if (magnetized) {
            dismiss()
            return
        }
        // A hard fling at the target counts as dropping on it.
        val half = dp(BUBBLE_DP) / 2f
        val projected = bubblePos.value + Offset(half, half) + velocity * FLING_PROJECTION_S
        if (velocity.y > dp(FLING_DISMISS_MIN_DP_S) &&
            (projected - targetCenter()).getDistance() < dp(MAGNET_CAPTURE_DP)
        ) {
            targetHot.value = true
            dismiss()
            return
        }
        hideTarget()
        flingToEdge(velocity)
    }

    private fun press(down: Boolean) {
        if (dismissing) return
        scope.launch {
            if (down) {
                bubbleScale.animateTo(PRESSED_SCALE, spring(stiffness = Spring.StiffnessHigh))
            } else {
                // Released: pop back past full size and settle, like a chat head.
                bubbleScale.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 700f))
            }
        }
    }

    // ── Opening and closing the iPod ─────────────────────────────────────

    private fun toggle() {
        if (dismissing) return
        if (expanded) collapse(returnToRest = true) else expand()
    }

    private fun expand() {
        expanded = true
        // Remembered as it is, buried or not, so closing puts it back there.
        restPos = clampRest(bubblePos.value, restBuried)
        fitPodToScreen()
        val anchor = clampExpanded(bubblePos.value)
        // The iPod is placed once, at its final spot, and only its content
        // animates. Moving a window this size every frame alongside the bubble
        // is what made opening stutter.
        podTracksBubble = false
        placePod(anchor)
        showPodWindow()
        springTo(anchor, OPEN_SPRING)
        scope.launch {
            podProgress.animateTo(1f, POD_OPEN_SPRING)
        }
    }

    /**
     * Shrinks the iPod back into the bubble.
     *
     * The iPod window stays where it is while its content shrinks into its top
     * right corner; only the small bubble window moves.
     *
     * @param returnToRest whether the bubble springs back to where it rested
     *   before the iPod opened. False when a drag is about to take over.
     */
    private fun collapse(returnToRest: Boolean) {
        expanded = false
        podTracksBubble = false
        setPodTouchable(false)
        if (returnToRest) springTo(clampRest(restPos, restBuried), CLOSE_SPRING)
        scope.launch {
            podProgress.animateTo(0f, POD_CLOSE_SPRING)
            if (!expanded) hidePodWindow()
        }
    }

    private fun onPodDrag(delta: Offset) {
        if (!expanded || dismissing) return
        motionJob?.cancel()
        // From here the iPod and its bubble move as one.
        podTracksBubble = true
        podAnchor += delta
        snap(podAnchor)
    }

    private fun onPodDragEnd() {
        if (!expanded) return
        podTracksBubble = true
        podAnchor = clampExpanded(podAnchor)
        springTo(podAnchor, SETTLE_SPRING)
    }

    /** Puts the iPod window where it hangs off a bubble at [anchor]. */
    private fun placePod(anchor: Offset) {
        podAnchor = anchor
        val shadow = dp(POD_SHADOW_DP)
        val topLeft = bodyTopLeft(anchor)
        val width = bodyW() + 2 * shadow
        val height = bodyH() + 2 * shadow
        setPodWindow(topLeft.x - shadow, topLeft.y - shadow, width, height)
        setPodContent(0f, width, height)
    }

    private fun setPodWindow(left: Float, top: Float, width: Float, height: Float) {
        podParams.x = left.roundToInt()
        podParams.y = top.roundToInt()
        podParams.width = width.roundToInt()
        podParams.height = height.roundToInt()
        update(podView, podParams)
    }

    /**
     * Sizes and offsets the iPod's view inside its window. The iPod scales
     * itself to whatever size the view is given (see [PodPanel]).
     *
     * This is deliberately View layout, not Compose state. A layout param set
     * here is applied in the very traversal that applies the window's new
     * frame, so the two can never disagree. Compose state written from outside
     * a composition is only picked up a frame later, and for that one frame
     * the iPod was drawn at its old offset in the window's new frame — the
     * jump to the side at the start and end of every resize.
     */
    private fun setPodContent(left: Float, width: Float, height: Float) {
        podContentLeft = left
        val params = podContent.layoutParams as FrameLayout.LayoutParams
        params.leftMargin = left.roundToInt()
        params.topMargin = 0
        params.width = width.roundToInt()
        params.height = height.roundToInt()
        podContent.layoutParams = params
    }

    private fun showPodWindow() {
        podWindowShown = true
        podParams.alpha = 1f
        podParams.flags = podParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        update(podView, podParams)
    }

    private fun setPodTouchable(touchable: Boolean) {
        podParams.flags = if (touchable) {
            podParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            podParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        update(podView, podParams)
    }

    private fun hidePodWindow() {
        podWindowShown = false
        // Shrunk away by resizing too small: it reopens at the size the resize
        // started from, not the size that triggered the minimise.
        restoreScaleOnHide?.let { podScale.floatValue = it }
        restoreScaleOnHide = null
        if (stageShown || stageImage.value != null) hideStage()
        podParams.alpha = 0f
        podParams.flags = podParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        update(podView, podParams)
    }

    // ── Close target ─────────────────────────────────────────────────────

    private fun showTarget() {
        targetHideJob?.cancel()
        val center = targetCenter()
        val half = dp(TARGET_WINDOW_DP) / 2f
        targetParams.x = (center.x - half).roundToInt()
        targetParams.y = (center.y - half).roundToInt()
        targetParams.alpha = 1f
        update(targetView, targetParams)
        targetHot.value = false
        targetPull.value = Offset.Zero
        targetShown.value = true
    }

    private fun hideTarget() {
        targetShown.value = false
        targetHot.value = false
        targetPull.value = Offset.Zero
        targetHideJob?.cancel()
        targetHideJob = scope.launch {
            // Let the slide-out finish before the window goes fully transparent.
            delay(TARGET_HIDE_DELAY_MS)
            targetParams.alpha = 0f
            update(targetView, targetParams)
        }
    }

    private fun dismiss() {
        if (dismissing) return
        dismissing = true
        heavyClick()
        if (expanded) collapse(returnToRest = false)
        motionJob?.cancel()
        scope.launch {
            bubblePos.animateTo(magnetPos(), MAGNET_SPRING) { applyBubble(value) }
            bubbleScale.animateTo(0f, tween(durationMillis = 160))
            targetShown.value = false
            delay(TARGET_HIDE_DELAY_MS)
            stopSelf()
        }
    }

    // ── Motion helpers ───────────────────────────────────────────────────

    /**
     * Flings the bubble to the nearest side, carrying the finger's momentum.
     *
     * It lands half buried in that edge when it was let go already pushed
     * partly off it ([BURY_TRIGGER] of its width), or flicked hard toward it
     * from close by — and fully on screen otherwise. Either way it is the same
     * spring, so burying bounces in exactly like a normal snap, and dragging a
     * buried bubble back out needs nothing special.
     */
    private fun flingToEdge(velocity: Offset) {
        val area = area()
        val size = dp(BUBBLE_DP)
        val margin = dp(EDGE_MARGIN_DP)
        val pos = bubblePos.value
        val projected = pos + velocity * FLING_PROJECTION_S
        val toRight = projected.x + size / 2f > (area.left + area.right) / 2f

        // How much of the bubble is past the chosen edge, now and where the
        // fling is heading. 0 = fully on screen, 0.5 = half off.
        fun offscreen(x: Float) = if (toRight) (x + size - area.right) / size else (area.left - x) / size
        val towardEdge = if (toRight) velocity.x > 0f else velocity.x < 0f
        val bury = offscreen(pos.x) >= BURY_TRIGGER ||
            (towardEdge &&
                kotlin.math.abs(velocity.x) > dp(BURY_FLING_MIN_DP_S) &&
                offscreen(projected.x) >= 0.5f)

        restBuried = bury
        val x = restX(toRight, bury, area, size, margin)
        val y = projected.y
            .coerceIn(area.top + margin, max(area.top + margin, area.bottom - margin - size))
        restPos = Offset(x, y)
        springTo(restPos, FLING_SPRING, velocity)
    }

    /** The resting x on a side: half off the edge when buried, [EDGE_MARGIN_DP] in from it otherwise. */
    private fun restX(toRight: Boolean, buried: Boolean, area: Area, size: Float, margin: Float): Float =
        when {
            toRight && buried -> area.right - size / 2f
            toRight -> area.right - margin - size
            buried -> area.left - size / 2f
            else -> area.left + margin
        }

    /** Where the bubble rests while closed: on the nearest side, buried or on screen. */
    private fun clampRest(p: Offset, buried: Boolean = false): Offset {
        val area = area()
        val size = dp(BUBBLE_DP)
        val margin = dp(EDGE_MARGIN_DP)
        val toRight = p.x + size / 2f > (area.left + area.right) / 2f
        val x = restX(toRight, buried, area, size, margin)
        val y = p.y.coerceIn(area.top + margin, max(area.top + margin, area.bottom - margin - size))
        return Offset(x, y)
    }

    /** Where the bubble may be while open, so the iPod hanging off it fits on screen. */
    private fun clampExpanded(p: Offset): Offset {
        val area = area()
        val size = dp(BUBBLE_DP)
        val margin = dp(EDGE_MARGIN_DP)
        val minX = area.left + margin + bodyW() - size
        val maxX = area.right - margin - size
        val minY = area.top + margin
        val maxY = area.bottom - margin - size - dp(POD_GAP_DP) - bodyH()
        return Offset(p.x.coerceIn(minX, max(minX, maxX)), p.y.coerceIn(minY, max(minY, maxY)))
    }

    /** Where the bubble may be dragged: anywhere, up to half off either side. */
    private fun clampDrag(p: Offset): Offset {
        val area = area()
        val size = dp(BUBBLE_DP)
        return Offset(
            p.x.coerceIn(area.left - size * 0.5f, area.right - size * 0.5f),
            p.y.coerceIn(area.top, max(area.top, area.bottom - size)),
        )
    }

    private fun targetCenter(): Offset {
        val area = area()
        return Offset(
            (area.left + area.right) / 2f,
            area.bottom - dp(TARGET_BOTTOM_MARGIN_DP) - dp(TARGET_SIZE_DP) / 2f,
        )
    }

    /** The bubble position that centres it on the close target. */
    private fun magnetPos(): Offset {
        val half = dp(BUBBLE_DP) / 2f
        return targetCenter() - Offset(half, half)
    }

    private fun springTo(
        target: Offset,
        spec: AnimationSpec<Offset>,
        initialVelocity: Offset = bubblePos.velocity,
    ) {
        motionJob?.cancel()
        motionJob = scope.launch {
            bubblePos.animateTo(target, spec, initialVelocity) { applyBubble(value) }
        }
    }

    private fun snap(p: Offset) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            bubblePos.snapTo(p)
            applyBubble(p)
        }
    }

    /**
     * Moves the bubble window — and the iPod window with it, but only while the
     * two are being moved as one (dragging the iPod's body). Opening and closing
     * leave the iPod window still and animate its content instead.
     */
    private fun applyBubble(p: Offset) {
        val margin = dp(BUBBLE_MARGIN_DP)
        bubbleParams.x = (p.x - margin).roundToInt()
        bubbleParams.y = (p.y - margin).roundToInt()
        update(bubbleView, bubbleParams)
        if (podWindowShown && podTracksBubble) placePod(p)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation or a resize: put everything back on screen once the new
        // metrics are in.
        bubbleView?.post {
            if (dismissing) return@post
            if (expanded) {
                fitPodToScreen()
                podTracksBubble = true
                podAnchor = clampExpanded(podAnchor)
                springTo(podAnchor, SETTLE_SPRING)
            } else {
                restPos = clampRest(bubblePos.value, restBuried)
                springTo(restPos, SETTLE_SPRING)
            }
        }
    }

    // ── Resizing ─────────────────────────────────────────────────────────

    private enum class Corner { BOTTOM_LEFT, BOTTOM_RIGHT }

    /** The corner being dragged, or null when not resizing. */
    private var resizeCorner: Corner? = null
    private var resizeStartScale = 1f
    private var resizeStartRaw = Offset.Zero

    /** The body corner that stays put: its top-left for a bottom-right drag, top-right for bottom-left. */
    private var resizeFixed = Offset.Zero
    private var resizeMaxScale = 1f

    /** Below this the iPod minimises itself instead of getting any smaller. */
    private var resizeMinimizeBelow = AUTO_MINIMIZE_SCALE

    /** The size to put back once an auto-minimised iPod has finished closing. */
    private var restoreScaleOnHide: Float? = null

    private fun bodyW(scale: Float = podScale.floatValue) = dp(POD_WIDTH_DP) * scale
    private fun bodyH(scale: Float = podScale.floatValue) = dp(POD_HEIGHT_DP) * scale

    /** The iPod body's top-left on screen when its bubble is at [anchor]. */
    private fun bodyTopLeft(anchor: Offset, scale: Float = podScale.floatValue) = Offset(
        anchor.x + dp(BUBBLE_DP) - bodyW(scale),
        anchor.y + dp(BUBBLE_DP) + dp(POD_GAP_DP),
    )

    /** The bubble position for a body whose top-right corner is at [topRight]. */
    private fun anchorFor(topRight: Offset) = Offset(
        topRight.x - dp(BUBBLE_DP),
        topRight.y - dp(POD_GAP_DP) - dp(BUBBLE_DP),
    )

    /** Shrinks the iPod if it no longer fits the screen (rotation, a smaller window). */
    private fun fitPodToScreen() {
        val area = area()
        val margin = dp(EDGE_MARGIN_DP)
        val fit = minOf(
            MAX_POD_SCALE,
            (area.right - area.left - 2 * margin) / dp(POD_WIDTH_DP),
            (area.bottom - area.top - 2 * margin - dp(BUBBLE_DP) - dp(POD_GAP_DP)) / dp(POD_HEIGHT_DP),
        ).coerceAtLeast(MIN_FIT_SCALE)
        if (podScale.floatValue > fit) podScale.floatValue = fit
    }

    /** Which bottom corner, if any, a touch at window-local ([x], [y]) is grabbing. */
    private fun cornerAt(x: Float, y: Float): Corner? {
        if (!expanded || dismissing || podProgress.value < 0.95f) return null
        // Still swapping back from the stage after the last bottom-left drag.
        if (stageJob?.isActive == true || stageShown) return null
        val shadow = dp(POD_SHADOW_DP)
        val width = bodyW()
        val left = podContentLeft + shadow
        val bottom = shadow + bodyH()
        // Narrower on a small iPod, so the corners don't swallow the wheel.
        val reach = (width * 0.35f).coerceIn(dp(RESIZE_HANDLE_MIN_DP), dp(RESIZE_HANDLE_DP))
        return when {
            hypot(x - left, y - bottom) < reach -> Corner.BOTTOM_LEFT
            hypot(x - (left + width), y - bottom) < reach -> Corner.BOTTOM_RIGHT
            else -> null
        }
    }

    private fun startResize(corner: Corner, raw: Offset) {
        motionJob?.cancel()
        podTracksBubble = false
        val scale = podScale.floatValue
        val topLeft = bodyTopLeft(podAnchor, scale)
        val fromRight = corner == Corner.BOTTOM_RIGHT

        resizeCorner = corner
        resizeStartScale = scale
        resizeStartRaw = raw
        resizeFixed = if (fromRight) topLeft else Offset(topLeft.x + bodyW(scale), topLeft.y)
        resizeMinimizeBelow = minOf(AUTO_MINIMIZE_SCALE, scale * 0.85f)

        // As big as this drag could make it: up to the screen edge it grows
        // toward, and never so tall that it runs off the bottom.
        val area = area()
        val margin = dp(EDGE_MARGIN_DP)
        val roomX = if (fromRight) area.right - margin - resizeFixed.x else resizeFixed.x - (area.left + margin)
        val roomY = area.bottom - margin - resizeFixed.y
        resizeMaxScale = minOf(MAX_POD_SCALE, roomX / dp(POD_WIDTH_DP), roomY / dp(POD_HEIGHT_DP))
            .coerceAtLeast(scale)

        resizeActive.value = true
        val shadow = dp(POD_SHADOW_DP)
        resizeWindowW = bodyW(resizeMaxScale) + 2 * shadow
        val windowH = bodyH(resizeMaxScale) + 2 * shadow
        if (fromRight) {
            // Growing right and down: the window is enlarged once, now, to the
            // biggest size this drag can reach. Its top-left corner doesn't
            // move, so even the frame before the iPod redraws at the new size
            // shows it exactly where it was. Each frame then only re-lays-out
            // the content inside it.
            setPodWindow(resizeFixed.x - shadow, resizeFixed.y - shadow, resizeWindowW, windowH)
            layoutResizingContent(scale)
        } else {
            // Growing left: enlarging the window would move its left edge, and
            // the system can show the old picture at the new left edge for a
            // frame — the jump this avoids. The iPod's window is left exactly
            // as it is, and the resize is shown in the stage window instead.
            startStage(resizeFixed.x + shadow - resizeWindowW, resizeFixed.y - shadow, resizeWindowW, windowH)
        }
        tick()
    }

    /** The resize window's width, fixed for the length of one drag. */
    private var resizeWindowW = 0f

    /** Lays the iPod's view out at [scale] inside the enlarged resize window, against the fixed corner. */
    private fun layoutResizingContent(scale: Float) {
        val shadow = dp(POD_SHADOW_DP)
        val width = bodyW(scale) + 2 * shadow
        val height = bodyH(scale) + 2 * shadow
        setPodContent(0f, width, height)
    }

    private fun resizeTo(raw: Offset) {
        val corner = resizeCorner ?: return
        val fromRight = corner == Corner.BOTTOM_RIGHT
        val delta = raw - resizeStartRaw
        // The dragged corner, measured from the fixed one. Projected onto the
        // body's own diagonal so the ratio holds however the finger wanders.
        val width = bodyW(resizeStartScale) + if (fromRight) delta.x else -delta.x
        val height = bodyH(resizeStartScale) + delta.y
        val baseW = dp(POD_WIDTH_DP)
        val baseH = dp(POD_HEIGHT_DP)
        val scale = (width * baseW + height * baseH) / (baseW * baseW + baseH * baseH)

        if (scale < resizeMinimizeBelow) {
            autoMinimize()
            return
        }
        val clamped = scale.coerceAtMost(resizeMaxScale)
        podScale.floatValue = clamped
        if (fromRight) {
            layoutResizingContent(clamped)
            // The bubble rides the iPod's top-right corner, which only moves
            // when the right side is the one being dragged.
            val anchor = anchorFor(Offset(resizeFixed.x + bodyW(clamped), resizeFixed.y))
            podAnchor = anchor
            motionJob?.cancel()
            snap(anchor)
        }
        // Bottom-left: the stage window draws the new size from [podScale] —
        // unless there is no stage, in which case the iPod resizes in place.
        if (!fromRight && stageFallback) {
            val shadow = dp(POD_SHADOW_DP)
            val width = bodyW(clamped) + 2 * shadow
            setPodContent(resizeWindowW - width, width, bodyH(clamped) + 2 * shadow)
        }
    }

    private fun endResize() {
        val corner = resizeCorner ?: return
        resizeCorner = null
        resizeActive.value = false
        val shadow = dp(POD_SHADOW_DP)

        val viaStage = corner == Corner.BOTTOM_LEFT && !stageFallback
        stageFallback = false
        if (viaStage && !stageShown) {
            // Let go before the stage was even up: nothing visible has
            // changed, so neither should anything else.
            stageJob?.cancel()
            hideStage()
            podScale.floatValue = resizeStartScale
            return
        }

        val scale = podScale.floatValue
        val topLeft = if (corner == Corner.BOTTOM_RIGHT) {
            resizeFixed
        } else {
            Offset(resizeFixed.x - bodyW(scale), resizeFixed.y)
        }
        podAnchor = anchorFor(Offset(topLeft.x + bodyW(scale), topLeft.y))
        // Back to a window that fits the body exactly, so the space it no
        // longer covers stops catching touches meant for the apps beneath.
        val width = bodyW(scale) + 2 * shadow
        val height = bodyH(scale) + 2 * shadow
        setPodWindow(topLeft.x - shadow, topLeft.y - shadow, width, height)
        setPodContent(0f, width, height)
        // Bottom-left: the iPod's window was hidden behind the stage, so it
        // has just been moved and resized out of sight. It is shown again only
        // once it has drawn at its new size, then the stage steps away.
        if (viaStage) handBackFromStage()
    }

    /** Dragged too small: minimise, and remember the size the drag started from. */
    private fun autoMinimize() {
        val viaStage = resizeCorner == Corner.BOTTOM_LEFT && !stageFallback
        stageFallback = false
        resizeCorner = null
        resizeActive.value = false
        restoreScaleOnHide = resizeStartScale
        heavyClick()
        if (viaStage && !stageShown) {
            // The stage never came up, so the iPod on screen is still the
            // untouched one: minimise that as normal.
            stageJob?.cancel()
            hideStage()
        }
        // With the stage up, it is the stage that shrinks into the bubble —
        // it draws [podProgress] too — while the real iPod stays hidden.
        collapse(returnToRest = true)
    }

    // ── Stage (bottom-left resizing) ─────────────────────────────────────

    /** Captures the iPod as drawn; set by [PodPanel]. */
    private var podLayer: GraphicsLayer? = null

    /** The picture of the iPod the stage draws while resizing from the bottom left. */
    private val stageImage = mutableStateOf<ImageBitmap?>(null)

    /** Whether the stage is on screen with the real iPod hidden behind it. */
    private var stageShown = false
    private var stageJob: Job? = null

    /** True when this bottom-left drag couldn't use the stage and resizes the iPod in place instead. */
    private var stageFallback = false

    /**
     * Puts up the stage over the iPod: a window already the size the drag can
     * reach, showing a picture of the iPod that it scales as the corner moves.
     *
     * Nothing visible moves at any point. The stage is sized and filled while
     * still transparent, made visible only once it has drawn, and the real
     * iPod is hidden only after that — so for a frame there are two identical
     * iPods in the same place, never none and never one in the wrong place.
     */
    private fun startStage(left: Float, top: Float, width: Float, height: Float) {
        stageJob?.cancel()
        stageFallback = false
        stageParams.x = left.roundToInt()
        stageParams.y = top.roundToInt()
        stageParams.width = width.roundToInt()
        stageParams.height = height.roundToInt()
        stageParams.alpha = 0f
        update(stageView, stageParams)
        stageJob = scope.launch {
            val image = runCatching { podLayer?.toImageBitmap() }.getOrNull()
            if (image == null) {
                // Couldn't take the picture. Fall back to enlarging the iPod's
                // own window: it may jump once, but it still resizes.
                Log.w(TAG, "Classipod: no snapshot for the resize stage; resizing in place")
                resizeCorner?.let { fallBackToInPlaceResize() }
                return@launch
            }
            stageImage.value = image
            awaitDrawn(stageView)
            if (resizeCorner == null && !expanded) return@launch
            // Touchable while it is up. An overlay that lets touches through
            // is drawn at no more than 80% opacity from Android 12, which is
            // what made the iPod turn see-through here. The drag itself is
            // unaffected: its touches keep going to the iPod's window, where
            // it started.
            stageParams.alpha = 1f
            stageParams.flags = stageParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            update(stageView, stageParams)
            awaitFrames(2)
            podParams.alpha = 0f
            update(podView, podParams)
            stageShown = true
        }
    }

    /** Shows the real iPod again once it has drawn at its new size, then drops the stage. */
    private fun handBackFromStage() {
        stageJob?.cancel()
        stageJob = scope.launch {
            // Wait until the real iPod has actually put a frame on screen at
            // its new size and place — not just a fixed number of frames,
            // which wasn't always enough, and showing it any earlier shows
            // its old picture at the new position: the jump at the end.
            awaitDrawn(podView)
            awaitFrames(1)
            podParams.alpha = 1f
            update(podView, podParams)
            awaitFrames(2)
            hideStage()
        }
    }

    private fun hideStage() {
        stageShown = false
        stageParams.alpha = 0f
        stageParams.flags = stageParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        update(stageView, stageParams)
        stageImage.value = null
    }

    /**
     * Suspends until [view]'s window has committed a new frame — drawn and
     * handed to the display — or a short timeout passes.
     */
    private suspend fun awaitDrawn(view: View?) {
        if (view == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            awaitFrames(STAGE_SETTLE_FRAMES + 2)
            return
        }
        withTimeoutOrNull(DRAW_WAIT_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { continuation ->
                view.viewTreeObserver.registerFrameCommitCallback {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                view.invalidate()
            }
        }
    }

    /** The bottom-left drag without a stage: enlarge the iPod's own window, as the bottom right does. */
    private fun fallBackToInPlaceResize() {
        stageFallback = true
        val shadow = dp(POD_SHADOW_DP)
        val windowH = bodyH(resizeMaxScale) + 2 * shadow
        setPodWindow(resizeFixed.x + shadow - resizeWindowW, resizeFixed.y - shadow, resizeWindowW, windowH)
        val width = bodyW() + 2 * shadow
        setPodContent(resizeWindowW - width, width, bodyH() + 2 * shadow)
    }

    private suspend fun awaitFrames(count: Int) {
        repeat(count) { withFrameNanos { } }
    }

    /**
     * The iPod window's root. It sees every touch before Compose does, and
     * keeps the ones that start on a bottom corner for itself: those resize,
     * in raw screen coordinates, so the window being re-laid-out under the
     * finger can't skew the deltas.
     */
    private inner class PodWindowView(context: Context) : FrameLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            val raw = Offset(event.rawX, event.rawY)
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val corner = cornerAt(event.x, event.y)
                if (corner != null) {
                    startResize(corner, raw)
                    return true
                }
            }
            if (resizeCorner != null) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> resizeTo(raw)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endResize()
                }
                return true
            }
            return super.dispatchTouchEvent(event)
        }
    }

    // ── Plumbing ─────────────────────────────────────────────────────────

    private data class Area(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** The part of the screen clear of the system bars and cutouts, in px. */
    private fun area(): Area {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            val bounds = metrics.bounds
            return Area(
                left = insets.left.toFloat(),
                top = insets.top.toFloat(),
                right = (bounds.width() - insets.right).toFloat(),
                bottom = (bounds.height() - insets.bottom).toFloat(),
            )
        }
        val real = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(real)
        return Area(
            left = 0f,
            top = systemDimen("status_bar_height"),
            right = real.widthPixels.toFloat(),
            bottom = real.heightPixels - systemDimen("navigation_bar_height"),
        )
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun systemDimen(name: String): Float {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id).toFloat() else 0f
    }

    private fun dp(value: Int): Float = value * uiContext.resources.displayMetrics.density
    private fun dpInt(value: Int): Int = dp(value).roundToInt()

    private fun overlayParams(width: Int, height: Int, touchable: Boolean) =
        WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                // Overlay windows added from a service are software-rendered
                // unless asked otherwise, which is the single biggest cost in
                // scaling the whole iPod every frame.
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

    private fun composeView(content: @Composable () -> Unit) = ComposeView(uiContext).apply {
        setViewTreeLifecycleOwner(this@FloatingPodService)
        setViewTreeViewModelStoreOwner(this@FloatingPodService)
        setViewTreeSavedStateRegistryOwner(this@FloatingPodService)
        setContent { IPodifyTheme { content() } }
    }

    /** Applies new layout params. Safe to call before the window's first layout. */
    private fun update(view: View?, params: WindowManager.LayoutParams) {
        if (view == null) return
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {
            // Not (or no longer) added; nothing to move.
        }
    }

    private fun tick() = PodHaptics.tick(this)
    private fun heavyClick() = PodHaptics.heavy(this)

    override fun onDestroy() {
        isRunning.value = false
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            screenReceiverRegistered = false
        }
        // Closing the PIP hands the lock screen back to the system.
        LockPodActivity.dismiss()
        scope.cancel()
        velocityTracker?.recycle()
        velocityTracker = null
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        if (::windowManager.isInitialized) {
            listOf(bubbleView, stageView, podView, targetView).forEach { view ->
                if (view != null) runCatching { windowManager.removeView(view) }
            }
        }
        bubbleView = null
        podView = null
        stageView = null
        targetView = null
        store.clear()
        super.onDestroy()
    }
}

// ── Sizes (dp) ───────────────────────────────────────────────────────────

private const val BUBBLE_DP = 64
/** Room around the bubble inside its window for the shadow and the press bounce. */
private const val BUBBLE_MARGIN_DP = 10
/** How far a resting bubble sits from the screen edge. */
private const val EDGE_MARGIN_DP = 8

/**
 * The iPod body: the Figma mockup's 377 units at 290 dp wide, and its height
 * (626 plus [SCREEN_EXTRA]) at the same scale — 290 × 666 / 377.
 */
private const val POD_WIDTH_DP = 290
private const val POD_HEIGHT_DP = 512
/** Room around the iPod body inside its window for its shadow and a little overshoot. */
private const val POD_SHADOW_DP = 20
/** Space between the bubble and the top of the iPod beneath it. */
private const val POD_GAP_DP = 8

/** The largest the iPod can be resized to, as a multiple of its default size. */
private const val MAX_POD_SCALE = 1.35f
/** Resized below this, the iPod minimises itself (see [FloatingPodService.autoMinimize]). */
private const val AUTO_MINIMIZE_SCALE = 0.2f
/** The smallest it is ever shrunk to just to fit a short screen, e.g. in landscape. */
private const val MIN_FIT_SCALE = 0.45f
/** How far from a bottom corner a touch still grabs it for resizing. */
private const val RESIZE_HANDLE_DP = 40
/** The smallest that reach gets, on a very small iPod. */
private const val RESIZE_HANDLE_MIN_DP = 20

private const val TARGET_SIZE_DP = 64
/** The close target's window: big enough for the target to grow and lean toward the bubble. */
private const val TARGET_WINDOW_DP = 160
private const val TARGET_BOTTOM_MARGIN_DP = 40

/** Within this distance of the target the bubble is captured… */
private const val MAGNET_CAPTURE_DP = 88
/** …and it has to be pulled this far away to break free again. */
private const val MAGNET_RELEASE_DP = 128
/** The target starts leaning toward the bubble inside this distance. */
private const val PULL_RANGE_DP = 220
private const val PULL_FACTOR = 0.12f
private const val MAX_PULL_DP = 20

/** Released with at least this much of its width past an edge, the bubble buries itself in it. */
private const val BURY_TRIGGER = 0.25f
/** A flick toward a nearby edge faster than this buries it too, in dp per second. */
private const val BURY_FLING_MIN_DP_S = 600

private const val PRESSED_SCALE = 0.88f
/** How far ahead a fling is projected when choosing where it lands, in seconds. */
private const val FLING_PROJECTION_S = 0.15f
/** A downward fling faster than this toward the target dismisses, in dp per second. */
private const val FLING_DISMISS_MIN_DP_S = 900
/** How long the bubble springs to catch up with the finger after leaving the magnet. */
private const val CATCH_UP_MS = 220L
private const val TARGET_HIDE_DELAY_MS = 260L

private val POSITION_THRESHOLD = Offset(0.5f, 0.5f)

// Springs. A damping ratio below 1 overshoots and settles back — the bounce.
private val FLING_SPRING = spring(dampingRatio = 0.55f, stiffness = 220f, visibilityThreshold = POSITION_THRESHOLD)
private val OPEN_SPRING = spring(dampingRatio = 0.62f, stiffness = 320f, visibilityThreshold = POSITION_THRESHOLD)
private val CLOSE_SPRING = spring(dampingRatio = 0.6f, stiffness = 260f, visibilityThreshold = POSITION_THRESHOLD)
private val SETTLE_SPRING = spring(dampingRatio = 0.6f, stiffness = 300f, visibilityThreshold = POSITION_THRESHOLD)
private val MAGNET_SPRING = spring(dampingRatio = 0.5f, stiffness = 650f, visibilityThreshold = POSITION_THRESHOLD)
private val POD_OPEN_SPRING = spring(dampingRatio = 0.75f, stiffness = 450f, visibilityThreshold = 0.002f)
private val POD_CLOSE_SPRING = spring(dampingRatio = 1f, stiffness = 700f, visibilityThreshold = 0.004f)
private val CATCH_UP_SPRING = spring(dampingRatio = 0.8f, stiffness = 1400f, visibilityThreshold = POSITION_THRESHOLD)

// ── Content ──────────────────────────────────────────────────────────────

/** The chat-head bubble, styled like an adaptive app icon. Purely visual; the window handles touch. */
@Composable
private fun Bubble(scale: () -> Float) {
    val isDark = isSystemInDarkTheme()
    val containerColor = if (isDark) Color(0xFF202124) else Color.White
    val borderColor = if (isDark) Color(0x33FFFFFF) else Color(0x1F000000)

    Box(
        modifier = Modifier
            .size((BUBBLE_DP + 2 * BUBBLE_MARGIN_DP).dp)
            .padding(BUBBLE_MARGIN_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(BUBBLE_DP.dp)
                .graphicsLayer {
                    val s = scale()
                    scaleX = s
                    scaleY = s
                }
                .shadow(elevation = 6.dp, shape = CircleShape, clip = false)
                .border(width = 1.dp, color = borderColor, shape = CircleShape)
                .background(containerColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_ipod),
                contentDescription = null,
                modifier = Modifier.size(34.dp),
                tint = if (isDark) Color.White else Color(0xFF1F1F1F),
            )
        }
    }
}

/**
 * The iPod, growing out of and shrinking back into the bubble at its top right.
 *
 * It scales itself to fit the view it is given: laid out at its default size,
 * then drawn at whatever size its view has. A resize therefore keeps every
 * proportion — the click wheel, the type, the screen — rather than reflowing
 * it, and needs no Compose state to drive it (see
 * [FloatingPodService.setPodContent]). [progress] is read in the draw phase
 * only, so opening and closing don't recompose it either.
 *
 * @param resizing highlights the corner grips while one is being dragged.
 */
@Composable
private fun PodPanel(
    progress: () -> Float,
    resizing: Boolean,
    onLayer: (GraphicsLayer) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    // Whatever app is playing — see [NowPlaying].
    val playerState by NowPlaying.state.collectAsState()
    val controller: PodController = NowPlaying
    val gripColor = if (resizing) Color(0xF2FFFFFF) else Color(0x99A0A0A0)
    // Everything the iPod draws, shadow included, also goes into this layer,
    // so the service can take a picture of it for the resize stage.
    val captureLayer = rememberGraphicsLayer()
    SideEffect { onLayer(captureLayer) }

    // Dragging the body moves the iPod; dragging round the wheel scrolls. Both
    // wait for the finger to pass the touch slop, and whichever gets there
    // first takes the gesture. The body measures in screen pixels, but the
    // wheel sits inside the iPod's scale layer and measures in the iPod's own
    // units — so on an iPod bigger than default, the same finger movement
    // reached the body's slop first and every spin became a drag: the wheel
    // "stopped working" at large sizes. The body is given a wider slop, so the
    // wheel wins at any size up to [BODY_DRAG_SLOP_FACTOR]×, and everything
    // inside gets the normal one back.
    val viewConfiguration = LocalViewConfiguration.current
    val bodyDragConfiguration = remember(viewConfiguration) {
        object : ComposeViewConfiguration by viewConfiguration {
            override val touchSlop: Float = viewConfiguration.touchSlop * BODY_DRAG_SLOP_FACTOR
        }
    }

    CompositionLocalProvider(LocalViewConfiguration provides bodyDragConfiguration) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                captureLayer.record { this@drawWithContent.drawContent() }
                drawLayer(captureLayer)
            }
            .padding(POD_SHADOW_DP.dp),
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    val p = progress()
                    val s = MIN_POD_SCALE + (1f - MIN_POD_SCALE) * p
                    scaleX = s
                    scaleY = s
                    // Faded only over the first stretch. Below full opacity
                    // the whole iPod is drawn into an offscreen buffer every
                    // frame; at full opacity a scale is just a matrix change
                    // on an already-recorded layer. Resizing toward the
                    // minimise point fades it as a warning.
                    val podScale = size.width / POD_WIDTH_DP.dp.toPx()
                    val minimizeHint = ((podScale - AUTO_MINIMIZE_SCALE) / 0.1f).coerceIn(0f, 1f)
                    alpha = (p * 4f).coerceIn(0f, 1f) * (0.55f + 0.45f * minimizeHint)
                    // The bubble's centre, as a fraction of the body at its
                    // current size: the point it grows out of and shrinks into.
                    transformOrigin = TransformOrigin(
                        pivotFractionX = (size.width - (BUBBLE_DP / 2f).dp.toPx()) / size.width,
                        pivotFractionY = -(BUBBLE_DP / 2f + POD_GAP_DP).dp.toPx() / size.height,
                    )
                }
                .drawWithContent {
                    drawContent()
                    // Grips just outside the two bottom corners, following
                    // the body's rounding, where a drag resizes it.
                    val corner = POD_CORNER_DP.dp.toPx() * (size.width / POD_WIDTH_DP.dp.toPx())
                    val radius = corner + 7.dp.toPx()
                    val stroke = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                    val arcSize = Size(radius * 2, radius * 2)
                    val right = Offset(size.width - corner, size.height - corner)
                    val left = Offset(corner, size.height - corner)
                    drawArc(gripColor, 15f, 60f, false, right - Offset(radius, radius), arcSize, style = stroke)
                    drawArc(gripColor, 105f, 60f, false, left - Offset(radius, radius), arcSize, style = stroke)
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount)
                        },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd,
                    )
                }
                // Measured at the default size, then drawn scaled down or up
                // to fill whatever space the view gives it.
                .layout { measurable, constraints ->
                    val baseW = POD_WIDTH_DP.dp.roundToPx()
                    val baseH = POD_HEIGHT_DP.dp.roundToPx()
                    val s = constraints.maxWidth.toFloat() / baseW
                    val placeable = measurable.measure(Constraints.fixed(baseW, baseH))
                    layout(constraints.maxWidth, (baseH * s).roundToInt().coerceAtMost(constraints.maxHeight)) {
                        placeable.placeWithLayer(0, 0) {
                            scaleX = s
                            scaleY = s
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                    }
                },
        ) {
            CompositionLocalProvider(LocalViewConfiguration provides viewConfiguration) {
                ClassipodApp(controller, playerState)
            }
        }
    }
    }
}

/**
 * How much further the finger must move to drag the iPod than to turn its
 * wheel. Must exceed [MAX_POD_SCALE] for the wheel to win at every size.
 */
private const val BODY_DRAG_SLOP_FACTOR = 2f

/**
 * The stand-in drawn while the iPod is resized from its bottom-left corner:
 * [image] (the iPod as it was when the drag began) scaled to [scale], pinned
 * at its top-right corner, which is the window's top-right less the shadow
 * margin. It also follows [progress], so if the drag minimises the iPod, the
 * stage is what shrinks into the bubble.
 */
@Composable
private fun ResizeStage(image: ImageBitmap?, scale: () -> Float, progress: () -> Float) {
    if (image == null) return
    Canvas(modifier = Modifier.fillMaxSize()) {
        val shadow = POD_SHADOW_DP.dp.toPx()
        val baseW = POD_WIDTH_DP.dp.roundToPx().toFloat()
        val baseH = POD_HEIGHT_DP.dp.roundToPx().toFloat()
        val s = scale()
        // The scale the picture was taken at, from its own size.
        val takenAt = (image.width - 2 * shadow) / baseW
        val k = s / takenAt
        val bodyW = baseW * s
        val bodyH = baseH * s
        val bodyLeft = size.width - shadow - bodyW
        val bodyTop = shadow

        val p = progress()
        val open = MIN_POD_SCALE + (1f - MIN_POD_SCALE) * p
        val bubble = BUBBLE_DP.dp.toPx()
        val pivot = Offset(
            size.width - shadow - bubble / 2f,
            shadow - POD_GAP_DP.dp.toPx() - bubble / 2f,
        )
        val minimizeHint = ((s - AUTO_MINIMIZE_SCALE) / 0.1f).coerceIn(0f, 1f)
        val alpha = (p * 4f).coerceIn(0f, 1f) * (0.55f + 0.45f * minimizeHint)

        withTransform({ scale(open, open, pivot) }) {
            drawImage(
                image = image,
                dstOffset = IntOffset(
                    (bodyLeft - shadow * k).roundToInt(),
                    (bodyTop - shadow * k).roundToInt(),
                ),
                dstSize = IntSize(
                    (image.width * k).roundToInt(),
                    (image.height * k).roundToInt(),
                ),
                alpha = alpha,
                filterQuality = FilterQuality.Medium,
            )
            // The grips, lit, as on the real iPod while a corner is held.
            val corner = POD_CORNER_DP.dp.toPx() * s
            val radius = corner + 7.dp.toPx()
            val stroke = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
            val arcSize = Size(radius * 2, radius * 2)
            val grip = Color(0xF2FFFFFF).copy(alpha = 0.95f * alpha)
            val right = Offset(bodyLeft + bodyW - corner, bodyTop + bodyH - corner)
            val left = Offset(bodyLeft + corner, bodyTop + bodyH - corner)
            drawArc(grip, 15f, 60f, false, right - Offset(radius, radius), arcSize, style = stroke)
            drawArc(grip, 105f, 60f, false, left - Offset(radius, radius), arcSize, style = stroke)
        }
    }
}

/** How many frames a window is given to draw before it is shown, where frame commits can't be watched. */
private const val STAGE_SETTLE_FRAMES = 3
/** The longest to wait for a window to commit a frame before carrying on anyway. */
private const val DRAW_WAIT_TIMEOUT_MS = 250L

/** The iPod body's corner radius (see [ClassipodApp]). */
private const val POD_CORNER_DP = 29

private const val MIN_POD_SCALE = 0.1f

/** The close target at the bottom of the screen. Grows and turns red when the bubble is caught. */
@Composable
private fun DismissTarget(shown: Boolean, hot: Boolean, pull: Offset) {
    val scale by animateFloatAsState(
        targetValue = if (hot) 1.3f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 500f),
        label = "targetScale",
    )
    val lean by animateOffsetAsState(
        targetValue = pull,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 600f),
        label = "targetLean",
    )
    val color by animateColorAsState(
        targetValue = if (hot) Color(0xE6D93025) else Color(0x99000000),
        label = "targetColor",
    )

    Box(
        modifier = Modifier.size(TARGET_WINDOW_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn() + scaleIn(initialScale = 0.6f) + slideInVertically { it },
            exit = fadeOut() + scaleOut(targetScale = 0.6f) + slideOutVertically { it },
        ) {
            Box(
                modifier = Modifier
                    .size(TARGET_SIZE_DP.dp)
                    .graphicsLayer {
                        translationX = lean.x
                        translationY = lean.y
                        scaleX = scale
                        scaleY = scale
                    }
                    .background(color, CircleShape)
                    .border(1.dp, Color(0x33FFFFFF), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Drop here to close",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}
