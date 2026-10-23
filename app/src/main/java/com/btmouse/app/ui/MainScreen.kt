package com.btmouse.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.data.SettingsRepository
import com.btmouse.app.sensors.AirMouseManager
import com.btmouse.app.ui.screens.ConnectionScreen
import com.btmouse.app.ui.screens.KeyboardScreen
import com.btmouse.app.ui.screens.MediaRemoteScreen
import com.btmouse.app.ui.screens.NumpadScreen
import com.btmouse.app.ui.screens.SettingsScreen
import com.btmouse.app.ui.screens.TouchpadScreen
import com.btmouse.app.ui.theme.AccentCyan
import com.btmouse.app.ui.theme.DarkBackground
import com.btmouse.app.ui.theme.DarkSurface
import com.btmouse.app.ui.theme.PrimaryIndigo
import com.btmouse.app.ui.theme.TextMuted
import com.btmouse.app.ui.theme.TextPrimary

sealed class NavigationTab(val title: String, val icon: ImageVector) {
    object Touchpad : NavigationTab("Mouse", Icons.Default.Mouse)
    object Keyboard : NavigationTab("Keys", Icons.Default.Keyboard)
    object Numpad : NavigationTab("Numpad", Icons.Default.Dialpad)
    object Media : NavigationTab("Media", Icons.Default.PlayCircle)
    object Connect : NavigationTab("Connect", Icons.Default.Wifi)
    object Settings : NavigationTab("Tune", Icons.Default.Settings)
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
        NavigationTab.Numpad,
        NavigationTab.Media,
        NavigationTab.Connect,
        NavigationTab.Settings
    )

    var selectedTabIndex by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                tonalElevation = 8.dp
            ) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        icon = { Icon(imageVector = tab.icon, contentDescription = tab.title) },
                        label = { Text(text = tab.title, fontSize = 11.sp) },
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
            when (selectedTabIndex) {
                0 -> TouchpadScreen(coordinator, airMouseManager, settings)
                1 -> KeyboardScreen(coordinator, settings)
                2 -> NumpadScreen(coordinator, settings)
                3 -> MediaRemoteScreen(coordinator, settings)
                4 -> ConnectionScreen(coordinator, settings)
                5 -> SettingsScreen(settings, airMouseManager)
            }
        }
    }
}
