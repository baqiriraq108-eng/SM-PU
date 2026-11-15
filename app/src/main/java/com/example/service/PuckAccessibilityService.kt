package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.SmartPuckApp
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
        const val ACCESSIBILITY_FOREGROUND_NOTIFICATION_ID = 2

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
         * Touch-driven heartbeat that invalidates the 1x1px [TYPE_ACCESSIBILITY_OVERLAY] anchor view
         * whenever the floating puck is touched, preventing Xiaomi HyperOS Millet from classifying
         * the accessibility window token as idle/frozen.
         */
        fun pulseAnchorView() {
            try {
                instance?.overlayAnchorView?.postInvalidate()
            } catch (_: Throwable) {}
        }

        /**
         * Registers an in-process callback that executes immediately if [isServiceAliveAndBound]
         * is already true, or waits up to [timeoutMs] for [onServiceConnected] to fire.
         */
        fun awaitBinderAndExecute(
            timeoutMs: Long = 350L,
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

                val navMode = try {
                    Settings.Secure.getInt(service.contentResolver, "navigation_mode", 2)
                } catch (_: Throwable) {
                    2
                }

                val bottomOffsetDp = if (navMode == 0) 24f else 9f
                val targetX = screenW / 2f
                val targetY = (screenH - bottomOffsetDp * density).coerceIn(1f, screenH - 2f)

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
            PuckPermissionManager.resetSelfHealDebounce()

            // 1. Promote PuckAccessibilityService to Foreground priority (Notification ID = 2 on shared silent channel)
            startForegroundSafely()

            // 2. Attach 1x1px transparent TYPE_ACCESSIBILITY_OVERLAY hardware anchor
            attachAccessibilityOverlayAnchor()

            // 3. Flush any pending action callbacks immediately
            flushConnectionWaiters(this)

            // 4. Safely ensure FloatingPuckService is running post-boot / post-unlock
            val appCtx = applicationContext ?: this
            if (PuckPermissionManager.isUserUnlocked(appCtx)) {
                val prefs = PuckPreferences(appCtx)
                if (prefs.isServiceEnabled && !FloatingPuckService.isServiceRunning) {
                    PuckKeepAliveManager.ensureServiceAlive(appCtx)
                }
            }
        } catch (_: Throwable) {
            // Zero-crash guard so OS never marks service "Not working"
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            super.onTaskRemoved(rootIntent)
            instance = this
            if (isSystemBound) {
                startForegroundSafely()
                attachAccessibilityOverlayAnchor()
            }
            val appCtx = applicationContext ?: this
            if (PuckPermissionManager.isUserUnlocked(appCtx)) {
                PuckKeepAliveManager.ensureServiceAlive(appCtx)
            }
        } catch (_: Throwable) {}
    }

    override fun onUnbind(intent: Intent?): Boolean {
        return try {
            val appCtx = applicationContext ?: this
            detachAccessibilityOverlayAnchor()
            isSystemBound = false
            _isBoundState.value = false
            instance = null
            // Proactive self-heal 200ms after HyperOS unbinds the service in the background
            mainHandler.postDelayed({
                try {
                    PuckPermissionManager.ensureAccessibilityServiceAlive(appCtx, forceImmediate = true)
                } catch (_: Throwable) {}
            }, 200L)
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
            val appCtx = applicationContext ?: this
            detachAccessibilityOverlayAnchor()
            isSystemBound = false
            _isBoundState.value = false
            instance = null
            mainHandler.postDelayed({
                try {
                    PuckPermissionManager.ensureAccessibilityServiceAlive(appCtx, forceImmediate = true)
                } catch (_: Throwable) {}
            }, 200L)
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

    private fun startForegroundSafely() {
        try {
            val notification = buildSharedSilentNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    ACCESSIBILITY_FOREGROUND_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(ACCESSIBILITY_FOREGROUND_NOTIFICATION_ID, notification)
            }
        } catch (_: Throwable) {
            try {
                startForeground(ACCESSIBILITY_FOREGROUND_NOTIFICATION_ID, buildSharedSilentNotification())
            } catch (_: Throwable) {}
        }
    }

    private fun buildSharedSilentNotification(): Notification {
        val appIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val appPendingIntent = PendingIntent.getActivity(
            this,
            2,
            appIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, SmartPuckApp.CHANNEL_ID)
            .setContentTitle("Smart Puck Accessibility Active")
            .setContentText("Instant Screenshot, Lock Screen & Circle to Search ready")
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setContentIntent(appPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSound(null)
            .setVibrate(null)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
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
