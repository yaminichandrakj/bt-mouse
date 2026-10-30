package com.btmouse.app.ui.screens

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.ScreenshotMonitor
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.data.HidConstants
import com.btmouse.app.data.SettingsRepository
import com.btmouse.app.ui.components.ScreenPreviewPanel
import com.btmouse.app.ui.components.SegmentedControl
import com.btmouse.app.ui.components.ZoomState
import com.btmouse.app.ui.theme.Control
import com.btmouse.app.ui.theme.Inter
import com.btmouse.app.ui.theme.OnInk
import com.btmouse.app.ui.theme.OnInkFaint
import com.btmouse.app.ui.theme.OnInkSoft
import com.btmouse.app.ui.theme.Raised
import com.btmouse.app.ui.theme.Selected
import com.btmouse.app.ui.theme.Ink

private data class Shortcut(val label: String, val hint: String, val key: Byte, val mods: Byte, val icon: ImageVector? = null)

private val CTRL = HidConstants.MODIFIER_LEFT_CTRL
private val ALT = HidConstants.MODIFIER_LEFT_ALT
private val SHIFT = HidConstants.MODIFIER_LEFT_SHIFT
private val WIN = HidConstants.MODIFIER_LEFT_GUI

private fun mods(vararg m: Byte): Byte = m.fold(0) { acc, b -> acc or b.toInt() }.toByte()

// Shortcut rows, top to bottom: least used first, so copy, paste, cut and undo end up on the bottom row.
private val SHORTCUTS = listOf(
    Shortcut("Find", "Ctrl F", HidConstants.KEY_F, CTRL, Icons.Default.Search),
    Shortcut("Save", "Ctrl S", HidConstants.KEY_S, CTRL, Icons.Default.Save),
    Shortcut("Task Manager", "Ctrl ⇧ Esc", HidConstants.KEY_ESC, mods(CTRL, SHIFT), Icons.Default.MonitorHeart),
    Shortcut("Screenshot", "Win ⇧ S", HidConstants.KEY_S, mods(WIN, SHIFT), Icons.Default.ScreenshotMonitor),
    Shortcut("Redo", "Ctrl Y", HidConstants.KEY_Y, CTRL, Icons.AutoMirrored.Filled.Redo),
    Shortcut("Select all", "Ctrl A", HidConstants.KEY_A, CTRL, Icons.Default.SelectAll),
    Shortcut("Switch app", "Alt Tab", HidConstants.KEY_TAB, ALT, Icons.Default.FilterNone),
    Shortcut("Desktop", "Win D", HidConstants.KEY_D, WIN, Icons.Default.DesktopWindows),
    Shortcut("Copy", "Ctrl C", HidConstants.KEY_C, CTRL, Icons.Default.ContentCopy),
    Shortcut("Paste", "Ctrl V", HidConstants.KEY_V, CTRL, Icons.Default.ContentPaste),
    Shortcut("Cut", "Ctrl X", HidConstants.KEY_X, CTRL, Icons.Default.ContentCut),
    Shortcut("Undo", "Ctrl Z", HidConstants.KEY_Z, CTRL, Icons.AutoMirrored.Filled.Undo)
)

private val F_KEYS = listOf(
    HidConstants.KEY_F1, HidConstants.KEY_F2, HidConstants.KEY_F3, HidConstants.KEY_F4,
    HidConstants.KEY_F5, HidConstants.KEY_F6, HidConstants.KEY_F7, HidConstants.KEY_F8,
    HidConstants.KEY_F9, HidConstants.KEY_F10, HidConstants.KEY_F11, HidConstants.KEY_F12
).mapIndexed { i, key -> Shortcut("F${i + 1}", "", key, 0) }

/**
 * Keyboard tab: a text box that streams what you type, held modifiers, a shortcut grid
 * (Fn swaps it for F1–F12), arrows and editing keys, and a numpad, all on one screen.
 * With the screen preview on, the PC screen sits on top and the grid shrinks to two rows.
 */
