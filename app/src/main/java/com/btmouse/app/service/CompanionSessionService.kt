package com.btmouse.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.btmouse.app.MainActivity
import com.btmouse.app.R
import com.btmouse.app.connection.ConnectionCoordinator

/**
 * Foreground service that keeps explicitly user-started live camera or audio
 * companion streams running while the Android app is backgrounded.
 *
 * In accordance with Android 14+ foreground service guidelines, this service
 * displays a persistent, user-visible notification with explicit stop and
 * disconnect controls, and never auto-starts without user action.
 */
class CompanionSessionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE, ACTION_DISCONNECT -> {
                ConnectionCoordinator.activeInstance?.disconnectAll()
                stopForegroundSafely()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_MEDIA -> {
                ConnectionCoordinator.activeInstance?.let {
                    it.stopPcLiveAudio()
                    it.stopPhoneLiveAudio()
                    it.stopCameraStream()
                    it.stopPhoneCameraStream()
                }
                updateNotification()
                return START_STICKY
            }
            else -> {
                startForegroundWithSession()
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithSession() {
        val notification = buildSessionNotification()
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            val coord = ConnectionCoordinator.activeInstance
            if (coord?.isPhoneLiveAudioActive?.value == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
            }
            if (coord?.isPcLiveAudioActive?.value == true) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }
            if (coord?.isPhoneCameraStreaming?.value == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
            }
            type
        } else 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, foregroundType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireWakeLockIfNeeded()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildSessionNotification())
    }

    private fun acquireWakeLockIfNeeded() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BTMouse:SessionWakeLock").apply {
                setReferenceCounted(false)
                // Cap wake lock to 1 hour to prevent runaway battery drain
                acquire(60 * 60 * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun stopForegroundSafely() {
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
    }

    private fun buildSessionNotification(): Notification {
        val coord = ConnectionCoordinator.activeInstance
        val isAudioActive = (coord?.isPcLiveAudioActive?.value == true || coord?.isPhoneLiveAudioActive?.value == true)
        val isPcCameraActive = coord?.isCameraStreaming?.value == true
        val isPhoneCameraActive = coord?.isPhoneCameraStreaming?.value == true
        val isAnyMediaActive = isAudioActive || isPcCameraActive || isPhoneCameraActive
        val deviceName = coord?.connectedDeviceName?.value ?: "PC Companion"

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val disconnectIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CompanionSessionService::class.java).apply {
                action = ACTION_DISCONNECT
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopMediaIntent = PendingIntent.getService(
            this, 2,
            Intent(this, CompanionSessionService::class.java).apply {
                action = ACTION_STOP_MEDIA
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val statusText = when {
            isPhoneCameraActive -> "Phone Camera as Webcam streaming to PC"
            isAudioActive && isPcCameraActive -> "PC Camera feed & Live Audio active"
            isAudioActive -> "Live Audio active"
            isPcCameraActive -> "PC Camera stream active"
            else -> "Companion connection active"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_companion)
            .setContentTitle("Connected to $deviceName")
            .setContentText(statusText)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (isAnyMediaActive) {
            builder.addAction(0, "Stop Stream", stopMediaIntent)
        }
        builder.addAction(0, "Disconnect", disconnectIntent)

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Active Companion Sessions",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows notifications when a live PC companion session is active in the background."
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "companion_active_session"
        const val NOTIFICATION_ID = 2001

        const val ACTION_START = "com.btmouse.app.action.START_SESSION_SERVICE"
        const val ACTION_STOP_SERVICE = "com.btmouse.app.action.STOP_SESSION_SERVICE"
        const val ACTION_STOP_MEDIA = "com.btmouse.app.action.STOP_MEDIA"
        const val ACTION_DISCONNECT = "com.btmouse.app.action.DISCONNECT"

        fun start(context: Context) {
            try {
                val intent = Intent(context, CompanionSessionService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, CompanionSessionService::class.java).apply {
                    action = ACTION_STOP_SERVICE
                }
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun update(context: Context) {
            try {
                val intent = Intent(context, CompanionSessionService::class.java)
                context.startService(intent)
            } catch (_: Exception) {}
        }
    }
}
