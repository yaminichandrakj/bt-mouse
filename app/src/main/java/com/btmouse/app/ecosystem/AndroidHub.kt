package com.btmouse.app.ecosystem

import android.content.Context
import android.net.ConnectivityManager
import android.util.Base64
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.btmouse.app.control.RemoteControlService
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * "No PC needed": this phone answers other Android devices the way Lanyard for PC does.
 * A tablet scans this phone's QR (same format as the PC's), connects over Wi-Fi, and then:
 * its trackpad and keyboard drive this phone, and the two share the clipboard, files,
 * remote control and this phone's live screen.
 *
 * Messages from a client carry ``from`` = its device id once it said hello; anything
 * this phone sends to a client carries ``from`` = this phone's id, so replies come back.
 */
object AndroidHub {
    const val PORT = 53540
    private const val TAG = "AndroidHub"
    private const val PREFS = "bt_mouse_prefs"
    private const val KEY_PIN = "hub_pin"
    private const val KEY_ENABLED = "hub_enabled"

    data class HubClient(
        val address: InetSocketAddress,
        @Volatile var deviceId: String? = null,
        @Volatile var name: String = "Android device",
        @Volatile var kind: String = "tablet",
        @Volatile var screenW: Int = 0,
        @Volatile var screenH: Int = 0,
        @Volatile var caps: JsonObject = JsonObject(),
        @Volatile var lastSeen: Long = System.currentTimeMillis()
    )

    private val gson = Gson()
    private val clients = ConcurrentHashMap<String, HubClient>()
    private var socket: DatagramSocket? = null
    private var job: Job? = null
    private lateinit var appContext: Context
    private var scope: CoroutineScope? = null

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _clientsFlow = MutableStateFlow<List<HubClient>>(emptyList())
    /** Devices connected to this phone, for the Devices screen. */
    val connected: StateFlow<List<HubClient>> = _clientsFlow.asStateFlow()

    // Cursor for the client's trackpad driving this phone.
    private var cursorX = -1f
    private var cursorY = -1f
    private var lastButtons = 0
    @Volatile private var lastScreenQuery = 0L

    fun init(context: Context, scope: CoroutineScope) {
        appContext = context.applicationContext
        this.scope = scope
        // On by default: it is how this person's other devices reach this one without a PC.
        if (prefs().getBoolean(KEY_ENABLED, true)) start()
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun pin(): String {
        prefs().getString(KEY_PIN, null)?.let { return it }
        val pin = (100000 + SecureRandom().nextInt(900000)).toString()
        prefs().edit().putString(KEY_PIN, pin).apply()
        return pin
    }

    fun setEnabled(on: Boolean) {
        prefs().edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) start() else stop()
    }

    /** What another device scans: the same JSON the PC's pairing QR uses. */
    fun pairingCode(): String? {
        val ip = localIpv4() ?: return null
        return JsonObject().apply {
            addProperty("type", "lanyard_hub")
            addProperty("name", DeviceIdentity.name(appContext))
            addProperty("ip", ip)
            addProperty("port", PORT)
            addProperty("pin", pin())
        }.toString()
    }

    fun localIpv4(): String? {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networks = listOfNotNull(cm.activeNetwork) + cm.allNetworks.toList()
        for (n in networks) {
            val props = cm.getLinkProperties(n) ?: continue
            props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.let {
                return it.hostAddress
            }
        }
        return null
    }

