package com.btmouse.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import com.btmouse.app.ui.screens.WritingPadScreen
import com.btmouse.app.ui.theme.AccentCyan
import com.btmouse.app.ui.theme.AccentEmerald
import com.btmouse.app.ui.theme.AccentRose
import com.btmouse.app.ui.theme.DarkBackground
import com.btmouse.app.ui.theme.DarkSurface
import com.btmouse.app.ui.theme.PrimaryIndigo
import com.btmouse.app.ui.theme.TextMuted
import com.btmouse.app.ui.theme.TextPrimary

sealed class NavigationTab(val title: String, val icon: ImageVector) {
    object Touchpad : NavigationTab("Mouse", Icons.Default.Mouse)
    object Keyboard : NavigationTab("Keys", Icons.Default.Keyboard)
    object WritingPad : NavigationTab("Pen", Icons.Default.Draw)
    object Media : NavigationTab("Media", Icons.Default.PlayCircle)
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
        NavigationTab.Connect
    )

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var isSettingsOpen by remember { mutableStateOf(false) }
    val isConnected by coordinator.isConnected.collectAsState()

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurface)
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
                    Text(
                        text = if (isSettingsOpen) "SETTINGS & UPDATES" else "BT MOUSE & KEYBOARD",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(
                    onClick = { isSettingsOpen = !isSettingsOpen },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (isSettingsOpen) Icons.Default.Close else Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = if (isSettingsOpen) AccentCyan else TextMuted
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                tonalElevation = 8.dp
            ) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = !isSettingsOpen && selectedTabIndex == index,
                        onClick = {
                            isSettingsOpen = false
                            selectedTabIndex = index
                        },
                        icon = { Icon(imageVector = tab.icon, contentDescription = tab.title) },
                        label = { Text(text = tab.title, fontSize = 11.sp, maxLines = 1, softWrap = false) },
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
            if (isSettingsOpen) {
                SettingsScreen(settings, airMouseManager, onClose = { isSettingsOpen = false })
            } else {
                when (selectedTabIndex) {
                    0 -> TouchpadScreen(coordinator, airMouseManager, settings)
                    1 -> KeyboardScreen(coordinator, settings)
                    2 -> WritingPadScreen(coordinator, settings)
                    3 -> MediaRemoteScreen(coordinator, settings)
                    4 -> ConnectionScreen(coordinator, settings)
                }
            }
        }
    }
}