@Composable
fun KeyboardTab(
    coordinator: ConnectionCoordinator,
    settings: SettingsRepository,
    previewOn: Boolean,
    screenZoom: ZoomState,
    onScreenFullscreen: () -> Unit,
    notify: (String) -> Unit = {}
) {
    val haptics by settings.hapticFeedback.collectAsState()
    var numpad by rememberSaveable { mutableStateOf(false) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(false) }
    var win by remember { mutableStateOf(false) }
    var fn by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }

    fun held(): Byte {
        var m = 0
        if (ctrl) m = m or CTRL.toInt()
        if (alt) m = m or ALT.toInt()
        if (shift) m = m or SHIFT.toInt()
        if (win) m = m or WIN.toInt()
        return m.toByte()
    }

    /** Send a key with whatever modifiers are held, then let go of them like a real one-shot. */
    fun press(key: Byte, extra: Byte = 0) {
        coordinator.sendKeyStroke(key, mods(held(), extra))
        if (ctrl || alt || shift || win) {
            ctrl = false; alt = false; shift = false; win = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SegmentedControl(listOf("Keys", "Numpad"), if (numpad) 1 else 0, { numpad = it == 1 })

        if (previewOn) {
            ScreenPreviewPanel(coordinator, screenZoom, onScreenFullscreen)
        }

        SendBox(
            text = text,
            onTextChange = { text = it },
            onSend = {
                if (text.isNotEmpty()) {
                    coordinator.sendText(text)
                    text = ""
                }
            }
        )

        if (!numpad) {
            // Every row shares the height left on screen, so the keys fill it evenly.
            // Typing keys sit at the top; the shortcuts you tap most sit at the bottom, nearest
            // the thumb, with copy, paste, cut and undo on the very last row.
            val grid = (if (fn) F_KEYS else SHORTCUTS).let { if (previewOn) it.takeLast(8) else it }
            val gridRows = grid.chunked(4)
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyRow {
                    NavKey("Home", haptics, Modifier.weight(1f), quiet = true) { press(HidConstants.KEY_HOME) }
                    NavKey("Pg Up", haptics, Modifier.weight(1f), quiet = true) { press(HidConstants.KEY_PAGE_UP) }
                    NavKey("Up", haptics, Modifier.weight(1f), icon = Icons.Default.KeyboardArrowUp) { press(HidConstants.KEY_UP_ARROW) }
                    NavKey("Pg Dn", haptics, Modifier.weight(1f), quiet = true) { press(HidConstants.KEY_PAGE_DOWN) }
                    NavKey("End", haptics, Modifier.weight(1f), quiet = true) { press(HidConstants.KEY_END) }
                }
                KeyRow {
                    NavKey("Tab", haptics, Modifier.weight(1f), quiet = true) { press(HidConstants.KEY_TAB) }
                    NavKey("Left", haptics, Modifier.weight(1f), icon = Icons.Default.KeyboardArrowLeft) { press(HidConstants.KEY_LEFT_ARROW) }
                    NavKey("Down", haptics, Modifier.weight(1f), icon = Icons.Default.KeyboardArrowDown) { press(HidConstants.KEY_DOWN_ARROW) }
                    NavKey("Right", haptics, Modifier.weight(1f), icon = Icons.Default.KeyboardArrowRight) { press(HidConstants.KEY_RIGHT_ARROW) }
                    NavKey("Del", haptics, Modifier.weight(1f), quiet = true) { press(HidConstants.KEY_DELETE) }
                }
                KeyRow {
                    NavKey("Esc", haptics, Modifier.weight(1f), quiet = true) { press(HidConstants.KEY_ESC) }
                    NavKey("Space", haptics, Modifier.weight(2f), icon = Icons.Default.SpaceBar) { press(HidConstants.KEY_SPACE) }
                    NavKey("Backspace", haptics, Modifier.weight(1.2f), icon = Icons.AutoMirrored.Filled.Backspace) { press(HidConstants.KEY_BACKSPACE) }
                    PadKey("Enter", Modifier.weight(1.6f).fillMaxHeight(), height = Dp.Unspecified, strong = true, haptics = haptics) { press(HidConstants.KEY_ENTER) }
                }
                // Modifiers sit between the two halves: they combine with keys above and Fn swaps the shortcuts below.
                KeyRow {
                    ModKey("Ctrl", ctrl, Modifier.weight(1f)) { ctrl = !ctrl }
                    ModKey("Alt", alt, Modifier.weight(1f)) { alt = !alt }
                    ModKey("Shift", shift, Modifier.weight(1f)) { shift = !shift }
                    ModKey("Win", win, Modifier.weight(1f)) { win = !win }
                    ModKey("Fn", fn, Modifier.weight(1f)) { fn = !fn }
                }
                gridRows.forEachIndexed { index, row ->
                    KeyRow(weight = if (index == gridRows.lastIndex) 1.15f else 1f) {
                        row.forEach { s ->
                            PadKey(
                                label = s.label, icon = s.icon, hint = if (s.icon == null) s.hint else "",
                                description = if (s.hint.isEmpty()) s.label else "${s.label} (${s.hint})",
                                onLongPress = { notify(if (s.hint.isEmpty()) s.label else "${s.label} · ${s.hint}") },
                                height = Dp.Unspecified, haptics = haptics,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            ) { press(s.key, s.mods) }
                        }
                    }
                }
            }
        } else {
            NumberPad(Modifier.weight(1f).fillMaxWidth(), haptics) { key -> coordinator.sendKeyStroke(key, 0) }
        }
    }
}

