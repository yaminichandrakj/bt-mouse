package com.btmouse.app.ecosystem

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** A Lanyard device seen on this network. */
data class NearbyDevice(
    val id: String,
    val name: String,
    val kind: String,
    val familyId: String,
    val host: InetAddress,
    val port: Int
)

/**
 * Finds this person's other phones and tablets on the network and links to them directly,
 * with no PC and no QR codes. Every device advertises its hub over mDNS; for each pair of
 * family members, the one with the smaller id connects to the other's hub. Devices from
 * another family show up as "nearby" and can be paired once with their PIN.
 */
object PeerLinks {
    private const val TAG = "PeerLinks"
    private const val SERVICE_TYPE = "_lanyard._udp."

    private class Link(
        val deviceId: String,
        val address: InetSocketAddress,
        @Volatile var token: String,
        val socket: DatagramSocket
    ) {
        @Volatile var name = "Device"
        @Volatile var kind = "phone"
        @Volatile var screenW = 0
        @Volatile var screenH = 0
        @Volatile var canControl = false
        @Volatile var lastHeard = System.currentTimeMillis()
        var job: Job? = null
        var challenge: CompletableDeferred<JsonObject>? = null
        /** Set once the family handshake succeeds: everything after is sealed with it. */
        @Volatile var key: ByteArray? = null
    }

    private val gson = Gson()
    private lateinit var appContext: Context
    private var scope: CoroutineScope? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    @Volatile private var resolving = false

    private val links = ConcurrentHashMap<String, Link>()
    private val seen = ConcurrentHashMap<String, NearbyDevice>()

    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    /** Family devices linked directly (this device dialled them). */
    val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()

    private val _nearby = MutableStateFlow<List<NearbyDevice>>(emptyList())
    /** Lanyard devices on this network that are not in the family yet. */
    val nearby: StateFlow<List<NearbyDevice>> = _nearby.asStateFlow()

    fun init(context: Context, scope: CoroutineScope) {
        appContext = context.applicationContext
        this.scope = scope
        nsd = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        startDiscovery()
        scope.launch {
            while (true) {
                delay(5000)
                val now = System.currentTimeMillis()
                links.values.filter { now - it.lastHeard > 15_000 }.forEach { drop(it.deviceId) }
                // Retry family members we saw but are not linked to.
                seen.values.forEach { consider(it) }
            }
        }
    }