    private fun start() {
        if (job?.isActive == true) {
            _enabled.value = true
            return
        }
        val s = scope ?: return
        job = s.launch(Dispatchers.IO) {
            try {
                val sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                }
                socket = sock
                _enabled.value = true
                PeerLinks.advertise(PORT)
                val buf = ByteArray(65535)
                while (isActive) {
                    val packet = DatagramPacket(buf, buf.size)
                    sock.receive(packet)
                    val text = String(packet.data, 0, packet.length)
                    val from = InetSocketAddress(packet.address, packet.port)
                    runCatching { onPacket(text, from) }.onFailure { Log.w(TAG, "Bad packet", it) }
                }
            } catch (e: Exception) {
                if (_enabled.value) Log.w(TAG, "Hub stopped", e)
            } finally {
                _enabled.value = false
                socket?.close()
                socket = null
            }
        }
        // Drop clients that went quiet.
        s.launch {
            while (job?.isActive == true) {
                kotlinx.coroutines.delay(5000)
                val now = System.currentTimeMillis()
                val gone = clients.filterValues { now - it.lastSeen > 12_000 }.keys
                if (gone.isNotEmpty()) {
                    gone.forEach { key -> clients.remove(key) }
                    publishClients()
                }
            }
        }
    }

    fun onFamilyChanged() {
        if (_enabled.value) PeerLinks.advertise(PORT)
        PeerLinks.onFamilyChanged()
    }

    private fun stop() {
        PeerLinks.stopAdvertising()
        _enabled.value = false
        job?.cancel()
        socket?.close()
        clients.clear()
        publishClients()
    }

    private fun publishClients() {
        _clientsFlow.value = clients.values.filter { it.deviceId != null }.toList()
        Ecosystem.onHubClientsChanged()
        val list = deviceList()
        clients.values.forEach { send(it, JsonObject().apply { addProperty("type", "devices_update"); add("devices", list) }) }
    }

    fun hasClient(deviceId: String): Boolean = clients.values.any { it.deviceId == deviceId }

    /** A client's Screen preview counts as a viewer for a few seconds after each request. */
    fun screenViewers(): Int = if (System.currentTimeMillis() - lastScreenQuery < 3000) 1 else 0

    // --- sending -------------------------------------------------------
    private fun send(client: HubClient, obj: JsonObject) {
        val sock = socket ?: return
        if (!obj.has("from")) obj.addProperty("from", Ecosystem.selfId)
        val bytes = obj.toString().toByteArray()
        if (bytes.size > 64000) return
        scope?.launch(Dispatchers.IO) {
            runCatching { sock.send(DatagramPacket(bytes, bytes.size, client.address)) }
        }
    }

    /** Ecosystem.send() routes messages addressed to one of this phone's clients through here. */
    fun sendTo(deviceId: String, obj: JsonObject): Boolean {
        val client = clients.values.firstOrNull { it.deviceId == deviceId } ?: return false
        obj.remove("to")
        send(client, obj)
        return true
    }

    fun broadcast(obj: JsonObject, except: String? = null) {
        clients.values.filter { it.deviceId != null && it.deviceId != except }.forEach { send(it, obj.deepCopy()) }
    }

    private fun deviceList(): JsonArray = JsonArray().apply {
        val (w, h) = DeviceIdentity.screenSize(appContext)
        add(JsonObject().apply {
            addProperty("device_id", Ecosystem.selfId)
            addProperty("name", DeviceIdentity.name(appContext))
            addProperty("kind", if (DeviceIdentity.isTablet(appContext)) "tablet" else "phone")
            addProperty("screen_w", w)
            addProperty("screen_h", h)
            add("caps", JsonObject().apply {
                addProperty("control", RemoteControlService.instance != null)
                addProperty("files", true)
                addProperty("clipboard", true)
            })
        })
        clients.values.filter { it.deviceId != null }.forEach { c ->
            add(JsonObject().apply {
                addProperty("device_id", c.deviceId)
                addProperty("name", c.name)
                addProperty("kind", c.kind)
                addProperty("screen_w", c.screenW)
                addProperty("screen_h", c.screenH)
                add("caps", c.caps)
            })
        }
    }

    // --- receiving -----------------------------------------------------
    private fun onPacket(text: String, from: InetSocketAddress) {
        val json = gson.fromJson(text, JsonObject::class.java) ?: return
        val type = json.get("type")?.asString ?: return
        val key = "${from.address.hostAddress}:${from.port}"
        val token = json.get("token")?.asString?.trim()
        val byPin = token == pin()
        val byFamily = token != null && token.length == 64 && token == Family.key()
        if (!byPin && !byFamily) {
            if (type == "connect") send(HubClient(from), JsonObject().apply {
                addProperty("type", "connect_ack")
                addProperty("success", false)
                addProperty("error", "Invalid pairing PIN")
            }) else send(HubClient(from), JsonObject().apply {
                addProperty("type", "auth_error")
                addProperty("message", "Pairing PIN required or invalid")
            })
            return
        }
        val client = clients.getOrPut(key) { HubClient(from) }
        client.lastSeen = System.currentTimeMillis()
        listOf("token", "client_session", "seq").forEach { json.remove(it) }
        client.deviceId?.let { json.addProperty("from", it) }

        when (type) {
            "connect", "client_connect" -> send(client, JsonObject().apply {
                addProperty("type", "connect_ack")
                addProperty("success", true)
                addProperty("server_name", DeviceIdentity.name(appContext))
                addProperty("version", "1.8.0")
                if (byPin) {
                    // Pairing by PIN brings the other device into this family.
                    addProperty("group_id", Family.id())
                    addProperty("group_key", Family.key())
                }
            })
            "ping" -> send(client, JsonObject().apply {
                addProperty("type", "pong")
                addProperty("time", System.currentTimeMillis() / 1000.0)
                json.get("client_time")?.let { add("client_time", it) }
            })
            "path_switch" -> send(client, JsonObject().apply { addProperty("type", "path_switch_ack"); addProperty("success", true) })
            "disconnect" -> {
                clients.remove(key)
                publishClients()
            }
            "device_hello" -> {
                val first = client.deviceId == null
                client.deviceId = json.get("device_id")?.asString?.take(64)
                client.name = json.get("name")?.asString?.take(60) ?: client.name
                client.kind = json.get("kind")?.asString ?: client.kind
                client.screenW = json.get("screen_w")?.asInt ?: 0
                client.screenH = json.get("screen_h")?.asInt ?: 0
                client.caps = json.getAsJsonObject("caps") ?: JsonObject()
                send(client, JsonObject().apply { addProperty("type", "device_hello_ack"); addProperty("pc_name", DeviceIdentity.name(appContext)) })
                if (first) publishClients()
            }
            "devices_query" -> send(client, JsonObject().apply { addProperty("type", "devices_update"); add("devices", deviceList()) })
            "streams_query" -> send(client, streamsUpdate())
            "screen_query" -> answerScreenQuery(client)
            "mouse" -> driveMouse(json)
            "button" -> driveButtons(json.get("btn")?.asInt ?: 0)
            "key_down", "key_up", "key_stroke" -> driveKey(type, json)
            "text" -> json.get("text")?.asString?.let { t ->
                RemoteControlService.instance?.handle(JsonObject().apply { addProperty("ev", "text"); addProperty("text", t) })
            }
            else -> route(client, json)
        }
    }

    private fun route(client: HubClient, json: JsonObject) {
        val to = json.get("to")?.asString
        if (to != null && to != Ecosystem.selfId) {
            if (!sendTo(to, json)) send(client, JsonObject().apply {
                addProperty("type", "error")
                addProperty("message", "That device is not connected")
            })
            return
        }
        json.remove("to")
        if (json.get("type")?.asString == "clipboard_push") {
            broadcast(json.deepCopy().apply { addProperty("from_name", client.name) }, except = client.deviceId)
            json.addProperty("from_name", client.name)
        }
        Ecosystem.handleIncoming(json)
    }

    private fun streamsUpdate(): JsonObject = JsonObject().apply {
        addProperty("type", "streams_update")
        add("streams", JsonArray().apply {
            if (ScreenShare.active.value) add(JsonObject().apply {
                addProperty("id", "${Ecosystem.selfId}:screen")
                addProperty("kind", "screen")
                addProperty("label", "Screen")
                addProperty("owner", Ecosystem.selfId)
                addProperty("owner_name", DeviceIdentity.name(appContext))
                addProperty("viewers", 0)
            })
        })
    }

    fun announceStreams() {
        broadcast(streamsUpdate())
    }

    /** The tablet's "Screen" preview shows this phone, while screen sharing is on. */
    private fun answerScreenQuery(client: HubClient) {
        lastScreenQuery = System.currentTimeMillis()
        val jpeg = ScreenShare.lastJpeg
        if (!ScreenShare.active.value || jpeg == null) {
            send(client, JsonObject().apply {
                addProperty("type", "screen_frame")
                addProperty("error", "Turn on Share screen on ${DeviceIdentity.name(appContext)} (Devices › Live)")
            })
            return
        }
        send(client, JsonObject().apply {
            addProperty("type", "screen_frame")
            addProperty("frame", Base64.encodeToString(jpeg, Base64.NO_WRAP))
            addProperty("width", ScreenShare.lastWidth)
            addProperty("height", ScreenShare.lastHeight)
        })
    }

    // --- a client's trackpad and keyboard drive this phone --------------
    private fun ensureCursor() {
        if (cursorX < 0) {
            val (w, h) = DeviceIdentity.screenSize(appContext)
            cursorX = w / 2f
            cursorY = h / 2f
        }
    }

    private fun remote(ev: String, build: JsonObject.() -> Unit = {}) {
        RemoteControlService.instance?.handle(JsonObject().apply {
            addProperty("ev", ev)
            addProperty("x", cursorX)
            addProperty("y", cursorY)
            build()
        })
    }

    private fun driveMouse(json: JsonObject) {
        ensureCursor()
        val (w, h) = DeviceIdentity.screenSize(appContext)
        val dx = json.get("dx")?.asFloat ?: 0f
        val dy = json.get("dy")?.asFloat ?: 0f
        if (dx != 0f || dy != 0f) {
            // Phone screens are dense: one trackpad unit is two pixels here.
            cursorX = (cursorX + dx * 2f).coerceIn(0f, w - 1f)
            cursorY = (cursorY + dy * 2f).coerceIn(0f, h - 1f)
            remote("move")
        }
        val v = json.get("v_scroll")?.asFloat ?: 0f
        val hs = json.get("h_scroll")?.asFloat ?: 0f
        if (v != 0f || hs != 0f) remote("wheel") { addProperty("dy", v); addProperty("dx", hs) }
        json.get("btn")?.asInt?.let { driveButtons(it) }
    }

    private fun driveButtons(buttons: Int) {
        ensureCursor()
        val changed = buttons xor lastButtons
        lastButtons = buttons
        listOf(1 to "left", 2 to "right", 4 to "middle").forEach { (bit, name) ->
            if (changed and bit != 0) remote("button") {
                addProperty("button", name)
                addProperty("down", buttons and bit != 0)
            }
        }
    }

    private fun driveKey(type: String, json: JsonObject) {
        val code = hidToAndroid(json.get("code")?.asInt ?: return) ?: return
        val meta = hidModifiersToMeta(json.get("mod")?.asInt ?: 0)
        val char = runCatching {
            val c = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).get(code, meta and KeyEvent.META_SHIFT_ON)
            if (c > 0 && meta and (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON) == 0) c.toChar().toString() else null
        }.getOrNull()
        fun key(down: Boolean) = remote("key") {
            addProperty("code", code)
            addProperty("down", down)
            addProperty("meta", meta)
            if (down && char != null) addProperty("char", char)
        }
        when (type) {
            "key_down" -> key(true)
            "key_up" -> key(false)
            else -> { key(true); key(false) }
        }
    }

    private fun hidModifiersToMeta(mod: Int): Int {
        var meta = 0
        if (mod and 0x11 != 0) meta = meta or KeyEvent.META_CTRL_ON
        if (mod and 0x22 != 0) meta = meta or KeyEvent.META_SHIFT_ON
        if (mod and 0x44 != 0) meta = meta or KeyEvent.META_ALT_ON
        if (mod and 0x88 != 0) meta = meta or KeyEvent.META_META_ON
        return meta
    }

    private fun hidToAndroid(hid: Int): Int? = when (hid) {
        in 0x04..0x1D -> KeyEvent.KEYCODE_A + (hid - 0x04)
        in 0x1E..0x26 -> KeyEvent.KEYCODE_1 + (hid - 0x1E)
        0x27 -> KeyEvent.KEYCODE_0
        0x28 -> KeyEvent.KEYCODE_ENTER
        0x29 -> KeyEvent.KEYCODE_ESCAPE
        0x2A -> KeyEvent.KEYCODE_DEL
        0x2B -> KeyEvent.KEYCODE_TAB
        0x2C -> KeyEvent.KEYCODE_SPACE
        0x2D -> KeyEvent.KEYCODE_MINUS
        0x2E -> KeyEvent.KEYCODE_EQUALS
        0x2F -> KeyEvent.KEYCODE_LEFT_BRACKET
        0x30 -> KeyEvent.KEYCODE_RIGHT_BRACKET
        0x31 -> KeyEvent.KEYCODE_BACKSLASH
        0x33 -> KeyEvent.KEYCODE_SEMICOLON
        0x34 -> KeyEvent.KEYCODE_APOSTROPHE
        0x35 -> KeyEvent.KEYCODE_GRAVE
        0x36 -> KeyEvent.KEYCODE_COMMA
        0x37 -> KeyEvent.KEYCODE_PERIOD
        0x38 -> KeyEvent.KEYCODE_SLASH
        in 0x3A..0x45 -> KeyEvent.KEYCODE_F1 + (hid - 0x3A)
        0x4A -> KeyEvent.KEYCODE_MOVE_HOME
        0x4B -> KeyEvent.KEYCODE_PAGE_UP
        0x4C -> KeyEvent.KEYCODE_FORWARD_DEL
        0x4D -> KeyEvent.KEYCODE_MOVE_END
        0x4E -> KeyEvent.KEYCODE_PAGE_DOWN
        0x4F -> KeyEvent.KEYCODE_DPAD_RIGHT
        0x50 -> KeyEvent.KEYCODE_DPAD_LEFT
        0x51 -> KeyEvent.KEYCODE_DPAD_DOWN
        0x52 -> KeyEvent.KEYCODE_DPAD_UP
        0xE3, 0xE7 -> KeyEvent.KEYCODE_META_LEFT
        else -> null
    }
}
