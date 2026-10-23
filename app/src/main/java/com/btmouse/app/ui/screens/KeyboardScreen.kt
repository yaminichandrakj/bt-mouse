package com.btmouse.app.ui.screens

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardReturn
import androidx.compose.material.icons.filled.KeyboardTab
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btmouse.app.connection.AppInfo
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.connection.CustomAppEntry
import com.btmouse.app.connection.ProcessInfo
import com.btmouse.app.data.HidConstants
import com.btmouse.app.data.SettingsRepository
import com.btmouse.app.ui.components.KeyButton
import com.btmouse.app.ui.theme.AccentCyan
import com.btmouse.app.ui.theme.AccentEmerald
import com.btmouse.app.ui.theme.AccentRose
import com.btmouse.app.ui.theme.DarkBackground
import com.btmouse.app.ui.theme.DarkBorder
import com.btmouse.app.ui.theme.DarkSurface
import com.btmouse.app.ui.theme.DarkSurfaceVariant
import com.btmouse.app.ui.theme.PrimaryIndigo
import com.btmouse.app.ui.theme.TextMuted
import com.btmouse.app.ui.theme.TextPrimary
import com.btmouse.app.ui.theme.TextSecondary
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.delay

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
        // Keyboard and numpad remain in Keys; PC utilities live in their own tab.
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
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
fun QuickAppsSection(
    coordinator: ConnectionCoordinator,
    settings: SettingsRepository,
    hapticsEnabled: Boolean
) {
    var customCommand by remember { mutableStateOf("") }
    var showBrowseDialog by remember { mutableStateOf(false) }
    var showAddCustomDialog by remember { mutableStateOf(false) }
    var appSearchQuery by remember { mutableStateOf("") }
    var newCustomName by remember { mutableStateOf("") }
    var newCustomCmd by remember { mutableStateOf("") }

    val customAppsJson by settings.customAppsJson.collectAsState()
    val customApps: List<CustomAppEntry> = remember(customAppsJson) {
        try {
            Gson().fromJson(customAppsJson, object : TypeToken<List<CustomAppEntry>>() {}.type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    val installedApps by coordinator.installedApps.collectAsState()
    val quickApps = installedApps.filter { it.isQuick }

    LaunchedEffect(Unit) { coordinator.queryInstalledApps() }

    // Browse PC Installed Apps Modal
    if (showBrowseDialog) {
        AlertDialog(
            onDismissRequest = { showBrowseDialog = false },
            containerColor = DarkSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DesktopWindows, contentDescription = null, tint = AccentCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Installed PC Apps", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = appSearchQuery,
                        onValueChange = { appSearchQuery = it },
                        placeholder = { Text("Filter installed apps...", color = TextMuted, fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        singleLine = true
                    )

                    val filteredApps = installedApps.filter {
                        it.name.contains(appSearchQuery, ignoreCase = true) || it.path.contains(appSearchQuery, ignoreCase = true)
                    }

                    if (filteredApps.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (installedApps.isEmpty()) "Scanning PC apps or waiting for response..."
                                else "No matching apps found.",
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(filteredApps) { app ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(app.name, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                                            Text(app.path, color = TextMuted, fontSize = 10.sp, maxLines = 1)
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            IconButton(
                                                onClick = {
                                                    val exists = customApps.any { it.cmd == app.path }
                                                    if (!exists) {
                                                        val updated = customApps + CustomAppEntry(app.name, app.path)
                                                        settings.setCustomAppsJson(Gson().toJson(updated))
                                                    }
                                                    showBrowseDialog = false
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.PushPin, contentDescription = "Pin", tint = AccentEmerald, modifier = Modifier.size(16.dp))
                                            }
                                            IconButton(
                                                onClick = {
                                                    coordinator.launchApp(app.path)
                                                    showBrowseDialog = false
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.PlayArrow, contentDescription = "Open", tint = AccentCyan, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    coordinator.queryInstalledApps()
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp), tint = AccentCyan)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Refresh List", color = AccentCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBrowseDialog = false }) {
                    Text("Close", color = TextMuted)
                }
            }
        )
    }

    // Add Custom Shortcut Modal
    if (showAddCustomDialog) {
        AlertDialog(
            onDismissRequest = { showAddCustomDialog = false },
            containerColor = DarkSurface,
            title = { Text("Add Quick App Shortcut", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newCustomName,
                        onValueChange = { newCustomName = it },
                        label = { Text("App Name (e.g. Steam, Blender)") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newCustomCmd,
                        onValueChange = { newCustomCmd = it },
                        label = { Text("Executable / Path / Command") },
                        placeholder = { Text("steam or C:\\Program Files\\...") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newCustomName.isNotBlank() && newCustomCmd.isNotBlank()) {
                            val updated = customApps + CustomAppEntry(newCustomName.trim(), newCustomCmd.trim())
                            settings.setCustomAppsJson(Gson().toJson(updated))
                            newCustomName = ""
                            newCustomCmd = ""
                            showAddCustomDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                ) {
                    Text("Add Shortcut", color = DarkBackground, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCustomDialog = false }) {
                    Text("Cancel", color = TextMuted)
                }
            }
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Quick Launch & Desktop Control", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Launch apps on your PC instantly with 1 tap.", color = TextSecondary, fontSize = 12.sp)
            }
        }

        // Browse & Add Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    coordinator.queryInstalledApps()
                    showBrowseDialog = true
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Browse PC Apps", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = { showAddCustomDialog = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("+ Custom App", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

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

        // Custom Pinned Apps Section
        if (customApps.isNotEmpty()) {
            Text("📌 Pinned Quick Apps", color = AccentEmerald, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                customApps.chunked(2).forEach { rowApps ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowApps.forEach { app ->
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { coordinator.launchApp(app.cmd) },
                                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, AccentEmerald.copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .background(AccentEmerald.copy(alpha = 0.2f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.PushPin, contentDescription = null, tint = AccentEmerald, modifier = Modifier.size(16.dp))
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(app.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                                            Text(app.cmd, color = TextSecondary, fontSize = 9.sp, maxLines = 1)
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            val updated = customApps.filterNot { it.cmd == app.cmd }
                                            settings.setCustomAppsJson(Gson().toJson(updated))
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Unpin", tint = TextMuted, modifier = Modifier.size(14.dp))
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

        Text("Quick Apps from PC Companion", color = AccentEmerald, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        if (quickApps.isEmpty()) {
            Text("Add installed apps to Quick Apps in the PC companion. Only apps present on this PC are shown here.",
                color = TextMuted, fontSize = 11.sp)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                quickApps.chunked(2).forEach { rowApps ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowApps.forEach { app ->
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { coordinator.launchApp(app.path) },
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
                                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = app.name, tint = AccentCyan, modifier = Modifier.size(20.dp))
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(app.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
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
}

@Composable
fun TasksAndHardwareSection(
    coordinator: ConnectionCoordinator
) {
    var processSearch by remember { mutableStateOf("") }
    val stats by coordinator.systemStats.collectAsState()
    val processes by coordinator.runningProcesses.collectAsState()

    // Poll stats every 2.5 seconds
    LaunchedEffect(Unit) {
        coordinator.querySystemStats()
        coordinator.queryRunningProcesses()
        var processRefreshElapsed = 0
        while (true) {
            delay(2500)
            coordinator.querySystemStats()
            processRefreshElapsed += 2500
            if (processRefreshElapsed >= 10_000) {
                coordinator.queryRunningProcesses()
                processRefreshElapsed = 0
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("PC Hardware & Live Tasks", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)

        // 2x2 Stats Dashboard
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Live System Performance", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    IconButton(
                        onClick = {
                            coordinator.querySystemStats()
                            coordinator.queryRunningProcesses()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = AccentCyan, modifier = Modifier.size(16.dp))
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // CPU Card
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("CPU Usage", color = TextSecondary, fontSize = 11.sp)
                            val cpu = stats?.cpu ?: 0f
                            Text("${cpu.toInt()}%", color = AccentCyan, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { (cpu / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = AccentCyan,
                                trackColor = DarkBorder
                            )
                        }
                    }

                    // RAM Card
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("RAM Usage", color = TextSecondary, fontSize = 11.sp)
                            val ram = stats?.ram ?: 0f
                            Text("${ram.toInt()}%", color = PrimaryIndigo, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { (ram / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = PrimaryIndigo,
                                trackColor = DarkBorder
                            )
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Battery Card
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("Battery Status", color = TextSecondary, fontSize = 11.sp)
                            val batt = stats?.battery ?: -1f
                            val plugged = stats?.powerPlugged ?: true
                            Text(
                                if (batt >= 0) "${batt.toInt()}%" else "AC Power",
                                color = AccentEmerald,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(if (plugged) "⚡ Charging / Plugged" else "🔋 On Battery", color = TextMuted, fontSize = 10.sp)
                        }
                    }

                    // Disk Card
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("Disk Usage", color = TextSecondary, fontSize = 11.sp)
                            val disk = stats?.disk ?: 0f
                            Text("${disk.toInt()}%", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { (disk / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = AccentRose,
                                trackColor = DarkBorder
                            )
                        }
                    }
                }
            }
        }

        // Running Processes / Task Manager
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Running Tasks (${processes.size})", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    TextButton(onClick = { coordinator.queryRunningProcesses() }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp), tint = AccentCyan)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Refresh Tasks", color = AccentCyan, fontSize = 11.sp)
                    }
                }

                OutlinedTextField(
                    value = processSearch,
                    onValueChange = { processSearch = it },
                    placeholder = { Text("Filter processes...", color = TextMuted, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = DarkBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    singleLine = true
                )

                val filtered = processes.filter { it.name.contains(processSearch, ignoreCase = true) }

                if (filtered.isEmpty()) {
                    Text(
                        if (processes.isEmpty()) "Tap 'Refresh Tasks' to fetch active programs from your PC."
                        else "No processes matching '$processSearch'",
                        color = TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        filtered.take(30).forEach { proc ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(proc.name, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                                        Text("PID: ${proc.pid}  |  RAM: ${proc.ram.toInt()} MB  |  CPU: ${proc.cpu.toInt()}%", color = TextSecondary, fontSize = 10.sp)
                                    }
                                    Button(
                                        onClick = { coordinator.killProcess(proc.pid) },
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentRose.copy(alpha = 0.2f), contentColor = AccentRose),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text("End Task", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CameraAndRecorderSection(
    coordinator: ConnectionCoordinator
) {
    val rawBytes by coordinator.latestCameraFrame.collectAsState()
    val isStreaming by coordinator.isCameraStreaming.collectAsState()
    val isRecording by coordinator.isAudioRecording.collectAsState()
    val connectionStatus by coordinator.statusMessage.collectAsState()

    val imageBitmap = remember(rawBytes) {
        rawBytes?.let { bytes ->
            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Camera & Microphone Control", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        if (connectionStatus.startsWith("Microphone")) {
            Text(connectionStatus, color = if (connectionStatus.contains("error", ignoreCase = true)) AccentRose else AccentEmerald,
                fontSize = 11.sp)
        }

        // Webcam Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Videocam, contentDescription = null, tint = AccentCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Webcam Viewfinder", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isStreaming) AccentEmerald.copy(alpha = 0.2f) else DarkSurfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            if (isStreaming) "LIVE FEED" else "STANDBY",
                            color = if (isStreaming) AccentEmerald else TextMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Viewfinder Screen
                if (imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = "Webcam frame",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(DarkSurfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, tint = TextMuted, modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Camera stream standby", color = TextSecondary, fontSize = 12.sp)
                            Text("Tap 'Capture Snapshot' or 'Live Stream' below", color = TextMuted, fontSize = 10.sp)
                        }
                    }
                }

                // Camera Action Buttons
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { coordinator.requestCameraSnapshot() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Snapshot", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            if (isStreaming) coordinator.stopCameraStream()
                            else coordinator.startCameraStream()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = if (isStreaming) AccentRose else AccentCyan),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = if (isStreaming) Icons.Default.Stop else Icons.Default.Videocam,
                            contentDescription = null,
                            tint = if (isStreaming) TextPrimary else DarkBackground,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (isStreaming) "Stop Live" else "Start Live",
                            color = if (isStreaming) TextPrimary else DarkBackground,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Microphone Recorder Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Mic, contentDescription = null, tint = AccentRose)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Microphone Recorder", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    if (isRecording) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(AccentRose.copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("● RECORDING (16kHz WAV)", color = AccentRose, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Text(
                    "Record high-clarity microphone audio on your PC remotely. Captured recordings are saved directly into the 'pc_server/recordings/' directory.",
                    color = TextSecondary,
                    fontSize = 11.sp
                )

                Button(
                    onClick = {
                        if (isRecording) coordinator.stopAudioRecord()
                        else coordinator.startAudioRecord()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = if (isRecording) AccentRose else PrimaryIndigo),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (isRecording) "Stop Microphone Recording" else "Start Microphone Recording",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
fun PowerAndUnlockSection(
    coordinator: ConnectionCoordinator
) {
    var showRestartDialog by remember { mutableStateOf(false) }
    var showShutdownDialog by remember { mutableStateOf(false) }

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

    if (showShutdownDialog) {
        AlertDialog(
            onDismissRequest = { showShutdownDialog = false },
            title = { Text("Shut Down PC?", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to shut down your PC remotely?", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        coordinator.systemPower("shutdown")
                        showShutdownDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRose)
                ) {
                    Text("Shut Down", color = TextPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showShutdownDialog = false }) {
                    Text("Cancel", color = TextMuted)
                }
            },
            containerColor = DarkSurface
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Desktop Security & Power", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text("Windows requires sign-in at the PC to unlock its secure desktop. Remote unlock is unavailable.",
            color = TextMuted, fontSize = 11.sp)

        // Lock the workstation. Unlocking a Windows secure desktop by injected credentials
        // is not supported safely by this companion.
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
                    Text("Lock Workstation", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                Button(
                    onClick = { coordinator.systemPower("lock") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRose.copy(alpha = 0.85f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Lock PC", fontWeight = FontWeight.Bold)
                }
            }
        }

        // Sleep, Restart & Shutdown Card
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

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { coordinator.systemPower("sleep") },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Sleep", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = { showRestartDialog = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRose),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Restart", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = { showShutdownDialog = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRose),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Shutdown", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