    // --- advertising (called by AndroidHub when it starts or the family changes) ---
    fun advertise(port: Int) {
        val manager = nsd ?: return
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            serviceName = "Lanyard-${Ecosystem.selfId.take(8)}"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("did", Ecosystem.selfId)
            setAttribute("fam", Family.id())
            setAttribute("name", DeviceIdentity.name(appContext).take(40))
            setAttribute("kind", if (DeviceIdentity.isTablet(appContext)) "tablet" else "phone")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "Advertise failed $errorCode") }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onSuccess { registration = listener }
    }

    fun stopAdvertising() {
        registration?.let { l -> runCatching { nsd?.unregisterService(l) } }
        registration = null
    }

    // --- discovery -------------------------------------------------------
    private fun startDiscovery() {
        val manager = nsd ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "Discovery failed $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName.startsWith("Lanyard-")) {
                    resolveQueue.add(info)
                    resolveNext()
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                val prefix = info.serviceName.removePrefix("Lanyard-")
                seen.keys.filter { it.startsWith(prefix) }.forEach { seen.remove(it) }
                publishNearby()
            }
        }
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onSuccess { discovery = listener }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val manager = nsd ?: return
        synchronized(this) {
            if (resolving) return
            val next = resolveQueue.poll() ?: return
            resolving = true
            runCatching {
                manager.resolveService(next, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        resolving = false
                        resolveNext()
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        resolving = false
                        onResolved(info)
                        resolveNext()
                    }
                })
            }.onFailure { resolving = false }
        }
    }

    private fun attr(info: NsdServiceInfo, key: String): String =
        info.attributes[key]?.let { String(it) } ?: ""

    @Suppress("DEPRECATION")
    private fun onResolved(info: NsdServiceInfo) {
        val id = attr(info, "did")
        val host = info.host ?: return
        if (id.isBlank() || id == Ecosystem.selfId) return
        val device = NearbyDevice(id, attr(info, "name").ifBlank { "Android device" }, attr(info, "kind").ifBlank { "phone" },
            attr(info, "fam"), host, info.port)
        seen[id] = device
        publishNearby()
        consider(device)
    }

    private fun publishNearby() {
        val linkedIds = links.keys + AndroidHub.connected.value.mapNotNull { it.deviceId }
        _nearby.value = seen.values.filter { it.familyId != Family.id() && Family.peerToken(it.id) == null && it.id !in linkedIds }
            .sortedBy { it.name }
    }

    /** Link to a family member if this device is the one that should dial. */
    private fun consider(device: NearbyDevice) {
        if (links.containsKey(device.id) || device.id in connecting) return
        if (AndroidHub.connected.value.any { it.deviceId == device.id }) return
        val family = device.familyId == Family.id() && Family.key().isNotEmpty()
        val pinToken = if (family) null else Family.peerToken(device.id) ?: return
        // Only one side dials, or both would link twice.
        if (family && Ecosystem.selfId > device.id) return
        connect(device, pinToken, pairing = false)
    }

    private val connecting = ConcurrentHashMap.newKeySet<String>()

    /** Pair with a nearby device by the PIN it shows on its Devices screen. */
    fun pairWithPin(device: NearbyDevice, pin: String, onResult: (Boolean, String) -> Unit) {
        connect(device, pin.trim(), pairing = true, onResult = onResult)
    }

    /**
     * ``pinToken`` null means a family member: prove the family key with a challenge (the key
     * itself never goes on the network) and check the hub's proof back, so a device that
     * merely copies the public family id learns nothing and gets nothing.
     */
    private fun connect(device: NearbyDevice, pinToken: String?, pairing: Boolean, onResult: ((Boolean, String) -> Unit)? = null) {
        val s = scope ?: return
        if (!connecting.add(device.id)) return
        s.launch(Dispatchers.IO) {
            try {
                runConnect(s, device, pinToken, pairing, onResult)
            } finally {
                connecting.remove(device.id)
            }
        }
    }

    private suspend fun runConnect(
        s: CoroutineScope, device: NearbyDevice, pinToken: String?, pairing: Boolean, onResult: ((Boolean, String) -> Unit)?
    ) {
        val socket = runCatching { DatagramSocket() }.getOrNull() ?: return
        val link = Link(device.id, InetSocketAddress(device.host, device.port), pinToken ?: "", socket)
        val ack = CompletableDeferred<JsonObject>()
        val challenge = CompletableDeferred<JsonObject>()
        link.challenge = challenge
        link.job = s.launch(Dispatchers.IO) { receiveLoop(link, ack) }

        fun fail(message: String) {
            link.job?.cancel()
            socket.close()
            onResult?.invoke(false, message)
        }

        var connectPacket = JsonObject().apply { addProperty("type", "connect"); addProperty("client", "Lanyard peer") }
        var expectedHubProof: String? = null
        var linkKey: ByteArray? = null
        if (pinToken == null) {
            var nonceReply: JsonObject? = null
            repeat(3) {
                if (nonceReply == null) {
                    sendRaw(link, JsonObject().apply { addProperty("type", "family_challenge") })
                    nonceReply = withTimeoutOrNull(1500) { challenge.await() }
                }
            }
            val nonce = nonceReply?.get("nonce")?.asString ?: return fail("No answer from ${device.name}")
            val clientNonce = AndroidHub.randomHex(16)
            val key = Family.key()
            connectPacket.addProperty("client_nonce", clientNonce)
            connectPacket.addProperty("proof", AndroidHub.hmac(key, "client|$nonce|$clientNonce"))
            expectedHubProof = AndroidHub.hmac(key, "hub|$nonce|$clientNonce")
            linkKey = AndroidHub.hmacBytes(key, "enc|$nonce|$clientNonce")
        }
        var reply: JsonObject? = null
        // A proof is single-use, so a family connect is sent once; a PIN connect may retry.
        repeat(if (pinToken == null) 1 else 3) {
            if (reply == null) {
                sendRaw(link, connectPacket.deepCopy())
                reply = withTimeoutOrNull(2500) { ack.await() }
            }
        }
        val ok = reply?.get("success")?.asBoolean == true
        if (!ok) return fail(reply?.get("error")?.asString ?: "No answer from ${device.name}")
        if (expectedHubProof != null) {
            val hubProof = reply?.get("hub_proof")?.asString.orEmpty()
            val session = reply?.get("session")?.asString.orEmpty()
            if (session.isEmpty() || !AndroidHub.constantTimeEquals(hubProof, expectedHubProof)) {
                return fail("${device.name} could not prove it is one of your devices")
            }
            link.token = session
            link.key = linkKey
        }
        run {
            if (pairing && pinToken != null) {
                val gid = reply?.get("group_id")?.asString.orEmpty()
                val gkey = reply?.get("group_key")?.asString.orEmpty()
                if (!Family.adoptFromPeer(gid, gkey)) Family.rememberPeer(device.id, pinToken)
            }
            links[device.id]?.let { old -> old.job?.cancel(); old.socket.close() }
            links[device.id] = link
            link.name = device.name
            link.kind = device.kind
            publish()
            publishNearby()
            onResult?.invoke(true, "Paired with ${device.name}")
            while (kotlin.coroutines.coroutineContext.isActive && links[device.id] === link) {
                sendRaw(link, Ecosystem.helloPayload())
                repeat(3) {
                    sendRaw(link, JsonObject().apply {
                        addProperty("type", "ping")
                        addProperty("client_time", System.currentTimeMillis())
                    })
                    delay(3000)
                }
            }
        }
    }

    private suspend fun receiveLoop(link: Link, ack: CompletableDeferred<JsonObject>) {
        val buf = ByteArray(65535)
        while (true) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                link.socket.receive(packet)
            } catch (_: Exception) {
                return
            }
            val raw = runCatching { gson.fromJson(String(packet.data, 0, packet.length), JsonObject::class.java) }.getOrNull() ?: continue
            val json = if (raw.has("enc")) {
                val key = link.key ?: continue
                AndroidHub.openSealed(key, raw) ?: continue
            } else raw
            link.lastHeard = System.currentTimeMillis()
            when (json.get("type")?.asString) {
                "connect_ack" -> ack.complete(json)
                "family_challenge" -> link.challenge?.complete(json)
                // The PC's lists stay authoritative; a direct link only adds this one device.
                "pong", "device_hello_ack", "path_switch_ack", "streams_update" -> Unit
                "auth_error" -> drop(link.deviceId)
                "devices_update" -> {
                    // The hub lists itself first: that is the peer's own name and abilities.
                    json.getAsJsonArray("devices")?.firstOrNull()?.asJsonObject?.let { me ->
                        link.name = me.get("name")?.asString ?: link.name
                        link.kind = me.get("kind")?.asString ?: link.kind
                        link.screenW = me.get("screen_w")?.asInt ?: 0
                        link.screenH = me.get("screen_h")?.asInt ?: 0
                        link.canControl = me.getAsJsonObject("caps")?.get("control")?.asBoolean ?: false
                        publish()
                    }
                }
                else -> {
                    if (!json.has("from")) json.addProperty("from", link.deviceId)
                    Ecosystem.handleIncoming(json)
                }
            }
        }
    }

    private fun sendRaw(link: Link, obj: JsonObject) {
        val key = link.key
        val packet = if (key != null) {
            AndroidHub.sealJson(key, obj.toString()).apply { addProperty("token", link.token) }
        } else {
            obj.apply { addProperty("token", link.token) }
        }
        val bytes = packet.toString().toByteArray()
        if (bytes.size > 64000) return
        runCatching { link.socket.send(DatagramPacket(bytes, bytes.size, link.address)) }
    }

    /** Ecosystem.send() uses this for messages addressed to a directly linked device. */
    fun sendTo(deviceId: String, obj: JsonObject): Boolean {
        val link = links[deviceId] ?: return false
        obj.remove("to")
        val s = scope ?: return false
        s.launch(Dispatchers.IO) { sendRaw(link, obj) }
        return true
    }

    fun isLinked(deviceId: String): Boolean = links.containsKey(deviceId)

    private fun drop(deviceId: String) {
        links.remove(deviceId)?.let { it.job?.cancel(); runCatching { it.socket.close() } }
        publish()
    }

    private fun publish() {
        _peers.value = links.values.map {
            PeerDevice(it.deviceId, it.name, it.kind, it.screenW, it.screenH, canBeControlled = it.canControl, acceptsFiles = true)
        }
        Ecosystem.onHubClientsChanged()
    }

    /** The family changed (joined the PC's): links made with the old key are rebuilt. */
    fun onFamilyChanged() {
        links.keys.toList().forEach { drop(it) }
        publishNearby()
        seen.values.forEach { consider(it) }
    }
}
