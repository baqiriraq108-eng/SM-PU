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
import android.os.UserManager
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import com.example.data.PuckPreferences
import com.example.service.PuckAccessibilityService
import com.example.service.PuckDeviceAdminReceiver
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Clean, root-cause Permission & Capability Engine for Smart Puck on Android 16 and Xiaomi HyperOS.
 *
 * Three dedicated, independent pipelines:
 * 1. Lock Screen Pipeline ([isLockScreenGranted], [isLockScreenZombieState], [executeLockScreen])
 * 2. Screenshot Pipeline ([isScreenshotGranted], [isScreenshotZombieState], [executeScreenshot])
 * 3. Circle to Search Pipeline ([isCircleToSearchGranted], [isCircleToSearchZombieState], [executeCircleToSearch])
 *
 * Guarantees:
 * - Never calls `PackageManager.setComponentEnabledSetting(DISABLED)` (which would strip the toggle from Android Settings).
 * - Only runs `Settings.Secure` self-healing if `Manifest.permission.WRITE_SECURE_SETTINGS` is explicitly granted.
 * - Reports honest permission status using live binder state ([PuckAccessibilityService.isServiceAliveAndBound])
 *   plus OS `ENABLED_ACCESSIBILITY_SERVICES` (and `DevicePolicyManager` for Lock Screen) without misleading stale checkmarks.
 */
object PuckPermissionManager {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isSelfHealInFlight = AtomicBoolean(false)
    private const val BINDER_RECOVERY_TIMEOUT_MS = 2500L

    /**
     * Checks whether credential-encrypted storage is unlocked (`UserManager.isUserUnlocked`),
     * preventing Direct Boot `IllegalStateException` crashes on standard `SharedPreferences`.
     */
    fun isUserUnlocked(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val um = context.getSystemService(UserManager::class.java)
                um?.isUserUnlocked != false
            } else {
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    // =========================================================================
    // AUTHORITATIVE OS & SELF-HEALING ENGINE
    // =========================================================================

    fun isAccessibilityBound(): Boolean = PuckAccessibilityService.isServiceAliveAndBound()

    /**
     * Authoritatively inspects Android OS settings and AccessibilityManager to determine
     * whether [PuckAccessibilityService] is enabled in system settings.
     */
    fun isAccessibilityEnabledInOs(
        context: Context,
        serviceClass: Class<*> = PuckAccessibilityService::class.java
    ): Boolean {
        if (PuckAccessibilityService.isServiceAliveAndBound()) {
            return true
        }

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
        } catch (_: Throwable) {}

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
        } catch (_: Throwable) {}

