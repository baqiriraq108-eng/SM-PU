package com.example.util

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.data.PuckPreferences
import com.example.service.PuckAccessibilityService
import com.example.service.PuckDeviceAdminReceiver

/**
 * Ground-up, resilient Permission & Capability Architecture for Smart Puck on Android 16 and Xiaomi HyperOS.
 *
 * Provides three dedicated, independent pipelines:
 * 1. Lock Screen Pipeline ([isLockScreenGranted], [executeLockScreen], [requestLockScreenAccessibility], [requestDeviceAdmin])
 * 2. Screenshot Pipeline ([isScreenshotGranted], [executeScreenshot], [requestScreenshotAccessibility])
 * 3. Circle to Search Pipeline ([isCircleToSearchGranted], [executeCircleToSearch], [requestCircleToSearchAccessibility])
 *
 * Guarantees:
 * - Synchronous `.commit()` latches per feature so transient binder transitions after swiping from Recent Apps
 *   never reset UI state or re-prompt the user.
 * - Upfront `WRITE_SECURE_SETTINGS` binder self-healing ([trySelfHealAccessibilityBinder]) triggered at t = 0ms
 *   before awaiting the 2500ms in-process `onServiceConnected()` callback window.
 * - Non-silent failure handling: falls back to `DevicePolicyManager.lockNow()` for Lock Screen, or restores
 *   overlay state and displays a concise toast only when an action genuinely fails due to OS revocation.
 */
object PuckPermissionManager {

    private val mainHandler = Handler(Looper.getMainLooper())
    private const val BINDER_RECOVERY_TIMEOUT_MS = 2500L

    // =========================================================================
    // LIFECYCLE CALLBACKS FROM SYSTEM SERVICES & RECEIVERS
    // =========================================================================

    /**
     * Invoked immediately when [PuckAccessibilityService.onServiceConnected] attaches the system binder.
     * Synchronously commits all three feature latches to disk via `.commit()`.
     */
    fun onAccessibilityServiceConnected(context: Context) {
        val prefs = PuckPreferences(context.applicationContext)
        prefs.latchAllAccessibilityPermissions()
    }

    /**
     * Invoked when [PuckDeviceAdminReceiver.onEnabled] fires.
     * Synchronously commits the Lock Screen latch to disk via `.commit()`.
     */
    fun onDeviceAdminEnabled(context: Context) {
        val prefs = PuckPreferences(context.applicationContext)
        prefs.latchLockPermission()
    }

    /**
     * Invoked when [PuckDeviceAdminReceiver.onDisabled] fires.
     * Only clears the Lock Screen latch if Accessibility is also disabled in OS settings.
     */
    fun onDeviceAdminDisabled(context: Context) {
        val appContext = context.applicationContext
        if (!PuckAccessibilityService.isServiceAliveAndBound() && !isAccessibilityEnabledInOs(appContext)) {
            val prefs = PuckPreferences(appContext)
            prefs.isLockPermissionLatched = false
        }
    }

    // =========================================================================
    // AUTHORITATIVE OS & SELF-HEALING ENGINE
    // =========================================================================

