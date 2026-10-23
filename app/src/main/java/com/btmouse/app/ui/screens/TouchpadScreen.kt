package com.btmouse.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ControlCamera
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
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

    val pointerSpeed by settings.pointerSpeed.collectAsState()
    val scrollSpeed by settings.scrollSpeed.collectAsState()
    val invertScroll by settings.invertScroll.collectAsState()
    val tapToClick by settings.tapToClick.collectAsState()
    val hapticsEnabled by settings.hapticFeedback.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Top Status Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(DarkSurfaceVariant)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isConnected) AccentEmerald else AccentRose)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = if (isConnected) (connectedDevice ?: "Connected") else "Disconnected",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = statusMessage,
                        color = TextMuted,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }

            // Air Mouse quick toggle button
            if (isAirMouseSupported) {
                FilledTonalButton(
                    onClick = { airMouseManager.toggleAirMouse() },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (isAirMouseEnabled) PrimaryIndigo else DarkBackground,
                        contentColor = if (isAirMouseEnabled) TextPrimary else TextSecondary
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ControlCamera,
                        contentDescription = "Air Mouse",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isAirMouseEnabled) "Air On" else "Air Off",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Center Touchpad Area with right-side Scroll Wheel
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

        Spacer(modifier = Modifier.height(12.dp))

        // Bottom Mouse Buttons (Left, Middle, Right)
        MouseButtonsBar(
            hapticsEnabled = hapticsEnabled,
            onButtonChange = { buttonMask ->
                coordinator.sendMouseButton(buttonMask)
            }
        )
    }
}
