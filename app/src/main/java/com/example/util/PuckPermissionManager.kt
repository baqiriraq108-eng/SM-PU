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
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import com.example.data.PuckPreferences
import com.example.service.PuckAccessibilityService
import com.example.service.PuckDeviceAdminReceiver
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * HyperOS-Exclusive Accessibility Anti-Kill & Force Toggle-Cycle Self-Healing Engine.
 *
 * Implements:
 * - [ensureAccessibilityServiceAlive]: Checks if [PuckAccessibilityService] is null or disconnected.
 *   If disconnected and `WRITE_SECURE_SETTINGS` is granted, performs a Hard Toggle Cycle:
 *   1. Removes both full (`$pkg/$cls`) and short (`$pkg/${cls.removePrefix(pkg)}`) component names from
 *      `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` and writes the cleaned string.
 *   2. Flushes `Settings.Secure.ACCESSIBILITY_ENABLED = 0` (if no other services remain) and waits `80ms`.
 *   3. Appends `$pkg/$cls` back into `ENABLED_ACCESSIBILITY_SERVICES` and sets `ACCESSIBILITY_ENABLED = 1`.
 *   4. Awaits `onServiceConnected` callback (up to `350ms` timeout) so actions execute seamlessly.
 */
object PuckPermissionManager {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isSelfHealInFlight = AtomicBoolean(false)
    private val lastSelfHealTimeMs = AtomicLong(0L)

    private const val SELF_HEAL_DEBOUNCE_MS = 3000L
    private const val TOGGLE_FLUSH_DELAY_MS = 80L
    private const val REBIND_WAIT_TIMEOUT_MS = 350L

    fun resetSelfHealDebounce() {
        isSelfHealInFlight.set(false)
        lastSelfHealTimeMs.set(0L)
    }

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

    fun isAccessibilityBound(): Boolean = PuckAccessibilityService.isServiceAliveAndBound()

