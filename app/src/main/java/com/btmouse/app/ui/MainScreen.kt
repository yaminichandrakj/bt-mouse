package com.btmouse.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btmouse.app.connection.ActiveTransport
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.data.HidConstants
import com.btmouse.app.data.SettingsRepository
import com.btmouse.app.sensors.AirMouseManager
import com.btmouse.app.ui.components.FullscreenViewer
import com.btmouse.app.ui.components.PillStyle
import com.btmouse.app.ui.components.RoundIconButton
import com.btmouse.app.ui.components.ScreenFeedPoller
import com.btmouse.app.ui.components.StatusDot
import com.btmouse.app.ui.components.rememberScreenBitmap
import com.btmouse.app.ui.components.rememberZoomState
import com.btmouse.app.ui.screens.CamerasTab
import com.btmouse.app.ui.screens.ConnectionScreen
import com.btmouse.app.ui.screens.ControlTab
import com.btmouse.app.ui.screens.KeyboardTab
import com.btmouse.app.ui.screens.MediaTab
import com.btmouse.app.ui.screens.PcTab
import com.btmouse.app.ui.screens.QuickAppsSheet
import com.btmouse.app.ui.screens.RecordingsSheet
import com.btmouse.app.ui.screens.SettingsScreen
import com.btmouse.app.ui.screens.TasksSheet
import com.btmouse.app.ui.theme.AccentAmber
import com.btmouse.app.ui.theme.Control
import com.btmouse.app.ui.theme.Ink
import com.btmouse.app.ui.theme.OnInk
import com.btmouse.app.ui.theme.OnInkMuted
import com.btmouse.app.ui.theme.Raised
import com.btmouse.app.ui.theme.Selected
import com.btmouse.app.ui.theme.StatusGreen
import com.btmouse.app.ui.theme.StatusRed
import kotlinx.coroutines.delay

private enum class Tab(val label: String, val icon: ImageVector) {
    Control("Control", Icons.Default.Mouse),
    Keyboard("Keyboard", Icons.Default.Keyboard),
    Cameras("Cameras", Icons.Default.Videocam),
    Media("Media", Icons.Default.PlayCircle),
    Pc("PC", Icons.Default.Computer)
}

private enum class Overlay { Connect, Settings }
private enum class Sheet { Tasks, Recordings, Apps }

