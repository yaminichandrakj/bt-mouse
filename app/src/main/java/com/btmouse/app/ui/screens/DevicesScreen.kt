package com.btmouse.app.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.control.RemoteControlService
import com.btmouse.app.control.RemoteKeyboardService
import com.btmouse.app.data.SettingsRepository
import com.btmouse.app.ecosystem.DeviceIdentity
import com.btmouse.app.ecosystem.Ecosystem
import com.btmouse.app.ecosystem.LiveStream
import com.btmouse.app.ecosystem.PeerDevice
import com.btmouse.app.ecosystem.ScreenShare
import com.btmouse.app.service.CompanionSessionService
import com.btmouse.app.ecosystem.TransferInfo
import com.btmouse.app.ui.components.Caption
import com.btmouse.app.ui.components.MonoSwitch
import com.btmouse.app.ui.components.PanelCard
import com.btmouse.app.ui.components.PillButton
import com.btmouse.app.ui.components.PillStyle
import com.btmouse.app.ui.components.RoundIconButton
import com.btmouse.app.ui.components.SectionHeader
import com.btmouse.app.ui.components.StatusDot
import com.btmouse.app.ui.theme.AccentAmber
import com.btmouse.app.ui.theme.Control
import com.btmouse.app.ui.theme.Ink
import com.btmouse.app.ui.theme.OnInk
import com.btmouse.app.ui.theme.OnInkMuted
import com.btmouse.app.ui.theme.Panel
import com.btmouse.app.ui.theme.StatusGreen
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max

