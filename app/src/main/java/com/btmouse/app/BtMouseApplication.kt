package com.btmouse.app

import android.app.Application
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.data.SettingsRepository
import com.btmouse.app.sensors.AirMouseManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class BtMouseApplication : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var connectionCoordinator: ConnectionCoordinator
        private set

    lateinit var airMouseManager: AirMouseManager
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(applicationContext)
        connectionCoordinator = ConnectionCoordinator(
            context = applicationContext,
            settings = settingsRepository,
            scope = applicationScope
        )
        airMouseManager = AirMouseManager(
            context = applicationContext,
            connection = connectionCoordinator,
            settings = settingsRepository
        )
        // Always initialize coordinator at startup so Bluetooth, Wi-Fi, and WAN
        // are never blocked by optional runtime permission prompts.
        connectionCoordinator.initialize()
    }
}
