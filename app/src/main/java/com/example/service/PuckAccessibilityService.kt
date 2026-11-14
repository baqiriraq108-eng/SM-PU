package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.PendingIntent
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
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.SmartPuckApp
import com.example.util.PuckKeepAliveManager

class PuckAccessibilityService : AccessibilityService() {

    sealed class PendingAction {
        object LockScreen : PendingAction()
        object TakeScreenshot : PendingAction()
        data class CircleToSearch(
            val holdDurationMs: Long,
            val onResult: ((Boolean) -> Unit)?
        ) : PendingAction()
    }

    companion object {
        @Volatile
        var instance: PuckAccessibilityService? = null
            private set

        @Volatile
        var isSystemBound: Boolean = false
            private set

        @Volatile
        private var pendingAction: PendingAction? = null

        private val mainHandler = Handler(Looper.getMainLooper())

        fun isRunning(): Boolean = instance != null && isSystemBound

        fun lockScreen(): Boolean {
            val service = instance
            if (service != null && isSystemBound && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            }
            return false
        }

        fun takeScreenshot(): Boolean {
            val service = instance
            if (service != null && isSystemBound && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return service.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            }
            return false
        }

        /**
         * Enqueues a Lock Screen action to run immediately if bound, or as soon as the OS
         * finishes rebinding [onServiceConnected].
         */
        fun enqueueOrPerformLock(): Boolean {
            if (lockScreen()) return true
            pendingAction = PendingAction.LockScreen
            scheduleRetryFlush()
            return true
        }

        /**
         * Enqueues a Screenshot action to run immediately if bound, or as soon as the OS
         * finishes rebinding [onServiceConnected].
         */
        fun enqueueOrPerformScreenshot(): Boolean {
            if (takeScreenshot()) return true
            pendingAction = PendingAction.TakeScreenshot
            scheduleRetryFlush()
            return true
        }

        /**
         * Enqueues a Circle to Search hold gesture to run immediately if bound, or as soon as
         * the OS finishes rebinding [onServiceConnected].
         */
        fun enqueueOrTriggerCircleToSearch(
            holdDurationMs: Long = 850L,
            onResult: ((Boolean) -> Unit)? = null
        ): Boolean {
            if (triggerCircleToSearch(holdDurationMs, onResult)) return true
            pendingAction = PendingAction.CircleToSearch(holdDurationMs, onResult)
            scheduleRetryFlush()
            return true
        }

        private fun scheduleRetryFlush(attemptsRemaining: Int = 12) {
            if (attemptsRemaining <= 0) {
                val expired = pendingAction
                pendingAction = null
                if (expired is PendingAction.CircleToSearch) {
                    expired.onResult?.invoke(false)
                }
                return
            }
            mainHandler.postDelayed({
                if (pendingAction != null) {
                    if (isRunning()) {
                        flushPendingAction()
                    } else {
                        scheduleRetryFlush(attemptsRemaining - 1)
                    }
                }
            }, 80L)
        }

        private fun flushPendingAction() {
            val action = pendingAction ?: return
            if (!isRunning()) return
            pendingAction = null
            when (action) {
                is PendingAction.LockScreen -> lockScreen()
                is PendingAction.TakeScreenshot -> takeScreenshot()
                is PendingAction.CircleToSearch -> triggerCircleToSearch(action.holdDurationMs, action.onResult)
            }
        }

        /**
         * Triggers Circle to Search on Android 16 and Xiaomi HyperOS 3 & 4 by dispatching
         * a stationary press-and-hold gesture on the system navigation handle / home bar.
         * SystemUI and HyperOS launcher intercept this hold at the bottom center of the screen
         * to launch the native Circle to Search overlay with live screen capture context.
         */
        fun triggerCircleToSearch(
            holdDurationMs: Long = 850L,
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
                // without interpreting any upward swipe movement.
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
                            onResult?.invoke(false)
                        }
                    },
                    mainHandler
                )
            } catch (_: Exception) {
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        if (isSystemBound) {
            startForegroundSafely()
            flushPendingAction()
        }
        return START_STICKY
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isSystemBound = true
        // Promote AccessibilityService to foreground priority so Xiaomi HyperOS and Android 16
        // never kill the process or sever the system IAccessibilityServiceConnection binder on task swipe.
        startForegroundSafely()
        flushPendingAction()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        instance = this
        if (isSystemBound) {
            startForegroundSafely()
        }
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
        // No event interception required for global action dispatch
    }

    override fun onInterrupt() {}

    private fun startForegroundSafely() {
        try {
            val notification = buildSharedNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    1,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(1, notification)
            }
        } catch (_: Exception) {
            try {
                startForeground(1, buildSharedNotification())
            } catch (_: Exception) {}
        }
    }

    private fun buildSharedNotification(): Notification {
        val appIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val appPendingIntent = PendingIntent.getActivity(
            this, 0, appIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val lockIntent = Intent(this, FloatingPuckService::class.java).apply {
            action = FloatingPuckService.ACTION_LOCK_SCREEN
        }
        val lockPendingIntent = PendingIntent.getService(
            this, 1, lockIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, SmartPuckApp.CHANNEL_ID)
            .setContentTitle("Smart Puck Active")
            .setContentText("Continuous background overlay running • Tap for options")
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setContentIntent(appPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_lock_power_off, "Lock Screen", lockPendingIntent)
            .addAction(android.R.drawable.ic_menu_manage, "Open App", appPendingIntent)
            .setSound(null)
            .setVibrate(null)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
}
