package com.example.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import com.example.service.PuckAccessibilityService

/**
 * Platform-optimized Circle to Search launcher engineered for Android 16 and Xiaomi HyperOS 3 & 4.
 *
 * Execution Strategy:
 * 1. Primary (Android 16 + Xiaomi HyperOS 3/4 Native System Trigger):
 *    Uses [PuckAccessibilityService.triggerCircleToSearch] to synthesize a precision 520ms
 *    press-and-hold gesture on the bottom system navigation pill / home button. Both Android 16
 *    SystemUI and HyperOS 3/4 intercept this native gesture to launch Circle to Search with live
 *    screen context.
 * 2. Secondary (Google Omnient / Contextual Search & Xiaomi HyperOS AI Intents):
 *    If Accessibility Service is unavailable or gesture dispatch is rejected, falls back through
 *    explicit Google Quick Search Box Omnient / Contextual Search and standard Assistant intents.
 */
object CircleToSearchHelper {

    fun performCircleToSearch(
        context: Context,
        holdDurationMs: Long = 850L,
        onFinished: (() -> Unit)? = null
    ): Boolean {
        // Stage 1: Native Accessibility System Navigation Handle Hold (Android 16 & HyperOS 3/4)
        if (PuckAccessibilityService.isRunning()) {
            val dispatched = PuckAccessibilityService.triggerCircleToSearch(holdDurationMs) { success ->
                if (!success) {
                    launchFallbackSearchIntent(context)
                }
                onFinished?.invoke()
            }
            if (dispatched) {
                return true
            }
        }

        // Stage 2: Direct Contextual Search / Omnient / Assistant Intent Fallback
        val fallbackLaunched = launchFallbackSearchIntent(context)
        onFinished?.invoke()

        if (!fallbackLaunched && !LockScreenHelper.isAccessibilityServiceActive(context)) {
            Toast.makeText(
                context,
                "Enable 'Smart Puck Accessibility Lock' in Accessibility settings for Circle to Search",
                Toast.LENGTH_SHORT
            ).show()
        }
        return fallbackLaunched
    }

    private fun launchFallbackSearchIntent(context: Context): Boolean {
        // 1. Try Google App Contextual Search / Omnient / Lens entry points (Android 15/16 & HyperOS 3/4)
        val googleSearchIntents = listOf(
            Intent("com.google.android.googlequicksearchbox.OMNIENT_ACTIVITY").apply {
                setPackage("com.google.android.googlequicksearchbox")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent("android.intent.action.VOICE_COMMAND").apply {
                setPackage("com.google.android.googlequicksearchbox")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Intent.ACTION_SEARCH_LONG_PRESS).apply {
                setPackage("com.google.android.googlequicksearchbox")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        for (intent in googleSearchIntents) {
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return true
                }
            } catch (_: Exception) {}
        }

        // 2. Try Xiaomi HyperOS 3 & 4 Super XiaoAi / Mi AI Visual Search / System Assistant
        if (isXiaomiOrHyperOS()) {
            val hyperOsIntents = listOf(
                Intent(Intent.ACTION_ASSIST).apply {
                    setPackage("com.miui.voiceassist")
                    putExtra("voice_assist_start_from_key", "long_press_home")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                Intent().apply {
                    component = ComponentName(
                        "com.miui.voiceassist",
                        "com.xiaomi.voiceassistant.CTAAlertActivity"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            for (intent in hyperOsIntents) {
                try {
                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                        return true
                    }
                } catch (_: Exception) {}
            }
        }

        // 3. Universal Android System Assistant / Contextual Search long-press fallback
        val systemFallbackIntents = listOf(
            Intent(Intent.ACTION_SEARCH_LONG_PRESS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Intent.ACTION_VOICE_COMMAND).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Intent.ACTION_ASSIST).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        for (intent in systemFallbackIntents) {
            try {
                context.startActivity(intent)
                return true
            } catch (_: Exception) {}
        }

        return false
    }

    fun isXiaomiOrHyperOS(): Boolean {
        val manufacturer = Build.MANUFACTURER?.lowercase() ?: ""
        val brand = Build.BRAND?.lowercase() ?: ""
        return manufacturer.contains("xiaomi") ||
            brand.contains("xiaomi") ||
            brand.contains("redmi") ||
            brand.contains("poco")
    }
}