@Composable
fun MainScreen(
    coordinator: ConnectionCoordinator,
    airMouseManager: AirMouseManager,
    settings: SettingsRepository
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var previewOn by rememberSaveable { mutableStateOf(false) }
    var slides by rememberSaveable { mutableStateOf(false) }
    var screenFullscreen by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val screenZoom = rememberZoomState()
    val fullZoom = rememberZoomState()

    val isConnected by coordinator.isConnected.collectAsState()
    val isHealthy by coordinator.isConnectionHealthy.collectAsState()
    val transport by coordinator.activeTransport.collectAsState()
    val reconnectStatus by coordinator.reconnectStatus.collectAsState()
    val deviceName by coordinator.connectedDeviceName.collectAsState()
    val savedPcName by settings.pcName.collectAsState()
    val current = Tab.entries[tab]

    val notify: (String) -> Unit = { toast = it }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(1800)
            toast = null
        }
    }

    // One poller feeds every screen view, so the PC is asked once however many panels show it.
    val screenWanted = overlay == null && (
        screenFullscreen ||
            (previewOn && (current == Tab.Control || current == Tab.Keyboard)) ||
            (slides && current == Tab.Media)
        )
    ScreenFeedPoller(coordinator, screenWanted)

    BackHandler(enabled = overlay != null) { overlay = null }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (current == Tab.Pc) (deviceName ?: savedPcName.ifBlank { "Your PC" }) else current.label,
                    color = OnInk,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (current == Tab.Control || current == Tab.Keyboard) {
                    HeaderPill(
                        text = "Screen",
                        icon = Icons.Default.DesktopWindows,
                        on = previewOn,
                        onClick = { previewOn = !previewOn }
                    )
                }
                ConnectionPill(isConnected, isHealthy, transport) { overlay = Overlay.Connect }
                if (current == Tab.Pc) {
                    RoundIconButton(Icons.Default.Settings, "Settings", { overlay = Overlay.Settings }, style = PillStyle.Quiet, size = 44.dp)
                }
            }

            reconnectStatus?.let { status ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Control)
                        .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(status, color = OnInk, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 2)
                    if (status.contains("retry", ignoreCase = true)) {
                        TextAction("Retry") { coordinator.startAutoReconnect() }
                    }
                    TextAction("Cancel") { coordinator.cancelReconnect() }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                Crossfade(targetState = current, animationSpec = tween(160), label = "tab") { shown ->
                    when (shown) {
                        Tab.Control -> ControlTab(
                            coordinator, airMouseManager, settings, previewOn, screenZoom,
                            onScreenFullscreen = { screenFullscreen = true },
                            onOpenKeyboard = { tab = Tab.Keyboard.ordinal },
                            notify = notify
                        )
                        Tab.Keyboard -> KeyboardTab(coordinator, settings, previewOn, screenZoom, { screenFullscreen = true }, notify)
                        Tab.Cameras -> CamerasTab(coordinator) { sheet = Sheet.Recordings }
                        Tab.Media -> MediaTab(coordinator, settings, slides, { slides = it }, screenZoom) { screenFullscreen = true }
                        Tab.Pc -> PcTab(
                            coordinator, settings,
                            onOpenTasks = { sheet = Sheet.Tasks },
                            onEditApps = { sheet = Sheet.Apps },
                            notify = notify
                        )
                    }
                }
            }

            BottomBar(current) { tab = it.ordinal }
        }

        overlay?.let { shown ->
            Column(Modifier.fillMaxSize().background(Ink)) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", { overlay = null }, style = PillStyle.Quiet)
                    Spacer(Modifier.width(6.dp))
                    Text(if (shown == Overlay.Connect) "Connect" else "Settings", color = OnInk, fontSize = 26.sp, fontWeight = FontWeight.Medium)
                }
                Box(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
                    when (shown) {
                        Overlay.Connect -> ConnectionScreen(coordinator, settings)
                        Overlay.Settings -> SettingsScreen(settings, airMouseManager, onClose = { overlay = null })
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = toast != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 104.dp)
        ) {
            Text(
                toast.orEmpty(),
                color = Ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(CircleShape).background(OnInk).padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }
    }

    when (sheet) {
        Sheet.Tasks -> TasksSheet(coordinator, { sheet = null }, notify)
        Sheet.Recordings -> RecordingsSheet(coordinator) { sheet = null }
        Sheet.Apps -> QuickAppsSheet(coordinator, settings) { sheet = null }
        null -> Unit
    }

    if (screenFullscreen) {
        val frame by coordinator.screenFrame.collectAsState()
        val bitmap = rememberScreenBitmap(frame)
        val presenting = slides && current == Tab.Media
        FullscreenViewer(
            onDismiss = { screenFullscreen = false },
            state = fullZoom,
            extraControls = {
                if (presenting) {
                    Row(Modifier.align(Alignment.BottomStart).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RoundIconButton(Icons.Default.ChevronLeft, "Previous slide", { coordinator.sendKeyStroke(HidConstants.KEY_PAGE_UP, 0) })
                        RoundIconButton(Icons.Default.ChevronRight, "Next slide", { coordinator.sendKeyStroke(HidConstants.KEY_PAGE_DOWN, 0) }, style = PillStyle.Primary)
                    }
                }
            }
        ) {
            if (bitmap != null) {
                Image(bitmap, "Live view of the PC screen", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                Text(frame?.error ?: "Waiting for the PC screen…", color = OnInkMuted, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun TextAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier.height(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = OnInk, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun HeaderPill(text: String, icon: ImageVector, on: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(if (on) Selected else Control)
            .clickable(role = Role.Switch, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Icon(icon, contentDescription = null, tint = if (on) Color.White else OnInkMuted, modifier = Modifier.size(18.dp))
        Text(text, color = if (on) Color.White else OnInkMuted, fontSize = 15.sp)
    }
}

/** Which link is carrying input right now; tap it to open Connect. */
@Composable
private fun ConnectionPill(connected: Boolean, healthy: Boolean, transport: ActiveTransport, onClick: () -> Unit) {
    val live = connected && healthy && transport != ActiveTransport.NONE
    val label = when {
        live -> when (transport) {
            ActiveTransport.WIFI -> "Wi-Fi"
            ActiveTransport.BLUETOOTH_RFCOMM, ActiveTransport.BLUETOOTH_HID -> "Bluetooth"
            ActiveTransport.REMOTE_WAN -> "Internet"
            ActiveTransport.NONE -> "Connect"
        }
        connected -> "No response"
        else -> "Connect"
    }
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(Control)
            .clickable(role = Role.Button, onClickLabel = "Open Connect", onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatusDot(if (live) StatusGreen else if (connected) AccentAmber else StatusRed)
        Text(label, color = OnInk, fontSize = 15.sp, maxLines = 1)
    }
}

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Raised)
            .navigationBarsPadding()
            .height(76.dp)
            .padding(top = 10.dp)
    ) {
        Tab.entries.forEach { item ->
            val on = item == current
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(role = Role.Tab, onClickLabel = item.label) { onSelect(item) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.size(width = 60.dp, height = 32.dp).clip(CircleShape).background(if (on) Selected else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(item.icon, contentDescription = null, tint = if (on) Color.White else OnInkMuted, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    item.label,
                    color = if (on) Color.White else OnInkMuted,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1
                )
            }
        }
    }
}
