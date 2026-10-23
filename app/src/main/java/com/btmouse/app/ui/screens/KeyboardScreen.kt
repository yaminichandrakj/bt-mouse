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

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import com.btmouse.app.ui.theme.AccentRose

@Composable
fun KeyboardScreen(
    coordinator: ConnectionCoordinator,
    settings: SettingsRepository
) {
    val context = LocalContext.current
    val hapticsEnabled by settings.hapticFeedback.collectAsState()
    var inputText by remember { mutableStateOf("") }
    var isLiveTypingEnabled by remember { mutableStateOf(true) }
    var activeSubTab by remember { mutableStateOf("keys") } // "keys", "apps", "power", "numpad"

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
                val added = newText.substring(oldText.length)
                coordinator.sendText(added)
            } else {
                repeat(oldText.length) {
                    coordinator.sendKeyStroke(HidConstants.KEY_BACKSPACE, 0)
                }
                coordinator.sendText(newText)
            }
        } else if (newText.length < oldText.length) {
            if (oldText.startsWith(newText)) {
                val deletedCount = oldText.length - newText.length
                repeat(deletedCount) {
                    coordinator.sendKeyStroke(HidConstants.KEY_BACKSPACE, 0)
                }
            } else {
                repeat(oldText.length) {
                    coordinator.sendKeyStroke(HidConstants.KEY_BACKSPACE, 0)
                }
                coordinator.sendText(newText)
            }
        }
        inputText = newText
    }

    val scrollState = rememberScrollState()
    val tabScrollState = rememberScrollState()

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
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(tabScrollState),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = activeSubTab == "keys",
                onClick = { activeSubTab = "keys" },
                label = { Text("⌨️ Keyboard", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = PrimaryIndigo.copy(alpha = 0.35f),
                    selectedLabelColor = AccentCyan,
                    containerColor = DarkSurface
                ),
                shape = RoundedCornerShape(8.dp)
            )
            FilterChip(
                selected = activeSubTab == "apps",
                onClick = { activeSubTab = "apps" },
                label = { Text("🚀 Quick Apps", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = PrimaryIndigo.copy(alpha = 0.35f),
                    selectedLabelColor = AccentCyan,
                    containerColor = DarkSurface
                ),
                shape = RoundedCornerShape(8.dp)
            )
            FilterChip(
                selected = activeSubTab == "power",
                onClick = { activeSubTab = "power" },
                label = { Text("🔒 Power & Unlock", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = PrimaryIndigo.copy(alpha = 0.35f),
                    selectedLabelColor = AccentCyan,
                    containerColor = DarkSurface
                ),
                shape = RoundedCornerShape(8.dp)
            )
            FilterChip(
                selected = activeSubTab == "numpad",
                onClick = { activeSubTab = "numpad" },
                label = { Text("🔢 Numpad", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = PrimaryIndigo.copy(alpha = 0.35f),
                    selectedLabelColor = AccentCyan,
                    containerColor = DarkSurface
                ),
                shape = RoundedCornerShape(8.dp)
            )

            if (activeSubTab == "keys") {
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

        when (activeSubTab) {
            "numpad" -> NumpadScreen(coordinator, settings)
            "apps" -> QuickAppsSection(coordinator, hapticsEnabled)
            "power" -> PowerAndUnlockSection(coordinator, context)
            else -> {

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

@Composable
fun QuickAppsSection(
    coordinator: ConnectionCoordinator,
    hapticsEnabled: Boolean
) {
    var customCommand by remember { mutableStateOf("") }

    val apps = listOf(
        Triple("OneNote", "onenote", Icons.Default.Edit),
        Triple("Spotify", "spotify", Icons.Default.MusicNote),
        Triple("Chrome", "chrome", Icons.Default.Language),
        Triple("Terminal", "terminal", Icons.Default.Terminal),
        Triple("Task Manager", "taskmgr", Icons.Default.Speed),
        Triple("Calculator", "calc", Icons.Default.Bolt),
        Triple("Explorer", "explorer", Icons.Default.Folder),
        Triple("Settings", "settings", Icons.Default.Settings),
        Triple("Notepad", "notepad", Icons.Default.Description),
        Triple("VS Code", "vscode", Icons.Default.Code),
        Triple("Edge", "edge", Icons.Default.Language)
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Quick Launch Apps", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text("Launch programs on your PC instantly with a single tap.", color = TextSecondary, fontSize = 12.sp)

        // Custom command runner
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = customCommand,
                onValueChange = { customCommand = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Run custom app, path or URL...", fontSize = 12.sp, color = TextMuted) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentCyan,
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
            Button(
                onClick = {
                    if (customCommand.isNotBlank()) {
                        coordinator.launchApp(customCommand.trim())
                        customCommand = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Run", color = DarkBackground, fontWeight = FontWeight.Bold)
            }
        }

        // Grid of Apps
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val chunked = apps.chunked(2)
            chunked.forEach { rowApps ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowApps.forEach { (name, appId, icon) ->
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { coordinator.launchApp(appId) },
                            colors = CardDefaults.cardColors(containerColor = DarkSurface),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(PrimaryIndigo.copy(alpha = 0.25f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(imageVector = icon, contentDescription = name, tint = AccentCyan, modifier = Modifier.size(20.dp))
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text("Open on PC", color = TextSecondary, fontSize = 10.sp)
                                }
                            }
                        }
                    }
                    if (rowApps.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun PowerAndUnlockSection(
    coordinator: ConnectionCoordinator,
    context: Context
) {
    var showUnlockDialog by remember { mutableStateOf(false) }
    var showRestartDialog by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var rememberPassword by remember { mutableStateOf(true) }

    val prefs = remember { context.getSharedPreferences("bt_unlock_prefs", Context.MODE_PRIVATE) }
    var savedPassword by remember { mutableStateOf(prefs.getString("saved_unlock_pw", "") ?: "") }

    if (showUnlockDialog) {
        AlertDialog(
            onDismissRequest = { showUnlockDialog = false },
            title = { Text("Unlock Windows PC", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Enter your Windows password or PIN. The PC companion will automatically wake the screen, enter the credentials, and log you in.",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("Windows Password / PIN") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentEmerald,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = rememberPassword,
                            onCheckedChange = { rememberPassword = it },
                            colors = CheckboxDefaults.colors(checkedColor = AccentEmerald)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Remember password on device", color = TextPrimary, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (passwordInput.isNotEmpty()) {
                            if (rememberPassword) {
                                prefs.edit().putString("saved_unlock_pw", passwordInput).apply()
                                savedPassword = passwordInput
                            }
                            coordinator.unlockWorkstation(passwordInput)
                            showUnlockDialog = false
                            passwordInput = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald)
                ) {
                    Text("Unlock Now", color = DarkBackground, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnlockDialog = false }) {
                    Text("Cancel", color = TextMuted)
                }
            },
            containerColor = DarkSurface
        )
    }

    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            title = { Text("Restart PC?", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to restart your PC? Unsaved work will be lost.", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        coordinator.systemPower("restart")
                        showRestartDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRose)
                ) {
                    Text("Restart", color = TextPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestartDialog = false }) {
                    Text("Cancel", color = TextMuted)
                }
            },
            containerColor = DarkSurface
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Desktop Security & Power", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)

        // Lock & Unlock Cards
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = AccentCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Lock & Unlock Workstation", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { coordinator.systemPower("lock") },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRose.copy(alpha = 0.85f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Lock PC", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            if (savedPassword.isNotEmpty()) {
                                coordinator.unlockWorkstation(savedPassword)
                            } else {
                                showUnlockDialog = true
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentEmerald),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.LockOpen, contentDescription = null, tint = DarkBackground, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (savedPassword.isNotEmpty()) "Unlock (1-Tap)" else "Unlock PC", color = DarkBackground, fontWeight = FontWeight.Bold)
                    }
                }

                if (savedPassword.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("PIN/Password saved for 1-tap unlock", color = AccentEmerald, fontSize = 11.sp)
                        TextButton(onClick = {
                            passwordInput = savedPassword
                            showUnlockDialog = true
                        }) {
                            Text("Change PIN", fontSize = 11.sp, color = AccentCyan)
                        }
                    }
                }
            }
        }

        // Sleep & Restart Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = AccentCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Power State Management", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { coordinator.systemPower("sleep") },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Sleep PC")
                    }

                    OutlinedButton(
                        onClick = { showRestartDialog = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRose),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Restart PC")
                    }
                }
            }
        }
    }
}
