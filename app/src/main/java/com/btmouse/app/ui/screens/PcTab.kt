package com.btmouse.app.ui.screens

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.connection.CustomAppEntry
import com.btmouse.app.connection.ProcessInfo
import com.btmouse.app.data.SettingsRepository
import com.btmouse.app.ui.components.ActionTile
import com.btmouse.app.ui.components.AppOrPlatformIconBadge
import com.btmouse.app.ui.components.PillButton
import com.btmouse.app.ui.components.PillStyle
import com.btmouse.app.ui.components.SectionHeader
import com.btmouse.app.ui.theme.Control
import com.btmouse.app.ui.theme.Hairline
import com.btmouse.app.ui.theme.Ink
import com.btmouse.app.ui.theme.OnInk
import com.btmouse.app.ui.theme.OnInkMuted
import com.btmouse.app.ui.theme.OnInkSoft
import com.btmouse.app.ui.theme.Outline
import com.btmouse.app.ui.theme.Panel
import com.btmouse.app.ui.theme.ControlHigh
import com.btmouse.app.ui.theme.Selected
import com.btmouse.app.ui.theme.StatusGreen
import com.btmouse.app.ui.theme.StatusRedText
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.delay

/** One launchable shortcut, from the PC's quick apps or the user's own commands. */
private data class Launchable(val name: String, val target: String, val iconBase64: String, val brandKey: String)

/** PC tab: live stats, power, quick apps and the busiest tasks, without scrolling. */
@Composable
fun PcTab(
    coordinator: ConnectionCoordinator,
    settings: SettingsRepository,
    onOpenTasks: () -> Unit,
    onEditApps: () -> Unit,
    notify: (String) -> Unit
) {
    val connected by coordinator.isConnected.collectAsState()
    val stats by coordinator.systemStats.collectAsState()
    val processes by coordinator.runningProcesses.collectAsState()
    val installed by coordinator.installedApps.collectAsState()
    val icons by coordinator.appIcons.collectAsState()
    val customJson by settings.customAppsJson.collectAsState()
    var confirm by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        coordinator.queryInstalledApps()
        var tick = 0
        while (true) {
            coordinator.querySystemStats()
            if (tick % 2 == 0) coordinator.queryRunningProcesses()
            tick++
            delay(2500)
        }
    }

    val custom: List<CustomAppEntry> = remember(customJson) {
        runCatching { Gson().fromJson<List<CustomAppEntry>>(customJson, object : TypeToken<List<CustomAppEntry>>() {}.type) }
            .getOrNull() ?: emptyList()
    }
    val apps = remember(installed, custom, icons) {
        installed.filter { it.isQuick }.map { Launchable(it.name, it.path, it.iconBase64.ifBlank { icons[it.path].orEmpty() }, it.brandKey) } +
            custom.map { Launchable(it.name, it.cmd, it.iconBase64.ifBlank { icons[it.cmd].orEmpty() }, it.brandKey) }
    }
    LaunchedEffect(apps.map { it.target }) {
        val missing = apps.filter { it.iconBase64.isBlank() }.map { it.target }
        if (connected && missing.isNotEmpty()) coordinator.queryAppIcons(missing)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val s = stats
            StatTile("CPU", s?.cpu, OnInk, Modifier.weight(1f))
            StatTile("Memory", s?.ram, OnInk, Modifier.weight(1f))
            StatTile("Battery", s?.battery?.takeIf { it >= 0 }, StatusGreen, Modifier.weight(1f))
            StatTile("Disk", s?.disk, OnInk, Modifier.weight(1f))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionTile(Icons.Default.Lock, "Lock", { coordinator.systemPower("lock"); notify("Locking your PC") },
                Modifier.weight(1f), style = PillStyle.Selected, height = 76.dp)
            ActionTile(Icons.Default.Bedtime, "Sleep", { confirm = "sleep" }, Modifier.weight(1f), style = PillStyle.Tonal, height = 76.dp)
            ActionTile(Icons.Default.RestartAlt, "Restart", { confirm = "restart" }, Modifier.weight(1f), style = PillStyle.Tonal, height = 76.dp)
            ActionTile(Icons.Default.PowerSettingsNew, "Shut down", { confirm = "shutdown" }, Modifier.weight(1f),
                style = PillStyle.Tonal, height = 76.dp, tint = StatusRedText)
        }

        Column {
            SectionHeader("Quick apps", action = "Edit", onAction = onEditApps)
            val shown = apps.take(7)
            (shown.map<Launchable, Launchable?> { it } + listOf(null)).chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { app ->
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(role = Role.Button) {
                                    if (app == null) onEditApps() else {
                                        coordinator.launchApp(app.target)
                                        notify("Opening ${app.name} on your PC")
                                    }
                                }
                                .padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (app == null) {
                                Box(Modifier.size(56.dp).clip(RoundedCornerShape(20.dp)).background(Control), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Add, contentDescription = null, tint = OnInkSoft)
                                }
                            } else {
                                AppOrPlatformIconBadge(name = app.name, pathOrCmd = app.target, iconBase64 = app.iconBase64,
                                    brandKey = app.brandKey, size = 56.dp, cornerRadius = 20.dp)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(app?.name ?: "Add", color = OnInk, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Panel).padding(vertical = 4.dp)
        ) {
            Row(Modifier.padding(start = 16.dp, end = 10.dp)) {
                SectionHeader("Using the most CPU", action = "All tasks", onAction = onOpenTasks)
            }
            val top = processes.sortedByDescending { it.cpu }.take(2)
            if (top.isEmpty()) {
                Text(if (connected) "Loading tasks…" else "Connect to your PC to see its tasks",
                    color = OnInkMuted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            }
            top.forEach { p ->
                Row(Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, color = OnInk, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("%.1f%%".format(p.cpu), color = OnInkMuted, fontSize = 14.sp)
                }
            }
        }
    }

    confirm?.let { action ->
        val (title, verb) = when (action) {
            "restart" -> "Restart your PC?" to "Restart"
            "shutdown" -> "Shut down your PC?" to "Shut down"
            else -> "Put your PC to sleep?" to "Sleep"
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = Control,
            title = { Text(title, color = OnInk) },
            text = { Text(if (action == "sleep") "The phone disconnects until the PC wakes." else "Unsaved work on the PC may be lost.", color = OnInkMuted) },
            confirmButton = {
                PillButton(verb, {
                    coordinator.systemPower(action)
                    notify(if (action == "sleep") "Putting your PC to sleep" else "$verb in progress")
                    confirm = null
                }, style = if (action == "sleep") PillStyle.Selected else PillStyle.Danger)
            },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel", color = OnInk) } }
        )
    }
}

