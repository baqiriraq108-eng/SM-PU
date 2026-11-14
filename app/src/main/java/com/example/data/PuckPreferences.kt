package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PuckPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("smart_puck_prefs", Context.MODE_PRIVATE)

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        notifyState()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    // State flows for Compose reactive updates
    private val _isServiceEnabledState = MutableStateFlow(isServiceEnabled)
    val isServiceEnabledState: StateFlow<Boolean> = _isServiceEnabledState.asStateFlow()

    private val _puckColorState = MutableStateFlow(puckColor)
    val puckColorState: StateFlow<Int> = _puckColorState.asStateFlow()

    private val _puckSizeState = MutableStateFlow(puckSize)
    val puckSizeState: StateFlow<Int> = _puckSizeState.asStateFlow()

    private val _puckOpacityState = MutableStateFlow(puckOpacity)
    val puckOpacityState: StateFlow<Int> = _puckOpacityState.asStateFlow()

    private val _iconStyleState = MutableStateFlow(iconStyle)
    val iconStyleState: StateFlow<String> = _iconStyleState.asStateFlow()

    private val _appThemeState = MutableStateFlow(appTheme)
    val appThemeState: StateFlow<String> = _appThemeState.asStateFlow()

    private val _menuOrderState = MutableStateFlow(menuOrder)
    val menuOrderState: StateFlow<List<String>> = _menuOrderState.asStateFlow()

    private val _puckShapeState = MutableStateFlow(puckShape)
    val puckShapeState: StateFlow<String> = _puckShapeState.asStateFlow()

    private val _puckStyleState = MutableStateFlow(puckStyle)
    val puckStyleState: StateFlow<String> = _puckStyleState.asStateFlow()

    private val _swapAxesState = MutableStateFlow(swapAxes)
    val swapAxesState: StateFlow<Boolean> = _swapAxesState.asStateFlow()

    private val _axisLockThresholdPxState = MutableStateFlow(axisLockThresholdPx)
    val axisLockThresholdPxState: StateFlow<Int> = _axisLockThresholdPxState.asStateFlow()

    private val _doubleTapCtsEnabledState = MutableStateFlow(doubleTapCtsEnabled)
    val doubleTapCtsEnabledState: StateFlow<Boolean> = _doubleTapCtsEnabledState.asStateFlow()

    private val _doubleTapSpeedModeState = MutableStateFlow(doubleTapSpeedMode)
    val doubleTapSpeedModeState: StateFlow<String> = _doubleTapSpeedModeState.asStateFlow()

    private val _doubleTapCustomDelayMsState = MutableStateFlow(doubleTapCustomDelayMs)
    val doubleTapCustomDelayMsState: StateFlow<Int> = _doubleTapCustomDelayMsState.asStateFlow()

    private val _ctsHoldModeState = MutableStateFlow(ctsHoldMode)
    val ctsHoldModeState: StateFlow<String> = _ctsHoldModeState.asStateFlow()

    private val _ctsCustomHoldDurationMsState = MutableStateFlow(ctsCustomHoldDurationMs)
    val ctsCustomHoldDurationMsState: StateFlow<Int> = _ctsCustomHoldDurationMsState.asStateFlow()

    private val _isLockPermissionLatchedState = MutableStateFlow(isLockPermissionLatched)
    val isLockPermissionLatchedState: StateFlow<Boolean> = _isLockPermissionLatchedState.asStateFlow()

    private val _isScreenshotPermissionLatchedState = MutableStateFlow(isScreenshotPermissionLatched)
    val isScreenshotPermissionLatchedState: StateFlow<Boolean> = _isScreenshotPermissionLatchedState.asStateFlow()

    private val _isCtsPermissionLatchedState = MutableStateFlow(isCtsPermissionLatched)
    val isCtsPermissionLatchedState: StateFlow<Boolean> = _isCtsPermissionLatchedState.asStateFlow()

    private fun notifyState() {
        _isServiceEnabledState.value = isServiceEnabled
        _puckColorState.value = puckColor
        _puckSizeState.value = puckSize
        _puckOpacityState.value = puckOpacity
        _iconStyleState.value = iconStyle
        _appThemeState.value = appTheme
        _menuOrderState.value = menuOrder
        _puckShapeState.value = puckShape
        _puckStyleState.value = puckStyle
        _swapAxesState.value = swapAxes
        _axisLockThresholdPxState.value = axisLockThresholdPx
        _doubleTapCtsEnabledState.value = doubleTapCtsEnabled
        _doubleTapSpeedModeState.value = doubleTapSpeedMode
        _doubleTapCustomDelayMsState.value = doubleTapCustomDelayMs
        _ctsHoldModeState.value = ctsHoldMode
        _ctsCustomHoldDurationMsState.value = ctsCustomHoldDurationMs
        _isLockPermissionLatchedState.value = isLockPermissionLatched
        _isScreenshotPermissionLatchedState.value = isScreenshotPermissionLatched
        _isCtsPermissionLatchedState.value = isCtsPermissionLatched
    }

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean("service_enabled", false)
        set(value) {
            prefs.edit().putBoolean("service_enabled", value).commit()
        }

    var autoStart: Boolean
        get() = prefs.getBoolean("auto_start", false)
        set(value) {
            prefs.edit().putBoolean("auto_start", value).commit()
        }

    var isBatteryOptPermanentlyGranted: Boolean
        get() = prefs.getBoolean("perm_battery_opt_granted", false)
        set(value) {
            prefs.edit().putBoolean("perm_battery_opt_granted", value).commit()
        }

    var isLockPermissionLatched: Boolean
        get() = prefs.getBoolean(KEY_LATCH_LOCK_SCREEN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LATCH_LOCK_SCREEN, value).commit()
            _isLockPermissionLatchedState.value = value
        }

    var isScreenshotPermissionLatched: Boolean
        get() = prefs.getBoolean(KEY_LATCH_SCREENSHOT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LATCH_SCREENSHOT, value).commit()
            _isScreenshotPermissionLatchedState.value = value
        }

    var isCtsPermissionLatched: Boolean
        get() = prefs.getBoolean(KEY_LATCH_CTS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LATCH_CTS, value).commit()
            _isCtsPermissionLatchedState.value = value
        }

    fun latchAllAccessibilityPermissions() {
        prefs.edit()
            .putBoolean(KEY_LATCH_LOCK_SCREEN, true)
            .putBoolean(KEY_LATCH_SCREENSHOT, true)
            .putBoolean(KEY_LATCH_CTS, true)
            .commit()
        _isLockPermissionLatchedState.value = true
        _isScreenshotPermissionLatchedState.value = true
        _isCtsPermissionLatchedState.value = true
    }

    fun latchLockPermission() {
        prefs.edit()
            .putBoolean(KEY_LATCH_LOCK_SCREEN, true)
            .commit()
        _isLockPermissionLatchedState.value = true
    }

    var hasShownInitialPrompt: Boolean
        get() = prefs.getBoolean("has_shown_initial_prompt", false)
        set(value) = prefs.edit().putBoolean("has_shown_initial_prompt", value).apply()

    var swapSides: Boolean
        get() = prefs.getBoolean("swap_sides", false)
        set(value) = prefs.edit().putBoolean("swap_sides", value).apply()

    var swapAxes: Boolean
        get() = prefs.getBoolean("swap_control_axes", false)
        set(value) = prefs.edit().putBoolean("swap_control_axes", value).apply()

    var axisLockThresholdPx: Int
        get() = prefs.getInt("axis_lock_threshold_px", 4)
        set(value) = prefs.edit().putInt("axis_lock_threshold_px", value.coerceIn(2, 16)).apply()

    var doubleTapCtsEnabled: Boolean
        get() = prefs.getBoolean("double_tap_cts_enabled", true)
        set(value) = prefs.edit().putBoolean("double_tap_cts_enabled", value).apply()

    var doubleTapSpeedMode: String
        get() = prefs.getString("double_tap_speed_mode", SPEED_MODE_AUTO) ?: SPEED_MODE_AUTO
        set(value) {
            val normalized = if (value == SPEED_MODE_MANUAL) SPEED_MODE_MANUAL else SPEED_MODE_AUTO
            prefs.edit().putString("double_tap_speed_mode", normalized).apply()
        }

    var doubleTapCustomDelayMs: Int
        get() = prefs.getInt("double_tap_custom_delay_ms", DEFAULT_MANUAL_DOUBLE_TAP_MS)
        set(value) = prefs.edit().putInt(
            "double_tap_custom_delay_ms",
            value.coerceIn(MIN_DOUBLE_TAP_MS, MAX_DOUBLE_TAP_MS)
        ).apply()

    var ctsHoldMode: String
        get() = prefs.getString("cts_hold_mode", SPEED_MODE_AUTO) ?: SPEED_MODE_AUTO
        set(value) {
            val normalized = if (value == SPEED_MODE_MANUAL) SPEED_MODE_MANUAL else SPEED_MODE_AUTO
            prefs.edit().putString("cts_hold_mode", normalized).apply()
        }

    var ctsCustomHoldDurationMs: Int
        get() = prefs.getInt("cts_custom_hold_duration_ms", DEFAULT_AUTO_CTS_HOLD_MS)
        set(value) = prefs.edit().putInt(
            "cts_custom_hold_duration_ms",
            value.coerceIn(MIN_CTS_HOLD_MS, MAX_CTS_HOLD_MS)
        ).apply()

    /**
     * Resolves the active double-tap interval in milliseconds:
     * - Automatic mode: adaptive system-tuned interval (255ms default on Android 16 / HyperOS 120Hz displays).
     * - Manual mode: user-configured delay in [140ms..500ms].
     */
    fun getEffectiveDoubleTapDelayMs(): Int {
        return if (doubleTapSpeedMode == SPEED_MODE_MANUAL) {
            doubleTapCustomDelayMs.coerceIn(MIN_DOUBLE_TAP_MS, MAX_DOUBLE_TAP_MS)
        } else {
            getAutomaticDoubleTapDelayMs()
        }
    }

    fun getAutomaticDoubleTapDelayMs(): Int {
        val sysTimeout = try {
            android.view.ViewConfiguration.getDoubleTapTimeout()
        } catch (_: Exception) {
            300
        }
        return (sysTimeout * 0.85f).toInt().coerceIn(220, 280)
    }

    /**
     * Resolves the active bottom navigation bar press-and-hold duration in milliseconds:
     * - Automatic mode: 850ms reliable hold threshold for Android 16 & Xiaomi HyperOS 3/4.
     * - Manual mode: user-configured hold duration in [400ms..1500ms].
     */
    fun getEffectiveCtsHoldDurationMs(): Int {
        return if (ctsHoldMode == SPEED_MODE_MANUAL) {
            ctsCustomHoldDurationMs.coerceIn(MIN_CTS_HOLD_MS, MAX_CTS_HOLD_MS)
        } else {
            getAutomaticCtsHoldDurationMs()
        }
    }

    fun getAutomaticCtsHoldDurationMs(): Int = DEFAULT_AUTO_CTS_HOLD_MS

    var horizontalBrightness: Boolean
        get() = prefs.getBoolean("horizontal_brightness", true)
        set(value) = prefs.edit().putBoolean("horizontal_brightness", value).apply()

    var horizontalVolume: Boolean
        get() = prefs.getBoolean("horizontal_volume", true)
        set(value) = prefs.edit().putBoolean("horizontal_volume", value).apply()

    var puckSize: Int
        get() = prefs.getInt("puck_size", 60)
        set(value) = prefs.edit().putInt("puck_size", value.coerceIn(36, 100)).apply()

    var puckOpacity: Int
        get() = prefs.getInt("puck_opacity", 85)
        set(value) = prefs.edit().putInt("puck_opacity", value.coerceIn(20, 100)).apply()

    var puckColor: Int
        get() = prefs.getInt("puck_color", Color.parseColor("#795548")) // Android 17 Earth Brown default
        set(value) = prefs.edit().putInt("puck_color", value).apply()

    var smoothControl: Boolean
        get() = prefs.getBoolean("smooth_control", true)
        set(value) = prefs.edit().putBoolean("smooth_control", value).apply()

    var iconStyle: String
        get() = prefs.getString("icon_style", "orbit") ?: "orbit"
        set(value) = prefs.edit().putString("icon_style", value).apply()

    var appTheme: String
        get() = prefs.getString("app_theme", "system") ?: "system"
        set(value) = prefs.edit().putString("app_theme", value).apply()

    var sensitivity: Int
        get() = prefs.getInt("sensitivity", 60)
        set(value) = prefs.edit().putInt("sensitivity", value.coerceIn(10, 100)).apply()

    var vibrationStrength: Int
        get() = prefs.getInt("vibration_strength", 50)
        set(value) = prefs.edit().putInt("vibration_strength", value.coerceIn(0, 100)).apply()

    var lastPuckX: Int
        get() = prefs.getInt("last_x", -1)
        set(value) = prefs.edit().putInt("last_x", value).apply()

    var lastPuckY: Int
        get() = prefs.getInt("last_y", -1)
        set(value) = prefs.edit().putInt("last_y", value).apply()

    var wasRightSide: Boolean
        get() = prefs.getBoolean("was_right_side", true)
        set(value) = prefs.edit().putBoolean("was_right_side", value).apply()

    var menuOrder: List<String>
        get() {
            val raw = prefs.getString("menu_order", null)
            if (raw.isNullOrBlank()) return DEFAULT_MENU_ORDER
            val items = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val merged = items.filter { DEFAULT_MENU_ORDER.contains(it) }.toMutableList()
            DEFAULT_MENU_ORDER.forEach { if (!merged.contains(it)) merged.add(it) }
            return merged
        }
        set(value) {
            val valid = value.filter { DEFAULT_MENU_ORDER.contains(it) }
            prefs.edit().putString("menu_order", valid.joinToString(",")).apply()
        }

    fun moveMenuItem(fromIndex: Int, toIndex: Int) {
        val current = menuOrder.toMutableList()
        if (fromIndex in current.indices && toIndex in current.indices && fromIndex != toIndex) {
            val item = current.removeAt(fromIndex)
            current.add(toIndex, item)
            menuOrder = current
        }
    }

    fun resetMenuOrder() {
        menuOrder = DEFAULT_MENU_ORDER
    }

    var puckShape: String
        get() = prefs.getString("puck_shape", "circle") ?: "circle"
        set(value) = prefs.edit().putString("puck_shape", value).apply()

    var puckStyle: String
        get() = prefs.getString("puck_style", "solid") ?: "solid"
        set(value) = prefs.edit().putString("puck_style", value).apply()

    companion object {
        const val KEY_LATCH_LOCK_SCREEN = "perm_latch_lock_screen_v3"
        const val KEY_LATCH_SCREENSHOT = "perm_latch_screenshot_v3"
        const val KEY_LATCH_CTS = "perm_latch_cts_v3"

        const val SPEED_MODE_AUTO = "auto"
        const val SPEED_MODE_MANUAL = "manual"
        const val MIN_DOUBLE_TAP_MS = 140
        const val MAX_DOUBLE_TAP_MS = 500
        const val DEFAULT_MANUAL_DOUBLE_TAP_MS = 280

        const val MIN_CTS_HOLD_MS = 400
        const val MAX_CTS_HOLD_MS = 1500
        const val DEFAULT_AUTO_CTS_HOLD_MS = 850

        val DEFAULT_MENU_ORDER = listOf(
            "circle_to_search",
            "flashlight",
            "screenshot",
            "dnd",
            "audio",
            "brightness",
            "auto_rotate",
            "smart_puck_app",
            "lock_screen"
        )
    }
}
