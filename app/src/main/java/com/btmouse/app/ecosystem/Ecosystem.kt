package com.btmouse.app.ecosystem

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import com.btmouse.app.connection.ConnectionCoordinator
import com.btmouse.app.control.RemoteControlService
import com.btmouse.app.control.RemoteKeyboardService
import com.btmouse.app.data.SettingsRepository
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.security.MessageDigest

/** Another device in the ecosystem, as the PC last described it. */
data class PeerDevice(
    val id: String,
    val name: String,
    val kind: String,
    val screenW: Int = 0,
    val screenH: Int = 0,
    val canBeControlled: Boolean = false,
    val acceptsFiles: Boolean = true
) {
    val isPc: Boolean get() = kind == "pc"
}

/**
 * This device's part of the ecosystem: says hello to the PC, answers remote control,
 * syncs the clipboard, and moves files. Everything travels over whichever link the
 * coordinator is using; messages for another phone or tablet carry ``to`` and the PC
 * relays them.
 */
object Ecosystem {
    const val PC_ID = "pc"

    private lateinit var appContext: Context
    private var coordinator: ConnectionCoordinator? = null
    private var settings: SettingsRepository? = null
    private val main = Handler(Looper.getMainLooper())
    private var initialized = false

    lateinit var files: FileTransfers
        private set

    private val _devices = MutableStateFlow<List<PeerDevice>>(emptyList())
    /** Other devices reachable through the PC, the PC itself first. */
    val devices: StateFlow<List<PeerDevice>> = _devices.asStateFlow()

    private val _controlledBy = MutableStateFlow<String?>(null)
    /** Name of the device currently driving this one, or null. */
    val controlledBy: StateFlow<String?> = _controlledBy.asStateFlow()

    private val _lastClipboard = MutableStateFlow<String?>(null)
    val lastClipboardNotice: StateFlow<String?> = _lastClipboard.asStateFlow()

    private var lastClipId: String? = null
    private var suppressNextLocalClip = false

    val selfId: String get() = DeviceIdentity.id(appContext)

