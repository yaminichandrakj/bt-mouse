package com.btmouse.app.ui.screens

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.connection.LinkState
import com.btmouse.app.data.SettingsRepository
import com.btmouse.app.ui.theme.Ink
import com.btmouse.app.ui.theme.AccentAmber
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
import com.google.gson.JsonObject

private const val DEFAULT_UDP_PORT = 53530
private const val DEFAULT_WS_PORT = 53531

/** One Connect screen: scan the PC's single QR once, then Wi-Fi, Bluetooth and internet all come up together. */
@SuppressLint("MissingPermission")
@Composable
fun ConnectionScreen(
    coordinator: ConnectionCoordinator,
    settings: SettingsRepository
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val isConnected by coordinator.isConnected.collectAsState()
    val isHealthy by coordinator.isConnectionHealthy.collectAsState()
    val linkLive = isConnected && isHealthy
    val statusMessage by coordinator.statusMessage.collectAsState()
    val connectedDevice by coordinator.connectedDeviceName.collectAsState()
    val reconnectStatus by coordinator.reconnectStatus.collectAsState()
    val activeTransportLabel by coordinator.activeTransportLabel.collectAsState()
    val links by coordinator.linkStates.collectAsState()
    val linkNotice by coordinator.linkNotice.collectAsState()
    val retrying by coordinator.retryingLinks.collectAsState()
    val pairedDevices by coordinator.bluetoothRfcommManager.pairedDevices.collectAsState()

    val pcName by settings.pcName.collectAsState()
    val savedPin by settings.serverPin.collectAsState()
    val wifiHost by settings.wifiHost.collectAsState()
    val wifiPort by settings.wifiPort.collectAsState()
    val wanUrl by settings.wanUrl.collectAsState()
    val rvWorker by settings.rvWorker.collectAsState()
    val pcBluetoothAddress by settings.pcBluetoothAddress.collectAsState()

    val pinValid = savedPin.length == 6 && savedPin.all { it.isDigit() }
    val hasPairedPc = pinValid && (pcName.isNotBlank() || rvWorker.isNotBlank() || wanUrl.isNotBlank())
    val pcBluetoothPaired = pairedDevices.any { device ->
        val name = try { device.name } catch (_: SecurityException) { null }
        device.address.equals(pcBluetoothAddress, ignoreCase = true) ||
            (pcName.isNotBlank() && name.equals(pcName, ignoreCase = true))
    }

    var showPinDialog by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }
    var manualError by remember { mutableStateOf<String?>(null) }
    var showBluetoothList by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }

    /** Save everything one pairing QR carries, then bring up every link. Returns false if unrecognised. */
    fun pairFromCode(rawText: String): Boolean {
        val trimmed = rawText.trim()
        return try {
            when {
                trimmed.startsWith("{") && trimmed.endsWith("}") -> {
                    val obj = Gson().fromJson(trimmed, JsonObject::class.java)
                    val type = obj.get("type")?.asString.orEmpty()
                    val ip = obj.get("ip")?.asString.orEmpty()
                    val name = obj.get("name")?.asString.orEmpty()
                    // Older "btmouse_wan" codes put the WebSocket port in "port".
                    val udpPort = if (type == "btmouse_wan") DEFAULT_UDP_PORT else obj.get("port")?.asInt ?: DEFAULT_UDP_PORT
                    val wsPort = obj.get("ws_port")?.asInt ?: DEFAULT_WS_PORT
                    val wsUrl = obj.get("url")?.asString ?: if (ip.isNotBlank()) "ws://$ip:$wsPort" else ""
                    val rv = obj.getAsJsonObject("rv")
                    if (ip.isBlank() && wsUrl.isBlank() && rv == null) return false
                    val pin = (obj.get("pin")?.asString ?: obj.get("token")?.asString)?.trim().orEmpty()
                        .ifBlank { savedPin }
                    settings.savePairedPc(name, ip, udpPort, pin, wsUrl)
                    settings.setRendezvous(
                        rv?.get("worker")?.asString.orEmpty(),
                        rv?.get("id")?.asString.orEmpty(),
                        rv?.get("pub")?.asString.orEmpty()
                    )
                }
                trimmed.startsWith("btmouse://") -> {
                    val uri = android.net.Uri.parse(trimmed)
                    val ip = uri.getQueryParameter("ip").orEmpty()
                    if (ip.isBlank()) return false
                    val pin = (uri.getQueryParameter("pin") ?: uri.getQueryParameter("token")).orEmpty().ifBlank { savedPin }
                    settings.savePairedPc(
                        uri.getQueryParameter("name").orEmpty(), ip,
                        uri.getQueryParameter("port")?.toIntOrNull() ?: DEFAULT_UDP_PORT, pin, "ws://$ip:$DEFAULT_WS_PORT"
                    )
                }
                trimmed.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}(:\d+)?$""")) -> {
                    val parts = trimmed.split(":")
                    val ip = parts[0]
                    settings.savePairedPc(
                        pcName, ip, parts.getOrNull(1)?.toIntOrNull() ?: DEFAULT_UDP_PORT, savedPin, "ws://$ip:$DEFAULT_WS_PORT"
                    )
                }
                else -> return false
            }
            val pin = settings.serverPin.value
            if (pin.length == 6 && pin.all { it.isDigit() }) {
                coordinator.connectSavedPc()
            } else {
                showPinDialog = true
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun launchQrScanner() {
        try {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()
            GmsBarcodeScanning.getClient(context, options).startScan()
                .addOnSuccessListener { barcode ->
                    val raw = barcode.rawValue ?: barcode.displayValue ?: ""
                    if (raw.isNotEmpty()) {
                        if (pairFromCode(raw)) {
                            Toast.makeText(context, "PC paired; connecting over every available link…", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "That QR code isn't from Lanyard for PC", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .addOnFailureListener { e ->
                    manualError = "Camera scanner unavailable: ${e.message ?: "enter the details by hand"}"
                    showManualDialog = true
                }
        } catch (e: Exception) {
            manualError = "Scanner error: ${e.message}"
            showManualDialog = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Status: overall state plus one chip per link.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (linkLive) AccentEmerald else if (isConnected) AccentAmber else AccentRose)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                linkLive -> connectedDevice ?: pcName.ifBlank { "Connected" }
                                isConnected -> "Waiting for the PC to respond"
                                else -> "Not connected"
                            },
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (linkLive && activeTransportLabel != "Disconnected") "Using $activeTransportLabel" else statusMessage,
                            color = if (linkLive) AccentCyan else TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (isConnected) {
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { coordinator.disconnectAll() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRose),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Disconnect", fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
                Column {
                    LinkRetryRow("Wi-Fi", Icons.Default.Wifi, links.wifi, ConnectionCoordinator.Link.WIFI in retrying,
                        activeTransportLabel.startsWith("Wi-Fi")) { coordinator.retryLink(ConnectionCoordinator.Link.WIFI) }
                    LinkRetryRow("Bluetooth", Icons.Default.Bluetooth, links.bluetooth, ConnectionCoordinator.Link.BLUETOOTH in retrying,
                        activeTransportLabel.startsWith("Bluetooth")) { coordinator.retryLink(ConnectionCoordinator.Link.BLUETOOTH) }
                    LinkRetryRow("Internet", Icons.Default.Language, links.internet, ConnectionCoordinator.Link.INTERNET in retrying,
                        activeTransportLabel.startsWith("Remote")) { coordinator.retryLink(ConnectionCoordinator.Link.INTERNET) }
                }
                if (links.wifi == LinkState.OFF || links.bluetooth == LinkState.OFF || links.internet == LinkState.OFF) {
                    com.btmouse.app.ui.components.PillButton(
                        "Retry all", {
                            ConnectionCoordinator.Link.values().forEach { coordinator.retryLink(it) }
                        },
                        style = com.btmouse.app.ui.components.PillStyle.Selected, height = 42.dp
                    )
                }
            }
        }

        if (reconnectStatus != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = reconnectStatus.orEmpty(),
                        color = AccentCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (reconnectStatus?.contains("Tap to retry") == true) {
                        TextButton(onClick = { coordinator.startAutoReconnect() }) {
                            Text("Retry", color = AccentCyan, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                    TextButton(onClick = { coordinator.cancelReconnect() }) {
                        Text("Cancel", color = AccentRose, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }

        linkNotice?.let { notice ->
            Text(notice, color = AccentAmber, fontSize = 12.sp)
        }

        if (hasPairedPc) {
            // The paired PC: connect, change the PIN, or pair a different PC.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Computer, contentDescription = null, tint = AccentCyan)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                pcName.ifBlank { wifiHost.ifBlank { "Your PC" } },
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                buildString {
                                    append("PIN ••••••")
                                    if (rvWorker.isNotBlank()) append("  ·  internet ready")
                                },
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                    if (!isConnected) {
                        Button(
                            onClick = { coordinator.connectSavedPc() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Connect", color = TextPrimary, fontWeight = FontWeight.Medium, maxLines = 1)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { showPinDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Change PIN", color = TextPrimary, fontSize = 12.sp, maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { launchQrScanner() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Scan again", color = TextPrimary, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                    TextButton(onClick = {
                        coordinator.disconnectAll()
                        settings.forgetPairedPc()
                    }) {
                        Text("Forget this PC", color = AccentRose, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        } else {
            // Not paired yet: one scan does everything.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Pair your PC", color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                    Text(
                        "1. Open Lanyard for PC on your computer and go to Connect.\n" +
                            "2. Scan its QR code here.\n\n" +
                            "That's it: the app saves the PC and connects over Wi-Fi, Bluetooth and the " +
                            "internet by itself. Later you only need to change the PIN.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Button(
                        onClick = { launchQrScanner() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Ink),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = DarkBackground)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scan PC QR code", color = DarkBackground, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                    TextButton(onClick = { showManualDialog = true }) {
                        Text("Enter the details by hand instead", color = AccentCyan, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }

        // Bluetooth: tap the PC once so it is matched exactly; also usable as a plain Bluetooth mouse.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showBluetoothList = !showBluetoothList },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Bluetooth, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Bluetooth", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            when {
                                hasPairedPc && pcBluetoothPaired -> "Connects automatically when the PC is nearby"
                                hasPairedPc -> "Optional: works with no network once the PC is paired in Bluetooth settings"
                                else -> "Use as a Bluetooth mouse and keyboard, even without Lanyard for PC"
                            },
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                    Icon(
                        if (showBluetoothList) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (showBluetoothList) "Hide devices" else "Show devices",
                        tint = TextSecondary
                    )
                }
                if (showBluetoothList) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Paired devices", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            coordinator.bluetoothRfcommManager.refreshPairedDevices()
                            coordinator.bluetoothHidManager.refreshPairedDevices()
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = AccentCyan)
                        }
                        IconButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
                            Icon(Icons.Default.Settings, contentDescription = "Bluetooth settings", tint = TextSecondary)
                        }
                    }
                    if (pairedDevices.isEmpty()) {
                        Text(
                            "No paired devices. Pair this phone with your PC in Bluetooth settings, then refresh.",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    } else {
                        pairedDevices.forEach { device ->
                            DeviceItem(
                                device = device,
                                isCurrentlyConnected = isConnected && connectedDevice?.contains(device.name ?: "") == true,
                                onConnect = {
                                    // Remember it as this PC's Bluetooth device so later connects pick it exactly.
                                    if (hasPairedPc) settings.setPcBluetoothAddress(device.address)
                                    coordinator.prepareForManualConnect()
                                    coordinator.bluetoothRfcommManager.connectToDevice(device)
                                    coordinator.bluetoothHidManager.connectToDevice(device)
                                }
                            )
                        }
                    }
                }
            }
        }

        // Manual setup for networks where scanning or discovery doesn't work.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAdvanced = !showAdvanced },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Manual setup", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Icon(
                        if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                }
                if (showAdvanced) {
                    Text(
                        if (hasPairedPc) "Saved local address: $wifiHost:$wifiPort" else "Find the PC on this Wi-Fi, or type its address.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                coordinator.wifiClientManager.discoverServer { host, port, name ->
                                    settings.savePairedPc(
                                        name.ifBlank { pcName }, host, port, savedPin,
                                        "ws://$host:$DEFAULT_WS_PORT"
                                    )
                                    if (pinValid) coordinator.connectSavedPc() else showPinDialog = true
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Find on Wi-Fi", color = TextPrimary, fontSize = 12.sp, maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { showManualDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Type address", color = TextPrimary, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    if (showPinDialog) {
        var pinInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            containerColor = DarkSurface,
            title = { Text("Enter the PC's PIN", color = TextPrimary, fontWeight = FontWeight.Medium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Type the 6-digit PIN shown on the Connect page of Lanyard for PC. Changing it there only needs this, not a new scan.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { pinInput = it.filter(Char::isDigit).take(6) },
                        label = { Text("PIN", maxLines = 1) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        settings.setPairingPin(pinInput)
                        showPinDialog = false
                        coordinator.disconnectAll()
                        coordinator.connectSavedPc()
                    },
                    enabled = pinInput.length == 6,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Ink)
                ) {
                    Text("Save and connect", color = DarkBackground, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false }) {
                    Text("Cancel", color = TextMuted, maxLines = 1)
                }
            }
        )
    }

    if (showManualDialog) {
        var addressInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {
                showManualDialog = false
                manualError = null
            },
            containerColor = DarkSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Computer, contentDescription = null, tint = AccentCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add your PC by hand", color = TextPrimary, fontWeight = FontWeight.Medium)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Type the IP address shown on the Connect page of Lanyard for PC, or paste a copied pairing code.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = addressInput,
                        onValueChange = {
                            addressInput = it
                            manualError = null
                        },
                        label = { Text("PC IP address or pairing code", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        placeholder = { Text("172.25.100.123") },
                        singleLine = true,
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = {
                        clipboardManager.getText()?.text?.takeIf { it.isNotBlank() }?.let { addressInput = it }
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp), tint = AccentCyan)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Paste from clipboard", color = AccentCyan, fontSize = 12.sp)
                    }
                    manualError?.let { Text(it, color = AccentRose, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pairFromCode(addressInput)) {
                            showManualDialog = false
                            manualError = null
                        } else {
                            manualError = "That isn't an IP address or a Lanyard pairing code."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Ink)
                ) {
                    Text("Connect", color = DarkBackground, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualDialog = false }) {
                    Text("Cancel", color = TextMuted, maxLines = 1)
                }
            }
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentCyan,
    unfocusedBorderColor = DarkBorder,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary
)

/** One link with its state; tap it to try that link right now. */
@Composable
private fun LinkRetryRow(label: String, icon: ImageVector, state: LinkState, trying: Boolean, inUse: Boolean, onRetry: () -> Unit) {
    val (sub, subColor) = when {
        trying -> "Trying to connect…" to TextSecondary
        state == LinkState.LIVE && inUse -> "Connected · in use" to AccentEmerald
        state == LinkState.LIVE -> "Ready · takes over if needed" to TextSecondary
        state == LinkState.WEAK -> "Slow · tap to reconnect" to AccentAmber
        else -> "Not connected · tap to try now" to TextMuted
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = !trying && !(state == LinkState.LIVE), onClick = onRetry)
            .padding(horizontal = 6.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = TextPrimary, fontSize = 16.sp)
            Text(sub, color = subColor, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            trying -> androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp, color = TextPrimary, trackColor = DarkBorder
            )
            state == LinkState.LIVE -> Box(
                Modifier.clip(CircleShape).background(Color(0xFF0F3D1E)).padding(horizontal = 12.dp, vertical = 5.dp)
            ) { Text(if (inUse) "In use" else "Ready", color = Color(0xFFC4EED0), fontSize = 13.sp) }
            else -> Box(
                Modifier.clip(CircleShape).border(1.dp, com.btmouse.app.ui.theme.Outline, CircleShape).padding(horizontal = 14.dp, vertical = 6.dp)
            ) { Text("Retry", color = TextPrimary, fontSize = 14.sp) }
        }
    }
}

@Composable
private fun LinkChip(label: String, icon: ImageVector, state: LinkState, modifier: Modifier = Modifier) {
    val (color: Color, stateText) = when (state) {
        LinkState.LIVE -> AccentEmerald to "On"
        LinkState.WEAK -> AccentAmber to "Slow"
        LinkState.OFF -> TextMuted to "Off"
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurfaceVariant)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(label, color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stateText, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun DeviceItem(
    device: BluetoothDevice,
    isCurrentlyConnected: Boolean,
    onConnect: () -> Unit
) {
    val devName = try { device.name ?: device.address } catch (e: SecurityException) { device.address }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isCurrentlyConnected) AccentEmerald else DarkBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isCurrentlyConnected) Icons.Default.BluetoothConnected else Icons.Default.Computer,
                contentDescription = null,
                tint = if (isCurrentlyConnected) AccentEmerald else AccentCyan
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = devName, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = device.address, color = TextMuted, fontSize = 13.sp)
            }
            Button(
                onClick = onConnect,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isCurrentlyConnected) AccentEmerald.copy(alpha = 0.2f) else PrimaryIndigo,
                    contentColor = if (isCurrentlyConnected) AccentEmerald else TextPrimary
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (isCurrentlyConnected) "Active" else "Connect", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