        return false
    }

    fun isDeviceAdminActive(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val adminComponent = ComponentName(context, PuckDeviceAdminReceiver::class.java)
            dpm?.isAdminActive(adminComponent) == true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Only attempts `Settings.Secure` self-healing if `Manifest.permission.WRITE_SECURE_SETTINGS`
     * is explicitly granted. Never disables the service component via PackageManager.
     */
    fun trySelfHealAccessibilityBinder(context: Context): Boolean {
        val appContext = context.applicationContext ?: context
        val hasWriteSecure = try {
            appContext.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        if (!hasWriteSecure) return false

        if (!isSelfHealInFlight.compareAndSet(false, true)) {
            return true
        }

        return try {
            val resolver = appContext.contentResolver
            val componentName = ComponentName(appContext, PuckAccessibilityService::class.java)
            val flattened = componentName.flattenToString()
            val shortFlattened = componentName.flattenToShortString()
            val simpleName = PuckAccessibilityService::class.java.simpleName

            val currentRaw = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            val withoutSelf = currentRaw.split(":")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filterNot {
                    it.equals(flattened, ignoreCase = true) ||
                        it.equals(shortFlattened, ignoreCase = true) ||
                        it.contains(simpleName, ignoreCase = true)
                }

            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                withoutSelf.joinToString(":")
            )
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)

            val updatedList = withoutSelf.toMutableList().apply { add(flattened) }
            mainHandler.postDelayed({
                try {
                    Settings.Secure.putString(
                        resolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        updatedList.joinToString(":")
                    )
                    Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                } catch (_: Throwable) {
                } finally {
                    isSelfHealInFlight.set(false)
                }
            }, 150L)
            true
        } catch (_: Throwable) {
            isSelfHealInFlight.set(false)
            false
        }
    }

    fun onDeviceAdminEnabled(context: Context) {
        if (isUserUnlocked(context)) {
            PuckPreferences(context).latchLockPermission()
        }
    }

    fun onDeviceAdminDisabled(context: Context) {
        if (isUserUnlocked(context)) {
            PuckPreferences(context).isLockPermissionLatched = false
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
        } catch (_: Throwable) {
            false
        }
    }

    // =========================================================================
    // PIPELINE 1: LOCK SCREEN (DEDICATED & INDEPENDENT)
    // =========================================================================

    /**
     * Honest Lock Screen status: true ONLY when live Accessibility binder is bound OR Device Admin is active.
     */
    fun isLockScreenGranted(context: Context): Boolean {
        return PuckAccessibilityService.isServiceAliveAndBound() || isDeviceAdminActive(context)
    }

    /**
     * True when OS Settings toggle is ON, Device Admin is inactive, and Accessibility binder is dead ("Not working").
     */
    fun isLockScreenZombieState(context: Context): Boolean {
        return !PuckAccessibilityService.isServiceAliveAndBound() &&
            !isDeviceAdminActive(context) &&
            isAccessibilityEnabledInOs(context)
    }

    fun executeLockScreen(
        context: Context,
        onResult: ((Boolean) -> Unit)? = null
    ): Boolean {
        val appContext = context.applicationContext ?: context

        // 1. Fast path: Live Accessibility Service binder
        if (PuckAccessibilityService.isServiceAliveAndBound() && PuckAccessibilityService.lockScreen()) {
            onResult?.invoke(true)
            return true
        }

        // 2. Fast path: Active Device Policy Manager (Device Admin)
        if (lockViaDeviceAdmin(appContext)) {
            onResult?.invoke(true)
            return true
        }

        // 3. If OS toggle is ON while binder is unbound, try WRITE_SECURE_SETTINGS self-heal (if granted) + wait
        if (!isAccessibilityEnabledInOs(appContext)) {
            onResult?.invoke(false)
            return false
        }

        trySelfHealAccessibilityBinder(appContext)
        PuckKeepAliveManager.ensureServiceAlive(appContext)

        PuckAccessibilityService.awaitBinderAndExecute(
            timeoutMs = BINDER_RECOVERY_TIMEOUT_MS,
            onConnected = {
                val locked = PuckAccessibilityService.lockScreen() || lockViaDeviceAdmin(appContext)
                onResult?.invoke(locked)
            },
            onTimeout = {
                if (lockViaDeviceAdmin(appContext)) {
                    onResult?.invoke(true)
                } else {
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
        openAccessibilitySettings(context, "Toggle 'Smart Puck Accessibility Lock' Off & On in Accessibility settings")
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
        } catch (_: Throwable) {
            requestLockScreenAccessibility(context)
        }
    }

    // =========================================================================
    // PIPELINE 2: SCREENSHOT (DEDICATED & INDEPENDENT)
    // =========================================================================

    /**
     * Honest Screenshot status: true ONLY when Accessibility binder is actively bound.
     */
    fun isScreenshotGranted(context: Context): Boolean {
        return PuckAccessibilityService.isServiceAliveAndBound()
    }

    /**
     * True when OS Settings toggle is ON while Accessibility binder is unbound ("Not working").
     */
    fun isScreenshotZombieState(context: Context): Boolean {
        return !PuckAccessibilityService.isServiceAliveAndBound() && isAccessibilityEnabledInOs(context)
    }

    fun executeScreenshot(
        context: Context,
        onCompleted: ((Boolean) -> Unit)? = null
    ): Boolean {
        val appContext = context.applicationContext ?: context

        // 1. Fast path: Live Accessibility Service binder
        if (PuckAccessibilityService.isServiceAliveAndBound() && PuckAccessibilityService.takeScreenshot()) {
            onCompleted?.invoke(true)
            return true
        }

        // 2. If OS toggle is ON while binder is unbound, try WRITE_SECURE_SETTINGS self-heal (if granted) + wait
        if (!isAccessibilityEnabledInOs(appContext)) {
            onCompleted?.invoke(false)
            return false
        }

        trySelfHealAccessibilityBinder(appContext)
        PuckKeepAliveManager.ensureServiceAlive(appContext)

        PuckAccessibilityService.awaitBinderAndExecute(
            timeoutMs = BINDER_RECOVERY_TIMEOUT_MS,
            onConnected = {
                val captured = PuckAccessibilityService.takeScreenshot()
                onCompleted?.invoke(captured)
            },
            onTimeout = {
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
        openAccessibilitySettings(context, "Toggle 'Smart Puck Accessibility Lock' Off & On for Screenshot")
    }

    // =========================================================================
    // PIPELINE 3: CIRCLE TO SEARCH (DEDICATED & INDEPENDENT)
    // =========================================================================

    /**
     * Honest Circle to Search status: true ONLY when Accessibility binder is actively bound.
     */
    fun isCircleToSearchGranted(context: Context): Boolean {
        return PuckAccessibilityService.isServiceAliveAndBound()
    }

    /**
     * True when OS Settings toggle is ON while Accessibility binder is unbound ("Not working").
     */
    fun isCircleToSearchZombieState(context: Context): Boolean {
        return !PuckAccessibilityService.isServiceAliveAndBound() && isAccessibilityEnabledInOs(context)
    }

    /**
     * Executes Circle to Search bottom navigation bar press-and-hold gesture with:
     * - Zero Voice Search / Assistant intent fallbacks.
     * - Upfront `WRITE_SECURE_SETTINGS` binder self-healing (if granted) BEFORE the 2500ms wait.
     * - Non-silent failure handling: restores the puck and shows a concise toast on dead binder.
     */
    fun executeCircleToSearch(
        context: Context,
        holdDurationMs: Long = 850L,
        onCompleted: ((Boolean) -> Unit)? = null
    ): Boolean {
        val appContext = context.applicationContext ?: context

        // 1. Fast path: Live Accessibility Service binder
        if (PuckAccessibilityService.isServiceAliveAndBound()) {
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

        // 2. If OS toggle is ON while binder is unbound, try WRITE_SECURE_SETTINGS self-heal (if granted) + wait
        if (!isAccessibilityEnabledInOs(appContext)) {
            showNonIntrusiveFailureToast(
                appContext,
                "Enable 'Smart Puck Accessibility Lock' in Accessibility settings for Circle to Search"
            )
            onCompleted?.invoke(false)
            return false
        }

        trySelfHealAccessibilityBinder(appContext)
        PuckKeepAliveManager.ensureServiceAlive(appContext)

        PuckAccessibilityService.awaitBinderAndExecute(
            timeoutMs = BINDER_RECOVERY_TIMEOUT_MS,
            onConnected = {
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
        openAccessibilitySettings(context, "Toggle 'Smart Puck Accessibility Lock' Off & On for Circle to Search")
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
        } catch (_: Throwable) {
            try {
                val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            } catch (_: Throwable) {}
        }
    }

    private fun showNonIntrusiveFailureToast(context: Context, message: String) {
        mainHandler.post {
            try {
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
        }
    }
}
