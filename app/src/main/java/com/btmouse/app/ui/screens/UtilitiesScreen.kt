package com.btmouse.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.data.SettingsRepository
import com.btmouse.app.ui.theme.AccentCyan
import com.btmouse.app.ui.theme.DarkSurface
import com.btmouse.app.ui.theme.PrimaryIndigo
import com.btmouse.app.ui.theme.TextPrimary

@Composable
fun UtilitiesScreen(coordinator: ConnectionCoordinator, settings: SettingsRepository) {
    var activeSection by remember { mutableStateOf("apps") }
    val hapticsEnabled by settings.hapticFeedback.collectAsState()
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("apps" to "Quick Apps", "tasks" to "Tasks & Stats", "camera" to "Camera & Mic", "power" to "Power & Lock").forEach { (key, label) ->
                FilterChip(
                    selected = activeSection == key,
                    onClick = { activeSection = key },
                    label = { Text(label, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PrimaryIndigo.copy(alpha = 0.35f),
                        selectedLabelColor = AccentCyan,
                        containerColor = DarkSurface,
                        labelColor = TextPrimary
                    )
                )
            }
        }
        when (activeSection) {
            "tasks" -> TasksAndHardwareSection(coordinator)
            "camera" -> CameraAndRecorderSection(coordinator)
            "power" -> PowerAndUnlockSection(coordinator)
            else -> QuickAppsSection(coordinator, settings, hapticsEnabled)
        }
    }
}
