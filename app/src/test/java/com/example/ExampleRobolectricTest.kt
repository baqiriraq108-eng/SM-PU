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
  fun `verify accessibility permission remains active across task swipe when enabled in OS Settings`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val componentString = "${context.packageName}/${com.example.service.PuckAccessibilityService::class.java.name}"

    android.provider.Settings.Secure.putString(
      context.contentResolver,
      android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
      componentString
    )

    // Even when PuckAccessibilityService.instance is null (e.g., right after task swipe),
    // LockScreenHelper must recognize the service as enabled in the OS and never fail.
    assertEquals(true, com.example.util.LockScreenHelper.isAccessibilityServiceEnabled(context))
    assertEquals(true, com.example.util.LockScreenHelper.isAccessibilityServiceActive(context))
    assertEquals(true, com.example.util.LockScreenHelper.isLockPermissionGranted(context))
    assertEquals(true, com.example.util.LockScreenHelper.performLock(context))
    assertEquals(true, com.example.util.LockScreenHelper.performScreenshot(context))
    assertEquals(true, com.example.util.CircleToSearchHelper.performCircleToSearch(context, 850L))
  }
}