/** Everything about the other devices: being controlled, clipboard, files, and controlling a tablet. */
@Composable
fun DevicesScreen(
    coordinator: ConnectionCoordinator,
    settings: SettingsRepository,
    onControl: (PeerDevice) -> Unit,
    onWatch: (LiveStream) -> Unit = {}
) {
    val context = LocalContext.current
    val connected by coordinator.isConnected.collectAsState()
    val devices by Ecosystem.devices.collectAsState()
    val controlledBy by Ecosystem.controlledBy.collectAsState()
    val transfers by Ecosystem.files.transfers.collectAsState()
    val stay by settings.stayConnected.collectAsState()
    val allowControl by settings.allowRemoteControl.collectAsState()
    val clipboardSync by settings.clipboardSync.collectAsState()
    val accessibilityOn by RemoteControlService.enabled.collectAsState()
    var keyboardEnabled by remember { mutableStateOf(false) }
    var keyboardSelected by remember { mutableStateOf(false) }
    var batteryOk by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        while (true) {
            keyboardEnabled = isKeyboardEnabled(context)
            keyboardSelected = isKeyboardSelected(context)
            batteryOk = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(context.packageName)
            delay(1000)
        }
    }

    val liveStreams by Ecosystem.streams.collectAsState()
    val hubOn by com.btmouse.app.ecosystem.AndroidHub.enabled.collectAsState()
    val hubClients by com.btmouse.app.ecosystem.AndroidHub.connected.collectAsState()
    val sharingScreen by ScreenShare.active.collectAsState()
    val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            ScreenShare.setPermission(result.resultCode, data)
            CompanionSessionService.startProjection(context)
        }
    }

    var sendTarget by remember { mutableStateOf<PeerDevice?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val target = sendTarget ?: return@rememberLauncherForActivityResult
        uris.forEach { Ecosystem.files.sendUri(target.id, target.name, it) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        controlledBy?.let { who ->
            PanelCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(StatusGreen)
                    Spacer(Modifier.width(10.dp))
                    Text("$who is controlling this ${if (DeviceIdentity.isTablet(context)) "tablet" else "phone"}",
                        color = OnInk, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    PillButton("Release", { Ecosystem.releaseControl() })
                }
            }
        }

        SectionHeader("This ${if (DeviceIdentity.isTablet(context)) "tablet" else "phone"}")
        PanelCard {
            Text(DeviceIdentity.name(context), color = OnInk, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp))
            ToggleRow("Stay connected", "Runs in the background, starts after a restart, and reconnects by itself.", stay) {
                settings.setStayConnected(it)
                if (it) coordinator.reconnectNow()
            }
            if (stay && !batteryOk) {
                Spacer(Modifier.height(10.dp))
                Caption("Android may close Lanyard in the background. Allow it to run unrestricted so it stays connected.", color = AccentAmber)
                Spacer(Modifier.height(8.dp))
                PillButton("Allow background use", { requestBatteryExemption(context) }, style = PillStyle.Primary)
            }
        }

        SectionHeader("Let your laptop control this device")
        PanelCard {
            ToggleRow("Allow control from other devices", "Your laptop's keyboard and mouse, or another phone or tablet.", allowControl) {
                settings.setAllowRemoteControl(it)
                Ecosystem.sendHello()
            }
            Spacer(Modifier.height(14.dp))
            SetupStep(
                done = accessibilityOn,
                title = "1. Turn on Lanyard control",
                detail = "Settings › Accessibility › Lanyard › On. Draws the pointer and taps for you.",
                action = "Open settings"
            ) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            Spacer(Modifier.height(10.dp))
            SetupStep(
                done = keyboardEnabled,
                title = "2. Turn on the Lanyard keyboard",
                detail = "Lets the laptop type anywhere, including arrows, Backspace and shortcuts.",
                action = "Open settings"
            ) { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            Spacer(Modifier.height(10.dp))
            SetupStep(
                done = keyboardSelected,
                title = "3. Switch to it while you type from the laptop",
                detail = "Without it, plain letters still work through step 1.",
                action = "Choose keyboard"
            ) { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
            Spacer(Modifier.height(14.dp))
            Caption(
                "On the laptop, push the mouse off the screen edge where this device sits in Lanyard › Devices, " +
                    "or press Ctrl+Alt+→. Ctrl+Alt+Home always brings it back. Right-click is Back, middle-click is Home."
            )
        }

        SectionHeader("Clipboard")
        PanelCard {
            ToggleRow("Share the clipboard", "Copy on one device, paste on the others.", clipboardSync) {
                settings.setClipboardSync(it)
                Ecosystem.sendHello()
            }
            Spacer(Modifier.height(10.dp))
            Caption(
                "From the laptop it arrives instantly. From this device it is sent while Lanyard is open, while the " +
                    "Lanyard keyboard is active, from Share › Lanyard, or with the Send clipboard tile in Quick Settings."
            )
            Spacer(Modifier.height(10.dp))
            PillButton("Send clipboard now", { Ecosystem.pushClipboardIfNew(force = true) }, enabled = connected)
        }

        SectionHeader("Live")
        PanelCard {
            Text(if (sharingScreen) "Sharing this screen" else "Share this screen", color = OnInk, fontSize = 16.sp)
            Caption(
                if (sharingScreen) "Your other devices can watch it, and your laptop can use it with its mouse. " +
                    "Pictures are only taken while someone watches."
                else "Let your laptop or another device watch this screen. Android asks each time."
            )
            Spacer(Modifier.height(10.dp))
            if (sharingScreen) {
                PillButton("Stop sharing", { CompanionSessionService.stopProjection(context) }, style = PillStyle.Danger)
            } else {
                PillButton("Share screen", { captureLauncher.launch(ScreenShare.captureIntent(context)) }, enabled = connected || hubOn)
            }
            if (liveStreams.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                liveStreams.forEach { stream ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("${stream.ownerName} · ${stream.label}", color = OnInk, fontSize = 15.sp)
                            if (stream.viewers > 0) Caption("${stream.viewers} watching")
                        }
                        PillButton("Watch", { onWatch(stream) }, height = 38.dp)
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Caption("When another device shares its screen or camera, it shows up here.")
            }
        }

        SectionHeader("No PC? Connect a tablet to this phone")
        PanelCard {
            ToggleRow(
                "Let my other Android devices connect here",
                "The tablet's trackpad and keyboard then drive this phone, and the two share the clipboard, files and this screen.",
                hubOn
            ) { com.btmouse.app.ecosystem.AndroidHub.setEnabled(it) }
            if (hubOn) {
                val code = remember(hubOn) { com.btmouse.app.ecosystem.AndroidHub.pairingCode() }
                Spacer(Modifier.height(14.dp))
                if (code == null) {
                    Caption("Join a Wi-Fi network (or turn on this phone's hotspot and join it from the tablet), then reopen this page.", color = AccentAmber)
                } else {
                    val qr = remember(code) { qrBitmap(code, 600) }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        androidx.compose.foundation.Image(
                            qr, "Pairing code for this phone",
                            Modifier.size(220.dp).clip(RoundedCornerShape(12.dp))
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Caption(
                        "On the tablet: Lanyard › Connect › Scan PC QR code, and scan this. " +
                            "PIN ${com.btmouse.app.ecosystem.AndroidHub.pin()}. The tablet then treats this phone as its PC; " +
                            "scan your PC's code again to switch back."
                    )
                }
                if (hubClients.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Caption("Connected here: " + hubClients.joinToString { it.name }, color = StatusGreen)
                }
            }
        }

        SectionHeader("Your devices")
        if (!connected) {
            Caption("Connect to your PC to see your other devices.")
        } else if (devices.isEmpty()) {
            Caption("Looking for your devices…")
        }
        devices.forEach { device ->
            PanelCard(padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when (device.kind) {
                            "pc" -> Icons.Default.Computer
                            "tablet" -> Icons.Default.Tablet
                            else -> Icons.Default.PhoneAndroid
                        },
                        contentDescription = null, tint = OnInk, modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(device.name, color = OnInk, fontSize = 16.sp, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (device.acceptsFiles) {
                        PillButton("Send files", {
                            sendTarget = device
                            picker.launch(arrayOf("*/*"))
                        })
                    }
                    if (!device.isPc && device.canBeControlled) {
                        PillButton("Control", { onControl(device) }, style = PillStyle.Primary)
                    }
                }
            }
        }

        if (transfers.isNotEmpty()) {
            SectionHeader("Transfers")
            transfers.forEach { TransferRow(it, context) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = OnInk, fontSize = 16.sp)
            Caption(detail)
        }
        Spacer(Modifier.width(12.dp))
        MonoSwitch(checked, onChange)
    }
}

@Composable
private fun SetupStep(done: Boolean, title: String, detail: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(if (done) StatusGreen else AccentAmber, size = 10.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = OnInk, fontSize = 15.sp)
            Caption(if (done) "Done" else detail)
        }
        if (!done) {
            Spacer(Modifier.width(8.dp))
            PillButton(action, onAction, height = 38.dp)
        }
    }
}

