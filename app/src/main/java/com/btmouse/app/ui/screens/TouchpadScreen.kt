package com.btmouse.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ControlCamera
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.data.SettingsRepository
import com.btmouse.app.sensors.AirMouseManager
import com.btmouse.app.ui.components.MouseButtonsBar
import com.btmouse.app.ui.components.TouchpadSurface
import com.btmouse.app.ui.components.VirtualScrollWheel
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

@Composable
fun TouchpadScreen(
    coordinator: ConnectionCoordinator,
    airMouseManager: AirMouseManager,
    settings: SettingsRepository
) {
    val isConnected by coordinator.isConnected.collectAsState()
    val statusMessage by coordinator.statusMessage.collectAsState()
    val connectedDevice by coordinator.connectedDeviceName.collectAsState()
    val isAirMouseEnabled by airMouseManager.isAirMouseEnabled.collectAsState()
    val isAirMouseSupported by airMouseManager.isSupported.collectAsState()
    val mediaStatus by coordinator.mediaStatus.collectAsState()

    val pointerSpeed by settings.pointerSpeed.collectAsState()
    val scrollSpeed by settings.scrollSpeed.collectAsState()
    val invertScroll by settings.invertScroll.collectAsState()
    val tapToClick by settings.tapToClick.collectAsState()
    val hapticsEnabled by settings.hapticFeedback.collectAsState()

    var padMode by remember { mutableStateOf("trackpad") } // "trackpad" or "pen"
    var showQuickType by remember { mutableStateOf(false) }
    var quickTypeText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // Unified Quick Command Strip (Trackpad vs Pen Slate + 1-Tap PC Actions)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = padMode == "trackpad",
                onClick = { padMode = "trackpad" },
                leadingIcon = {
                    Icon(Icons.Default.Mouse, contentDescription = null, modifier = Modifier.size(14.dp))
                },
                label = { Text("Trackpad", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = PrimaryIndigo.copy(alpha = 0.4f),
                    selectedLabelColor = AccentCyan,
                    containerColor = DarkSurface,
                    labelColor = TextSecondary
                )
            )

            FilterChip(
                selected = padMode == "pen",
                onClick = { padMode = "pen" },
                leadingIcon = {
                    Icon(Icons.Default.Draw, contentDescription = null, modifier = Modifier.size(14.dp))
                },
                label = { Text("Pen Slate", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = PrimaryIndigo.copy(alpha = 0.4f),
                    selectedLabelColor = AccentCyan,
                    containerColor = DarkSurface,
                    labelColor = TextSecondary
                )
            )

            if (isAirMouseSupported) {
                FilterChip(
                    selected = isAirMouseEnabled,
                    onClick = { airMouseManager.toggleAirMouse() },
                    leadingIcon = {
                        Icon(Icons.Default.ControlCamera, contentDescription = null, modifier = Modifier.size(14.dp))
                    },
                    label = { Text(if (isAirMouseEnabled) "Gyro ON" else "Gyro Air", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PrimaryIndigo.copy(alpha = 0.4f),
                        selectedLabelColor = AccentCyan,
                        containerColor = DarkSurface,
                        labelColor = TextSecondary
                    )
                )
            }

            FilterChip(
                selected = showQuickType,
                onClick = { showQuickType = !showQuickType },
                leadingIcon = {
                    Icon(Icons.Default.Keyboard, contentDescription = null, modifier = Modifier.size(14.dp))
                },
                label = { Text("Quick Type", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = PrimaryIndigo.copy(alpha = 0.4f),
                    selectedLabelColor = AccentCyan,
                    containerColor = DarkSurface,
                    labelColor = TextSecondary
                )
            )

            FilterChip(
                selected = false,
                onClick = { coordinator.sendMediaToggle() },
                leadingIcon = {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp), tint = AccentCyan)
                },
                label = { Text("Play/Pause", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(containerColor = DarkSurface, labelColor = TextSecondary)
            )

            FilterChip(
                selected = mediaStatus.isMuted,
                onClick = { coordinator.setMute(!mediaStatus.isMuted) },
                leadingIcon = {
                    Icon(Icons.Default.VolumeOff, contentDescription = null, modifier = Modifier.size(14.dp), tint = if (mediaStatus.isMuted) AccentRose else TextSecondary)
                },
                label = { Text(if (mediaStatus.isMuted) "Unmute PC" else "Mute PC", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = AccentRose.copy(alpha = 0.25f),
                    selectedLabelColor = AccentRose,
                    containerColor = DarkSurface,
                    labelColor = TextSecondary
                )
            )

            FilterChip(
                selected = false,
                onClick = { coordinator.systemPower("lock") },
                leadingIcon = {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp), tint = AccentRose)
                },
                label = { Text("Lock PC", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(containerColor = DarkSurface, labelColor = TextSecondary)
            )
        }

        // Slide-Down Quick Type Bar (lets users type/paste text without leaving the Trackpad!)
        AnimatedVisibility(visible = showQuickType) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = quickTypeText,
                    onValueChange = { quickTypeText = it },
                    placeholder = { Text("Type or paste text to send to PC...", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = DarkBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
                Button(
                    onClick = {
                        if (quickTypeText.isNotEmpty()) {
                            coordinator.sendText(quickTypeText)
                            quickTypeText = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send", modifier = Modifier.size(16.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (padMode == "pen") {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                WritingPadScreen(coordinator = coordinator, settings = settings)
            }
        } else {
            // Center Touchpad Area with right-side Tactile Scroll Wheel
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TouchpadSurface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    pointerSpeed = pointerSpeed,
                    scrollSpeed = scrollSpeed,
                    invertScroll = invertScroll,
                    tapToClick = tapToClick,
                    hapticsEnabled = hapticsEnabled,
                    onMouseMove = { dx, dy, buttons, vScroll, hScroll ->
                        coordinator.sendMouseMove(dx, dy, buttons, vScroll, hScroll)
                    },
                    onMouseButton = { buttons ->
                        coordinator.sendMouseButton(buttons)
                    }
                )

                VirtualScrollWheel(
                    scrollSpeed = scrollSpeed,
                    invertScroll = invertScroll,
                    hapticsEnabled = hapticsEnabled,
                    onScroll = { vScroll ->
                        coordinator.sendMouseMove(0, 0, 0, vScroll, 0)
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Bottom Mouse Buttons (Left, Middle, Right)
            MouseButtonsBar(
                hapticsEnabled = hapticsEnabled,
                onButtonChange = { buttonMask ->
                    coordinator.sendMouseButton(buttonMask)
                }
            )
        }
    }
}