@Composable
private fun SendBox(text: String, onTextChange: (String) -> Unit, onSend: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(CircleShape)
            .background(Control)
            .padding(start = 20.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text("Type or dictate, then send", color = OnInkFaint, fontSize = 16.sp)
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                singleLine = true,
                textStyle = TextStyle(color = OnInk, fontSize = 16.sp, fontFamily = Inter),
                cursorBrush = SolidColor(OnInk),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(OnInk)
                .clickable(role = Role.Button, onClickLabel = "Send to PC", onClick = onSend),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.ArrowUpward, contentDescription = "Send to PC", tint = Ink, modifier = Modifier.size(20.dp))
        }
    }
}

/** One row of keys that takes its share of the free height, never shorter than a finger. */
@Composable
private fun ColumnScope.KeyRow(weight: Float = 1f, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.weight(weight).fillMaxWidth().heightIn(min = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}

@Composable
private fun NavKey(
    label: String,
    haptics: Boolean,
    modifier: Modifier,
    icon: ImageVector? = null,
    quiet: Boolean = false,
    onPress: () -> Unit
) = PadKey(label, modifier.fillMaxHeight(), icon = icon, height = Dp.Unspecified, quiet = quiet, haptics = haptics, onPress = onPress)

@Composable
private fun ModKey(label: String, on: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(16.dp))
            .background(if (on) Selected else Raised)
            .clickable(role = Role.Switch, onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (on) Color.White else OnInkSoft, fontSize = 15.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal)
    }
}

/** One key. Brightens while pressed and gives a tick if haptics are on; long-press can explain it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PadKey(
    label: String,
    modifier: Modifier = Modifier,
    hint: String = "",
    icon: ImageVector? = null,
    height: Dp = 52.dp,
    fontSize: TextUnit = 14.sp,
    quiet: Boolean = false,
    strong: Boolean = false,
    haptics: Boolean = true,
    description: String = label,
    onLongPress: (() -> Unit)? = null,
    onPress: () -> Unit
) {
    val view = LocalView.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val base = when {
        strong -> Selected
        quiet -> Raised
        else -> Control
    }
    Column(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .background(if (pressed) Selected else base)
            .combinedClickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClickLabel = description,
                onLongClick = onLongPress,
                onClick = {
                    if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onPress()
                }
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = description, tint = OnInk, modifier = Modifier.size(22.dp))
        } else {
            Text(label, color = if (strong) Color.White else OnInk, fontSize = fontSize, maxLines = 1,
                fontWeight = if (strong) FontWeight.Medium else FontWeight.Normal)
            if (hint.isNotEmpty()) Text(hint, color = Color(0xFF9A9A9A), fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun NumberPad(modifier: Modifier, haptics: Boolean, send: (Byte) -> Unit) {
    val k = HidConstants
    @Composable
    fun key(label: String, code: Byte, mod: Modifier, quiet: Boolean = false, strong: Boolean = false, size: TextUnit = 24.sp) =
        PadKey(label, mod.fillMaxHeight(), height = Dp.Unspecified, fontSize = size, quiet = quiet, strong = strong, haptics = haptics) { send(code) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            key("Num", k.KEY_NUM_LOCK, Modifier.weight(1f), quiet = true, size = 15.sp)
            key("/", k.KEYPAD_DIVIDE, Modifier.weight(1f), quiet = true)
            key("*", k.KEYPAD_MULTIPLY, Modifier.weight(1f), quiet = true)
            key("−", k.KEYPAD_SUBTRACT, Modifier.weight(1f), quiet = true)
        }
        Row(Modifier.weight(2f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    key("7", k.KEYPAD_7, Modifier.weight(1f)); key("8", k.KEYPAD_8, Modifier.weight(1f)); key("9", k.KEYPAD_9, Modifier.weight(1f))
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    key("4", k.KEYPAD_4, Modifier.weight(1f)); key("5", k.KEYPAD_5, Modifier.weight(1f)); key("6", k.KEYPAD_6, Modifier.weight(1f))
                }
            }
            key("+", k.KEYPAD_ADD, Modifier.weight(1f), quiet = true)
        }
        Row(Modifier.weight(2f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    key("1", k.KEYPAD_1, Modifier.weight(1f)); key("2", k.KEYPAD_2, Modifier.weight(1f)); key("3", k.KEYPAD_3, Modifier.weight(1f))
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    key("0", k.KEYPAD_0, Modifier.weight(2f)); key(".", k.KEYPAD_DOT, Modifier.weight(1f))
                }
            }
            key("Enter", k.KEYPAD_ENTER, Modifier.weight(1f), strong = true, size = 16.sp)
        }
    }
}
