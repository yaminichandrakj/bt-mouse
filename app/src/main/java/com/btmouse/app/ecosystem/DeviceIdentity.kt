package com.btmouse.app.ecosystem

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import java.util.UUID

/** This phone or tablet as the other devices see it: a stable id, a name, and its screen. */
object DeviceIdentity {
    private const val PREFS = "bt_mouse_prefs"
    private const val KEY_ID = "eco_device_id"
    private const val KEY_NAME = "eco_device_name"

    fun id(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString().replace("-", "").take(20)
        prefs.edit().putString(KEY_ID, id).apply()
        return id
    }

    fun name(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val system = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        return system?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android device"
    }

    fun setName(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_NAME, name.trim().take(40)).apply()
    }

    fun isTablet(context: Context): Boolean = context.resources.configuration.smallestScreenWidthDp >= 600

    /** Full display size in pixels for the current rotation, the space remote cursor positions use. */
    fun screenSize(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    fun rotationDegrees(context: Context): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val rotation = runCatching { wm.defaultDisplay.rotation }.getOrDefault(Surface.ROTATION_0)
        return when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }
}
