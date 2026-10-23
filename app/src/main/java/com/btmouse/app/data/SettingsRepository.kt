package com.btmouse.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnectionMode {
    BLUETOOTH,
    WIFI,
    REMOTE_WAN
}

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("bt_mouse_prefs", Context.MODE_PRIVATE)

    private val _pointerSpeed = MutableStateFlow(prefs.getFloat(KEY_POINTER_SPEED, 1.2f))
    val pointerSpeed: StateFlow<Float> = _pointerSpeed.asStateFlow()

    private val _scrollSpeed = MutableStateFlow(prefs.getFloat(KEY_SCROLL_SPEED, 1.0f))
    val scrollSpeed: StateFlow<Float> = _scrollSpeed.asStateFlow()

    private val _invertScroll = MutableStateFlow(prefs.getBoolean(KEY_INVERT_SCROLL, false))
    val invertScroll: StateFlow<Boolean> = _invertScroll.asStateFlow()

    private val _tapToClick = MutableStateFlow(prefs.getBoolean(KEY_TAP_TO_CLICK, true))
    val tapToClick: StateFlow<Boolean> = _tapToClick.asStateFlow()

    private val _hapticFeedback = MutableStateFlow(prefs.getBoolean(KEY_HAPTIC, true))
    val hapticFeedback: StateFlow<Boolean> = _hapticFeedback.asStateFlow()

    private val _airMouseSensitivity = MutableStateFlow(prefs.getFloat(KEY_AIR_MOUSE_SENSITIVITY, 18.0f))
    val airMouseSensitivity: StateFlow<Float> = _airMouseSensitivity.asStateFlow()

    private val _wifiHost = MutableStateFlow(prefs.getString(KEY_WIFI_HOST, "192.168.1.100") ?: "192.168.1.100")
    val wifiHost: StateFlow<String> = _wifiHost.asStateFlow()

    private val _wifiPort = MutableStateFlow(prefs.getInt(KEY_WIFI_PORT, 53530))
    val wifiPort: StateFlow<Int> = _wifiPort.asStateFlow()

    private val _wanUrl = MutableStateFlow(prefs.getString(KEY_WAN_URL, "ws://192.168.1.100:53531") ?: "ws://192.168.1.100:53531")
    val wanUrl: StateFlow<String> = _wanUrl.asStateFlow()

    private val _wanToken = MutableStateFlow(prefs.getString(KEY_WAN_TOKEN, "123456") ?: "123456")
    val wanToken: StateFlow<String> = _wanToken.asStateFlow()

    private val _laptopPin = MutableStateFlow(prefs.getString(KEY_LAPTOP_PIN, "") ?: "")
    val laptopPin: StateFlow<String> = _laptopPin.asStateFlow()

    private val _customAppsJson = MutableStateFlow(prefs.getString(KEY_CUSTOM_APPS, "[]") ?: "[]")
    val customAppsJson: StateFlow<String> = _customAppsJson.asStateFlow()

    private val _connectionMode = MutableStateFlow(
        try {
            ConnectionMode.valueOf(prefs.getString(KEY_CONNECTION_MODE, ConnectionMode.BLUETOOTH.name) ?: ConnectionMode.BLUETOOTH.name)
        } catch (e: Exception) {
            ConnectionMode.BLUETOOTH
        }
    )
    val connectionMode: StateFlow<ConnectionMode> = _connectionMode.asStateFlow()

    fun setPointerSpeed(value: Float) {
        _pointerSpeed.value = value
        prefs.edit().putFloat(KEY_POINTER_SPEED, value).apply()
    }

    fun setScrollSpeed(value: Float) {
        _scrollSpeed.value = value
        prefs.edit().putFloat(KEY_SCROLL_SPEED, value).apply()
    }

    fun setInvertScroll(value: Boolean) {
        _invertScroll.value = value
        prefs.edit().putBoolean(KEY_INVERT_SCROLL, value).apply()
    }

    fun setTapToClick(value: Boolean) {
        _tapToClick.value = value
        prefs.edit().putBoolean(KEY_TAP_TO_CLICK, value).apply()
    }

    fun setHapticFeedback(value: Boolean) {
        _hapticFeedback.value = value
        prefs.edit().putBoolean(KEY_HAPTIC, value).apply()
    }

    fun setAirMouseSensitivity(value: Float) {
        _airMouseSensitivity.value = value
        prefs.edit().putFloat(KEY_AIR_MOUSE_SENSITIVITY, value).apply()
    }

    fun setWifiHost(value: String) {
        _wifiHost.value = value
        prefs.edit().putString(KEY_WIFI_HOST, value).apply()
    }

    fun setWifiPort(value: Int) {
        _wifiPort.value = value
        prefs.edit().putInt(KEY_WIFI_PORT, value).apply()
    }

    fun setWanUrl(value: String) {
        _wanUrl.value = value
        prefs.edit().putString(KEY_WAN_URL, value).apply()
    }

    fun setWanToken(value: String) {
        _wanToken.value = value
        prefs.edit().putString(KEY_WAN_TOKEN, value).apply()
    }

    fun setLaptopPin(value: String) {
        _laptopPin.value = value
        prefs.edit().putString(KEY_LAPTOP_PIN, value).apply()
    }

    fun setCustomAppsJson(value: String) {
        _customAppsJson.value = value
        prefs.edit().putString(KEY_CUSTOM_APPS, value).apply()
    }

    fun setConnectionMode(mode: ConnectionMode) {
        _connectionMode.value = mode
        prefs.edit().putString(KEY_CONNECTION_MODE, mode.name).apply()
    }

    companion object {
        private const val KEY_POINTER_SPEED = "pointer_speed"
        private const val KEY_SCROLL_SPEED = "scroll_speed"
        private const val KEY_INVERT_SCROLL = "invert_scroll"
        private const val KEY_TAP_TO_CLICK = "tap_to_click"
        private const val KEY_HAPTIC = "haptic_feedback"
        private const val KEY_AIR_MOUSE_SENSITIVITY = "air_mouse_sensitivity"
        private const val KEY_WIFI_HOST = "wifi_host"
        private const val KEY_WIFI_PORT = "wifi_port"
        private const val KEY_WAN_URL = "wan_url"
        private const val KEY_WAN_TOKEN = "wan_token"
        private const val KEY_LAPTOP_PIN = "laptop_pin"
        private const val KEY_CUSTOM_APPS = "custom_apps"
        private const val KEY_CONNECTION_MODE = "connection_mode"
    }
}
