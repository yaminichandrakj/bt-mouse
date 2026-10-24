package com.btmouse.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.data.SettingsRepository
import com.btmouse.app.sensors.AirMouseManager
import com.btmouse.app.ui.screens.ConnectionScreen
import com.btmouse.app.ui.screens.KeyboardScreen
import com.btmouse.app.ui.screens.MediaRemoteScreen
import com.btmouse.app.ui.screens.SettingsScreen
import com.btmouse.app.ui.screens.TouchpadScreen
import com.btmouse.app.ui.screens.UtilitiesScreen
import com.btmouse.app.ui.theme.AccentCyan
import com.btmouse.app.ui.theme.AccentEmerald
import com.btmouse.app.ui.theme.AccentRose
import com.btmouse.app.ui.theme.DarkBackground
import com.btmouse.app.ui.theme.DarkSurface
import com.btmouse.app.ui.theme.DarkSurfaceVariant
import com.btmouse.app.ui.theme.PrimaryIndigo
import com.btmouse.app.ui.theme.TextMuted
import com.btmouse.app.ui.theme.TextPrimary

sealed class NavigationTab(val title: String, val icon: ImageVector) {
    object Touchpad : NavigationTab("Mouse", Icons.Default.Mouse)
    object Keyboard : NavigationTab("Keys", Icons.Default.Keyboard)
    object WritingPad : NavigationTab("Pen", Icons.Default.Draw)
    object Media : NavigationTab("Media", Icons.Default.PlayCircle)
    object Utilities : NavigationTab("Tools", Icons.Default.Build)
    object Connect : NavigationTab("Connect", Icons.Default.Wifi)
}

@Composable
fun MainScreen(
    coordinator: ConnectionCoordinator,
    airMouseManager: AirMouseManager,
    settings: SettingsRepository
) {
    val tabs = listOf(
        NavigationTab.Touchpad,
        NavigationTab.Keyboard,
        NavigationTab.WritingPad,
        NavigationTab.Media,
        NavigationTab.Utilities,
        NavigationTab.Connect
    )

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    val isConnected by coordinator.isConnected.collectAsState()
    val reconnectStatus by coordinator.reconnectStatus.collectAsState()
    val activeTransportLabel by coordinator.activeTransportLabel.collectAsState()

    Scaffold(
        topBar = {
            Column(modifier = Modifier.fillMaxWidth().background(DarkSurface)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
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
                                text = if (showSettings) "SETTINGS & UPDATES" else when (selectedTabIndex) {
                                    0 -> "BT MOUSE & TRACKPAD"
                                    1 -> "KEYBOARD & SHORTCUTS"
                                    2 -> "PEN & TOUCHSCREEN"
                                    3 -> "MEDIA & VOLUME REMOTE"
                                    4 -> "PC UTILITIES"
                                    5 -> "CONNECTION & QR"
                                    else -> "BT MOUSE"
                                },
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (isConnected && activeTransportLabel != "Disconnected") {
                                Text(
                                    text = activeTransportLabel,
                                    color = AccentCyan,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = { showSettings = !showSettings },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (showSettings) Icons.Default.ArrowBack else Icons.Default.Settings,
                            contentDescription = if (showSettings) "Back" else "Settings",
                            tint = if (showSettings) AccentCyan else TextMuted
                        )
                    }
                }

                reconnectStatus?.let { status ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DarkSurfaceVariant)
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = status,
                            color = AccentCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (status.contains("Tap to retry")) {
                                Text(
                                    text = "Retry",
                                    color = AccentCyan,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clickable { coordinator.startAutoReconnect() }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                text = "Cancel",
                                color = AccentRose,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable { coordinator.cancelReconnect() }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier.navigationBarsPadding(),
                containerColor = DarkSurface,
                tonalElevation = 8.dp
            ) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTabIndex == index,
                        onClick = { if (tab != NavigationTab.WritingPad) selectedTabIndex = index },
                        enabled = tab != NavigationTab.WritingPad,
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = if (tab == NavigationTab.WritingPad) "Pen and touch controls, temporarily disabled" else tab.title,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = {
                            Text(
                                text = tab.title,
                                fontSize = 10.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AccentCyan,
                            selectedTextColor = AccentCyan,
                            indicatorColor = PrimaryIndigo.copy(alpha = 0.25f),
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground)
                .padding(innerPadding)
        ) {
            if (showSettings) {
                SettingsScreen(settings, airMouseManager, onClose = { showSettings = false })
            } else when (selectedTabIndex) {
                0 -> TouchpadScreen(coordinator, airMouseManager, settings)
                1 -> KeyboardScreen(coordinator, settings)
                2 -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Pen and touch controls are temporarily disabled while this feature is rebuilt.")
                }
                3 -> MediaRemoteScreen(coordinator, settings)
                4 -> UtilitiesScreen(coordinator, settings)
                5 -> ConnectionScreen(coordinator, settings)
            }
        }
    }
}
