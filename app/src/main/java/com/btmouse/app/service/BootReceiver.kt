package com.btmouse.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.btmouse.app.BtMouseApplication

/**
 * Starts "Stay connected" after the phone restarts or the app updates. Creating the
 * application already initialises the coordinator, which reconnects on its own.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, "android.intent.action.QUICKBOOT_POWERON" -> {
                val app = context.applicationContext as? BtMouseApplication ?: return
                if (app.connectionCoordinator.shouldStayConnected()) {
                    CompanionSessionService.start(context)
                }
            }
        }
    }
}