@Composable
private fun StatTile(label: String, value: Float?, bar: Color, modifier: Modifier) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(20.dp)).background(Panel).padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, color = OnInkMuted, fontSize = 13.sp, maxLines = 1)
        Text(value?.let { "${it.toInt()}%" } ?: "—", color = OnInk, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).clip(CircleShape).background(Hairline)) {
            Box(Modifier.fillMaxWidth(((value ?: 0f) / 100f).coerceIn(0f, 1f)).height(3.dp).clip(CircleShape).background(bar))
        }
    }
}

/** All running tasks: sort, tap one for Switch to and End task. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksSheet(coordinator: ConnectionCoordinator, onDismiss: () -> Unit, notify: (String) -> Unit) {
    val processes by coordinator.runningProcesses.collectAsState()
    var sort by remember { mutableStateOf("cpu") }
    var selected by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            coordinator.queryRunningProcesses()
            delay(4000)
        }
    }
    val rows: List<ProcessInfo> = remember(processes, sort) {
        when (sort) {
            "mem" -> processes.sortedByDescending { it.ram }
            "name" -> processes.sortedBy { it.name.lowercase() }
            else -> processes.sortedByDescending { it.cpu }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Panel) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text("Running tasks", color = OnInk, fontSize = 24.sp, fontWeight = FontWeight.Medium)
            Text("${processes.size} shown · tap one for actions", color = OnInkMuted, fontSize = 14.sp)
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("cpu" to "CPU", "mem" to "Memory", "name" to "Name").forEach { (key, label) ->
                    val on = sort == key
                    Box(
                        Modifier
                            .height(36.dp)
                            .clip(CircleShape)
                            .background(if (on) Selected else Color.Transparent)
                            .then(if (on) Modifier else Modifier.border(1.dp, Outline, CircleShape))
                            .clickable(role = Role.Tab) { sort = key }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(label, color = if (on) Color.White else OnInkSoft, fontSize = 14.sp) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp)) {
                Text("Name", color = OnInkMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("CPU", color = OnInkMuted, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
                Text("Memory", color = OnInkMuted, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.width(80.dp))
            }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 12.dp).padding(bottom = 24.dp)) {
            items(rows, key = { it.pid }) { p ->
                val open = selected == p.pid
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(if (open) ControlHigh else Color.Transparent)) {
                    Row(
                        Modifier.fillMaxWidth().height(52.dp).clickable { selected = if (open) null else p.pid }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(p.name, color = OnInk, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("%.1f%%".format(p.cpu), color = OnInk, fontSize = 15.sp, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
                        Text(formatMemory(p.ram), color = OnInkSoft, fontSize = 15.sp, textAlign = TextAlign.End, modifier = Modifier.width(80.dp))
                    }
                    if (open) {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 14.dp), horizontalArrangement = Arrangement.End) {
                            PillButton("End task", {
                                coordinator.killProcess(p.pid)
                                notify("Ending ${p.name}")
                                selected = null
                            }, style = PillStyle.Danger, height = 42.dp)
                        }
                    }
                }
            }
        }
    }
}

private fun formatMemory(mb: Float): String = if (mb >= 1000f) "%.1f GB".format(mb / 1024f) else "${mb.toInt()} MB"

/** Add, remove and run quick apps; the full editor from the PC companion's app list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAppsSheet(coordinator: ConnectionCoordinator, settings: SettingsRepository, onDismiss: () -> Unit) {
    val haptics by settings.hapticFeedback.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Ink) {
        Column(Modifier.fillMaxWidth().heightIn(max = 700.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            QuickAppsSection(coordinator, settings, haptics)
        }
    }
}
