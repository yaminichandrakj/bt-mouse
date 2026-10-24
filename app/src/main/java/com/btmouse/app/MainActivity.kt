package com.btmouse.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.data.SettingsRepository
import com.btmouse.app.data.HidConstants
import com.btmouse.app.sensors.AirMouseManager
import com.btmouse.app.ui.MainScreen
import com.btmouse.app.ui.theme.BtMouseTheme

class MainActivity : ComponentActivity() {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var connectionCoordinator: ConnectionCoordinator
    private lateinit var airMouseManager: AirMouseManager

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val volumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (::settingsRepository.isInitialized && ::connectionCoordinator.isInitialized && volumeKey &&
            settingsRepository.mediaRemoteVisible.value && settingsRepository.presentationVolumeNavigation.value) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val key = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) HidConstants.KEY_PAGE_DOWN else HidConstants.KEY_PAGE_UP
                connectionCoordinator.sendKeyStroke(key, HidConstants.MODIFIER_NONE)
            }
            if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            connectionCoordinator.initialize()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keep screen awake while using the touchpad
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        settingsRepository = SettingsRepository(applicationContext)
        connectionCoordinator = ConnectionCoordinator(applicationContext, settingsRepository, lifecycleScope)
        airMouseManager = AirMouseManager(applicationContext, connectionCoordinator, settingsRepository)

        checkAndRequestPermissions()

        setContent {
            BtMouseTheme {
                MainScreen(
                    coordinator = connectionCoordinator,
                    airMouseManager = airMouseManager,
                    settings = settingsRepository
                )
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            connectionCoordinator.initialize()
        }
    }

    override fun onPause() {
        super.onPause()
        // Stop sensor listener when app is paused to conserve battery
        airMouseManager.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionCoordinator.cleanup()
    }
}
