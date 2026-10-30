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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.data.SettingsRepository
import com.btmouse.app.sensors.AirMouseManager
import com.btmouse.app.ui.components.ActionTile
import com.btmouse.app.ui.components.MouseButtonsBar
import com.btmouse.app.ui.components.PillButton
import com.btmouse.app.ui.components.PillStyle
import com.btmouse.app.ui.components.ScreenPreviewPanel
import com.btmouse.app.ui.components.SegmentedControl
import com.btmouse.app.ui.components.TouchpadSurface
import com.btmouse.app.ui.components.VirtualScrollWheel
import com.btmouse.app.ui.components.ZoomState
import com.btmouse.app.ui.theme.OnInk
import com.btmouse.app.ui.theme.OnInkFaint
import com.btmouse.app.ui.theme.Panel

/**
 * Control tab: trackpad or air mouse, mouse buttons and four everyday actions.
 * With the screen preview on, the PC screen sits above a shorter trackpad so you can
 * watch the pointer move while you steer it.
 */
@Composable
fun ControlTab(
    coordinator: ConnectionCoordinator,
    airMouseManager: AirMouseManager,
    settings: SettingsRepository,
    previewOn: Boolean,
    screenZoom: ZoomState,
    onScreenFullscreen: () -> Unit,
    notify: (String) -> Unit
) {
    val pointerSpeed by settings.pointerSpeed.collectAsState()
    val scrollSpeed by settings.scrollSpeed.collectAsState()
    val invertScroll by settings.invertScroll.collectAsState()
    val tapToClick by settings.tapToClick.collectAsState()
    val haptics by settings.hapticFeedback.collectAsState()
    val airOn by airMouseManager.isAirMouseEnabled.collectAsState()
    val airSupported by airMouseManager.isSupported.collectAsState()
    val media by coordinator.mediaStatus.collectAsState()
    val stats by coordinator.systemStats.collectAsState()
    val connected by coordinator.isConnected.collectAsState()
    var lockedPending by remember { mutableStateOf(false) }

    // Keep the play, mute and lock buttons true to the PC while this tab is open.
    LaunchedEffect(connected) {
        var tick = 0
        while (connected) {
            coordinator.queryMedia()
            if (tick % 2 == 0) coordinator.querySystemStats()
            tick++
            delay(2000)
        }
    }
    LaunchedEffect(stats?.locked) {
        if (stats?.locked != null) lockedPending = false
    }
    LaunchedEffect(lockedPending) {
        if (lockedPending) {
            delay(8000)
            lockedPending = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (airSupported) {
            SegmentedControl(
                options = listOf("Trackpad", "Air mouse"),
                selectedIndex = if (airOn) 1 else 0,
                onSelect = { index -> if ((index == 1) != airOn) airMouseManager.toggleAirMouse() }
            )
        }

        if (previewOn) {
            ScreenPreviewPanel(coordinator, screenZoom, onScreenFullscreen)
        }

        if (airOn) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Panel)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Air mouse is on", color = OnInk, fontSize = 18.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Point and tilt the phone to move the pointer.\nUse the buttons below to click.",
                    color = OnInkFaint, fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                PillButton("Back to trackpad", { airMouseManager.toggleAirMouse() }, style = PillStyle.Outline)
            }
        } else {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TouchpadSurface(
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    pointerSpeed = pointerSpeed,
                    scrollSpeed = scrollSpeed,
                    invertScroll = invertScroll,
                    tapToClick = tapToClick,
                    hapticsEnabled = haptics,
                    hint = if (previewOn) "Drag here and watch the pointer above"
                    else "Move with one finger · tap to click\nTwo fingers to scroll · double-tap and hold to drag",
                    onMouseMove = { dx, dy, buttons, v, h -> coordinator.sendMouseMove(dx, dy, buttons, v, h) },
                    onMouseButton = { coordinator.sendMouseButton(it) }
                )
                VirtualScrollWheel(
                    scrollSpeed = scrollSpeed,
                    invertScroll = invertScroll,
                    hapticsEnabled = haptics,
                    onScroll = { coordinator.sendMouseMove(0, 0, 0, it, 0) }
                )
            }
        }

        MouseButtonsBar(hapticsEnabled = haptics, onButtonChange = { coordinator.sendMouseButton(it) })

        // Each action shows what the PC is doing now and lights up when it is on.
        val playing = media.status.equals("Playing", ignoreCase = true)
        val locked = lockedPending || stats?.locked == true
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionTile(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                when {
                    playing -> "Pause"
                    media.hasMedia -> "Play"
                    else -> "Play/Pause"
                },
                { coordinator.sendMediaToggle() },
                Modifier.weight(1f),
                style = if (playing) PillStyle.Selected else PillStyle.Quiet
            )
            ActionTile(
                if (media.isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                if (media.isMuted) "Muted" else "Mute PC",
                { coordinator.setMute(!media.isMuted) },
                Modifier.weight(1f),
                style = if (media.isMuted) PillStyle.Danger else PillStyle.Quiet
            )
            ActionTile(
                if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                if (locked) "Locked" else "Lock PC",
                {
                    if (locked) {
                        notify("Unlock it on the PC itself")
                    } else {
                        coordinator.systemPower("lock")
                        lockedPending = true
                        notify("Locking your PC")
                    }
                },
                Modifier.weight(1f),
                style = if (locked) PillStyle.Success else PillStyle.Quiet
            )
        }
    }
}
