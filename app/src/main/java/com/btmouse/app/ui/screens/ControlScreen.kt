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
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
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
    onOpenKeyboard: () -> Unit,
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

        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ActionTile(Icons.Default.Keyboard, "Type", onOpenKeyboard, Modifier.weight(1f))
            ActionTile(Icons.Default.PlayArrow, "Play/Pause", { coordinator.sendMediaToggle() }, Modifier.weight(1f))
            ActionTile(
                if (media.isMuted) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                if (media.isMuted) "Unmute PC" else "Mute PC",
                { coordinator.setMute(!media.isMuted) },
                Modifier.weight(1f)
            )
            ActionTile(Icons.Default.Lock, "Lock PC", {
                coordinator.systemPower("lock")
                notify("Locking your PC")
            }, Modifier.weight(1f))
        }
    }
}