@Composable
private fun TransferRow(info: TransferInfo, context: Context) {
    PanelCard(padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(info.name, color = OnInk, fontSize = 15.sp, maxLines = 1)
                val direction = if (info.outgoing) "To ${info.peerName}" else "From ${info.peerName}"
                val state = when (info.state) {
                    "done" -> "Done"
                    "failed" -> info.error ?: "Failed"
                    "waiting" -> "Waiting for the other device"
                    "preparing" -> "Preparing"
                    "verifying" -> "Checking"
                    else -> "${(info.progress * 100).toInt()}%"
                }
                Caption("$direction · $state")
            }
            when {
                info.state == "done" && info.openUri != null -> PillButton("Open", {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse(info.openUri), null)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                }, height = 38.dp)
                info.state in setOf("sending", "receiving", "waiting", "preparing") ->
                    PillButton("Cancel", { Ecosystem.files.cancel(info.id) }, height = 38.dp, style = PillStyle.Outline)
            }
        }
        if (info.state in setOf("sending", "receiving", "verifying")) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { info.progress },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = OnInk,
                trackColor = Control
            )
        }
    }
}

private fun isKeyboardEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    return imm.enabledInputMethodList.any { it.packageName == context.packageName }
}

private fun isKeyboardSelected(context: Context): Boolean {
    val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
    return current.startsWith(context.packageName + "/")
}

