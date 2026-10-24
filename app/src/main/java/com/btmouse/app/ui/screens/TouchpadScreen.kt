package com.btmouse.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

    val pointerSpeed by settings.pointerSpeed.collectAsState()
    val scrollSpeed by settings.scrollSpeed.collectAsState()
    val invertScroll by settings.invertScroll.collectAsState()
    val tapToClick by settings.tapToClick.collectAsState()
    val hapticsEnabled by settings.hapticFeedback.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // Fluent Status & Air Mouse Telemetry Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(DarkSurface)
                .border(1.dp, DarkBorder, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isConnected) AccentEmerald else AccentRose)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isConnected) (connectedDevice ?: "Connected to PC") else "Ready to Pair",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text = statusMessage,
                        color = if (isConnected) AccentCyan else TextMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (isAirMouseSupported) {
                FilledTonalButton(
                    onClick = { airMouseManager.toggleAirMouse() },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (isAirMouseEnabled) PrimaryIndigo else DarkSurfaceVariant,
                        contentColor = if (isAirMouseEnabled) TextPrimary else TextSecondary
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ControlCamera,
                        contentDescription = "Air Mouse",
                        tint = if (isAirMouseEnabled) AccentCyan else TextSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (isAirMouseEnabled) "Gyro Air ON" else "Gyro Air OFF",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

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
