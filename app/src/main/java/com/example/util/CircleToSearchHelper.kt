package com.example.util

import android.content.Context
import android.widget.Toast
import com.example.service.PuckAccessibilityService

/**
 * Pure Accessibility gesture launcher for Circle to Search on Android 16 and Xiaomi HyperOS 3 & 4.
 *
 * Exclusively dispatches a precision press-and-hold gesture on the bottom system navigation handle
 * via [PuckAccessibilityService.triggerCircleToSearch].
 * Checks authoritative OS settings via [LockScreenHelper.isAccessibilityServiceActive] so it never
 * falsely asks the user to re-enable Accessibility permission after swiping the app from Recents.
 */
object CircleToSearchHelper {

    fun performCircleToSearch(
        context: Context,
        holdDurationMs: Long = 850L,
        onFinished: (() -> Unit)? = null
    ): Boolean {
        // 1. Authoritative OS & binder check: only prompt if genuinely disabled in Android Settings
        if (!LockScreenHelper.isAccessibilityServiceActive(context)) {
            onFinished?.invoke()
            Toast.makeText(
                context,
                "Enable 'Smart Puck Accessibility Lock' in Accessibility settings for Circle to Search",
                Toast.LENGTH_SHORT
            ).show()
            return false
        }

        // 2. Execute immediately if bound, or queue seamlessly if the OS is mid-rebind
        val dispatched = PuckAccessibilityService.enqueueOrTriggerCircleToSearch(holdDurationMs) {
            onFinished?.invoke()
        }

        if (!dispatched) {
            onFinished?.invoke()
        }
        return dispatched
    }
}
