package com.btmouse.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.HorizontalDivider
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
import com.btmouse.app.ui.screens.WritingPadScreen
import com.btmouse.app.ui.theme.AccentCyan
import com.btmouse.app.ui.theme.AccentEmerald
import com.btmouse.app.ui.theme.AccentRose
import com.btmouse.app.ui.theme.DarkBackground
import com.btmouse.app.ui.theme.DarkBorder
import com.btmouse.app.ui.theme.DarkHeaderSurface
import com.btmouse.app.ui.theme.DarkSurfaceVariant
import com.btmouse.app.ui.theme.PrimaryIndigo
import com.btmouse.app.ui.theme.TextMuted
import com.btmouse.app.ui.theme.TextPrimary
import com.btmouse.app.ui.theme.TextSecondary

sealed class NavigationTab(val title: String, val subtitle: String, val icon: ImageVector) {
    object Touchpad : NavigationTab("Mouse", "Precision Trackpad & Air Mouse", Icons.Default.Mouse)
    object Keyboard : NavigationTab("Keys", "Keyboard, Shortcuts & Numpad", Icons.Default.Keyboard)
    object WritingPad : NavigationTab("Pen", "Graphics Slate & Handwriting", Icons.Default.Draw)
    object Media : NavigationTab("Media", "SMTC Player & Master Volume", Icons.Default.PlayCircle)
    object Utilities : NavigationTab("Tools", "Quick Apps, Camera, Audio & Tasks", Icons.Default.Build)
    object Connect : NavigationTab("Connect", "Wi-Fi QR, Bluetooth & Remote WAN", Icons.Default.Wifi)
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
    val currentTab = tabs.getOrElse(selectedTabIndex) { NavigationTab.Touchpad }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.fillMaxWidth().background(DarkHeaderSurface)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(DarkSurfaceVariant)
                                .border(1.dp, DarkBorder, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (showSettings) Icons.Default.Settings else currentTab.icon,
                                contentDescription = null,
                                tint = AccentCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (showSettings) "Settings & Updates" else currentTab.title,
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Text(
                                text = if (showSettings) "Preferences, failover & release updater" else currentTab.subtitle,
                                color = TextMuted,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Interactive Fluent Transport Pill (tapping jumps to Connect tab)
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(DarkSurfaceVariant)
                                .border(
                                    width = 1.dp,
                                    color = if (isConnected) AccentEmerald.copy(alpha = 0.55f) else DarkBorder,
                                    shape = RoundedCornerShape(20.dp)
                                )
                                .clickable {
                                    showSettings = false
                                    selectedTabIndex = 5
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isConnected) AccentEmerald else AccentRose)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isConnected && activeTransportLabel != "Disconnected") {
                                    activeTransportLabel
                                } else {
                                    "Connect"
                                },
                                color = if (isConnected) AccentCyan else TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }

                        IconButton(
                            onClick = { showSettings = !showSettings },
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (showSettings) PrimaryIndigo.copy(alpha = 0.25f) else DarkSurfaceVariant)
                                .border(1.dp, if (showSettings) AccentCyan else DarkBorder, RoundedCornerShape(10.dp))
                        ) {
                            Icon(
                                imageVector = if (showSettings) Icons.Default.ArrowBack else Icons.Default.Settings,
                                contentDescription = if (showSettings) "Back" else "Settings",
                                tint = if (showSettings) AccentCyan else TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
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

                HorizontalDivider(color = DarkBorder, thickness = 1.dp)
            }
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth().background(DarkHeaderSurface)) {
                HorizontalDivider(color = DarkBorder, thickness = 1.dp)
                NavigationBar(
                    modifier = Modifier.navigationBarsPadding().height(68.dp),
                    containerColor = DarkHeaderSurface,
                    tonalElevation = 0.dp
                ) {
                    tabs.forEachIndexed { index, tab ->
                        val isSelected = !showSettings && selectedTabIndex == index
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = {
                                showSettings = false
                                selectedTabIndex = index
                            },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.title,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = tab.title,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AccentCyan,
                                selectedTextColor = AccentCyan,
                                indicatorColor = PrimaryIndigo.copy(alpha = 0.28f),
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted
                            )
                        )
                    }
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
            val screenState = if (showSettings) -1 else selectedTabIndex
            Crossfade(
                targetState = screenState,
                animationSpec = tween(durationMillis = 180),
                label = "MainScreenCrossfade"
            ) { state ->
                when (state) {
                    -1 -> SettingsScreen(settings, airMouseManager, onClose = { showSettings = false })
                    0 -> TouchpadScreen(coordinator, airMouseManager, settings)
                    1 -> KeyboardScreen(coordinator, settings)
                    2 -> WritingPadScreen(coordinator, settings)
                    3 -> MediaRemoteScreen(coordinator, settings)
                    4 -> UtilitiesScreen(coordinator, settings)
                    5 -> ConnectionScreen(coordinator, settings)
                }
            }
        }
    }
}
