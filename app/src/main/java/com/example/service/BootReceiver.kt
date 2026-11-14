package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.PuckPreferences
import com.example.util.PuckKeepAliveManager
import com.example.util.PuckPermissionManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (!PuckPermissionManager.isUserUnlocked(context)) {
                return
            }

            val action = intent.action ?: return
            val isUnlockedBootAction = action == Intent.ACTION_BOOT_COMPLETED ||
                action == "android.intent.action.QUICKBOOT_POWERON" ||
                action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
                action == Intent.ACTION_USER_UNLOCKED ||
                action == Intent.ACTION_MY_PACKAGE_REPLACED ||
                action == Intent.ACTION_USER_PRESENT

            if (isUnlockedBootAction) {
                val prefs = PuckPreferences(context.applicationContext)
                if ((prefs.autoStart || action == Intent.ACTION_MY_PACKAGE_REPLACED) && prefs.isServiceEnabled) {
                    try {
                        PuckKeepAliveManager.startPuckService(context.applicationContext)
                    } catch (_: Throwable) {
                        // Swallowing Android 15/16 ForegroundServiceStartNotAllowedException so
                        // shared process stays alive and PuckAccessibilityService never breaks on boot.
                    }
                }
            }
        } catch (_: Throwable) {
            // Absolute crash isolation in BootReceiver to protect AccessibilityService process health
        }
    }
}
