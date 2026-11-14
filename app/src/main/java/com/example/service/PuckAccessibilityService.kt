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

class PuckAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: PuckAccessibilityService? = null
            private set

        fun lockScreen(): Boolean {
            val service = instance
            if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            }
            return false
        }

        fun takeScreenshot(): Boolean {
            val service = instance
            if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return service.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            }
            return false
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
                    Handler(Looper.getMainLooper())
                )
            } catch (_: Exception) {
                false
            }
        }

        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No event interception required for global action dispatch
    }

    override fun onInterrupt() {}
}
