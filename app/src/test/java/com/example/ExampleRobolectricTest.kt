package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Smart Puck", appName)
  }

  @Test
  fun `verify axis lock preferences default and update`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = com.example.data.PuckPreferences(context)
    
    // Default mapping: Horizontal = Brightness, Vertical = Volume (swapAxes = false)
    assertEquals(false, prefs.swapAxes)
    assertEquals(4, prefs.axisLockThresholdPx)

    // Updating swapAxes and threshold
    prefs.swapAxes = true
    assertEquals(true, prefs.swapAxes)
    assertEquals(true, prefs.swapAxesState.value)

    prefs.axisLockThresholdPx = 8
    assertEquals(8, prefs.axisLockThresholdPx)
    assertEquals(8, prefs.axisLockThresholdPxState.value)
  }

  @Test
  fun `verify double tap circle to search preferences and speed modes`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = com.example.data.PuckPreferences(context)

    // Enabled by default in Automatic speed mode
    assertEquals(true, prefs.doubleTapCtsEnabled)
    assertEquals(com.example.data.PuckPreferences.SPEED_MODE_AUTO, prefs.doubleTapSpeedMode)
    assertEquals(true, prefs.menuOrder.contains("circle_to_search"))

    // Switch to Manual speed mode and adjust delay
    prefs.doubleTapSpeedMode = com.example.data.PuckPreferences.SPEED_MODE_MANUAL
    prefs.doubleTapCustomDelayMs = 340
    assertEquals(com.example.data.PuckPreferences.SPEED_MODE_MANUAL, prefs.doubleTapSpeedMode)
    assertEquals(340, prefs.doubleTapCustomDelayMs)
    assertEquals(340, prefs.getEffectiveDoubleTapDelayMs())

    // Verify bounds clamping [140..500]
    prefs.doubleTapCustomDelayMs = 80
    assertEquals(com.example.data.PuckPreferences.MIN_DOUBLE_TAP_MS, prefs.doubleTapCustomDelayMs)
    prefs.doubleTapCustomDelayMs = 900
    assertEquals(com.example.data.PuckPreferences.MAX_DOUBLE_TAP_MS, prefs.doubleTapCustomDelayMs)

    // Verify Bottom Bar Hold Duration Automatic (850ms) and Manual [400..1500ms]
    assertEquals(com.example.data.PuckPreferences.SPEED_MODE_AUTO, prefs.ctsHoldMode)
    assertEquals(850, prefs.getEffectiveCtsHoldDurationMs())

    prefs.ctsHoldMode = com.example.data.PuckPreferences.SPEED_MODE_MANUAL
    prefs.ctsCustomHoldDurationMs = 1100
    assertEquals(com.example.data.PuckPreferences.SPEED_MODE_MANUAL, prefs.ctsHoldMode)
    assertEquals(1100, prefs.getEffectiveCtsHoldDurationMs())

    prefs.ctsCustomHoldDurationMs = 200
    assertEquals(com.example.data.PuckPreferences.MIN_CTS_HOLD_MS, prefs.ctsCustomHoldDurationMs)
    prefs.ctsCustomHoldDurationMs = 2000
    assertEquals(com.example.data.PuckPreferences.MAX_CTS_HOLD_MS, prefs.ctsCustomHoldDurationMs)
  }

  @Test
  fun `verify independent honest permission pipelines and directBootAware removal`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val componentName = android.content.ComponentName(
      context,
      com.example.service.PuckAccessibilityService::class.java
    )
    val componentString = "${context.packageName}/${com.example.service.PuckAccessibilityService::class.java.name}"

    // 1. Verify directBootAware is false on PuckAccessibilityService & FloatingPuckService
    val serviceInfo = context.packageManager.getServiceInfo(componentName, 0)
    assertEquals(false, serviceInfo.directBootAware)

    // 2. When OS Settings toggle is ON but binder is dead (!isServiceAliveAndBound),
    // honest checks MUST detect zombie ("Not working") state and NOT claim granted!
    android.provider.Settings.Secure.putString(
      context.contentResolver,
      android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
      componentString
    )
    assertEquals(true, com.example.util.PuckPermissionManager.isAccessibilityEnabledInOs(context))
    assertEquals(false, com.example.util.PuckPermissionManager.isLockScreenGranted(context))
    assertEquals(false, com.example.util.PuckPermissionManager.isScreenshotGranted(context))
    assertEquals(false, com.example.util.PuckPermissionManager.isCircleToSearchGranted(context))
    assertEquals(true, com.example.util.PuckPermissionManager.isLockScreenZombieState(context))
    assertEquals(true, com.example.util.PuckPermissionManager.isScreenshotZombieState(context))
    assertEquals(true, com.example.util.PuckPermissionManager.isCircleToSearchZombieState(context))

    // 3. Verify WRITE_SECURE_SETTINGS gating: returns false if permission not held
    com.example.util.PuckPermissionManager.resetSelfHealDebounce()
    assertEquals(false, com.example.util.PuckPermissionManager.ensureAccessibilityServiceAlive(context))

    // 4. Grant WRITE_SECURE_SETTINGS and verify Force Toggle-Cycle (Remove -> 80ms -> Re-Add)
    val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    com.example.util.PuckPermissionManager.resetSelfHealDebounce()

    val healTriggered = com.example.util.PuckPermissionManager.ensureAccessibilityServiceAlive(
      context = context,
      forceImmediate = true
    )
    assertEquals(true, healTriggered)

    // Immediately after Phase A (0ms), our component was stripped from ENABLED_ACCESSIBILITY_SERVICES
    val duringFlush = android.provider.Settings.Secure.getString(
      context.contentResolver,
      android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: ""
    assertEquals(false, duringFlush.contains("PuckAccessibilityService"))

    // Advance main looper by 100ms (>= 80ms flush delay) and verify Phase B re-added the component
    org.robolectric.shadows.ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
    val afterReAdd = android.provider.Settings.Secure.getString(
      context.contentResolver,
      android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: ""
    assertEquals(true, afterReAdd.contains(componentString))
    assertEquals(
      1,
      android.provider.Settings.Secure.getInt(
        context.contentResolver,
        android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,
        0
      )
    )

    // 5. Verify in-process queued recovery accepts actions when OS setting is ON
    assertEquals(true, com.example.util.PuckPermissionManager.executeLockScreen(context))
    assertEquals(true, com.example.util.PuckPermissionManager.executeScreenshot(context))
    assertEquals(true, com.example.util.PuckPermissionManager.executeCircleToSearch(context, 850L))
  }
}
