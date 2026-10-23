package com.btmouse.app.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.btmouse.app.data.HidConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class BluetoothHidManager(
    private val context: Context,
    private val scope: CoroutineScope
) : InputConnection {

    private val tag = "BluetoothHidManager"

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isAppRegistered = MutableStateFlow(false)
    val isAppRegistered: StateFlow<Boolean> = _isAppRegistered.asStateFlow()

    private val _statusMessage = MutableStateFlow("Bluetooth initializing...")
    override val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    override val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val pairedDevices: StateFlow<List<BluetoothDevice>> = _pairedDevices.asStateFlow()

    private var currentMouseButtons = HidConstants.MOUSE_BUTTON_NONE
    private var currentModifiers: Byte = HidConstants.MODIFIER_NONE

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            super.onAppStatusChanged(pluggedDevice, registered)
            Log.d(tag, "HID App Status changed: registered=$registered")
            _isAppRegistered.value = registered
            if (registered) {
                _statusMessage.value = "HID Service Ready. Waiting for PC..."
                refreshPairedDevices()
            } else {
                _statusMessage.value = "HID Service not registered"
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            super.onConnectionStateChanged(device, state)
            val devName = try { device?.name ?: device?.address ?: "PC" } catch (e: SecurityException) { "PC" }
            Log.d(tag, "Connection state changed: state=$state, device=$devName")

            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    _isConnected.value = true
                    _connectedDeviceName.value = devName
                    _statusMessage.value = "Connected to $devName"
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    _statusMessage.value = "Connecting to $devName..."
                }
                BluetoothProfile.STATE_DISCONNECTING -> {
                    _statusMessage.value = "Disconnecting..."
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectedDevice == device || connectedDevice == null) {
                        connectedDevice = null
                        _isConnected.value = false
                        _connectedDeviceName.value = null
                        _statusMessage.value = "Disconnected"
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            super.onGetReport(device, type, id, bufferSize)
            Log.d(tag, "onGetReport: type=$type, id=$id")
            if (id == HidConstants.REPORT_ID_MOUSE) {
                val emptyMouse = byteArrayOf(0, 0, 0, 0, 0)
                try {
                    hidDevice?.replyReport(device, type, id, emptyMouse)
                } catch (e: SecurityException) {
                    Log.e(tag, "SecurityException on replyReport", e)
                }
            }
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                Log.d(tag, "HID Profile proxy connected")
                hidDevice = proxy as? BluetoothHidDevice
                registerHidApp()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                Log.d(tag, "HID Profile proxy disconnected")
                hidDevice = null
                _isAppRegistered.value = false
                _isConnected.value = false
                _connectedDeviceName.value = null
            }
        }
    }

    fun initialize() {
        if (bluetoothAdapter == null) {
            _statusMessage.value = "Bluetooth is not supported on this device"
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            _statusMessage.value = "Bluetooth is turned off. Please enable Bluetooth."
            return
        }

        try {
            bluetoothAdapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
        } catch (e: Exception) {
            Log.e(tag, "Failed to getProfileProxy", e)
            _statusMessage.value = "Error initializing HID profile: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerHidApp() {
        val hid = hidDevice ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "BT Mouse & Keyboard",
            "Universal Remote HID Device",
            "Android BT Mouse",
            0xC0.toByte(), // Combo Keyboard/Mouse subclass
            HidConstants.HID_REPORT_DESCRIPTOR
        )

        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = hid.registerApp(sdp, null, null, executor, hidCallback)
            Log.d(tag, "registerApp result: $result")
            if (!result) {
                _statusMessage.value = "Failed to register HID App (Not supported by kernel?)"
            }
        } catch (e: SecurityException) {
            Log.e(tag, "SecurityException during registerApp", e)
            _statusMessage.value = "Bluetooth permission required"
        } catch (e: Exception) {
            Log.e(tag, "Exception during registerApp", e)
            _statusMessage.value = "HID Registration error: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshPairedDevices() {
        try {
            val devices = bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
            _pairedDevices.value = devices
        } catch (e: SecurityException) {
            Log.e(tag, "SecurityException getting bonded devices", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        val hid = hidDevice ?: run {
            _statusMessage.value = "HID Device not ready"
            return
        }
        try {
            _statusMessage.value = "Connecting to ${device.name ?: device.address}..."
            hid.connect(device)
        } catch (e: SecurityException) {
            Log.e(tag, "SecurityException connecting to device", e)
            _statusMessage.value = "Bluetooth permission error"
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        val dev = connectedDevice ?: return
        val hid = hidDevice ?: return
        try {
            hid.disconnect(dev)
        } catch (e: SecurityException) {
            Log.e(tag, "SecurityException disconnecting", e)
        }
    }

    @SuppressLint("MissingPermission")
    override fun sendMouseMove(dx: Int, dy: Int, buttons: Int, vScroll: Int, hScroll: Int) {
        val dev = connectedDevice ?: return
        val hid = hidDevice ?: return

        currentMouseButtons = buttons
        val clampedDx = dx.coerceIn(-127, 127).toByte()
        val clampedDy = dy.coerceIn(-127, 127).toByte()
        val clampedVScroll = vScroll.coerceIn(-127, 127).toByte()
        val clampedHScroll = hScroll.coerceIn(-127, 127).toByte()

        val report = byteArrayOf(
            buttons.toByte(),
            clampedDx,
            clampedDy,
            clampedVScroll,
            clampedHScroll
        )

        try {
            hid.sendReport(dev, HidConstants.REPORT_ID_MOUSE.toInt(), report)
        } catch (e: SecurityException) {
            Log.e(tag, "SecurityException sending mouse report", e)
        }
    }

    override fun sendMouseButton(buttons: Int) {
        sendMouseMove(0, 0, buttons, 0, 0)
    }

    @SuppressLint("MissingPermission")
    override fun sendKeyPress(keyCode: Byte, modifiers: Byte) {
        val dev = connectedDevice ?: return
        val hid = hidDevice ?: return

        currentModifiers = modifiers
        val report = byteArrayOf(
            modifiers,
            0x00,           // Reserved
            keyCode,
            0x00, 0x00, 0x00, 0x00, 0x00 // 6-key rollover slots
        )

        try {
            hid.sendReport(dev, HidConstants.REPORT_ID_KEYBOARD.toInt(), report)
        } catch (e: SecurityException) {
            Log.e(tag, "SecurityException sending keyboard report", e)
        }
    }

    @SuppressLint("MissingPermission")
    override fun sendKeyRelease(keyCode: Byte, modifiers: Byte) {
        val dev = connectedDevice ?: return
        val hid = hidDevice ?: return

        currentModifiers = modifiers
        val report = byteArrayOf(
            modifiers,
            0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )

        try {
            hid.sendReport(dev, HidConstants.REPORT_ID_KEYBOARD.toInt(), report)
        } catch (e: SecurityException) {
            Log.e(tag, "SecurityException sending key release", e)
        }
    }

    override fun sendKeyStroke(keyCode: Byte, modifiers: Byte) {
        scope.launch(Dispatchers.IO) {
            sendKeyPress(keyCode, modifiers)
            delay(15)
            sendKeyRelease(keyCode, modifiers)
        }
    }

    @SuppressLint("MissingPermission")
    override fun sendMediaKey(usageCode: Int) {
        val dev = connectedDevice ?: return
        val hid = hidDevice ?: return

        scope.launch(Dispatchers.IO) {
            val reportPress = byteArrayOf(
                (usageCode and 0xFF).toByte(),
                ((usageCode shr 8) and 0xFF).toByte()
            )
            val reportRelease = byteArrayOf(0x00, 0x00)

            try {
                hid.sendReport(dev, HidConstants.REPORT_ID_CONSUMER.toInt(), reportPress)
                delay(30)
                hid.sendReport(dev, HidConstants.REPORT_ID_CONSUMER.toInt(), reportRelease)
            } catch (e: SecurityException) {
                Log.e(tag, "SecurityException sending media key", e)
            }
        }
    }

    override fun sendText(text: String) {
        scope.launch(Dispatchers.IO) {
            for (char in text) {
                val hidPair = HidConstants.charToHid(char)
                if (hidPair != null) {
                    sendKeyPress(hidPair.second, hidPair.first)
                    delay(12)
                    sendKeyRelease(hidPair.second, hidPair.first)
                    delay(12)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun cleanup() {
        try {
            hidDevice?.let { hid ->
                bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
            }
        } catch (e: SecurityException) {
            Log.e(tag, "SecurityException during cleanup", e)
        }
    }
}