    fun init(context: Context, coordinator: ConnectionCoordinator, settings: SettingsRepository, scope: CoroutineScope) {
        this.coordinator = coordinator
        this.settings = settings
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        files = FileTransfers(appContext, scope) { send(it) }

        scope.launch {
            coordinator.isConnected.collectLatest { connected ->
                if (!connected) {
                    _controlledBy.value = null
                    RemoteControlService.instance?.setControlled(false)
                    return@collectLatest
                }
                delay(400)
                while (true) {
                    sendHello()
                    send(JsonObject().apply { addProperty("type", "devices_query") })
                    delay(10_000)
                }
            }
        }

        appContext.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                main.postDelayed({ sendHello() }, 300)
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {}
        })

        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.addPrimaryClipChangedListener {
            if (suppressNextLocalClip) {
                suppressNextLocalClip = false
                return@addPrimaryClipChangedListener
            }
            // Android only lets the focused app or the active keyboard read this; elsewhere it is null.
            pushClipboardIfNew()
        }
    }

    fun onControlCapabilityChanged() {
        if (initialized) main.post { sendHello() }
    }

    // --- outgoing ------------------------------------------------------
    fun send(obj: JsonObject) {
        coordinator?.sendJsonObject(obj)
    }

    /** Address a message to another device; the PC needs no ``to``. */
    fun sendTo(deviceId: String, obj: JsonObject) {
        if (deviceId != PC_ID) obj.addProperty("to", deviceId)
        send(obj)
    }

    fun sendHello() {
        if (!initialized) return
        val (w, h) = DeviceIdentity.screenSize(appContext)
        val allowControl = settings?.allowRemoteControl?.value ?: true
        send(JsonObject().apply {
            addProperty("type", "device_hello")
            addProperty("device_id", selfId)
            addProperty("name", DeviceIdentity.name(appContext))
            addProperty("kind", if (DeviceIdentity.isTablet(appContext)) "tablet" else "phone")
            addProperty("screen_w", w)
            addProperty("screen_h", h)
            addProperty("density", appContext.resources.displayMetrics.density)
            addProperty("rotation", DeviceIdentity.rotationDegrees(appContext))
            add("caps", JsonObject().apply {
                addProperty("control", allowControl && RemoteControlService.instance != null)
                addProperty("keyboard", RemoteKeyboardService.instance != null)
                addProperty("clipboard", settings?.clipboardSync?.value ?: true)
                addProperty("files", true)
            })
        })
    }

    // --- incoming ------------------------------------------------------
    private val handledTypes = setOf(
        "device_hello_ack", "control_state", "remote_input", "clipboard_push", "devices_update",
        "file_offer", "file_accept", "file_chunk", "file_chunk_ack", "file_done", "file_result", "file_cancel"
    )

    /** Called by every transport for every packet; returns true when the packet was ours. */
    fun handleIncoming(json: JsonObject): Boolean {
        val type = json.get("type")?.asString ?: return false
        if (type !in handledTypes || !initialized) return false
        when (type) {
            "device_hello_ack" -> Unit
            "control_state" -> {
                val active = json.get("active")?.asBoolean ?: false
                val allowed = settings?.allowRemoteControl?.value ?: true
                _controlledBy.value = if (active && allowed) (json.get("controller")?.asString ?: json.get("from_name")?.asString ?: "Another device") else null
                RemoteControlService.instance?.setControlled(active && allowed)
            }
            "remote_input" -> if (settings?.allowRemoteControl?.value != false) {
                RemoteControlService.instance?.handle(json)
            }
            "clipboard_push" -> applyRemoteClipboard(json)
            "devices_update" -> parseDevices(json.getAsJsonArray("devices"))
            else -> files.handle(type, json)
        }
        return true
    }

    private fun parseDevices(list: JsonArray?) {
        val me = selfId
        _devices.value = list?.mapNotNull { el ->
            val o = el.asJsonObject
            val id = o.get("device_id")?.asString ?: return@mapNotNull null
            if (id == me) return@mapNotNull null
            val caps = o.getAsJsonObject("caps")
            PeerDevice(
                id = id,
                name = o.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "Device",
                kind = o.get("kind")?.takeIf { !it.isJsonNull }?.asString ?: "phone",
                screenW = o.get("screen_w")?.asInt ?: 0,
                screenH = o.get("screen_h")?.asInt ?: 0,
                canBeControlled = caps?.get("control")?.asBoolean ?: false,
                acceptsFiles = caps?.get("files")?.asBoolean ?: true
            )
        }?.sortedBy { if (it.isPc) 0 else 1 } ?: emptyList()
    }

    // --- clipboard -----------------------------------------------------
    private fun clipId(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    private fun applyRemoteClipboard(json: JsonObject) {
        if (settings?.clipboardSync?.value == false) return
        val text = json.get("text")?.asString ?: return
        val id = clipId(text)
        if (id == lastClipId) return
        lastClipId = id
        main.post {
            runCatching {
                val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                suppressNextLocalClip = true
                clipboard.setPrimaryClip(ClipData.newPlainText("Copied on ${json.get("from_name")?.asString ?: "your PC"}", text))
                _lastClipboard.value = "Clipboard from ${json.get("from_name")?.asString ?: "your PC"}"
            }
        }
    }

    /** Send the clipboard if it changed. Only works while the app or the BT Mouse keyboard has focus. */
    fun pushClipboardIfNew(force: Boolean = false): Boolean {
        if (!initialized || (settings?.clipboardSync?.value == false && !force)) return false
        val text = runCatching {
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(appContext)?.toString()
        }.getOrNull()
        if (text.isNullOrEmpty()) return false
        return pushText(text, force)
    }

    fun pushText(text: String, force: Boolean = true): Boolean {
        val clipped = text.take(30_000)
        val id = clipId(clipped)
        if (id == lastClipId && !force) return false
        lastClipId = id
        send(JsonObject().apply {
            addProperty("type", "clipboard_push")
            addProperty("text", clipped)
            addProperty("id", id)
        })
        return true
    }

    // --- controlling another Android device ----------------------------
    fun startControlling(deviceId: String) {
        sendTo(deviceId, JsonObject().apply {
            addProperty("type", "control_state")
            addProperty("active", true)
            addProperty("controller", DeviceIdentity.name(appContext))
        })
    }

    fun stopControlling(deviceId: String) {
        sendTo(deviceId, JsonObject().apply {
            addProperty("type", "control_state")
            addProperty("active", false)
            addProperty("controller", DeviceIdentity.name(appContext))
        })
    }

    fun remoteInput(deviceId: String, ev: String, build: JsonObject.() -> Unit = {}) {
        sendTo(deviceId, JsonObject().apply {
            addProperty("type", "remote_input")
            addProperty("ev", ev)
            build()
        })
    }

    /** Ask the PC to hand keyboard and mouse back to itself. */
    fun releaseControl() {
        send(JsonObject().apply { addProperty("type", "control_release") })
        _controlledBy.value = null
        RemoteControlService.instance?.setControlled(false)
    }
}
