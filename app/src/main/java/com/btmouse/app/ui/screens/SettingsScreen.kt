package com.btmouse.app.ui.screens

import android.os.Build
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.btmouse.app.data.InAppUpdateManager
import com.btmouse.app.data.SettingsRepository
import com.btmouse.app.data.UpdateState
import com.btmouse.app.sensors.AirMouseManager
import com.btmouse.app.ui.theme.AccentCyan
import com.btmouse.app.ui.theme.AccentEmerald
import com.btmouse.app.ui.theme.AccentRose
import com.btmouse.app.ui.theme.DarkBackground
import com.btmouse.app.ui.theme.DarkBorder
import com.btmouse.app.ui.theme.DarkSurface
import com.btmouse.app.ui.theme.DarkSurfaceVariant
import com.btmouse.app.ui.theme.PrimaryIndigo
import com.btmouse.app.ui.theme.TextMuted
import com.btmouse.app.ui.theme.TextPrimary
import com.btmouse.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    airMouseManager: AirMouseManager,
    onClose: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val updateManager = remember { InAppUpdateManager(context) }
    val updateState by updateManager.updateState.collectAsState()
    val lifecycleOwner = remember(context) {
        var current: android.content.Context? = context
        var owner: LifecycleOwner? = null
        while (current != null && owner == null) {
            owner = current as? LifecycleOwner
            current = (current as? ContextWrapper)?.baseContext
        }
        owner
    }

    DisposableEffect(updateManager, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) updateManager.onHostResumed()
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    val pointerSpeed by settings.pointerSpeed.collectAsState()
    val scrollSpeed by settings.scrollSpeed.collectAsState()
    val invertScroll by settings.invertScroll.collectAsState()
    val tapToClick by settings.tapToClick.collectAsState()
    val hapticFeedback by settings.hapticFeedback.collectAsState()
    val airMouseSensitivity by settings.airMouseSensitivity.collectAsState()
    val isGyroSupported by airMouseManager.isSupported.collectAsState()
    val backgroundSessionEnabled by settings.backgroundSessionEnabled.collectAsState()
    val autoReconnect by settings.autoReconnect.collectAsState()
    val failoverEnabled by settings.failoverEnabled.collectAsState()

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (onClose != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClose() }
                    .padding(vertical = 4.dp)
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = AccentCyan)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Back to Controls", color = AccentCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        Text("Preferences & Tuning", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)

        // Mouse & Touchpad Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Touchpad & Pointer", color = AccentCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)

                // Pointer Speed Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Pointer Speed", color = TextPrimary, fontSize = 14.sp)
                        Text(String.format(Locale.US, "%.1fx", pointerSpeed), color = TextSecondary, fontSize = 14.sp)
                    }
                    Slider(
                        value = pointerSpeed,
                        onValueChange = { settings.setPointerSpeed(it) },
                        valueRange = 0.4f..3.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = PrimaryIndigo,
                            activeTrackColor = PrimaryIndigo
                        )
                    )
                }

                // Scroll Speed Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Scroll Speed", color = TextPrimary, fontSize = 14.sp)
                        Text(String.format(Locale.US, "%.1fx", scrollSpeed), color = TextSecondary, fontSize = 14.sp)
                    }
                    Slider(
                        value = scrollSpeed,
                        onValueChange = { settings.setScrollSpeed(it) },
                        valueRange = 0.5f..3.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = PrimaryIndigo,
                            activeTrackColor = PrimaryIndigo
                        )
                    )
                }

                // Invert Scroll Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Natural / Invert Scroll", color = TextPrimary, fontSize = 14.sp)
                        Text("Reverse vertical scroll direction", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = invertScroll,
                        onCheckedChange = { settings.setInvertScroll(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = AccentCyan, checkedTrackColor = PrimaryIndigo)
                    )
                }

                // Tap to Click Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Tap to Click", color = TextPrimary, fontSize = 14.sp)
                        Text("Single tap = Left Click, Double tap = Drag", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = tapToClick,
                        onCheckedChange = { settings.setTapToClick(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = AccentCyan, checkedTrackColor = PrimaryIndigo)
                    )
                }

                // Tactile Haptic Feedback Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Haptic Feedback", color = TextPrimary, fontSize = 14.sp)
                        Text("Vibrate on taps and button presses", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = hapticFeedback,
                        onCheckedChange = { settings.setHapticFeedback(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = AccentCyan, checkedTrackColor = PrimaryIndigo)
                    )
                }
            }
        }

        // Connection & Background Sessions Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Connection & Background Sessions", color = AccentCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)

                // Background Companion Session Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Text("Background Companion Session", color = TextPrimary, fontSize = 14.sp)
                        Text("Keep live audio and camera streams connected when switching apps using a foreground notification", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = backgroundSessionEnabled,
                        onCheckedChange = { settings.setBackgroundSessionEnabled(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = AccentCyan, checkedTrackColor = PrimaryIndigo)
                    )
                }

                // Automatic Reconnect Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Text("Automatic Reconnect", color = TextPrimary, fontSize = 14.sp)
                        Text("Automatically reconnect to saved PC with exponential backoff on network drop", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = autoReconnect,
                        onCheckedChange = { settings.setAutoReconnect(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = AccentCyan, checkedTrackColor = PrimaryIndigo)
                    )
                }

                // Automatic Measured Failover Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Text("Automatic Measured Failover", color = TextPrimary, fontSize = 14.sp)
                        Text("Measure ping latency across Wi-Fi, Bluetooth, and WAN, seamlessly routing commands over the healthiest path with zero duplicate inputs", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = failoverEnabled,
                        onCheckedChange = { settings.setFailoverEnabled(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = AccentCyan, checkedTrackColor = PrimaryIndigo)
                    )
                }
            }
        }

        // Air Mouse Gyroscope Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Gyroscopic Air Mouse", color = AccentCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)

                if (isGyroSupported) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Gyro Sensitivity", color = TextPrimary, fontSize = 14.sp)
                            Text(String.format(Locale.US, "%.0f", airMouseSensitivity), color = TextSecondary, fontSize = 14.sp)
                        }
                        Slider(
                            value = airMouseSensitivity,
                            onValueChange = { settings.setAirMouseSensitivity(it) },
                            valueRange = 5.0f..40.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = AccentCyan,
                                activeTrackColor = AccentCyan
                            )
                        )
                    }
                } else {
                    Text(
                        "Hardware Gyroscope not detected on this device.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Software Updates Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Software Updates", color = AccentCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("v${updateManager.currentVersion}", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Text(
                    "Keep your app up to date with new features, tablet writing improvements, and bug fixes directly from GitHub Releases.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )

                // Update Status Display
                when (val state = updateState) {
                    is UpdateState.Idle -> {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    updateManager.checkForUpdates()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Check for Updates")
                        }
                    }

                    is UpdateState.Checking -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = AccentCyan, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Checking GitHub Releases...", color = TextPrimary, fontSize = 13.sp)
                        }
                    }

                    is UpdateState.NoUpdateAvailable -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentEmerald, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("You are using the latest version (v${updateManager.currentVersion})", color = AccentEmerald, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch { updateManager.checkForUpdates() }
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Check Again", color = TextPrimary)
                        }
                    }

                    is UpdateState.UpdateAvailable -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(DarkSurfaceVariant)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("New Version: ${state.release.tagName}", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }

                            if (state.release.releaseNotes.isNotEmpty()) {
                                Text(
                                    state.release.releaseNotes.take(200) + if (state.release.releaseNotes.length > 200) "..." else "",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }

                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        updateManager.downloadAndInstall(state.release)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.CloudDownload, contentDescription = null, tint = DarkBackground, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Download & Install Update", color = DarkBackground, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    updateManager.openDownloadInBrowser(state.release.downloadUrl)
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Download APK in Browser", color = TextPrimary, fontSize = 12.sp)
                            }
                        }
                    }

                    is UpdateState.Downloading -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Downloading APK Update...", color = TextPrimary, fontSize = 12.sp)
                                Text(String.format(Locale.US, "%.1f / %.1f MB", state.downloadedMb, state.totalMb), color = AccentCyan, fontSize = 12.sp)
                            }
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = AccentCyan,
                                trackColor = DarkSurfaceVariant
                            )
                        }
                    }

                    is UpdateState.ReadyToInstall -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Download complete. The Android installer opens after install permission is granted. If it does not, tap Install APK Now.", color = AccentEmerald, fontSize = 12.sp)
                            Button(
                                onClick = { updateManager.launchApkInstaller(state.apkFile) },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Install APK Now", color = DarkBackground, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    is UpdateState.Error -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = AccentRose, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(state.message, color = AccentRose, fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    coroutineScope.launch { updateManager.checkForUpdates() }
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Retry", color = TextPrimary)
                            }
                        }
                    }
                }
            }
        }

        // Device & System Info Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("System Compatibility", color = AccentCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", color = TextPrimary, fontSize = 12.sp)
                Text("Device Model: ${Build.MANUFACTURER} ${Build.MODEL}", color = TextPrimary, fontSize = 12.sp)
                Text("Bluetooth HID API: Supported (API 28+)", color = TextPrimary, fontSize = 12.sp)
                Text("Gyroscope Sensor: ${if (isGyroSupported) "Present" else "Unavailable"}", color = TextPrimary, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}
