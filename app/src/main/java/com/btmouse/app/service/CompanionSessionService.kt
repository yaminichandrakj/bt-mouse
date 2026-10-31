package com.btmouse.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.btmouse.app.MainActivity
import com.btmouse.app.R
import com.btmouse.app.connection.ConnectionCoordinator

/**
 * Foreground service that keeps the phone connected to the PC in the background
 * ("Stay connected"), and keeps user-started camera or audio streams running.
 *
 * Android requires a visible notification for this; it carries Disconnect,
 * Reconnect now, Stop Stream and Release (hand keyboard and mouse back) actions.
 */
class CompanionSessionService : Service() {

    private val tag = "CompanionSessionService"
    private var wakeLock: PowerManager.WakeLock? = null
    private var isForegroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE, ACTION_DISCONNECT -> {
                if (intent.action == ACTION_DISCONNECT) {
                    ConnectionCoordinator.activeInstance?.disconnectAll()
                }
                stopForegroundSafely()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_PROJECTION -> {
                startForegroundWithSession()
                return START_STICKY
            }
            ACTION_STOP_PROJECTION -> {
                com.btmouse.app.ecosystem.ScreenShare.stop()
                startForegroundWithSession()
                return START_STICKY
            }
            ACTION_RELEASE_CONTROL -> {
                com.btmouse.app.ecosystem.Ecosystem.releaseControl()
                updateNotification()
                return START_STICKY
            }
            ACTION_RECONNECT -> {
                ConnectionCoordinator.activeInstance?.reconnectNow()
                startForegroundWithSession()
                return START_STICKY
            }
            ACTION_STOP_MEDIA -> {
                com.btmouse.app.ecosystem.ScreenShare.stop()
                ConnectionCoordinator.activeInstance?.let {
                    it.stopPcLiveAudio()
                    it.stopPhoneLiveAudio()
                    it.stopCameraStream()
                    it.stopPhoneCameraStream()
                }
                updateNotification()
                return START_STICKY
            }
            ACTION_UPDATE -> {
                val coord = ConnectionCoordinator.activeInstance
                val hasActiveMedia = coord?.let {
                    it.isPcLiveAudioActive.value ||
                        it.isPhoneLiveAudioActive.value ||
                        it.isCameraStreaming.value ||
                        it.isPhoneCameraStreaming.value
                } ?: false
                if (!hasActiveMedia && coord?.isConnected?.value != true && coord?.shouldStayConnected() != true) {
                    stopForegroundSafely()
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (isForegroundStarted) {
                    startForegroundWithSession()
                } else {
                    updateNotification()
                }
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
            val hasMicPerm = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            val hasCamPerm = ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

            if (coord?.isPhoneLiveAudioActive?.value == true && hasMicPerm) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
            }
            if (coord?.isPcLiveAudioActive?.value == true) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }
            if (coord?.isPhoneCameraStreaming?.value == true && hasCamPerm) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
            }
            if (com.btmouse.app.ecosystem.ScreenShare.needsForeground) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            type
        } else 0

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, foregroundType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForegroundStarted = true
            acquireWakeLockIfNeeded()
            com.btmouse.app.ecosystem.ScreenShare.beginIfPending(this)
        } catch (e: Exception) {
            Log.w(tag, "Could not start foreground service with full type mask ($foregroundType), trying fallback", e)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                isForegroundStarted = true
                acquireWakeLockIfNeeded()
            } catch (e2: Exception) {
                Log.e(tag, "Foreground service start blocked by OS permissions/state", e2)
                stopSelf()
            }
        }
    }

    private fun updateNotification() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildSessionNotification())
        } catch (e: Exception) {
            Log.w(tag, "Failed to update session notification", e)
        }
    }

    private fun acquireWakeLockIfNeeded() {
        // Staying connected needs no wake lock; only live camera/audio keep the CPU awake.
        val coord = ConnectionCoordinator.activeInstance
        val hasActiveMedia = coord?.let {
            it.isPcLiveAudioActive.value || it.isPhoneLiveAudioActive.value ||
                it.isCameraStreaming.value || it.isPhoneCameraStreaming.value
        } ?: false
        if (!hasActiveMedia) {
            releaseWakeLock()
            return
        }
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
        isForegroundStarted = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        isForegroundStarted = false
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

        val controller = com.btmouse.app.ecosystem.Ecosystem.controlledBy.value
        val connected = coord?.isConnected?.value == true
        val statusText = when {
            controller != null -> "$controller is using its keyboard and mouse here"
            com.btmouse.app.ecosystem.ScreenShare.active.value -> "Sharing this screen with your devices"
            isPhoneCameraActive -> "Phone Camera as Webcam streaming to PC"
            isAudioActive && isPcCameraActive -> "PC Camera feed & Live Audio active"
            isAudioActive -> "Live Audio active"
            isPcCameraActive -> "PC Camera stream active"
            connected -> "Connected · clipboard and files ready"
            coord?.settings?.serverPin?.value?.length == 6 -> "Waiting for your PC · reconnects on its own"
            else -> "Phones and tablets on this Wi-Fi can reach this one"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_companion)
            .setContentTitle(
                when {
                    connected -> "Connected to $deviceName"
                    coord?.settings?.serverPin?.value?.length == 6 ->
                        "Staying connected to ${coord.settings.pcName.value.ifBlank { "your PC" }}"
                    else -> "Lanyard is ready for your devices"
                }
            )
            .setContentText(statusText)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (controller != null) {
            val releaseIntent = PendingIntent.getService(
                this, 3,
                Intent(this, CompanionSessionService::class.java).apply { action = ACTION_RELEASE_CONTROL },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, "Release", releaseIntent)
        }
        if (isAnyMediaActive || com.btmouse.app.ecosystem.ScreenShare.active.value) {
            builder.addAction(0, "Stop Stream", stopMediaIntent)
        }
        if (!connected) {
            val reconnectIntent = PendingIntent.getService(
                this, 4,
                Intent(this, CompanionSessionService::class.java).apply { action = ACTION_RECONNECT },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, "Reconnect now", reconnectIntent)
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
        const val ACTION_UPDATE = "com.btmouse.app.action.UPDATE_SESSION_SERVICE"
        const val ACTION_STOP_SERVICE = "com.btmouse.app.action.STOP_SESSION_SERVICE"
        const val ACTION_STOP_MEDIA = "com.btmouse.app.action.STOP_MEDIA"
        const val ACTION_DISCONNECT = "com.btmouse.app.action.DISCONNECT"
        const val ACTION_RELEASE_CONTROL = "com.btmouse.app.action.RELEASE_CONTROL"
        const val ACTION_RECONNECT = "com.btmouse.app.action.RECONNECT"
        const val ACTION_START_PROJECTION = "com.btmouse.app.action.START_PROJECTION"
        const val ACTION_STOP_PROJECTION = "com.btmouse.app.action.STOP_PROJECTION"

        fun startProjection(context: Context) {
            try {
                val intent = Intent(context, CompanionSessionService::class.java).apply { action = ACTION_START_PROJECTION }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            } catch (_: Exception) {}
        }

        fun stopProjection(context: Context) {
            try {
                context.startService(Intent(context, CompanionSessionService::class.java).apply { action = ACTION_STOP_PROJECTION })
            } catch (_: Exception) {}
        }

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
                val intent = Intent(context, CompanionSessionService::class.java).apply {
                    action = ACTION_UPDATE
                }
                context.startService(intent)
            } catch (_: Exception) {}
        }
    }
}
