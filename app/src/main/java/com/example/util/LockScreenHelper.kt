package com.example.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import com.example.service.PuckAccessibilityService
import com.example.service.PuckDeviceAdminReceiver

object LockScreenHelper {

    /**
     * Authoritatively queries Android OS and active binder state to determine if
     * [PuckAccessibilityService] is enabled in system settings, surviving process restarts,
     * task swipes from Recent Apps, and reboots.
     */
    fun isAccessibilityServiceEnabled(
        context: Context,
        serviceClass: Class<*> = PuckAccessibilityService::class.java
    ): Boolean {
        // 1. Fast path: if the system binder is currently active in memory, it is enabled
        if (PuckAccessibilityService.isRunning()) {
            return true
        }

        // 2. Authoritative OS Settings.Secure check
        try {
            val expectedComponent = ComponentName(context, serviceClass)
            val expectedClassName = serviceClass.name
            val expectedSimpleName = serviceClass.simpleName
            val pkgName = context.packageName

            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (!enabledServices.isNullOrBlank()) {
                val colonSplitter = TextUtils.SimpleStringSplitter(':')
                colonSplitter.setString(enabledServices)
                while (colonSplitter.hasNext()) {
                    val entry = colonSplitter.next().trim()
                    val enabledComponent = ComponentName.unflattenFromString(entry)
                    if (enabledComponent != null) {
                        if (enabledComponent == expectedComponent) {
                            return true
                        }
                        if (enabledComponent.packageName == pkgName &&
                            (enabledComponent.className == expectedClassName ||
                                enabledComponent.className.endsWith(expectedSimpleName, ignoreCase = true))
                        ) {
                            return true
                        }
                    }
                    if (entry.contains(expectedSimpleName, ignoreCase = true) &&
                        (entry.contains(pkgName, ignoreCase = true) || entry.contains("com.example", ignoreCase = true))
                    ) {
                        return true
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Fallback: AccessibilityManager check across all feedback masks
        try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
            if (am != null) {
                val masks = intArrayOf(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
                    AccessibilityServiceInfo.FEEDBACK_GENERIC
                )
                for (mask in masks) {
                    val enabledList = am.getEnabledAccessibilityServiceList(mask)
                    for (service in enabledList) {
                        val id = service.id ?: continue
                        val resolvePkg = service.resolveInfo?.serviceInfo?.packageName
                        if (resolvePkg == context.packageName ||
                            id.contains("PuckAccessibilityService", ignoreCase = true) ||
                            id.contains(context.packageName, ignoreCase = true)
                        ) {
                            return true
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return false
    }

    /**
     * Executes lock screen using the available method:
     * 1. AccessibilityService (Android 9+)
     * 2. DevicePolicyManager (Device Admin)
     * Never falsely reports failure if the Accessibility Service is enabled in OS settings.
     */
    fun performLock(context: Context): Boolean {
        // Method 1: Immediate in-memory bound Accessibility Service execution
        try {
            if (PuckAccessibilityService.lockScreen()) {
                return true
            }
        } catch (_: Exception) {}

        // Method 2: Device Admin / DevicePolicyManager
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val adminComponent = ComponentName(context, PuckDeviceAdminReceiver::class.java)
            if (dpm != null && dpm.isAdminActive(adminComponent)) {
                dpm.lockNow()
                return true
            }
        } catch (_: Exception) {}

        // Method 3: If enabled in OS settings while the binder is reconnecting after a task swipe,
        // queue the lock action to fire immediately upon connection and never prompt for permission.
        if (isAccessibilityServiceEnabled(context)) {
            PuckAccessibilityService.enqueueOrPerformLock()
            return true
        }

        return false
    }

    /**
     * Executes instant system screenshot via Accessibility Service (Android 9+).
     * If enabled in OS settings while binder is reconnecting, queues the screenshot seamlessly.
     */
    fun performScreenshot(context: Context): Boolean {
        try {
            if (PuckAccessibilityService.takeScreenshot()) {
                return true
            }
        } catch (_: Exception) {}

        if (isAccessibilityServiceEnabled(context)) {
            PuckAccessibilityService.enqueueOrPerformScreenshot()
            return true
        }

        return false
    }

    /**
     * Checks if any screen lock mechanism is genuinely active or enabled in the OS.
     */
    fun isLockPermissionGranted(context: Context): Boolean {
        return isAccessibilityServiceEnabled(context) || isDeviceAdminActive(context)
    }

    /**
     * Checks if PuckAccessibilityService is bound in-memory or enabled in Android OS settings.
     */
    fun isAccessibilityServiceActive(context: Context): Boolean {
        return PuckAccessibilityService.isRunning() || isAccessibilityServiceEnabled(context)
    }

    /**
     * Checks if Device Admin is active in system settings.
     */
    fun isDeviceAdminActive(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val adminComponent = ComponentName(context, PuckDeviceAdminReceiver::class.java)
            dpm?.isAdminActive(adminComponent) == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Opens Android Accessibility Settings.
     */
    fun requestAccessibilityPermission(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Toast.makeText(
                context,
                "Enable 'Smart Puck Accessibility Lock' in Accessibility settings",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Exception) {
            openGeneralSettings(context)
        }
    }

    /**
     * Prompts the user to grant Device Admin permission.
     */
    fun requestDeviceAdminPermission(context: Context) {
        val adminComponent = ComponentName(context, PuckDeviceAdminReceiver::class.java)
        try {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Grant Device Admin permission to allow Smart Puck to lock the screen instantly."
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            requestAccessibilityPermission(context)
        }
    }

    /**
     * Guides the user to grant lock permissions if not already enabled in system.
     */
    fun promptForLockPermission(context: Context) {
        if (isLockPermissionGranted(context)) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            requestAccessibilityPermission(context)
        } else {
            requestDeviceAdminPermission(context)
        }
    }

    private fun openGeneralSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }
}
