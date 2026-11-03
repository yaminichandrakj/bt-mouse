package com.btmouse.app.ecosystem

import android.content.Context
import android.util.Log
import com.btmouse.app.data.SettingsRepository
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Reaches this person's other phones and tablets over the internet when they are on
 * different networks and no PC is running. All family devices join one room on the
 * Cloudflare Worker that already publishes the PC's address; the room only forwards.
 * Every message is sealed with a key derived from the family key, so the Worker can't
 * read or forge anything, and each one carries its sender and time so replays are dropped.
 *
 * Links on the same network (PeerLinks, AndroidHub) are faster and always win; the relay
 * only carries traffic for devices that can't be reached any other way.
 */
object RelayLinks {
    private const val TAG = "RelayLinks"
    private const val MAX_AGE_MS = 120_000L

    private val gson = Gson()
    private lateinit var appContext: Context
    private var scope: CoroutineScope? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var room = ""
    @Volatile private var key: ByteArray? = null
    @Volatile private var wanted = ""
    @Volatile private var attempt = 0

    private val present = ConcurrentHashMap.newKeySet<String>()
    private val known = ConcurrentHashMap<String, PeerDevice>()
    /** Message ids seen in the last couple of minutes, to drop replays. */
    private val recent = LinkedHashMap<String, Long>()

    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    /** Family devices reachable only through the relay. */
    val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()

    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    fun init(context: Context, scope: CoroutineScope, settings: SettingsRepository) {
        appContext = context.applicationContext
        this.scope = scope
        scope.launch {
            combine(settings.relayUrl, settings.rvWorker, settings.internetRelay) { relay, rv, on ->
                if (on) relay.trim().ifBlank { rv.trim() }.trimEnd('/') else ""
            }.collect { url ->
                wanted = url
                reconnect()
            }
        }
        // Reconnect after drops, and keep peers' details fresh.
        scope.launch {
            while (true) {
                delay(30_000)
                if (socket == null && wanted.isNotEmpty()) reconnect()
                else if (socket != null) sayHello("*")
            }
        }
    }

    /** The family key changed: the room and seal key change with it. */
    fun onFamilyChanged() {
        if (::appContext.isInitialized) reconnect()
    }

    @Synchronized
    private fun reconnect() {
        socket?.close(1000, "reconnect")
        socket = null
        present.clear()
        known.clear()
        publish()
        val base = wanted
        val familyKey = Family.key()
        if (base.isEmpty() || familyKey.isEmpty() || !base.startsWith("https://")) {
            _online.value = false
            return
        }
        room = AndroidHub.hmac(familyKey, "lanyard-relay-room|v1").take(48)
        key = AndroidHub.hmacBytes(familyKey, "lanyard-relay-seal|v1")
        val url = base.replaceFirst("https://", "wss://") + "/relay/$room?d=${Ecosystem.selfId}"
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempt = 0
                _online.value = true
                sayHello("*")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket === socket) onRelayMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Relay failed: ${t.message}")
                closed(webSocket)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed(webSocket)
        })
    }

    private fun closed(webSocket: WebSocket) {
        if (webSocket !== socket) return
        socket = null
        _online.value = false
        present.clear()
        publish()
        val s = scope ?: return
        // Back off up to a minute so a room that's down isn't hammered.
        val wait = minOf(60_000L, 2_000L shl minOf(attempt++, 5))
        s.launch {
            delay(wait)
            if (socket == null && wanted.isNotEmpty()) reconnect()
        }
    }

    private fun onRelayMessage(text: String) {
        val outer = runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull() ?: return
        if (outer.get("type")?.asString == "presence") {
            val ids = outer.getAsJsonArray("devices")?.mapNotNull { it.asString }?.filter { it != Ecosystem.selfId } ?: emptyList()
            val joined = ids.filter { it !in present }
            present.clear()
            present.addAll(ids)
            known.keys.retainAll(present)
            joined.forEach { sayHello(it) }
            publish()
            return
        }
        val from = outer.get("from")?.asString ?: return
        val sealed = outer.getAsJsonObject("d") ?: return
        val k = key ?: return
        val json = AndroidHub.openSealed(k, sealed) ?: return
        // The Worker stamps "from" but can't be trusted with it; the sealed copy is the truth.
        if (json.get("relay_from")?.asString != from) return
        val ts = json.get("relay_ts")?.asLong ?: return
        if (kotlin.math.abs(System.currentTimeMillis() - ts) > MAX_AGE_MS) return
        val rid = json.get("relay_id")?.asString ?: return
        if (!remember(rid)) return
        json.remove("relay_from"); json.remove("relay_ts"); json.remove("relay_id")
        when (json.get("type")?.asString) {
            "device_hello" -> {
                val first = !known.containsKey(from)
                known[from] = PeerDevice(
                    id = from,
                    name = json.get("name")?.asString ?: "Device",
                    kind = json.get("kind")?.asString ?: "phone",
                    screenW = json.get("screen_w")?.asInt ?: 0,
                    screenH = json.get("screen_h")?.asInt ?: 0,
                    canBeControlled = json.getAsJsonObject("caps")?.get("control")?.asBoolean ?: false,
                    acceptsFiles = json.getAsJsonObject("caps")?.get("files")?.asBoolean ?: true
                )
                present.add(from)
                publish()
                if (first) sayHello(from)
            }
            else -> {
                json.addProperty("from", from)
                Ecosystem.handleIncoming(json)
            }
        }
    }

    @Synchronized
    private fun remember(rid: String): Boolean {
        val now = System.currentTimeMillis()
        val it = recent.entries.iterator()
        while (it.hasNext()) if (now - it.next().value > MAX_AGE_MS) it.remove() else break
        if (recent.containsKey(rid)) return false
        recent[rid] = now
        return true
    }

    private fun sayHello(to: String) {
        send(to, Ecosystem.helloPayload())
    }

    private fun send(to: String, obj: JsonObject): Boolean {
        val ws = socket ?: return false
        val k = key ?: return false
        val inner = obj.deepCopy().apply {
            remove("to")
            addProperty("relay_from", Ecosystem.selfId)
            addProperty("relay_ts", System.currentTimeMillis())
            addProperty("relay_id", AndroidHub.randomHex(12))
        }
        val packet = JsonObject().apply {
            addProperty("to", to)
            add("d", AndroidHub.sealJson(k, inner.toString()))
        }
        return ws.send(packet.toString())
    }

    /** Ecosystem.send() uses this last, for family devices on other networks. */
    fun sendTo(deviceId: String, obj: JsonObject): Boolean {
        if (deviceId !in present) return false
        val s = scope ?: return false
        s.launch(Dispatchers.IO) { send(deviceId, obj) }
        return true
    }

    /** Send to every family device in the room. */
    fun broadcast(obj: JsonObject) {
        if (present.isEmpty()) return
        val s = scope ?: return
        s.launch(Dispatchers.IO) { send("*", obj) }
    }

    fun isReachable(deviceId: String): Boolean = deviceId in present

    private fun publish() {
        _peers.value = known.values.filter { it.id in present }.sortedBy { it.name }
        if (::appContext.isInitialized) Ecosystem.onHubClientsChanged()
    }
}
