package com.btmouse.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

class AirMouseManager(
    private val context: Context,
    private val coordinator: ConnectionCoordinator,
    private val settings: SettingsRepository
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val _isAirMouseEnabled = MutableStateFlow(false)
    val isAirMouseEnabled: StateFlow<Boolean> = _isAirMouseEnabled.asStateFlow()

    private val _isSupported = MutableStateFlow(gyroscope != null)
    val isSupported: StateFlow<Boolean> = _isSupported.asStateFlow()

    // Deadband to ignore tiny vibrations/tremors
    private val deadband = 0.04f

    fun toggleAirMouse() {
        if (_isAirMouseEnabled.value) {
            stop()
        } else {
            start()
        }
    }

    fun start() {
        if (gyroscope == null) return
        _isAirMouseEnabled.value = true
        sensorManager?.registerListener(
            this,
            gyroscope,
            SensorManager.SENSOR_DELAY_GAME
        )
    }

    fun stop() {
        _isAirMouseEnabled.value = false
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!_isAirMouseEnabled.value || event == null) return
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return

        // In portrait mode:
        // event.values[2] (Z axis - yaw/turning left/right) maps to horizontal movement (dx)
        // event.values[0] (X axis - pitch/tilting up/down) maps to vertical movement (dy)
        val rawX = -event.values[2]
        val rawY = -event.values[0]

        val filterX = if (abs(rawX) > deadband) rawX else 0f
        val filterY = if (abs(rawY) > deadband) rawY else 0f

        if (filterX == 0f && filterY == 0f) return

        val sensitivity = settings.airMouseSensitivity.value
        val dx = (filterX * sensitivity).toInt()
        val dy = (filterY * sensitivity).toInt()

        if (dx != 0 || dy != 0) {
            coordinator.sendMouseMove(dx, dy, 0)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