    fun hasWriteSecureSettings(context: Context): Boolean {
        return try {
            val appContext = context.applicationContext ?: context
            appContext.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * HyperOS Force Toggle-Cycle Self-Healing helper (`ensureAccessibilityServiceAlive`).
     *
     * - If [PuckAccessibilityService.isServiceAliveAndBound] is already true, invokes [onReady](true)
     *   immediately (0ms) and returns `true`.
     * - If disconnected and `WRITE_SECURE_SETTINGS` is granted, performs the HyperOS Force Toggle-Cycle
     *   (`Remove -> 80ms Flush -> Re-Add -> ACCESSIBILITY_ENABLED = 1`) and awaits `onServiceConnected`
     *   (or 350ms timeout) before invoking [onReady].
     */
    fun ensureAccessibilityServiceAlive(
        context: Context,
        forceImmediate: Boolean = false,
        onReady: ((Boolean) -> Unit)? = null
    ): Boolean {
        val appContext = context.applicationContext ?: context

        // 1. Fast path: already alive and system-bound
        if (PuckAccessibilityService.isServiceAliveAndBound()) {
            onReady?.invoke(true)
            return true
        }

        // 2. Check WRITE_SECURE_SETTINGS permission
        if (!hasWriteSecureSettings(appContext)) {
            if (onReady != null) {
                if (isAccessibilityEnabledInOs(appContext)) {
                    PuckAccessibilityService.awaitBinderAndExecute(
                        timeoutMs = REBIND_WAIT_TIMEOUT_MS,
                        onConnected = { onReady.invoke(true) },
                        onTimeout = { onReady.invoke(PuckAccessibilityService.isServiceAliveAndBound()) }
                    )
                } else {
                    onReady.invoke(false)
                }
            }
            return false
        }

        // 3. Debounce guard (3000ms) so rapid touch events don't overlap Settings.Secure writes
        val nowMs = SystemClock.uptimeMillis()
        val lastMs = lastSelfHealTimeMs.get()
        if (!forceImmediate && (isSelfHealInFlight.get() || (lastMs > 0L && (nowMs - lastMs) < SELF_HEAL_DEBOUNCE_MS))) {
            if (onReady != null) {
                PuckAccessibilityService.awaitBinderAndExecute(
                    timeoutMs = REBIND_WAIT_TIMEOUT_MS,
                    onConnected = { onReady.invoke(true) },
                    onTimeout = { onReady.invoke(PuckAccessibilityService.isServiceAliveAndBound()) }
                )
            }
            return true
        }

        isSelfHealInFlight.set(true)
        lastSelfHealTimeMs.set(nowMs)

        return try {
            val resolver = appContext.contentResolver
            val pkg = appContext.packageName
            val cls = PuckAccessibilityService::class.java.name
            val simpleName = PuckAccessibilityService::class.java.simpleName
            val fullComponent = "$pkg/$cls"
            val shortComponent = "$pkg/${cls.removePrefix(pkg)}"
            val flattened = ComponentName(appContext, PuckAccessibilityService::class.java).flattenToString()
            val shortFlattened = ComponentName(appContext, PuckAccessibilityService::class.java).flattenToShortString()

            val currentRaw = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            // Step A: Remove all variants of our service from ENABLED_ACCESSIBILITY_SERVICES
            val withoutSelf = currentRaw.split(":")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filterNot {
                    it.equals(fullComponent, ignoreCase = true) ||
                        it.equals(shortComponent, ignoreCase = true) ||
                        it.equals(flattened, ignoreCase = true) ||
                        it.equals(shortFlattened, ignoreCase = true) ||
                        it.contains(simpleName, ignoreCase = true)
                }

            val cleanedString = withoutSelf.joinToString(":")
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                cleanedString
            )
            if (withoutSelf.isEmpty()) {
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            }

            // Register callback waiter BEFORE the 80ms re-add so we catch onServiceConnected the instant it fires
            if (onReady != null) {
                PuckAccessibilityService.awaitBinderAndExecute(
                    timeoutMs = TOGGLE_FLUSH_DELAY_MS + REBIND_WAIT_TIMEOUT_MS,
                    onConnected = {
                        isSelfHealInFlight.set(false)
                        onReady.invoke(true)
                    },
                    onTimeout = {
                        isSelfHealInFlight.set(false)
                        onReady.invoke(PuckAccessibilityService.isServiceAliveAndBound())
                    }
                )
            }

            // Step B: Wait 80ms for HyperOS AccessibilityManagerService to flush the unbind,
            // then re-append "$pkg/$cls" and enable accessibility.
            mainHandler.postDelayed({
                try {
                    val refreshedRaw = Settings.Secure.getString(
                        resolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                    ) ?: ""
                    val currentList = refreshedRaw.split(":")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .filterNot {
                            it.equals(fullComponent, ignoreCase = true) ||
                                it.equals(shortComponent, ignoreCase = true) ||
                                it.contains(simpleName, ignoreCase = true)
                        }
                        .toMutableList()

                    currentList.add(fullComponent)
                    Settings.Secure.putString(
                        resolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        currentList.joinToString(":")
                    )
                    Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                } catch (_: Throwable) {
                } finally {
                    isSelfHealInFlight.set(false)
                }
            }, TOGGLE_FLUSH_DELAY_MS)

            true
        } catch (_: Throwable) {
            isSelfHealInFlight.set(false)
            onReady?.invoke(false)
            false
        }
    }

    /**
     * Alias for backwards compatibility with existing callers/tests.
     */
    fun trySelfHealAccessibilityBinder(context: Context): Boolean {
        return ensureAccessibilityServiceAlive(context)
    }

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
    // PIPELINE 1: LOCK SCREEN
    // =========================================================================

    fun isLockScreenGranted(context: Context): Boolean {
        return PuckAccessibilityService.isServiceAliveAndBound() || isDeviceAdminActive(context)
    }

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

        // 2. Fast path: Active Device Admin
        if (lockViaDeviceAdmin(appContext)) {
            onResult?.invoke(true)
            return true
        }

        // 3. Attempt automatic self-healing via WRITE_SECURE_SETTINGS or OS rebind wait
        val canHealOrWait = hasWriteSecureSettings(appContext) || isAccessibilityEnabledInOs(appContext)
        if (!canHealOrWait) {
            onResult?.invoke(false)
            return false
        }

        ensureAccessibilityServiceAlive(appContext, forceImmediate = true) {
            val locked = PuckAccessibilityService.lockScreen() || lockViaDeviceAdmin(appContext)
            if (!locked) {
                showNonIntrusiveFailureToast(
                    appContext,
                    "Accessibility service disconnected — re-enable Lock Screen in Settings"
                )
            }
            onResult?.invoke(locked)
        }
        return true
    }

