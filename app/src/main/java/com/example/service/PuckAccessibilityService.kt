package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.example.data.PuckPreferences
import com.example.util.PuckKeepAliveManager
import com.example.util.PuckPermissionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class PuckAccessibilityService : AccessibilityService() {

    private class ConnectionWaiter(
        val onConnected: (PuckAccessibilityService) -> Unit,
        val onTimeout: () -> Unit
    ) {
        val completed = AtomicBoolean(false)
        var timeoutRunnable: Runnable? = null
    }

    private var overlayAnchorView: View? = null

    companion object {
        @Volatile
        var instance: PuckAccessibilityService? = null
            private set

        @Volatile
        var isSystemBound: Boolean = false
            private set

        private val _isBoundState = MutableStateFlow(false)
        val isBoundState: StateFlow<Boolean> = _isBoundState.asStateFlow()

        private val mainHandler = Handler(Looper.getMainLooper())
        private val connectionWaiters = CopyOnWriteArrayList<ConnectionWaiter>()

        /**
         * Returns true only when the service instance exists AND the OS system_server
         * IAccessibilityServiceConnection binder is actively attached via [onServiceConnected].
         */
        fun isServiceAliveAndBound(): Boolean = instance != null && isSystemBound

        fun isRunning(): Boolean = isServiceAliveAndBound()

        /**
         * Registers an in-process callback that executes immediately if [isServiceAliveAndBound]
         * is already true, or waits up to [timeoutMs] (default 2500ms) for [onServiceConnected]
         * to fire without calling startService() on the AccessibilityService.
         */
        fun awaitBinderAndExecute(
            timeoutMs: Long = 2500L,
            onConnected: (PuckAccessibilityService) -> Unit,
            onTimeout: () -> Unit
        ) {
            val current = instance
            if (current != null && isSystemBound) {
                onConnected(current)
                return
            }

            val waiter = ConnectionWaiter(onConnected = onConnected, onTimeout = onTimeout)
            val runnable = Runnable {
                if (waiter.completed.compareAndSet(false, true)) {
                    connectionWaiters.remove(waiter)
                    val lateService = instance
                    if (lateService != null && isSystemBound) {
                        waiter.onConnected(lateService)
                    } else {
                        waiter.onTimeout()
                    }
                }
            }
            waiter.timeoutRunnable = runnable
            connectionWaiters.add(waiter)
            mainHandler.postDelayed(runnable, timeoutMs)
        }

        private fun flushConnectionWaiters(service: PuckAccessibilityService) {
            for (waiter in connectionWaiters) {
                if (waiter.completed.compareAndSet(false, true)) {
                    waiter.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                    connectionWaiters.remove(waiter)
                    mainHandler.post {
                        try {
                            waiter.onConnected(service)
                        } catch (_: Throwable) {}
                    }
                }
            }
        }

        fun lockScreen(): Boolean {
            val service = instance
            if (service != null && isSystemBound && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return try {
                    service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                } catch (_: Throwable) {
                    false
                }
            }
            return false
        }

        fun takeScreenshot(): Boolean {
            val service = instance
            if (service != null && isSystemBound && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return try {
                    service.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                } catch (_: Throwable) {
                    false
                }
            }
            return false
        }

        /**
         * Triggers Circle to Search on Android 16 and Xiaomi HyperOS 3 & 4 by dispatching
         * a stationary press-and-hold gesture on the system navigation handle / home bar.
         * Never falls back to Voice Search or Assistant intents.
         */
        fun triggerCircleToSearch(
            holdDurationMs: Long = 850L,
            allowRetryOnCancel: Boolean = true,
            onResult: ((Boolean) -> Unit)? = null
        ): Boolean {
            val service = instance ?: return false
            if (!isSystemBound) return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

            return try {
                val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val realMetrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(realMetrics)

                val density = service.resources.displayMetrics.density
                val screenW = realMetrics.widthPixels.toFloat().coerceAtLeast(360f)
                val screenH = realMetrics.heightPixels.toFloat().coerceAtLeast(640f)

                // Detect navigation mode:
                // 2 = Gesture navigation (default on Android 16 & Xiaomi HyperOS 3/4)
                // 0 = 3-button navigation
                val navMode = try {
                    Settings.Secure.getInt(service.contentResolver, "navigation_mode", 2)
                } catch (_: Throwable) {
                    2
                }

                // Target the exact vertical coordinate of the system gesture pill (9dp from bottom)
                // or 3-button Home key (24dp from bottom)
                val bottomOffsetDp = if (navMode == 0) 24f else 9f
                val targetX = screenW / 2f
                val targetY = (screenH - bottomOffsetDp * density).coerceIn(1f, screenH - 2f)

                // Pure stationary point Path so SystemUI registers a clean long-press on the handle
                val holdPath = Path().apply {
                    moveTo(targetX, targetY)
                }

                val clampedDuration = holdDurationMs.coerceIn(400L, 1500L)
                val stroke = GestureDescription.StrokeDescription(holdPath, 0L, clampedDuration)
                val gesture = GestureDescription.Builder()
                    .addStroke(stroke)
                    .build()

                service.dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            super.onCompleted(gestureDescription)
                            onResult?.invoke(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            super.onCancelled(gestureDescription)
                            if (allowRetryOnCancel && isServiceAliveAndBound()) {
                                mainHandler.postDelayed({
                                    val retried = triggerCircleToSearch(
                                        holdDurationMs = clampedDuration,
                                        allowRetryOnCancel = false,
                                        onResult = onResult
                                    )
                                    if (!retried) {
                                        onResult?.invoke(false)
                                    }
                                }, 120L)
                            } else {
                                onResult?.invoke(false)
                            }
                        }
                    },
                    mainHandler
                )
            } catch (_: Throwable) {
                false
            }
        }
    }

    override fun onCreate() {
        try {
            super.onCreate()
            instance = this
        } catch (_: Throwable) {}
    }

    override fun onServiceConnected() {
        try {
            super.onServiceConnected()
            instance = this
            isSystemBound = true
            _isBoundState.value = true

            // 1. Anchor AccessibilityService alive on Xiaomi HyperOS with a 1x1 TYPE_ACCESSIBILITY_OVERLAY
            // so system_server and HyperOS battery cleaner maintain visual-accessibility priority.
            attachAccessibilityOverlayAnchor()

            // 2. Guarded post-boot & post-unlock FloatingPuckService startup driven safely from
            // system-bound AccessibilityService context (exempt from Android 15/16 FGS boot blocks).
            val appCtx = applicationContext ?: this
            if (PuckPermissionManager.isUserUnlocked(appCtx)) {
                val prefs = PuckPreferences(appCtx)
                if (prefs.isServiceEnabled && !FloatingPuckService.isServiceRunning) {
                    PuckKeepAliveManager.ensureServiceAlive(appCtx)
                }
            }

            // 3. Flush any pending in-process action callbacks
            flushConnectionWaiters(this)
        } catch (_: Throwable) {
            // Zero-crash guard: never throw inside onServiceConnected() or OS marks service "Not working"
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            super.onTaskRemoved(rootIntent)
            instance = this
            val appCtx = applicationContext ?: this
            if (PuckPermissionManager.isUserUnlocked(appCtx)) {
                PuckKeepAliveManager.ensureServiceAlive(appCtx)
            }
        } catch (_: Throwable) {}
    }

    override fun onUnbind(intent: Intent?): Boolean {
        return try {
            detachAccessibilityOverlayAnchor()
            isSystemBound = false
            _isBoundState.value = false
            instance = null
            super.onUnbind(intent)
        } catch (_: Throwable) {
            isSystemBound = false
            _isBoundState.value = false
            instance = null
            false
        }
    }

    override fun onDestroy() {
        try {
            detachAccessibilityOverlayAnchor()
            isSystemBound = false
            _isBoundState.value = false
            instance = null
            super.onDestroy()
        } catch (_: Throwable) {
            isSystemBound = false
            _isBoundState.value = false
            instance = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            // No event interception required for global action and gesture dispatch
        } catch (_: Throwable) {}
    }

    override fun onInterrupt() {
        try {
            // Intentionally no-op with zero-crash protection
        } catch (_: Throwable) {}
    }

    private fun attachAccessibilityOverlayAnchor() {
        try {
            if (overlayAnchorView != null) return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return

            val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
            val anchor = View(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
            }
            val anchorParams = WindowManager.LayoutParams(
                1,
                1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }
            wm.addView(anchor, anchorParams)
            overlayAnchorView = anchor
        } catch (_: Throwable) {}
    }

    private fun detachAccessibilityOverlayAnchor() {
        try {
            val anchor = overlayAnchorView ?: return
            overlayAnchorView = null
            val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            wm?.removeView(anchor)
        } catch (_: Throwable) {
            overlayAnchorView = null
        }
    }
}