    /**
     * Authoritatively inspects Android OS settings and AccessibilityManager to determine
     * whether [PuckAccessibilityService] is enabled in system settings.
     */
    fun isAccessibilityEnabledInOs(
        context: Context,
        serviceClass: Class<*> = PuckAccessibilityService::class.java
    ): Boolean {
        // Layer 1: Live bound system service
        if (PuckAccessibilityService.isServiceAliveAndBound()) {
            return true
        }

        // Layer 2: Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES inspection
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
                val splitter = TextUtils.SimpleStringSplitter(':')
                splitter.setString(enabledServices)
                while (splitter.hasNext()) {
                    val entry = splitter.next().trim()
                    val component = ComponentName.unflattenFromString(entry)
                    if (component != null) {
                        if (component == expectedComponent) {
                            return true
                        }
                        if (component.packageName == pkgName &&
                            (component.className == expectedClassName ||
                                component.className.endsWith(expectedSimpleName, ignoreCase = true))
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

        // Layer 3: AccessibilityManager enabled service list across all feedback types
        try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
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
     * Checks if Device Admin ([PuckDeviceAdminReceiver]) is currently active.
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
     * Checks if [PuckAccessibilityService] is either actively bound, enabled in OS settings,
     * or latched across any of the accessibility-backed pipelines.
     */
    fun isAccessibilityServiceActive(context: Context): Boolean {
        val appContext = context.applicationContext
        if (PuckAccessibilityService.isServiceAliveAndBound() || isAccessibilityEnabledInOs(appContext)) {
            PuckPreferences(appContext).latchAllAccessibilityPermissions()
            return true
        }
        val prefs = PuckPreferences(appContext)
        return prefs.isLockPermissionLatched ||
            prefs.isScreenshotPermissionLatched ||
            prefs.isCtsPermissionLatched
    }

    /**
     * Programmatically forces Android's `system_server` to rebind [PuckAccessibilityService]
     * if `WRITE_SECURE_SETTINGS` is granted.
     *
     * Invoked IMMEDIATELY (at t = 0ms) when an action is triggered while [PuckAccessibilityService.isServiceAliveAndBound]
     * is false, BEFORE waiting on the 2500ms `onServiceConnected()` callback window.
     */
    fun trySelfHealAccessibilityBinder(context: Context): Boolean {
        val hasWriteSecure = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasWriteSecure) return false

        return try {
            val resolver = context.contentResolver
            val componentName = ComponentName(context, PuckAccessibilityService::class.java)
            val flattened = componentName.flattenToString()
            val shortFlattened = componentName.flattenToShortString()
            val simpleName = PuckAccessibilityService::class.java.simpleName

            val currentRaw = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            val existingEntries = currentRaw.split(":")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            // Remove existing entry first so system_server detects a fresh service registration transition
            val withoutSelf = existingEntries.filterNot {
                it.equals(flattened, ignoreCase = true) ||
                    it.equals(shortFlattened, ignoreCase = true) ||
                    it.contains(simpleName, ignoreCase = true)
            }

            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                withoutSelf.joinToString(":")
            )

            // Re-append our service component and toggle ACCESSIBILITY_ENABLED (0 -> 1)
            val updatedList = withoutSelf.toMutableList().apply { add(flattened) }
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                updatedList.joinToString(":")
            )
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun lockViaDeviceAdmin(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val adminComponent = ComponentName(context, PuckDeviceAdminReceiver::class.java)
            if (dpm != null && dpm.isAdminActive(adminComponent)) {
                dpm.lockNow()
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    // =========================================================================
    // PIPELINE 1: LOCK SCREEN (DEDICATED & INDEPENDENT)
    // =========================================================================

    /**
     * Verifies whether Lock Screen permission is granted via live Accessibility binder,
     * active Device Admin, OS Settings.Secure inspection, or the synchronous Lock Screen latch.
     */
    fun isLockScreenGranted(context: Context): Boolean {
        val appContext = context.applicationContext
        val prefs = PuckPreferences(appContext)

        if (PuckAccessibilityService.isServiceAliveAndBound() || isAccessibilityEnabledInOs(appContext)) {
            prefs.latchAllAccessibilityPermissions()
            return true
        }
        if (isDeviceAdminActive(appContext)) {
            prefs.latchLockPermission()
            return true
        }
        return prefs.isLockPermissionLatched
    }

    /**
     * Executes Lock Screen with immediate fast paths, upfront `WRITE_SECURE_SETTINGS` binder self-healing,
     * a 2500ms in-process `onServiceConnected()` callback window, `DevicePolicyManager` fallback,
     * and non-silent failure notification on genuine OS revocation.
     */
    fun executeLockScreen(
        context: Context,
        onResult: ((Boolean) -> Unit)? = null
    ): Boolean {
        val appContext = context.applicationContext
        val prefs = PuckPreferences(appContext)

        // 1. Fast path: Live Accessibility Service binder
        if (PuckAccessibilityService.isServiceAliveAndBound() && PuckAccessibilityService.lockScreen()) {
            prefs.latchAllAccessibilityPermissions()
            onResult?.invoke(true)
            return true
        }

        // 2. Fast path: Active Device Policy Manager (Device Admin)
        if (lockViaDeviceAdmin(appContext)) {
            prefs.latchLockPermission()
            onResult?.invoke(true)
            return true
        }

        // 3. Check if Lock Screen was ever granted or enabled in OS
        if (!isLockScreenGranted(appContext)) {
            onResult?.invoke(false)
            return false
        }

        // 4. Binder is not currently attached:
        //    IMMEDIATELY attempt WRITE_SECURE_SETTINGS self-heal upfront (t = 0ms) BEFORE waiting 2500ms,
        //    so system_server rebinds within ~100-200ms and fires onServiceConnected().
        trySelfHealAccessibilityBinder(appContext)
        PuckKeepAliveManager.ensureServiceAlive(appContext)

        PuckAccessibilityService.awaitBinderAndExecute(
            timeoutMs = BINDER_RECOVERY_TIMEOUT_MS,
            onConnected = {
                prefs.latchAllAccessibilityPermissions()
                val locked = PuckAccessibilityService.lockScreen() || lockViaDeviceAdmin(appContext)
                onResult?.invoke(locked)
            },
            onTimeout = {
                // Recovery window finished and isServiceAliveAndBound() is still false:
                // Fall back to DevicePolicyManager first
                if (lockViaDeviceAdmin(appContext)) {
                    prefs.latchLockPermission()
                    onResult?.invoke(true)
                } else {
                    // Check if OS genuinely revoked Accessibility
                    if (!isAccessibilityEnabledInOs(appContext) && !isDeviceAdminActive(appContext)) {
                        prefs.isLockPermissionLatched = false
                    }
                    showNonIntrusiveFailureToast(
                        appContext,
                        "Accessibility service disconnected — re-enable Lock Screen in Settings"
                    )
                    onResult?.invoke(false)
                }
            }
        )
        return true
    }

    fun requestLockScreenAccessibility(context: Context) {
        openAccessibilitySettings(context, "Enable 'Smart Puck Accessibility Lock' in Accessibility settings")
    }

    fun requestDeviceAdmin(context: Context) {
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
            requestLockScreenAccessibility(context)
        }
    }

    // =========================================================================
    // PIPELINE 2: SCREENSHOT (DEDICATED & INDEPENDENT)
    // =========================================================================

    /**
     * Verifies whether Screenshot permission is granted via live Accessibility binder,
     * OS Settings.Secure inspection, or the synchronous Screenshot latch.
     */
    fun isScreenshotGranted(context: Context): Boolean {
        val appContext = context.applicationContext
        val prefs = PuckPreferences(appContext)

        if (PuckAccessibilityService.isServiceAliveAndBound() || isAccessibilityEnabledInOs(appContext)) {
            prefs.latchAllAccessibilityPermissions()
            return true
        }
        return prefs.isScreenshotPermissionLatched
    }

    /**
     * Executes Screenshot with immediate fast path, upfront `WRITE_SECURE_SETTINGS` binder self-healing,
     * a 2500ms in-process `onServiceConnected()` callback window, and guaranteed puck restoration +
     * concise toast feedback if the binder is genuinely dead.
     */
    fun executeScreenshot(
        context: Context,
        onCompleted: ((Boolean) -> Unit)? = null
    ): Boolean {
        val appContext = context.applicationContext
        val prefs = PuckPreferences(appContext)

        // 1. Check if Screenshot permission is granted/latched
        if (!isScreenshotGranted(appContext)) {
            onCompleted?.invoke(false)
            return false
        }

        // 2. Fast path: Live Accessibility Service binder
        if (PuckAccessibilityService.isServiceAliveAndBound() && PuckAccessibilityService.takeScreenshot()) {
            prefs.latchAllAccessibilityPermissions()
            onCompleted?.invoke(true)
            return true
        }

        // 3. Binder is not currently attached:
        //    IMMEDIATELY attempt WRITE_SECURE_SETTINGS self-heal upfront (t = 0ms) BEFORE waiting 2500ms.
        trySelfHealAccessibilityBinder(appContext)
        PuckKeepAliveManager.ensureServiceAlive(appContext)

        PuckAccessibilityService.awaitBinderAndExecute(
            timeoutMs = BINDER_RECOVERY_TIMEOUT_MS,
            onConnected = {
                prefs.latchAllAccessibilityPermissions()
                val captured = PuckAccessibilityService.takeScreenshot()
                onCompleted?.invoke(captured)
            },
            onTimeout = {
                if (!isAccessibilityEnabledInOs(appContext)) {
                    prefs.isScreenshotPermissionLatched = false
                }
                showNonIntrusiveFailureToast(
                    appContext,
                    "Accessibility service disconnected — re-enable for Screenshot"
                )
                onCompleted?.invoke(false)
            }
        )
        return true
    }

    fun requestScreenshotAccessibility(context: Context) {
        openAccessibilitySettings(context, "Enable 'Smart Puck Accessibility Lock' for instant Screenshots")
    }

    // =========================================================================
    // PIPELINE 3: CIRCLE TO SEARCH (DEDICATED & INDEPENDENT)
    // =========================================================================

    /**
     * Verifies whether Circle to Search gesture permission is granted via live Accessibility binder,
     * OS Settings.Secure inspection, or the synchronous Circle to Search latch.
     */
    fun isCircleToSearchGranted(context: Context): Boolean {
        val appContext = context.applicationContext
        val prefs = PuckPreferences(appContext)

        if (PuckAccessibilityService.isServiceAliveAndBound() || isAccessibilityEnabledInOs(appContext)) {
            prefs.latchAllAccessibilityPermissions()
            return true
        }
        return prefs.isCtsPermissionLatched
    }

    /**
     * Executes Circle to Search bottom navigation bar press-and-hold gesture with:
     * - Zero Voice Search / Assistant intent fallbacks.
     * - Immediate fast path when [PuckAccessibilityService.isServiceAliveAndBound] is true.
     * - Upfront `WRITE_SECURE_SETTINGS` binder self-healing (t = 0ms) BEFORE awaiting the 2500ms
     *   in-process `onServiceConnected()` callback window.
     * - Non-silent failure handling: restores the puck and shows a concise toast if the binder
     *   remains dead due to OS revocation.
     */
    fun executeCircleToSearch(
        context: Context,
        holdDurationMs: Long = 850L,
        onCompleted: ((Boolean) -> Unit)? = null
    ): Boolean {
        val appContext = context.applicationContext
        val prefs = PuckPreferences(appContext)

        // 1. Check if Circle to Search permission is granted/latched
        if (!isCircleToSearchGranted(appContext)) {
            showNonIntrusiveFailureToast(
                appContext,
                "Enable 'Smart Puck Accessibility Lock' in Accessibility settings for Circle to Search"
            )
            onCompleted?.invoke(false)
            return false
        }

        // 2. Fast path: Live Accessibility Service binder
        if (PuckAccessibilityService.isServiceAliveAndBound()) {
            prefs.latchAllAccessibilityPermissions()
            val dispatched = PuckAccessibilityService.triggerCircleToSearch(
                holdDurationMs = holdDurationMs,
                allowRetryOnCancel = true
            ) { success ->
                onCompleted?.invoke(success)
            }
            if (dispatched) {
                return true
            }
        }

        // 3. Binder is not currently attached:
        //    IMMEDIATELY attempt WRITE_SECURE_SETTINGS self-heal upfront (t = 0ms) BEFORE waiting 2500ms.
        trySelfHealAccessibilityBinder(appContext)
        PuckKeepAliveManager.ensureServiceAlive(appContext)

        PuckAccessibilityService.awaitBinderAndExecute(
            timeoutMs = BINDER_RECOVERY_TIMEOUT_MS,
            onConnected = {
                prefs.latchAllAccessibilityPermissions()
                val dispatched = PuckAccessibilityService.triggerCircleToSearch(
                    holdDurationMs = holdDurationMs,
                    allowRetryOnCancel = true
                ) { success ->
                    onCompleted?.invoke(success)
                }
                if (!dispatched) {
                    onCompleted?.invoke(false)
                }
            },
            onTimeout = {
                if (!isAccessibilityEnabledInOs(appContext)) {
                    prefs.isCtsPermissionLatched = false
                }
                showNonIntrusiveFailureToast(
                    appContext,
                    "Accessibility service disconnected — re-enable for Circle to Search"
                )
                onCompleted?.invoke(false)
            }
        )
        return true
    }

    fun requestCircleToSearchAccessibility(context: Context) {
        openAccessibilitySettings(context, "Enable 'Smart Puck Accessibility Lock' for Circle to Search")
    }

    // =========================================================================
    // SHARED SETTINGS NAVIGATION & FEEDBACK HELPERS
    // =========================================================================

    fun openAccessibilitySettings(
        context: Context,
        toastMessage: String = "Enable 'Smart Puck Accessibility Lock' in Accessibility settings"
    ) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Toast.makeText(context, toastMessage, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (_: Exception) {}
        }
    }

    private fun showNonIntrusiveFailureToast(context: Context, message: String) {
        mainHandler.post {
            try {
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
    }
}