    fun requestLockScreenAccessibility(context: Context) {
        if (ensureAccessibilityServiceAlive(context, forceImmediate = true)) {
            return
        }
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
    // PIPELINE 2: INSTANT SCREENSHOT
    // =========================================================================

    fun isScreenshotGranted(context: Context): Boolean {
        return PuckAccessibilityService.isServiceAliveAndBound()
    }

    fun isScreenshotZombieState(context: Context): Boolean {
        return !PuckAccessibilityService.isServiceAliveAndBound() && isAccessibilityEnabledInOs(context)
    }

    fun executeScreenshot(
        context: Context,
        onCompleted: ((Boolean) -> Unit)? = null
    ): Boolean {
        val appContext = context.applicationContext ?: context

        // 1. Fast path: Live Accessibility Service binder (0ms)
        if (PuckAccessibilityService.isServiceAliveAndBound() && PuckAccessibilityService.takeScreenshot()) {
            onCompleted?.invoke(true)
            return true
        }

        // 2. Disconnected: Run Force Toggle-Cycle self-heal (or wait if enabled in OS)
        val canHealOrWait = hasWriteSecureSettings(appContext) || isAccessibilityEnabledInOs(appContext)
        if (!canHealOrWait) {
            onCompleted?.invoke(false)
            return false
        }

        ensureAccessibilityServiceAlive(appContext, forceImmediate = true) {
            val captured = PuckAccessibilityService.takeScreenshot()
            if (!captured) {
                showNonIntrusiveFailureToast(
                    appContext,
                    "Accessibility service disconnected — re-enable for Screenshot"
                )
            }
            onCompleted?.invoke(captured)
        }
        return true
    }

    fun requestScreenshotAccessibility(context: Context) {
        if (ensureAccessibilityServiceAlive(context, forceImmediate = true)) {
            return
        }
        openAccessibilitySettings(context, "Toggle 'Smart Puck Accessibility Lock' Off & On for Screenshot")
    }

    // =========================================================================
    // PIPELINE 3: CIRCLE TO SEARCH
    // =========================================================================

    fun isCircleToSearchGranted(context: Context): Boolean {
        return PuckAccessibilityService.isServiceAliveAndBound()
    }

    fun isCircleToSearchZombieState(context: Context): Boolean {
        return !PuckAccessibilityService.isServiceAliveAndBound() && isAccessibilityEnabledInOs(context)
    }

    fun executeCircleToSearch(
        context: Context,
        holdDurationMs: Long = 850L,
        onCompleted: ((Boolean) -> Unit)? = null
    ): Boolean {
        val appContext = context.applicationContext ?: context

        // 1. Fast path: Live Accessibility Service binder (0ms)
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

        // 2. Disconnected: Run Force Toggle-Cycle self-heal (or wait if enabled in OS)
        val canHealOrWait = hasWriteSecureSettings(appContext) || isAccessibilityEnabledInOs(appContext)
        if (!canHealOrWait) {
            showNonIntrusiveFailureToast(
                appContext,
                "Enable 'Smart Puck Accessibility Lock' in Accessibility settings for Circle to Search"
            )
            onCompleted?.invoke(false)
            return false
        }

        ensureAccessibilityServiceAlive(appContext, forceImmediate = true) {
            val dispatched = PuckAccessibilityService.triggerCircleToSearch(
                holdDurationMs = holdDurationMs,
                allowRetryOnCancel = true
            ) { success ->
                onCompleted?.invoke(success)
            }
            if (!dispatched) {
                showNonIntrusiveFailureToast(
                    appContext,
                    "Accessibility service disconnected — re-enable for Circle to Search"
                )
                onCompleted?.invoke(false)
            }
        }
        return true
    }

    fun requestCircleToSearchAccessibility(context: Context) {
        if (ensureAccessibilityServiceAlive(context, forceImmediate = true)) {
            return
        }
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
