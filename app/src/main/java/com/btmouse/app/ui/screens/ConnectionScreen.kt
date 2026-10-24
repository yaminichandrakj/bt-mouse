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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.QrCode
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.data.ConnectionMode
import com.btmouse.app.data.SettingsRepository
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

@SuppressLint("MissingPermission")
@Composable
fun ConnectionScreen(
    coordinator: ConnectionCoordinator,
    settings: SettingsRepository
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val activeMode by coordinator.activeMode.collectAsState()
    val isConnected by coordinator.isConnected.collectAsState()
    val isHealthy by coordinator.isConnectionHealthy.collectAsState()
    val linkLive = isConnected && isHealthy
    val statusMessage by coordinator.statusMessage.collectAsState()
    val connectedDevice by coordinator.connectedDeviceName.collectAsState()
    val reconnectStatus by coordinator.reconnectStatus.collectAsState()
    val activeTransportLabel by coordinator.activeTransportLabel.collectAsState()
    val failoverEnabled by settings.failoverEnabled.collectAsState()

    val pairedDevices by coordinator.bluetoothRfcommManager.pairedDevices.collectAsState()

    val wifiHost by settings.wifiHost.collectAsState()
    val wifiPort by settings.wifiPort.collectAsState()
    val savedServerPin by settings.serverPin.collectAsState()

    var manualHost by remember(wifiHost) { mutableStateOf(wifiHost) }
    var manualPort by remember(wifiPort) { mutableStateOf(wifiPort.toString()) }
    var wifiPin by remember(savedServerPin) { mutableStateOf(savedServerPin) }

    var showQrDialog by remember { mutableStateOf(false) }
    var qrInputText by remember { mutableStateOf("") }
    var qrError by remember { mutableStateOf<String?>(null) }

    fun parseAndConnectQr(rawText: String): Boolean {
        return try {
            val trimmed = rawText.trim()
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                val gson = Gson()
                val obj = gson.fromJson(trimmed, JsonObject::class.java)
                val type = obj.get("type")?.asString ?: ""
                val scannedPin = (obj.get("pin")?.asString ?: obj.get("token")?.asString)?.trim().orEmpty()
                if (scannedPin.isNotEmpty()) {
                    wifiPin = scannedPin
                    settings.setServerPin(scannedPin)
                    settings.setWanToken(scannedPin)
                }
                if (type == "btmouse_wan" || obj.has("url")) {
                    val url = obj.get("url")?.asString ?: ""
                    val token = scannedPin.ifBlank {
                        settings.serverPin.value.ifBlank { settings.wanToken.value }
                    }
                    if (url.isNotEmpty()) {
                        settings.setWanUrl(url)
                        coordinator.setMode(ConnectionMode.REMOTE_WAN, disconnectPrevious = false)
                        if (token.isNotBlank()) {
                            settings.setWanToken(token)
                            settings.setServerPin(token)
                            coordinator.prepareForManualConnect()
                            coordinator.remoteWanManager.connect(url, token)
                        } else {
                            Toast.makeText(context, "Enter the pairing PIN, then connect", Toast.LENGTH_LONG).show()
                        }
                        return true
                    }
                }
                val ip = obj.get("ip")?.asString ?: ""
                val port = obj.get("port")?.asInt ?: 53530
                val name = obj.get("name")?.asString ?: "PC Server"
                val effectivePin = scannedPin.ifBlank { wifiPin.trim() }

                if (ip.isNotEmpty()) {
                    manualHost = ip
                    manualPort = port.toString()
                    if (effectivePin.isNotEmpty()) {
                        wifiPin = effectivePin
                        settings.setServerPin(effectivePin)
                    }
                    settings.setWifiHost(ip)
                    settings.setWifiPort(port)
                    coordinator.setMode(ConnectionMode.WIFI, disconnectPrevious = false)
                    coordinator.prepareForManualConnect()
                    coordinator.wifiClientManager.connectManual(ip, port, name)
                    return true
                }
            } else if (trimmed.startsWith("btmouse://")) {
                val uri = android.net.Uri.parse(trimmed)
                val ip = uri.getQueryParameter("ip") ?: ""
                val port = uri.getQueryParameter("port")?.toIntOrNull() ?: 53530
                val name = uri.getQueryParameter("name") ?: "PC Server"
                val scannedPin = (uri.getQueryParameter("pin") ?: uri.getQueryParameter("token"))?.trim().orEmpty()
                if (scannedPin.isNotEmpty()) {
                    wifiPin = scannedPin
                }
                if (ip.isNotEmpty()) {
                    manualHost = ip
                    manualPort = port.toString()
                    settings.setServerPin(wifiPin)
                    settings.setWifiHost(ip)
                    settings.setWifiPort(port)
                    coordinator.setMode(ConnectionMode.WIFI, disconnectPrevious = false)
                    coordinator.prepareForManualConnect()
                    coordinator.wifiClientManager.connectManual(ip, port, name)
                    return true
                }
            } else if (trimmed.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(:\d+)?$"""))) {
                val parts = trimmed.split(":")
                val ip = parts[0]
                val port = if (parts.size > 1) parts[1].toIntOrNull() ?: 53530 else 53530
                manualHost = ip
                manualPort = port.toString()
                settings.setServerPin(wifiPin)
                settings.setWifiHost(ip)
                settings.setWifiPort(port)
                coordinator.setMode(ConnectionMode.WIFI, disconnectPrevious = false)
                coordinator.prepareForManualConnect()
                coordinator.wifiClientManager.connectManual(ip, port, "PC Server")
                return true
            }
            false
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
            val scanner = GmsBarcodeScanning.getClient(context, options)

            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    val rawValue = barcode.rawValue ?: barcode.displayValue ?: ""
                    if (rawValue.isNotEmpty()) {
                        val success = parseAndConnectQr(rawValue)
                        if (success) {
                            Toast.makeText(context, "Details scanned; connecting to PC companion...", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Unrecognized QR format: $rawValue", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .addOnCanceledListener {
                    // User dismissed the camera viewfinder
                }
                .addOnFailureListener { e ->
                    // Camera scanner unavailable, fall back to manual entry dialog
                    qrError = "Camera scanner unavailable: ${e.message ?: "Please enter IP manually"}"
                    showQrDialog = true
                }
        } catch (e: Exception) {
            qrError = "Scanner error: ${e.message}"
            showQrDialog = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Mode Selector Tabs
        val selectedTab = when (activeMode) {
            ConnectionMode.BLUETOOTH -> 0
            ConnectionMode.WIFI -> 1
            ConnectionMode.REMOTE_WAN -> 2
        }

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = DarkSurface,
            contentColor = TextPrimary,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = AccentCyan
                )
            }
        ) {
            Tab(
                selected = activeMode == ConnectionMode.BLUETOOTH,
                onClick = { coordinator.setMode(ConnectionMode.BLUETOOTH, disconnectPrevious = false) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Bluetooth", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }
                }
            )
            Tab(
                selected = activeMode == ConnectionMode.WIFI,
                onClick = { coordinator.setMode(ConnectionMode.WIFI, disconnectPrevious = false) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Wi-Fi", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }
                }
            )
            Tab(
                selected = activeMode == ConnectionMode.REMOTE_WAN,
                onClick = { coordinator.setMode(ConnectionMode.REMOTE_WAN, disconnectPrevious = false) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp), tint = AccentCyan)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Remote WAN", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }
                }
            )
        }

        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                                linkLive -> connectedDevice ?: "Connected"
                                isConnected -> "Waiting for the PC to respond"
                                else -> "Not connected"
                            },
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (linkLive && activeTransportLabel != "Disconnected") {
                                if (failoverEnabled) "Failover Active • $activeTransportLabel" else activeTransportLabel
                            } else {
                                statusMessage
                            },
                            color = if (linkLive) AccentCyan else TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = reconnectStatus ?: "",
                        color = AccentCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Row {
                        if (reconnectStatus?.contains("Tap to retry") == true) {
                            TextButton(onClick = { coordinator.startAutoReconnect() }) {
                                Text("Retry", color = AccentCyan, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        TextButton(onClick = { coordinator.cancelReconnect() }) {
                            Text("Cancel", color = AccentRose, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        // Mode Content
        when (activeMode) {
            ConnectionMode.BLUETOOTH -> {
                // Bluetooth Content
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Paired Devices", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Row {
                        IconButton(onClick = {
                            coordinator.bluetoothRfcommManager.refreshPairedDevices()
                            coordinator.bluetoothHidManager.refreshPairedDevices()
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = AccentCyan)
                        }
                        IconButton(onClick = {
                            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                            context.startActivity(intent)
                        }) {
                            Icon(Icons.Default.Settings, contentDescription = "BT Settings", tint = TextSecondary)
                        }
                    }
                }

                if (pairedDevices.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "No Paired Devices Found",
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Pair your PC with this phone in Windows Bluetooth Settings. It will connect via Bluetooth RFCOMM or HID!",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = {
                                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                                    context.startActivity(intent)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Open Bluetooth Settings", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(pairedDevices) { device ->
                            DeviceItem(
                                device = device,
                                isCurrentlyConnected = isConnected && connectedDevice?.contains(device.name ?: "") == true,
                                onConnect = {
                                    coordinator.prepareForManualConnect()
                                    coordinator.bluetoothRfcommManager.connectToDevice(device)
                                    coordinator.bluetoothHidManager.connectToDevice(device)
                                }
                            )
                        }
                    }
                }
            }

            ConnectionMode.WIFI -> {
                // Wi-Fi Content
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // QR Code & Auto Discover Action Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { launchQrScanner() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = DarkBackground)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Scan QR Code", color = DarkBackground, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                settings.setServerPin(wifiPin)
                                coordinator.wifiClientManager.discoverServer { host, port, _ ->
                                    manualHost = host
                                    manualPort = port.toString()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Auto-Discover")
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Manual IP Configuration", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)

                            OutlinedTextField(
                                value = manualHost,
                                onValueChange = { manualHost = it },
                                label = { Text("PC IP Address (e.g. 172.25.100.123)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = PrimaryIndigo,
                                    unfocusedBorderColor = DarkBorder,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = manualPort,
                                onValueChange = { manualPort = it },
                                label = { Text("Port (Default: 53530)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = PrimaryIndigo,
                                    unfocusedBorderColor = DarkBorder,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = wifiPin,
                                onValueChange = { wifiPin = it.filter(Char::isDigit).take(12) },
                                label = { Text("PC companion pairing PIN", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = PrimaryIndigo,
                                    unfocusedBorderColor = DarkBorder,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation()
                            )

                            Button(
                                onClick = {
                                    val portInt = manualPort.toIntOrNull() ?: 53530
                                    settings.setServerPin(wifiPin)
                                    settings.setWifiHost(manualHost)
                                    settings.setWifiPort(portInt)
                                    coordinator.prepareForManualConnect()
                                    coordinator.wifiClientManager.connectManual(manualHost, portInt)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Connect to PC", color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("PC Companion App", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Launch 'BT-Mouse-Companion.exe' or 'run_pc_app.bat' on your PC to see the instant connection QR code and live packet monitor.",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            ConnectionMode.REMOTE_WAN -> {
                // Remote WAN Content
                val storedWanUrl by settings.wanUrl.collectAsState()
                val storedWanToken by settings.wanToken.collectAsState()
                var inputWanUrl by remember(storedWanUrl) { mutableStateOf(storedWanUrl) }
                var inputWanToken by remember(storedWanToken, savedServerPin) {
                    mutableStateOf(savedServerPin.ifBlank { storedWanToken })
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Button(
                        onClick = { launchQrScanner() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = DarkBackground)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scan WAN QR Code from PC", color = DarkBackground, fontWeight = FontWeight.Bold)
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Remote WAN / 5G Setup", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)

                            OutlinedTextField(
                                value = inputWanUrl,
                                onValueChange = { inputWanUrl = it },
                                label = { Text("WebSocket URL (use wss:// over the Internet)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                placeholder = { Text("wss://your-domain:53531 (Internet) or ws://PC-IP:53531 (LAN)") },
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
                                value = inputWanToken,
                                onValueChange = { inputWanToken = it },
                                label = { Text("Security Access Key / PIN", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                placeholder = { Text("Enter the PIN shown by the PC companion") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentCyan,
                                    unfocusedBorderColor = DarkBorder,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                singleLine = true
                            )

                            Button(
                                onClick = {
                                    settings.setWanUrl(inputWanUrl.trim())
                                    settings.setWanToken(inputWanToken.trim())
                                    settings.setServerPin(inputWanToken.trim())
                                    coordinator.prepareForManualConnect()
                                    coordinator.remoteWanManager.connect(inputWanUrl.trim(), inputWanToken.trim())
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Connect Over Internet", color = DarkBackground, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Language, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Anywhere Access (WAN / Cellular)", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "For Internet access, use a TLS-terminated wss:// address from a trusted tunnel or reverse proxy. Never forward the companion's plain ws:// listener directly to the Internet.",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }

    // Manual / QR Fallback Connect Modal Dialog
    if (showQrDialog) {
        AlertDialog(
            onDismissRequest = {
                showQrDialog = false
                qrError = null
            },
            containerColor = DarkSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Computer, contentDescription = null, tint = AccentCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Manual PC Connection", color = TextPrimary, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Enter your PC's IP address (shown on the PC Companion app screen):",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )

                    OutlinedTextField(
                        value = qrInputText,
                        onValueChange = {
                            qrInputText = it
                            qrError = null
                        },
                        label = { Text("PC IP Address (e.g. 172.25.100.123)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        placeholder = { Text("172.25.100.123") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                val clipText = clipboardManager.getText()?.text ?: ""
                                if (clipText.isNotEmpty()) {
                                    qrInputText = clipText
                                }
                            }
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp), tint = AccentCyan)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Paste from Clipboard", color = AccentCyan, fontSize = 12.sp)
                        }
                    }

                    if (qrError != null) {
                        Text(qrError!!, color = AccentRose, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val success = parseAndConnectQr(qrInputText)
                        if (success) {
                            showQrDialog = false
                            qrError = null
                        } else {
                            qrError = "Invalid IP format. Please check the IP shown on your PC screen."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                ) {
                    Text("Connect to PC", color = DarkBackground, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            dismissButton = {
                TextButton(onClick = { showQrDialog = false }) {
                    Text("Cancel", color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        )
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
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isCurrentlyConnected) AccentEmerald else DarkBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isCurrentlyConnected) Icons.Default.BluetoothConnected else Icons.Default.Computer,
                    contentDescription = null,
                    tint = if (isCurrentlyConnected) AccentEmerald else AccentCyan
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(text = devName, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(text = device.address, color = TextMuted, fontSize = 11.sp)
                }
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
