package com.btmouse.app.ecosystem

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btmouse.app.BtMouseApplication
import com.btmouse.app.ui.theme.BtMouseTheme
import com.btmouse.app.ui.theme.Control
import com.btmouse.app.ui.theme.OnInk
import com.btmouse.app.ui.theme.OnInkMuted
import com.btmouse.app.ui.theme.Panel

/**
 * "Share → Lanyard": pick a device and send the shared files there, or put shared
 * text on every device's clipboard.
 */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BtMouseApplication
        app.connectionCoordinator.initialize()
        val uris = sharedUris(intent)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)

        if (uris.isEmpty() && !text.isNullOrBlank()) {
            if (!app.connectionCoordinator.isConnected.value) {
                Toast.makeText(this, "Not connected to your PC", Toast.LENGTH_SHORT).show()
            } else {
                Ecosystem.pushText(text)
                Toast.makeText(this, "Copied to your other devices", Toast.LENGTH_SHORT).show()
            }
            finish()
            return
        }
        if (uris.isEmpty()) {
            finish()
            return
        }

        setContent {
            BtMouseTheme {
                val devices by Ecosystem.devices.collectAsState()
                val connected by app.connectionCoordinator.isConnected.collectAsState()
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .background(Panel)
                            .navigationBarsPadding()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            if (uris.size == 1) "Send 1 file to" else "Send ${uris.size} files to",
                            color = OnInk, fontSize = 20.sp, fontWeight = FontWeight.Medium
                        )
                        val targets = devices.filter { it.acceptsFiles }
                        if (!connected || targets.isEmpty()) {
                            Text(
                                if (!connected) "Not connected. Open Lanyard and connect to your PC first."
                                else "Looking for your devices…",
                                color = OnInkMuted, fontSize = 14.sp
                            )
                        }
                        targets.forEach { device ->
                            DeviceRow(device) {
                                uris.forEach { Ecosystem.files.sendUri(device.id, device.name, it) }
                                Toast.makeText(this@ShareReceiverActivity, "Sending to ${device.name}…", Toast.LENGTH_SHORT).show()
                                finish()
                            }
                        }
                        Text(
                            "Cancel", color = OnInk, fontSize = 15.sp,
                            modifier = Modifier.align(Alignment.End).clickable { finish() }.padding(12.dp)
                        )
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM)
        )
        Intent.ACTION_SEND_MULTIPLE -> (
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        )?.toList() ?: emptyList()
        else -> emptyList()
    }
}

@androidx.compose.runtime.Composable
internal fun DeviceRow(device: PeerDevice, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Control)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            when (device.kind) {
                "pc" -> Icons.Default.Computer
                "tablet" -> Icons.Default.Tablet
                else -> Icons.Default.PhoneAndroid
            },
            contentDescription = null, tint = OnInk, modifier = Modifier.size(22.dp)
        )
        Text(device.name, color = OnInk, fontSize = 16.sp, modifier = Modifier.weight(1f))
    }
}

/** Invisible activity: Android lets only the focused app read the clipboard. */
class ClipboardSendActivity : Activity() {
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val app = application as BtMouseApplication
        app.connectionCoordinator.initialize()
        val msg = when {
            !app.connectionCoordinator.isConnected.value -> "Not connected to your PC"
            Ecosystem.pushClipboardIfNew(force = true) -> "Clipboard sent to your devices"
            else -> "The clipboard is empty"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        finish()
    }
}

/** Quick Settings tile: "Send clipboard". */
class ClipboardTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, ClipboardSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
