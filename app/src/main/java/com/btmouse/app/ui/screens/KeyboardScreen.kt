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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.data.HidConstants
import com.btmouse.app.data.SettingsRepository
import com.btmouse.app.ui.components.KeyButton
import com.btmouse.app.ui.theme.AccentCyan
import com.btmouse.app.ui.theme.DarkBackground
import com.btmouse.app.ui.theme.DarkBorder
import com.btmouse.app.ui.theme.DarkSurfaceVariant
import com.btmouse.app.ui.theme.PrimaryIndigo
import com.btmouse.app.ui.theme.TextPrimary
import com.btmouse.app.ui.theme.TextSecondary

@Composable
fun KeyboardScreen(
    coordinator: ConnectionCoordinator,
    settings: SettingsRepository
) {
    val hapticsEnabled by settings.hapticFeedback.collectAsState()
    var inputText by remember { mutableStateOf("") }

    // Modifier toggles
    var isCtrlActive by remember { mutableStateOf(false) }
    var isAltActive by remember { mutableStateOf(false) }
    var isShiftActive by remember { mutableStateOf(false) }
    var isWinActive by remember { mutableStateOf(false) }

    fun currentModifierByte(): Byte {
        var mod: Byte = 0
        if (isCtrlActive) mod = (mod.toInt() or HidConstants.MODIFIER_LEFT_CTRL.toInt()).toByte()
        if (isAltActive) mod = (mod.toInt() or HidConstants.MODIFIER_LEFT_ALT.toInt()).toByte()
        if (isShiftActive) mod = (mod.toInt() or HidConstants.MODIFIER_LEFT_SHIFT.toInt()).toByte()
        if (isWinActive) mod = (mod.toInt() or HidConstants.MODIFIER_LEFT_GUI.toInt()).toByte()
        return mod
    }

    fun triggerKey(keyCode: Byte) {
        val mod = currentModifierByte()
        coordinator.sendKeyStroke(keyCode, mod)
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(12.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Text Input & Send
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type text to send directly to PC...", color = TextSecondary, fontSize = 13.sp) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryIndigo,
                    unfocusedBorderColor = DarkBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = DarkSurfaceVariant,
                    unfocusedContainerColor = DarkSurfaceVariant
                ),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (inputText.isNotEmpty()) {
                        coordinator.sendText(inputText)
                        inputText = ""
                    }
                },
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PrimaryIndigo)
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "Send",
                    tint = TextPrimary
                )
            }
        }

        // Quick Windows Shortcuts
        Text("PC Shortcuts", color = TextSecondary, fontSize = 12.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Copy",
                isAccent = true,
                hapticsEnabled = hapticsEnabled,
                onDown = { coordinator.sendKeyStroke(HidConstants.KEY_C, HidConstants.MODIFIER_LEFT_CTRL) },
                onUp = {}
            )
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Paste",
                isAccent = true,
                hapticsEnabled = hapticsEnabled,
                onDown = { coordinator.sendKeyStroke(HidConstants.KEY_V, HidConstants.MODIFIER_LEFT_CTRL) },
                onUp = {}
            )
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Undo",
                isAccent = true,
                hapticsEnabled = hapticsEnabled,
                onDown = { coordinator.sendKeyStroke(HidConstants.KEY_Z, HidConstants.MODIFIER_LEFT_CTRL) },
                onUp = {}
            )
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Select All",
                isAccent = true,
                hapticsEnabled = hapticsEnabled,
                onDown = { coordinator.sendKeyStroke(HidConstants.KEY_A, HidConstants.MODIFIER_LEFT_CTRL) },
                onUp = {}
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Alt+Tab",
                isAccent = true,
                hapticsEnabled = hapticsEnabled,
                onDown = { coordinator.sendKeyStroke(HidConstants.KEY_TAB, HidConstants.MODIFIER_LEFT_ALT) },
                onUp = {}
            )
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Desktop",
                isAccent = true,
                hapticsEnabled = hapticsEnabled,
                onDown = { coordinator.sendKeyStroke(HidConstants.KEY_D, HidConstants.MODIFIER_LEFT_GUI) },
                onUp = {}
            )
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Task Mgr",
                isAccent = true,
                hapticsEnabled = hapticsEnabled,
                onDown = {
                    val mod = (HidConstants.MODIFIER_LEFT_CTRL.toInt() or HidConstants.MODIFIER_LEFT_SHIFT.toInt()).toByte()
                    coordinator.sendKeyStroke(HidConstants.KEY_ESC, mod)
                },
                onUp = {}
            )
        }

        // Modifier Keys (Latchable toggles)
        Text("Modifiers", color = TextSecondary, fontSize = 12.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Ctrl",
                isActive = isCtrlActive,
                hapticsEnabled = hapticsEnabled,
                onDown = { isCtrlActive = !isCtrlActive },
                onUp = {}
            )
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Alt",
                isActive = isAltActive,
                hapticsEnabled = hapticsEnabled,
                onDown = { isAltActive = !isAltActive },
                onUp = {}
            )
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Shift",
                isActive = isShiftActive,
                hapticsEnabled = hapticsEnabled,
                onDown = { isShiftActive = !isShiftActive },
                onUp = {}
            )
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Win",
                isActive = isWinActive,
                hapticsEnabled = hapticsEnabled,
                onDown = { isWinActive = !isWinActive },
                onUp = {}
            )
        }

        // Function Row
        Text("Function Keys", color = TextSecondary, fontSize = 12.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            KeyButton(modifier = Modifier.weight(1.1f), label = "Esc", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_ESC) }, onUp = {})
            KeyButton(modifier = Modifier.weight(1f), label = "F1", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_F1) }, onUp = {})
            KeyButton(modifier = Modifier.weight(1f), label = "F2", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_F2) }, onUp = {})
            KeyButton(modifier = Modifier.weight(1f), label = "F3", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_F3) }, onUp = {})
            KeyButton(modifier = Modifier.weight(1f), label = "F4", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_F4) }, onUp = {})
            KeyButton(modifier = Modifier.weight(1f), label = "F5", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_F5) }, onUp = {})
            KeyButton(modifier = Modifier.weight(1f), label = "F6", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_F6) }, onUp = {})
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            KeyButton(modifier = Modifier.weight(1f), label = "F7", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_F7) }, onUp = {})
            KeyButton(modifier = Modifier.weight(1f), label = "F8", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_F8) }, onUp = {})
            KeyButton(modifier = Modifier.weight(1f), label = "F9", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_F9) }, onUp = {})
            KeyButton(modifier = Modifier.weight(1f), label = "F10", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_F10) }, onUp = {})
            KeyButton(modifier = Modifier.weight(1f), label = "F11", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_F11) }, onUp = {})
            KeyButton(modifier = Modifier.weight(1f), label = "F12", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_F12) }, onUp = {})
            KeyButton(modifier = Modifier.weight(1.1f), label = "Del", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_DELETE) }, onUp = {})
        }

        // Navigation cluster
        Text("Navigation & Editing", color = TextSecondary, fontSize = 12.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            KeyButton(modifier = Modifier.weight(1.2f), label = "Tab", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_TAB) }, onUp = {})
            KeyButton(modifier = Modifier.weight(1f), label = "Home", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_HOME) }, onUp = {})
            KeyButton(modifier = Modifier.weight(1f), label = "PgUp", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_PAGE_UP) }, onUp = {})
            KeyButton(modifier = Modifier.weight(1f), label = "PgDn", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_PAGE_DOWN) }, onUp = {})
            KeyButton(modifier = Modifier.weight(1f), label = "End", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_END) }, onUp = {})
            KeyButton(modifier = Modifier.weight(1.4f), label = "Backspace", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_BACKSPACE) }, onUp = {})
        }

        // Arrow cluster + Enter
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeyButton(modifier = Modifier.weight(1.8f), label = "Space", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_SPACE) }, onUp = {})
            KeyButton(modifier = Modifier.weight(1f), label = "←", fontSize = 18.sp, hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_LEFT_ARROW) }, onUp = {})
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                KeyButton(modifier = Modifier.fillMaxWidth(), label = "↑", fontSize = 18.sp, minHeight = 32.dp, hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_UP_ARROW) }, onUp = {})
                KeyButton(modifier = Modifier.fillMaxWidth(), label = "↓", fontSize = 18.sp, minHeight = 32.dp, hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_DOWN_ARROW) }, onUp = {})
            }
            KeyButton(modifier = Modifier.weight(1f), label = "→", fontSize = 18.sp, hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_RIGHT_ARROW) }, onUp = {})
            KeyButton(modifier = Modifier.weight(1.5f), label = "Enter", isAccent = true, hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_ENTER) }, onUp = {})
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}
