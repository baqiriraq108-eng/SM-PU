package com.example.util

import android.content.Context
import android.widget.Toast
import com.example.service.PuckAccessibilityService

/**
 * Pure Accessibility gesture launcher for Circle to Search on Android 16 and Xiaomi HyperOS 3 & 4.
 *
 * Exclusively dispatches a precision press-and-hold gesture on the bottom system navigation handle
 * via [PuckAccessibilityService.triggerCircleToSearch]. Never falls back to Voice Search or
 * Assistant intents so Google Voice Search ("Listening...") is never accidentally triggered.
 */
object CircleToSearchHelper {

    fun performCircleToSearch(
        context: Context,
        holdDurationMs: Long = 850L,
        onFinished: (() -> Unit)? = null
    ): Boolean {
        if (!PuckAccessibilityService.isRunning()) {
            onFinished?.invoke()
            Toast.makeText(
                context,
                "Enable 'Smart Puck Accessibility Lock' in Accessibility settings for Circle to Search",
                Toast.LENGTH_SHORT
            ).show()
            return false
        }

        val dispatched = PuckAccessibilityService.triggerCircleToSearch(holdDurationMs) {
            onFinished?.invoke()
        }

        if (!dispatched) {
            onFinished?.invoke()
        }
        return dispatched
    }
}
