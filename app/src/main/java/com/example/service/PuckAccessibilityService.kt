package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.example.util.PuckKeepAliveManager
import com.example.util.PuckPermissionManager
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

    companion object {
        @Volatile
        var instance: PuckAccessibilityService? = null
            private set

        @Volatile
        var isSystemBound: Boolean = false
            private set

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
                        waiter.onConnected(service)
                    }
                }
            }
        }

        fun lockScreen(): Boolean {
            val service = instance
            if (service != null && isSystemBound && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return try {
                    service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                } catch (_: Exception) {
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
                } catch (_: Exception) {
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
                } catch (_: Exception) {
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

                val dispatched = service.dispatchGesture(
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
                dispatched
            } catch (_: Exception) {
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isSystemBound = true
        // Synchronously commit all 3 feature permission latches (Lock Screen, Screenshot, Circle to Search)
        PuckPermissionManager.onAccessibilityServiceConnected(applicationContext)
        // Ensure FloatingPuckService maintains foreground process priority for the shared app process
        PuckKeepAliveManager.ensureServiceAlive(applicationContext)
        // Flush any in-process action callbacks waiting on binder connection
        flushConnectionWaiters(this)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        instance = this
        // Keep the shared process alive via FloatingPuckService without calling startForeground() on AccessibilityService
        PuckKeepAliveManager.ensureServiceAlive(applicationContext)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isSystemBound = false
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        isSystemBound = false
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No event interception required for global action and gesture dispatch
    }

    override fun onInterrupt() {}
}
