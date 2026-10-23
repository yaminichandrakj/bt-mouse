package com.btmouse.app.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btmouse.app.data.SettingsRepository
import com.btmouse.app.sensors.AirMouseManager
import com.btmouse.app.ui.theme.AccentCyan
import com.btmouse.app.ui.theme.DarkBackground
import com.btmouse.app.ui.theme.DarkBorder
import com.btmouse.app.ui.theme.DarkSurface
import com.btmouse.app.ui.theme.PrimaryIndigo
import com.btmouse.app.ui.theme.TextPrimary
import com.btmouse.app.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    airMouseManager: AirMouseManager
) {
    val pointerSpeed by settings.pointerSpeed.collectAsState()
    val scrollSpeed by settings.scrollSpeed.collectAsState()
    val invertScroll by settings.invertScroll.collectAsState()
    val tapToClick by settings.tapToClick.collectAsState()
    val hapticFeedback by settings.hapticFeedback.collectAsState()
    val airMouseSensitivity by settings.airMouseSensitivity.collectAsState()
    val isGyroSupported by airMouseManager.isSupported.collectAsState()

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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
                        valueRange = 0.4f..3.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = PrimaryIndigo,
                            activeTrackColor = PrimaryIndigo
                        )
                    )
                }

                // Invert Scroll
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Invert Scroll Direction", color = TextPrimary, fontSize = 14.sp)
                        Text("Natural / Mac-style reverse scrolling", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = invertScroll,
                        onCheckedChange = { settings.setInvertScroll(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = PrimaryIndigo)
                    )
                }

                // Tap to click
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Tap to Click", color = TextPrimary, fontSize = 14.sp)
                        Text("1-finger = Left click, 2-finger = Right click", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = tapToClick,
                        onCheckedChange = { settings.setTapToClick(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = PrimaryIndigo)
                    )
                }

                // Haptic feedback
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Haptic Vibration", color = TextPrimary, fontSize = 14.sp)
                        Text("Vibrate on taps and button presses", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = hapticFeedback,
                        onCheckedChange = { settings.setHapticFeedback(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = PrimaryIndigo)
                    )
                }
            }
        }

        // Air Mouse (Gyro) Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Air Mouse (Gyroscope)", color = AccentCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)

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