@SuppressLint("BatteryLife")
private fun requestBatteryExemption(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(direct) }.onFailure {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/**
 * Trackpad for another phone or tablet, relayed through the PC. One finger moves and
 * taps, two fingers scroll, a two-finger tap is Back.
 */
@Composable
fun RemotePadScreen(target: PeerDevice, onClose: () -> Unit) {
    val w = max(1, target.screenW).toFloat()
    val h = max(1, target.screenH).toFloat()
    var x by remember { mutableFloatStateOf(w / 2) }
    var y by remember { mutableFloatStateOf(h / 2) }
    var holding by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }

    DisposableEffect(target.id) {
        Ecosystem.startControlling(target.id)
        onDispose { Ecosystem.stopControlling(target.id) }
    }

    fun sendMove() = Ecosystem.remoteInput(target.id, "move") {
        addProperty("x", x.toInt())
        addProperty("y", y.toInt())
    }

    fun click(button: String, down: Boolean) = Ecosystem.remoteInput(target.id, "button") {
        addProperty("button", button)
        addProperty("down", down)
        addProperty("x", x.toInt())
        addProperty("y", y.toInt())
    }

    Column(Modifier.fillMaxSize().background(Ink).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Stop controlling", onClose, style = PillStyle.Quiet)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(target.name, color = OnInk, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Caption("Move with one finger, scroll with two")
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(Panel)
                .pointerInput(target.id) {
                    val scale = (w / size.width.coerceAtLeast(1)) * 1.4f
                    awaitEachGesture {
                        val first = awaitFirstDown()
                        val start = System.currentTimeMillis()
                        var last = first.position
                        var moved = 0f
                        var maxPointers = 1
                        var lastTwoY: Float? = null
                        var scrollAcc = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            maxPointers = max(maxPointers, pressed.size)
                            if (pressed.size == 1 && maxPointers == 1) {
                                val p = pressed[0].position
                                val dx = p.x - last.x
                                val dy = p.y - last.y
                                last = p
                                moved += abs(dx) + abs(dy)
                                x = (x + dx * scale).coerceIn(0f, w - 1)
                                y = (y + dy * scale).coerceIn(0f, h - 1)
                                sendMove()
                            } else if (pressed.size >= 2) {
                                val avgY = pressed.map { it.position.y }.average().toFloat()
                                lastTwoY?.let {
                                    scrollAcc += avgY - it
                                    moved += abs(avgY - it)
                                }
                                lastTwoY = avgY
                                while (abs(scrollAcc) >= 40f) {
                                    val notch = if (scrollAcc > 0) 1f else -1f
                                    scrollAcc -= notch * 40f
                                    Ecosystem.remoteInput(target.id, "wheel") {
                                        addProperty("dy", notch)
                                        addProperty("x", x.toInt())
                                        addProperty("y", y.toInt())
                                    }
                                }
                            }
                            event.changes.forEach { it.consume() }
                        }
                        val quick = System.currentTimeMillis() - start < 300 && moved < 24f
                        if (quick && maxPointers == 1) {
                            click("left", true)
                            click("left", false)
                        } else if (quick && maxPointers == 2) {
                            Ecosystem.remoteInput(target.id, "global") { addProperty("action", "back") }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text("${x.toInt()}, ${y.toInt()}", color = OnInkMuted, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PillButton(if (holding) "Release" else "Hold", {
                holding = !holding
                click("left", holding)
            }, style = if (holding) PillStyle.Primary else PillStyle.Tonal, modifier = Modifier.weight(1f))
            RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", { Ecosystem.remoteInput(target.id, "global") { addProperty("action", "back") } })
            RoundIconButton(Icons.Default.Home, "Home", { Ecosystem.remoteInput(target.id, "global") { addProperty("action", "home") } })
            RoundIconButton(Icons.Default.Apps, "Recent apps", { Ecosystem.remoteInput(target.id, "global") { addProperty("action", "recents") } })
            RoundIconButton(Icons.Default.Notifications, "Notifications", { Ecosystem.remoteInput(target.id, "global") { addProperty("action", "notifications") } })
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Control).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                if (text.isEmpty()) Text("Type to send to ${target.name}", color = OnInkMuted, fontSize = 15.sp)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(color = OnInk, fontSize = 15.sp),
                    cursorBrush = SolidColor(OnInk),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            RoundIconButton(Icons.AutoMirrored.Filled.Send, "Send text", {
                if (text.isNotEmpty()) {
                    Ecosystem.remoteInput(target.id, "text") { addProperty("text", text) }
                    text = ""
                }
            }, style = PillStyle.Quiet)
        }
    }
}


/** Watch a stream; on a phone or tablet screen that allows it, tap and swipe to use it. */
@Composable
fun StreamViewerScreen(stream: LiveStream, onClose: () -> Unit) {
    val frame by Ecosystem.watchedFrame.collectAsState()
    val devices by Ecosystem.devices.collectAsState()
    val canControl = stream.kind == "screen" && devices.any { it.id == stream.owner && it.canBeControlled }
    DisposableEffect(stream.id) {
        Ecosystem.watch(stream.id)
        onDispose { Ecosystem.unwatch() }
    }
    val bitmap = remember(frame) {
        frame?.let { f -> android.graphics.BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.size)?.asImageBitmap() }
    }
    Column(Modifier.fillMaxSize().background(Ink).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Close", onClose, style = PillStyle.Quiet)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("${stream.ownerName} · ${stream.label}", color = OnInk, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                Caption(if (canControl) "Tap and swipe on the picture to use it" else "Watching")
            }
        }
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel),
            contentAlignment = Alignment.Center
        ) {
            val bmp = bitmap
            if (bmp == null) {
                Caption("Waiting for the first picture…")
            } else {
                val boxW = constraints.maxWidth.toFloat()
                val boxH = constraints.maxHeight.toFloat()
                val scale = minOf(boxW / bmp.width, boxH / bmp.height)
                val shownW = bmp.width * scale
                val shownH = bmp.height * scale
                val left = (boxW - shownW) / 2
                val top = (boxH - shownH) / 2
                val f = frame
                fun toDevice(p: androidx.compose.ui.geometry.Offset): Pair<Int, Int>? {
                    val fx = (p.x - left) / shownW
                    val fy = (p.y - top) / shownH
                    if (fx !in 0f..1f || fy !in 0f..1f || f == null) return null
                    return (fx * f.width).toInt() to (fy * f.height).toInt()
                }
                androidx.compose.foundation.Image(
                    bmp, "Live picture from ${stream.ownerName}",
                    Modifier.fillMaxSize().then(
                        if (!canControl) Modifier else Modifier.pointerInput(stream.id) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val start = toDevice(down.position) ?: return@awaitEachGesture
                                Ecosystem.remoteInput(stream.owner, "move") { addProperty("x", start.first); addProperty("y", start.second) }
                                Ecosystem.remoteInput(stream.owner, "button") {
                                    addProperty("button", "left"); addProperty("down", true)
                                    addProperty("x", start.first); addProperty("y", start.second)
                                }
                                var last = start
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    toDevice(change.position)?.let { p ->
                                        last = p
                                        Ecosystem.remoteInput(stream.owner, "move") { addProperty("x", p.first); addProperty("y", p.second) }
                                    }
                                    change.consume()
                                    if (!change.pressed) break
                                }
                                Ecosystem.remoteInput(stream.owner, "button") {
                                    addProperty("button", "left"); addProperty("down", false)
                                    addProperty("x", last.first); addProperty("y", last.second)
                                }
                            }
                        }
                    ),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
            }
        }
        if (canControl) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", { Ecosystem.remoteInput(stream.owner, "global") { addProperty("action", "back") } })
                RoundIconButton(Icons.Default.Home, "Home", { Ecosystem.remoteInput(stream.owner, "global") { addProperty("action", "home") } })
                RoundIconButton(Icons.Default.Apps, "Recent apps", { Ecosystem.remoteInput(stream.owner, "global") { addProperty("action", "recents") } })
            }
        }
    }
}


private fun qrBitmap(text: String, size: Int): androidx.compose.ui.graphics.ImageBitmap {
    val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { i ->
        if (matrix.get(i % size, i / size)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}
