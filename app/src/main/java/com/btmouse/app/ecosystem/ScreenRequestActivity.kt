package com.btmouse.app.ecosystem

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import com.btmouse.app.R
import com.btmouse.app.service.CompanionSessionService

/**
 * Shown when another device asks to mirror this screen. It goes straight to Android's own
 * "Start now" prompt, which no app can skip; once allowed, sharing starts and the other
 * device sees and controls this screen.
 */
class ScreenRequestActivity : ComponentActivity() {
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ScreenShare.setPermission(result.resultCode, data)
            CompanionSessionService.startProjection(this)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        if (savedInstanceState == null) {
            if (ScreenShare.active.value) finish() else launcher.launch(ScreenShare.captureIntent(this))
        }
    }

    companion object {
        private const val CHANNEL_ID = "mirror_requests"
        private const val NOTIFICATION_ID = 4101

        /** Open the prompt now; if Android blocks that from the background, ask with a notification. */
        fun ask(context: Context, who: String) {
            val intent = Intent(context, ScreenRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra("who", who)
            runCatching { context.startActivity(intent) }
            runCatching {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Screen mirroring requests", NotificationManager.IMPORTANCE_HIGH))
                }
                val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                nm.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_companion)
                    .setContentTitle("$who wants to see this screen")
                    .setContentText("Tap, then Start now, to mirror and control it from $who")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setContentIntent(pending)
                    .setFullScreenIntent(pending, true)
                    .setAutoCancel(true)
                    .setTimeoutAfter(60_000)
                    .build())
            }
        }
    }
}
