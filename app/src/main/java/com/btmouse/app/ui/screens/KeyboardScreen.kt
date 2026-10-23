package com.btmouse.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material.icons.filled.KeyboardTab
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.data.HidConstants
import com.btmouse.app.data.SettingsRepository
import com.btmouse.app.ui.components.KeyButton
import com.btmouse.app.ui.theme.AccentCyan
import com.btmouse.app.ui.theme.AccentEmerald
import com.btmouse.app.ui.theme.DarkBackground
import com.btmouse.app.ui.theme.DarkBorder
import com.btmouse.app.ui.theme.DarkSurface
import com.btmouse.app.ui.theme.DarkSurfaceVariant
import com.btmouse.app.ui.theme.PrimaryIndigo
import com.btmouse.app.ui.theme.TextMuted
import com.btmouse.app.ui.theme.TextPrimary
import com.btmouse.app.ui.theme.TextSecondary

@Composable
fun KeyboardScreen(
    coordinator: ConnectionCoordinator,
    settings: SettingsRepository
) {
    val hapticsEnabled by settings.hapticFeedback.collectAsState()
    var inputText by remember { mutableStateOf("") }
    var isLiveTypingEnabled by remember { mutableStateOf(true) }
    var isNumpadMode by remember { mutableStateOf(false) }

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

    // Handles live keystroke streaming
    fun handleTextChange(newText: String) {
        if (!isLiveTypingEnabled) {
            inputText = newText
            return
        }

        val oldText = inputText
        if (newText.length > oldText.length) {
            if (newText.startsWith(oldText)) {
                // Characters added at the end
                val added = newText.substring(oldText.length)
                coordinator.sendText(added)
            } else {
                // Full replacement (e.g. paste or autocorrect suggestion)
                repeat(oldText.length) {
                    coordinator.sendKeyStroke(HidConstants.KEY_BACKSPACE, 0)
                }
                coordinator.sendText(newText)
            }
        } else if (newText.length < oldText.length) {
            if (oldText.startsWith(newText)) {
                // Backspaced characters
                val deletedCount = oldText.length - newText.length
                repeat(deletedCount) {
                    coordinator.sendKeyStroke(HidConstants.KEY_BACKSPACE, 0)
                }
            } else {
                // Modified replacement
                repeat(oldText.length) {
                    coordinator.sendKeyStroke(HidConstants.KEY_BACKSPACE, 0)
                }
                coordinator.sendText(newText)
            }
        }
        inputText = newText
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
        // Typing Mode & Sub-tab Selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = !isNumpadMode,
                    onClick = { isNumpadMode = false },
                    label = { Text("⌨️ Keyboard", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PrimaryIndigo.copy(alpha = 0.35f),
                        selectedLabelColor = AccentCyan,
                        containerColor = DarkSurface
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                FilterChip(
                    selected = isNumpadMode,
                    onClick = { isNumpadMode = true },
                    label = { Text("🔢 Numpad", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PrimaryIndigo.copy(alpha = 0.35f),
                        selectedLabelColor = AccentCyan,
                        containerColor = DarkSurface
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
            }

            if (!isNumpadMode) {
                FilterChip(
                    selected = isLiveTypingEnabled,
                    onClick = { isLiveTypingEnabled = !isLiveTypingEnabled },
                    label = { Text(if (isLiveTypingEnabled) "⚡ Live" else "📝 Batch", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PrimaryIndigo.copy(alpha = 0.35f),
                        selectedLabelColor = if (isLiveTypingEnabled) AccentCyan else TextPrimary,
                        containerColor = DarkSurface
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        }

        if (isNumpadMode) {
            NumpadScreen(coordinator, settings)
        } else {

        // Live Text Input Box
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { handleTextChange(it) },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (isLiveTypingEnabled) "Live typing directly streams to PC as you type..."
                        else "Type text here and tap send...",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = if (isLiveTypingEnabled) AccentCyan else PrimaryIndigo,
                    unfocusedBorderColor = DarkBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = DarkSurfaceVariant,
                    unfocusedContainerColor = DarkSurfaceVariant
                ),
                shape = RoundedCornerShape(12.dp),
                trailingIcon = {
                    if (inputText.isNotEmpty()) {
                        IconButton(onClick = { inputText = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextMuted)
                        }
                    }
                },
                singleLine = false,
                maxLines = 2
            )

            if (!isLiveTypingEnabled) {
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
        }

        // Quick PC Shortcuts with Icons - 4-Column Responsive Layout
        Text("Quick Shortcuts", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)

        // Row 1: Edit & History
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Copy",
                icon = Icons.Default.ContentCopy,
                isAccent = true,
                hapticsEnabled = hapticsEnabled,
                onDown = { coordinator.sendKeyStroke(HidConstants.KEY_C, HidConstants.MODIFIER_LEFT_CTRL) }
            )
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Paste",
                icon = Icons.Default.ContentPaste,
                isAccent = true,
                hapticsEnabled = hapticsEnabled,
                onDown = { coordinator.sendKeyStroke(HidConstants.KEY_V, HidConstants.MODIFIER_LEFT_CTRL) }
            )
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Cut",
                icon = Icons.Default.ContentCut,
                isAccent = true,
                hapticsEnabled = hapticsEnabled,
                onDown = { coordinator.sendKeyStroke(HidConstants.KEY_X, HidConstants.MODIFIER_LEFT_CTRL) }
            )
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Undo",
                icon = Icons.Default.Undo,
                isAccent = true,
                hapticsEnabled = hapticsEnabled,
                onDown = { coordinator.sendKeyStroke(HidConstants.KEY_Z, HidConstants.MODIFIER_LEFT_CTRL) }
            )
        }

        // Row 2: Windows Navigation
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "All",
                icon = Icons.Default.SelectAll,
                isAccent = true,
                hapticsEnabled = hapticsEnabled,
                onDown = { coordinator.sendKeyStroke(HidConstants.KEY_A, HidConstants.MODIFIER_LEFT_CTRL) }
            )
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Switch",
                icon = Icons.Default.Layers,
                isAccent = true,
                hapticsEnabled = hapticsEnabled,
                onDown = { coordinator.sendKeyStroke(HidConstants.KEY_TAB, HidConstants.MODIFIER_LEFT_ALT) }
            )
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Desktop",
                icon = Icons.Default.DesktopWindows,
                isAccent = true,
                hapticsEnabled = hapticsEnabled,
                onDown = { coordinator.sendKeyStroke(HidConstants.KEY_D, HidConstants.MODIFIER_LEFT_GUI) }
            )
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Lock",
                icon = Icons.Default.Lock,
                isAccent = true,
                hapticsEnabled = hapticsEnabled,
                onDown = { coordinator.sendKeyStroke(HidConstants.KEY_L, HidConstants.MODIFIER_LEFT_GUI) }
            )
        }

        // Row 3: Tools & Utilities
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Task Mgr",
                icon = Icons.Default.Speed,
                isAccent = true,
                hapticsEnabled = hapticsEnabled,
                onDown = {
                    val mod = (HidConstants.MODIFIER_LEFT_CTRL.toInt() or HidConstants.MODIFIER_LEFT_SHIFT.toInt()).toByte()
                    coordinator.sendKeyStroke(HidConstants.KEY_ESC, mod)
                }
            )
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Files",
                icon = Icons.Default.Folder,
                isAccent = true,
                hapticsEnabled = hapticsEnabled,
                onDown = { coordinator.sendKeyStroke(HidConstants.KEY_E, HidConstants.MODIFIER_LEFT_GUI) }
            )
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Search",
                icon = Icons.Default.Search,
                isAccent = true,
                hapticsEnabled = hapticsEnabled,
                onDown = { coordinator.sendKeyStroke(HidConstants.KEY_S, HidConstants.MODIFIER_LEFT_GUI) }
            )
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Esc",
                icon = Icons.Default.Close,
                hapticsEnabled = hapticsEnabled,
                onDown = { triggerKey(HidConstants.KEY_ESC) }
            )
        }

        // Row 4: Controls & Navigation
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Tab",
                icon = Icons.Default.KeyboardTab,
                hapticsEnabled = hapticsEnabled,
                onDown = { triggerKey(HidConstants.KEY_TAB) }
            )
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Bksp",
                icon = Icons.Default.Backspace,
                hapticsEnabled = hapticsEnabled,
                onDown = { triggerKey(HidConstants.KEY_BACKSPACE) }
            )
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Del",
                icon = Icons.Default.Delete,
                hapticsEnabled = hapticsEnabled,
                onDown = { triggerKey(HidConstants.KEY_DELETE) }
            )
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Enter",
                icon = Icons.Default.KeyboardReturn,
                isAccent = true,
                hapticsEnabled = hapticsEnabled,
                onDown = { triggerKey(HidConstants.KEY_ENTER) }
            )
        }

        // Modifier Keys (Latchable toggles)
        Text("Modifiers", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Ctrl",
                isActive = isCtrlActive,
                hapticsEnabled = hapticsEnabled,
                onDown = { isCtrlActive = !isCtrlActive }
            )
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Alt",
                isActive = isAltActive,
                hapticsEnabled = hapticsEnabled,
                onDown = { isAltActive = !isAltActive }
            )
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Shift",
                isActive = isShiftActive,
                hapticsEnabled = hapticsEnabled,
                onDown = { isShiftActive = !isShiftActive }
            )
            KeyButton(
                modifier = Modifier.weight(1f),
                label = "Win",
                isActive = isWinActive,
                hapticsEnabled = hapticsEnabled,
                onDown = { isWinActive = !isWinActive }
            )
        }

        // Function Row
        Text("Function Keys", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            KeyButton(modifier = Modifier.weight(1f), label = "F1", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_F1) })
            KeyButton(modifier = Modifier.weight(1f), label = "F2", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_F2) })
            KeyButton(modifier = Modifier.weight(1f), label = "F3", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_F3) })
            KeyButton(modifier = Modifier.weight(1f), label = "F4", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_F4) })
            KeyButton(modifier = Modifier.weight(1f), label = "F5", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_F5) })
            KeyButton(modifier = Modifier.weight(1f), label = "F6", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_F6) })
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            KeyButton(modifier = Modifier.weight(1f), label = "F7", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_F7) })
            KeyButton(modifier = Modifier.weight(1f), label = "F8", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_F8) })
            KeyButton(modifier = Modifier.weight(1f), label = "F9", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_F9) })
            KeyButton(modifier = Modifier.weight(1f), label = "F10", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_F10) })
            KeyButton(modifier = Modifier.weight(1f), label = "F11", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_F11) })
            KeyButton(modifier = Modifier.weight(1f), label = "F12", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_F12) })
        }

        // Navigation cluster
        Text("Navigation & Arrows", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            KeyButton(modifier = Modifier.weight(1f), label = "Home", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_HOME) })
            KeyButton(modifier = Modifier.weight(1f), label = "PgUp", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_PAGE_UP) })
            KeyButton(modifier = Modifier.weight(1f), label = "PgDn", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_PAGE_DOWN) })
            KeyButton(modifier = Modifier.weight(1f), label = "End", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_END) })
        }

        // Space + Arrows + Enter
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeyButton(modifier = Modifier.weight(1.8f), label = "Space", hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_SPACE) })
            KeyButton(modifier = Modifier.weight(1f), label = "←", fontSize = 18.sp, hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_LEFT_ARROW) })
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                KeyButton(modifier = Modifier.fillMaxWidth(), label = "↑", fontSize = 18.sp, minHeight = 32.dp, hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_UP_ARROW) })
                KeyButton(modifier = Modifier.fillMaxWidth(), label = "↓", fontSize = 18.sp, minHeight = 32.dp, hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_DOWN_ARROW) })
            }
            KeyButton(modifier = Modifier.weight(1f), label = "→", fontSize = 18.sp, hapticsEnabled = hapticsEnabled, onDown = { triggerKey(HidConstants.KEY_RIGHT_ARROW) })
            KeyButton(
                modifier = Modifier.weight(1.5f),
                label = "Enter",
                icon = Icons.Default.KeyboardReturn,
                isAccent = true,
                hapticsEnabled = hapticsEnabled,
                onDown = { triggerKey(HidConstants.KEY_ENTER) }
            )
        }

        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}
