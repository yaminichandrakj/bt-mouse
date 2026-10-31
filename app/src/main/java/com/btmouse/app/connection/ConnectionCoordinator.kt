package com.btmouse.app.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import com.btmouse.app.data.ConnectionMode
import com.btmouse.app.data.MediaStatus
import com.btmouse.app.data.SettingsRepository
import com.btmouse.app.service.CompanionSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ActiveTransport {
    WIFI,
    BLUETOOTH_RFCOMM,
    BLUETOOTH_HID,
    REMOTE_WAN,
    NONE
}

private data class TransportTelemetry(
    val connected: Boolean,
    val healthy: Boolean,
    val latencyMs: Long?
)

private data class BtTransportTelemetry(
    val rfcommConnected: Boolean,
    val rfcommHealthy: Boolean,
    val rfcommLatencyMs: Long?,
    val hidConnected: Boolean
)

private const val LINK_TOP_UP_INTERVAL_MS = 30_000L

enum class LinkState { OFF, WEAK, LIVE }

data class LinkStates(
    val wifi: LinkState = LinkState.OFF,
    val bluetooth: LinkState = LinkState.OFF,
    val internet: LinkState = LinkState.OFF
)

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionCoordinator(
    private val context: Context,
    val settings: SettingsRepository,
    private val scope: CoroutineScope
) : InputConnection {

    val bluetoothHidManager = BluetoothHidManager(context, scope)
    val bluetoothRfcommManager = BluetoothRfcommManager(context, scope)
    val wifiClientManager = WifiClientManager(context.applicationContext, settings, scope)
    val remoteWanManager = RemoteWanManager(context.applicationContext, settings, scope)
    val phoneCameraStreamer = PhoneCameraStreamer(context.applicationContext, scope)

    val clientSessionId: String = java.util.UUID.randomUUID().toString()
    private val commandSequence = java.util.concurrent.atomic.AtomicLong(1L)

    val activeMode: StateFlow<ConnectionMode> = settings.connectionMode

    private val wifiState = combine(
        wifiClientManager.isConnected,
        wifiClientManager.isConnectionHealthy,
        wifiClientManager.measuredLatencyMs
    ) { connected, healthy, lat ->
        TransportTelemetry(connected, healthy, lat)
    }

    private val btState = combine(
        bluetoothRfcommManager.isConnected,
        bluetoothRfcommManager.isConnectionHealthy,
        bluetoothRfcommManager.measuredLatencyMs,
        bluetoothHidManager.isConnected
    ) { rfcommConn, rfcommHealthy, rfcommLat, hidConn ->
        BtTransportTelemetry(rfcommConn, rfcommHealthy, rfcommLat, hidConn)
    }

    private val wanState = combine(
        remoteWanManager.isConnected,
        remoteWanManager.isConnectionHealthy,
        remoteWanManager.measuredLatencyMs
    ) { connected, healthy, lat ->
        TransportTelemetry(connected, healthy, lat)
    }

    // One paired PC, every link up at once: commands go over the fastest healthy one.
    val activeTransport: StateFlow<ActiveTransport> = combine(
        wifiState,
        btState,
        wanState
    ) { wifi, bt, wan ->
        val healthyCompanionCandidates = buildList {
            if (wifi.connected && wifi.healthy) {
                add(ActiveTransport.WIFI to (wifi.latencyMs ?: 15L))
            }
            if (bt.rfcommConnected && bt.rfcommHealthy) {
                add(ActiveTransport.BLUETOOTH_RFCOMM to (bt.rfcommLatencyMs ?: 35L))
            }
            if (wan.connected && wan.healthy) {
                add(ActiveTransport.REMOTE_WAN to (wan.latencyMs ?: 80L))
            }
        }
        if (healthyCompanionCandidates.isNotEmpty()) {
            healthyCompanionCandidates.minByOrNull { it.second }!!.first
        } else {
            when {
                wifi.connected -> ActiveTransport.WIFI
                bt.rfcommConnected -> ActiveTransport.BLUETOOTH_RFCOMM
                wan.connected -> ActiveTransport.REMOTE_WAN
                bt.hidConnected -> ActiveTransport.BLUETOOTH_HID
                else -> ActiveTransport.NONE
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, ActiveTransport.NONE)

    override val isConnected: StateFlow<Boolean> = combine(
        wifiState,
        btState,
        wanState
    ) { wifi, bt, wan ->
        wifi.connected || bt.rfcommConnected || bt.hidConnected || wan.connected
    }.stateIn(scope, SharingStarted.Eagerly, false)

    /** Per-link state for the Connect screen's Wi-Fi / Bluetooth / Internet chips. */
    val linkStates: StateFlow<LinkStates> = combine(wifiState, btState, wanState) { wifi, bt, wan ->
        LinkStates(
            wifi = linkStateOf(wifi.connected, wifi.healthy),
            bluetooth = linkStateOf(bt.rfcommConnected || bt.hidConnected, !bt.rfcommConnected || bt.rfcommHealthy),
            internet = linkStateOf(wan.connected, wan.healthy)
        )
    }.stateIn(scope, SharingStarted.Eagerly, LinkStates())

    private fun linkStateOf(connected: Boolean, healthy: Boolean): LinkState = when {
        !connected -> LinkState.OFF
        healthy -> LinkState.LIVE
        else -> LinkState.WEAK
    }

    private val _linkNotice = MutableStateFlow<String?>(null)
    /** Why a link could not come up (for example "PC is offline"), shown under the status card. */
    val linkNotice: StateFlow<String?> = _linkNotice.asStateFlow()

    override val measuredLatencyMs: StateFlow<Long?> = combine(
        activeTransport,
        wifiClientManager.measuredLatencyMs,
        bluetoothRfcommManager.measuredLatencyMs,
        remoteWanManager.measuredLatencyMs
    ) { transport, wifiLat, btLat, wanLat ->
        when (transport) {
            ActiveTransport.WIFI -> wifiLat
            ActiveTransport.BLUETOOTH_RFCOMM -> btLat
            ActiveTransport.REMOTE_WAN -> wanLat
            else -> null
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    override val isConnectionHealthy: StateFlow<Boolean> = combine(
        activeTransport,
        wifiClientManager.isConnectionHealthy,
        bluetoothRfcommManager.isConnectionHealthy,
        remoteWanManager.isConnectionHealthy
    ) { transport, wifiH, btH, wanH ->
        when (transport) {
            ActiveTransport.WIFI -> wifiH
            ActiveTransport.BLUETOOTH_RFCOMM -> btH
            ActiveTransport.REMOTE_WAN -> wanH
            ActiveTransport.BLUETOOTH_HID -> true
            ActiveTransport.NONE -> true
        }
    }.stateIn(scope, SharingStarted.Eagerly, true)

    val activeTransportLabel: StateFlow<String> = combine(
        activeTransport,
        measuredLatencyMs,
        isConnectionHealthy
    ) { transport, latency, healthy ->
        val suffix = if (latency != null) " • ${latency}ms" else ""
        val warning = if (!healthy) " (High Jitter)" else ""
        when (transport) {
            ActiveTransport.WIFI -> "Wi-Fi$suffix$warning"
            ActiveTransport.BLUETOOTH_RFCOMM -> "Bluetooth RFCOMM$suffix$warning"
            ActiveTransport.BLUETOOTH_HID -> "Bluetooth HID (Direct)"
            ActiveTransport.REMOTE_WAN -> "Remote WAN$suffix$warning"
            ActiveTransport.NONE -> "Disconnected"
        }
    }.stateIn(scope, SharingStarted.Eagerly, "Disconnected")

    override val statusMessage: StateFlow<String> = combine(
        activeTransport,
        bluetoothRfcommManager.statusMessage,
        bluetoothHidManager.statusMessage,
        wifiClientManager.statusMessage,
        remoteWanManager.statusMessage
    ) { transport, btRfcommStatus, btHidStatus, wifiStatus, wanStatus ->
        when (transport) {
            ActiveTransport.WIFI -> wifiStatus
            ActiveTransport.BLUETOOTH_RFCOMM -> btRfcommStatus
            ActiveTransport.BLUETOOTH_HID -> btHidStatus
            ActiveTransport.REMOTE_WAN -> wanStatus
            // Nothing up yet: show whichever link has something to say (connecting, failed, PIN wrong...).
            ActiveTransport.NONE -> listOf(
                wifiStatus to "Wi-Fi disconnected",
                wanStatus to "Disconnected",
                btRfcommStatus to "Bluetooth disconnected"
            ).firstOrNull { (status, idle) -> status.isNotBlank() && status != idle }?.first ?: "Not connected"
        }
    }.stateIn(scope, SharingStarted.Eagerly, "Initializing...")

    override val connectedDeviceName: StateFlow<String?> = combine(
        activeTransport,
        bluetoothRfcommManager.connectedDeviceName,
        bluetoothHidManager.connectedDeviceName,
        wifiClientManager.connectedDeviceName,
        remoteWanManager.connectedDeviceName
    ) { transport, btRfcommName, btHidName, wifiName, wanName ->
        when (transport) {
            ActiveTransport.WIFI -> wifiName
            ActiveTransport.BLUETOOTH_RFCOMM -> btRfcommName
            ActiveTransport.BLUETOOTH_HID -> btHidName
            ActiveTransport.REMOTE_WAN -> wanName
            ActiveTransport.NONE -> null
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val currentInputConnection: InputConnection
        get() = when (activeTransport.value) {
            ActiveTransport.WIFI -> wifiClientManager
            ActiveTransport.BLUETOOTH_RFCOMM -> bluetoothRfcommManager
            ActiveTransport.BLUETOOTH_HID -> bluetoothHidManager
            ActiveTransport.REMOTE_WAN -> remoteWanManager
            ActiveTransport.NONE -> when (settings.connectionMode.value) {
                ConnectionMode.BLUETOOTH -> {
                    if (bluetoothRfcommManager.isConnected.value) bluetoothRfcommManager
                    else if (bluetoothHidManager.isConnected.value) bluetoothHidManager
                    else bluetoothRfcommManager
                }
                ConnectionMode.WIFI -> wifiClientManager
                ConnectionMode.REMOTE_WAN -> remoteWanManager
            }
        }

    private fun selectCompanionManager(transport: ActiveTransport): InputConnection = when (transport) {
        ActiveTransport.WIFI -> wifiClientManager
        ActiveTransport.BLUETOOTH_RFCOMM -> bluetoothRfcommManager
        ActiveTransport.REMOTE_WAN -> remoteWanManager
        ActiveTransport.BLUETOOTH_HID, ActiveTransport.NONE -> when {
            wifiClientManager.isConnected.value -> wifiClientManager
            bluetoothRfcommManager.isConnected.value -> bluetoothRfcommManager
            remoteWanManager.isConnected.value -> remoteWanManager
            settings.connectionMode.value == ConnectionMode.WIFI -> wifiClientManager
            settings.connectionMode.value == ConnectionMode.REMOTE_WAN -> remoteWanManager
            else -> bluetoothRfcommManager
        }
    }

    private val currentCompanionConnection: InputConnection
        get() = selectCompanionManager(activeTransport.value)

    private val activeCompanionFlow: StateFlow<InputConnection> = combine(
        activeTransport,
        wifiClientManager.isConnected,
        bluetoothRfcommManager.isConnected,
        remoteWanManager.isConnected,
        settings.connectionMode
    ) { transport, _, _, _, _ ->
        selectCompanionManager(transport)
    }.stateIn(scope, SharingStarted.Eagerly, wifiClientManager)

    // Reactive StateFlows that seamlessly follow transport failover without dropping streams
    override val mediaStatus: StateFlow<MediaStatus> = activeCompanionFlow
        .flatMapLatest { it.mediaStatus ?: bluetoothRfcommManager.mediaStatus }
        .stateIn(scope, SharingStarted.Eagerly, MediaStatus())

    override val systemStats: StateFlow<SystemStats?> = activeCompanionFlow
        .flatMapLatest { it.systemStats ?: bluetoothRfcommManager.systemStats }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override val runningProcesses: StateFlow<List<ProcessInfo>> = activeCompanionFlow
        .flatMapLatest { it.runningProcesses ?: bluetoothRfcommManager.runningProcesses }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val installedApps: StateFlow<List<AppInfo>> = activeCompanionFlow
        .flatMapLatest { it.installedApps ?: bluetoothRfcommManager.installedApps }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Icons saved on the phone from earlier sessions, so apps show their icons straight away. */
    private val iconStore = com.btmouse.app.data.AppIconStore(context.applicationContext, scope)

    override val appIcons: StateFlow<Map<String, String>> = combine(
        iconStore.icons,
        wifiClientManager.appIcons,
        bluetoothRfcommManager.appIcons,
        remoteWanManager.appIcons
    ) { saved, w, b, r ->
        val merged = mutableMapOf<String, String>()
        merged.putAll(saved)
        merged.putAll(r)
        merged.putAll(b)
        merged.putAll(w)
        merged
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        scope.launch {
            merge(wifiClientManager.appIcons, bluetoothRfcommManager.appIcons, remoteWanManager.appIcons)
                .collect { iconStore.remember(it) }
        }
    }

    override val latestCameraFrame: StateFlow<ByteArray?> = merge(
        wifiClientManager.latestCameraFrame,
        bluetoothRfcommManager.latestCameraFrame,
        remoteWanManager.latestCameraFrame
    ).stateIn(scope, SharingStarted.Eagerly, null)

    override val screenFrame: StateFlow<ScreenFrame?> = merge(
        wifiClientManager.screenFrame,
        bluetoothRfcommManager.screenFrame,
        remoteWanManager.screenFrame
    ).stateIn(scope, SharingStarted.Eagerly, null)

    override val cameraSnapshots: StateFlow<List<SnapshotEntry>> = activeCompanionFlow
        .flatMapLatest { it.cameraSnapshots ?: bluetoothRfcommManager.cameraSnapshots }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val isCameraStreaming: StateFlow<Boolean> = combine(
        wifiClientManager.isCameraStreaming,
        bluetoothRfcommManager.isCameraStreaming,
        remoteWanManager.isCameraStreaming
    ) { w, b, r -> w || b || r }.stateIn(scope, SharingStarted.Eagerly, false)

    override val isCameraRecording: StateFlow<Boolean> = combine(
        wifiClientManager.isCameraRecording,
        bluetoothRfcommManager.isCameraRecording,
        remoteWanManager.isCameraRecording
    ) { w, b, r -> w || b || r }.stateIn(scope, SharingStarted.Eagerly, false)

    override val isAudioRecording: StateFlow<Boolean> = combine(
        wifiClientManager.isAudioRecording,
        bluetoothRfcommManager.isAudioRecording,
        remoteWanManager.isAudioRecording
    ) { w, b, r -> w || b || r }.stateIn(scope, SharingStarted.Eagerly, false)

    override val isPcLiveAudioActive: StateFlow<Boolean> = combine(
        wifiClientManager.isPcLiveAudioActive,
        bluetoothRfcommManager.isPcLiveAudioActive,
        remoteWanManager.isPcLiveAudioActive
    ) { w, b, r -> w || b || r }.stateIn(scope, SharingStarted.Eagerly, false)

    override val isPhoneLiveAudioActive: StateFlow<Boolean> = combine(
        wifiClientManager.isPhoneLiveAudioActive,
        bluetoothRfcommManager.isPhoneLiveAudioActive,
        remoteWanManager.isPhoneLiveAudioActive
    ) { w, b, r -> w || b || r }.stateIn(scope, SharingStarted.Eagerly, false)

    override val isPcLiveAudioRecording: StateFlow<Boolean> = combine(
        wifiClientManager.isPcLiveAudioRecording,
        bluetoothRfcommManager.isPcLiveAudioRecording,
        remoteWanManager.isPcLiveAudioRecording
    ) { w, b, r -> w || b || r }.stateIn(scope, SharingStarted.Eagerly, false)

    override val isPhoneLiveAudioRecording: StateFlow<Boolean> = combine(
        wifiClientManager.isPhoneLiveAudioRecording,
        bluetoothRfcommManager.isPhoneLiveAudioRecording,
        remoteWanManager.isPhoneLiveAudioRecording
    ) { w, b, r -> w || b || r }.stateIn(scope, SharingStarted.Eagerly, false)

    override val recordings: StateFlow<List<RecordingEntry>> = activeCompanionFlow
        .flatMapLatest { it.recordings ?: wifiClientManager.recordings }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val recordingTransferStatus: StateFlow<String> = activeCompanionFlow
        .flatMapLatest { it.recordingTransferStatus ?: wifiClientManager.recordingTransferStatus }
        .stateIn(scope, SharingStarted.Eagerly, "")

    override val recordingStreamUrl: StateFlow<String?> = activeCompanionFlow
        .flatMapLatest { it.recordingStreamUrl ?: wifiClientManager.recordingStreamUrl }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _reconnectStatus = MutableStateFlow<String?>(null)
    val reconnectStatus: StateFlow<String?> = _reconnectStatus.asStateFlow()

    private var reconnectJob: Job? = null
    private var sessionMonitorJob: Job? = null
    private var supervisionJob: Job? = null
    private var linkTopUpJob: Job? = null
    private var transportTransitionJob: Job? = null
    private var stayConnectedJob: Job? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var lastObservedTransport: ActiveTransport = ActiveTransport.NONE
    @Volatile private var wasConnected: Boolean = false
    @Volatile private var intentionalDisconnect: Boolean = false
    @Volatile private var isInitialized: Boolean = false

    fun initialize() {
        activeInstance = this
        wifiClientManager.clientSessionId = clientSessionId
        wifiClientManager.nextSeqSupplier = { commandSequence.getAndIncrement() }
        bluetoothRfcommManager.clientSessionId = clientSessionId
        bluetoothRfcommManager.nextSeqSupplier = { commandSequence.getAndIncrement() }
        remoteWanManager.clientSessionId = clientSessionId
        remoteWanManager.nextSeqSupplier = { commandSequence.getAndIncrement() }

        phoneCameraStreamer.onFrameAvailable = { bytes, width, height, lens ->
            currentCompanionConnection.sendPhoneCameraFrame(bytes, width, height, lens)
            com.btmouse.app.ecosystem.Ecosystem.onCameraFrame(bytes, width, height)
        }

        onPermissionsUpdated()

        com.btmouse.app.ecosystem.Ecosystem.init(context, this, settings, scope)

        if (!isInitialized) {
            isInitialized = true
            monitorTransportTransitions()
            monitorSessionAndMedia()
            monitorConnectionSupervision()
            monitorLinkTopUp()
            monitorStayConnected()
        }
    }

    /** True when the phone should keep itself connected in the background. */
    fun shouldStayConnected(): Boolean = settings.stayConnected.value && isPaired() && !intentionalDisconnect

    /**
     * "Stay connected": connect at start, reconnect whenever a network comes back, and
     * keep trying once a minute when the PC is unreachable, instead of giving up.
     */
    private fun monitorStayConnected() {
        stayConnectedJob?.cancel()
        stayConnectedJob = scope.launch {
            delay(1500)
            if (shouldStayConnected() && !isConnected.value) connectSavedPc()
            while (true) {
                delay(60_000)
                if (shouldStayConnected() && !isConnected.value && reconnectJob?.isActive != true) {
                    bringUpAllLinks()
                }
            }
        }
        registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                scope.launch {
                    // Give DHCP and routes a moment before probing the PC.
                    delay(2000)
                    if (!shouldStayConnected()) return@launch
                    val links = linkStates.value
                    if (!isConnected.value || links.wifi == LinkState.OFF) bringUpAllLinks()
                }
            }
        }
        runCatching {
            cm.registerNetworkCallback(
                android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback
            )
            networkCallback = callback
        }
    }

    /** The notification's Reconnect button: undo a manual disconnect and connect again. */
    fun reconnectNow() {
        intentionalDisconnect = false
        connectSavedPc()
    }

    override fun sendJsonObject(obj: com.google.gson.JsonObject) {
        currentCompanionConnection.sendJsonObject(obj)
    }

    /**
     * Auto-reconnect only runs once every link is down. While at least one is up, bring back
     * any that dropped (e.g. Wi-Fi when you walk back into range) so failover keeps options.
     */
    private fun monitorLinkTopUp() {
        linkTopUpJob?.cancel()
        linkTopUpJob = scope.launch {
            while (true) {
                delay(LINK_TOP_UP_INTERVAL_MS)
                val links = linkStates.value
                val anyDown = links.wifi == LinkState.OFF || links.bluetooth == LinkState.OFF || links.internet == LinkState.OFF
                if (isConnected.value && anyDown && !intentionalDisconnect && settings.autoReconnect.value) {
                    bringUpAllLinks()
                }
            }
        }
    }

    fun onPermissionsUpdated() {
        bluetoothHidManager.initialize()
        bluetoothHidManager.refreshPairedDevices()
        bluetoothRfcommManager.refreshPairedDevices()
    }

    private fun monitorTransportTransitions() {
        transportTransitionJob?.cancel()
        transportTransitionJob = scope.launch {
            activeTransport.collect { newTransport ->
                val oldTransport = lastObservedTransport
                if (oldTransport != ActiveTransport.NONE && oldTransport != newTransport) {
                    getTransportConnection(oldTransport)?.sendMouseButton(0)
                    val targetConn = selectCompanionManager(newTransport)
                    targetConn.notifyPathSwitch()
                    targetConn.queryMedia()
                }
                lastObservedTransport = newTransport
            }
        }
    }

    private fun getTransportConnection(transport: ActiveTransport): InputConnection? = when (transport) {
        ActiveTransport.WIFI -> wifiClientManager
        ActiveTransport.BLUETOOTH_RFCOMM -> bluetoothRfcommManager
        ActiveTransport.BLUETOOTH_HID -> bluetoothHidManager
        ActiveTransport.REMOTE_WAN -> remoteWanManager
        ActiveTransport.NONE -> null
    }

    fun setMode(mode: ConnectionMode, disconnectPrevious: Boolean = false) {
        val previousMode = settings.connectionMode.value
        if (mode == previousMode) return

        if (disconnectPrevious && !settings.failoverEnabled.value) {
            intentionalDisconnect = true
            cancelReconnect()
            when (previousMode) {
                ConnectionMode.BLUETOOTH -> {
                    bluetoothRfcommManager.disconnect()
                    bluetoothHidManager.disconnect()
                }
                ConnectionMode.WIFI -> wifiClientManager.disconnect()
                ConnectionMode.REMOTE_WAN -> remoteWanManager.disconnect()
            }
            intentionalDisconnect = false
        }
        settings.setConnectionMode(mode)
    }

    fun prepareForManualConnect() {
        intentionalDisconnect = false
        cancelReconnect()
    }

    /** Connect to the paired PC over every link it can reach; failover then uses the fastest. */
    fun connectSavedPc() {
        prepareForManualConnect()
        _linkNotice.value = null
        scope.launch {
            if (!bringUpAllLinks()) {
                _linkNotice.value = "Scan the QR code on the Connect page of Lanyard for PC first"
            }
        }
    }

    /**
     * Starts each link that is not already up, using what the single pairing QR saved.
     * Returns false when nothing is paired yet, so there was nothing to try.
     */
    private suspend fun bringUpAllLinks(): Boolean {
        if (!isPaired()) return false
        val bt = bringUpBluetooth()
        val wifi = bringUpWifi()
        val net = bringUpInternet()
        return bt || wifi || net
    }

    private fun isPaired(): Boolean {
        val pin = settings.serverPin.value.trim()
        return pin.length == 6 && pin.all { it.isDigit() }
    }

    // Bluetooth: only the PC we paired with, never just the first bonded device (earbuds, watch...).
    private fun bringUpBluetooth(): Boolean {
        val device = findPcBluetoothDevice(settings.pcName.value.trim()) ?: return false
        if (!bluetoothRfcommManager.isConnected.value) bluetoothRfcommManager.connectToDevice(device)
        if (!bluetoothHidManager.isConnected.value) bluetoothHidManager.connectToDevice(device)
        return true
    }

    // Wi-Fi: the fast UDP link when the phone is on the PC's network.
    private fun bringUpWifi(): Boolean {
        val host = settings.wifiHost.value.trim()
        val port = settings.wifiPort.value
        if (host.isBlank() || port !in 1..65535) return false
        if (!wifiClientManager.isConnected.value) {
            wifiClientManager.connectManual(host, port, settings.pcName.value.trim().ifBlank { "PC Server" })
        }
        return true
    }

    // Internet: look up the PC's current tunnel address; fall back to its local WebSocket.
    private suspend fun bringUpInternet(): Boolean {
        val rv = RemoteRendezvousResolver.fromSettings(settings)
        val fallbackUrl = settings.wanUrl.value.trim()
        if (!rv.isComplete && fallbackUrl.isBlank()) return false
        if (!remoteWanManager.isConnected.value) {
            val url = if (rv.isComplete) {
                when (val r = RemoteRendezvousResolver.resolve(rv)) {
                    is RemoteRendezvousResolver.Result.Ok -> r.url
                    is RemoteRendezvousResolver.Result.Error -> {
                        _linkNotice.value = "Internet: ${r.message}"
                        fallbackUrl
                    }
                }
            } else fallbackUrl
            if (url.isNotBlank()) remoteWanManager.connect(url, settings.serverPin.value.trim())
        }
        return true
    }

    enum class Link { WIFI, BLUETOOTH, INTERNET }

    private val _retryingLinks = MutableStateFlow<Set<Link>>(emptySet())
    /** Links the user asked to try now; each clears when it comes up or after a timeout. */
    val retryingLinks: StateFlow<Set<Link>> = _retryingLinks.asStateFlow()

    /**
     * Try one link right away, even if auto-reconnect gave up on it or never started it.
     * Returns false when there is nothing saved for that link, so the screen can say so.
     */
    fun retryLink(link: Link): Boolean {
        if (!isPaired()) {
            _linkNotice.value = "Scan the QR code in Lanyard for PC first"
            return false
        }
        val canTry = when (link) {
            Link.WIFI -> settings.wifiHost.value.isNotBlank()
            Link.BLUETOOTH -> findPcBluetoothDevice(settings.pcName.value.trim()) != null
            Link.INTERNET -> RemoteRendezvousResolver.fromSettings(settings).isComplete || settings.wanUrl.value.isNotBlank()
        }
        if (!canTry) {
            _linkNotice.value = when (link) {
                Link.WIFI -> "Wi-Fi: no PC address saved yet. Scan the code on the PC."
                Link.BLUETOOTH -> "Bluetooth: pair the PC in your phone's Bluetooth settings first"
                Link.INTERNET -> "Internet: turn on Internet access on the PC, then scan its code"
            }
            return false
        }
        intentionalDisconnect = false
        _linkNotice.value = null
        _retryingLinks.value = _retryingLinks.value + link
        scope.launch {
            when (link) {
                Link.WIFI -> bringUpWifi()
                Link.BLUETOOTH -> bringUpBluetooth()
                Link.INTERNET -> bringUpInternet()
            }
            var waited = 0L
            while (waited < 12_000L) {
                val up = when (link) {
                    Link.WIFI -> linkStates.value.wifi
                    Link.BLUETOOTH -> linkStates.value.bluetooth
                    Link.INTERNET -> linkStates.value.internet
                } != LinkState.OFF
                if (up) break
                delay(250)
                waited += 250
            }
            _retryingLinks.value = _retryingLinks.value - link
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun findPcBluetoothDevice(pcName: String): BluetoothDevice? {
        return try {
            bluetoothRfcommManager.refreshPairedDevices()
            val bonded = bluetoothRfcommManager.pairedDevices.value
            val savedAddress = settings.pcBluetoothAddress.value
            bonded.firstOrNull { savedAddress.isNotBlank() && it.address.equals(savedAddress, ignoreCase = true) }
                ?: bonded.firstOrNull { pcName.isNotBlank() && it.name.equals(pcName, ignoreCase = true) }
                ?: bluetoothRfcommManager.lastConnectedDevice?.takeIf { last -> bonded.any { it.address == last.address } }
        } catch (_: SecurityException) {
            null
        }
    }

    fun disconnectAll() {
        intentionalDisconnect = true
        cancelReconnect()
        phoneCameraStreamer.stop()
        CompanionSessionService.stop(context)
        bluetoothRfcommManager.disconnect()
        bluetoothHidManager.disconnect()
        wifiClientManager.disconnect()
        remoteWanManager.disconnect()
    }

    fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        _reconnectStatus.value = null
    }

    private fun monitorSessionAndMedia() {
        val hasActiveMediaFlow = combine(
            isPcLiveAudioActive,
            isPhoneLiveAudioActive,
            isCameraStreaming,
            phoneCameraStreamer.isStreaming
        ) { pcAudio, phoneAudio, camera, phoneCam ->
            pcAudio || phoneAudio || camera || phoneCam
        }

        sessionMonitorJob?.cancel()
        sessionMonitorJob = scope.launch {
            combine(
                hasActiveMediaFlow,
                isConnected,
                settings.backgroundSessionEnabled,
                settings.stayConnected,
                com.btmouse.app.ecosystem.Ecosystem.controlledBy
            ) { hasActiveMedia, connected, bgEnabled, stay, _ ->
                listOf(hasActiveMedia, connected, bgEnabled, stay)
            }.collect { (hasActiveMedia, connected, bgEnabled, stay) ->
                val keepAlive = stay && isPaired() && !intentionalDisconnect
                if ((bgEnabled && hasActiveMedia) || keepAlive) {
                    CompanionSessionService.start(context)
                } else if (!hasActiveMedia && !connected) {
                    CompanionSessionService.stop(context)
                } else if (hasActiveMedia) {
                    CompanionSessionService.update(context)
                }
            }
        }
    }

    private fun monitorConnectionSupervision() {
        supervisionJob?.cancel()
        supervisionJob = scope.launch {
            isConnected.collect { connected ->
                if (connected) {
                    wasConnected = true
                    cancelReconnect()
                } else {
                    if (wasConnected && !intentionalDisconnect && settings.autoReconnect.value) {
                        startAutoReconnect()
                    }
                }
            }
        }
    }

    fun startAutoReconnect() {
        cancelReconnect()
        reconnectJob = scope.launch {
            val delays = listOf(2000L, 4000L, 8000L, 16000L, 30000L)
            for (attempt in 1..delays.size) {
                if (!settings.autoReconnect.value || isConnected.value || intentionalDisconnect) {
                    _reconnectStatus.value = null
                    return@launch
                }
                val delaySec = delays[attempt - 1] / 1000
                for (sec in delaySec downTo 1) {
                    if (!settings.autoReconnect.value || isConnected.value || intentionalDisconnect) {
                        _reconnectStatus.value = null
                        return@launch
                    }
                    _reconnectStatus.value = "Reconnecting in ${sec}s (attempt $attempt/${delays.size})..."
                    delay(1000L)
                }
                if (!settings.autoReconnect.value || isConnected.value || intentionalDisconnect) {
                    _reconnectStatus.value = null
                    return@launch
                }
                _reconnectStatus.value = "Attempting reconnection ($attempt/${delays.size})..."

                if (!bringUpAllLinks()) {
                    _reconnectStatus.value = "Auto-reconnect cancelled: pair the PC by scanning its QR code"
                    return@launch
                }

                var elapsed = 0
                while (elapsed < 4000 && !isConnected.value && !intentionalDisconnect) {
                    delay(250)
                    elapsed += 250
                }
                if (isConnected.value) {
                    _reconnectStatus.value = null
                    return@launch
                }
            }
            _reconnectStatus.value = if (shouldStayConnected()) {
                "PC not reachable. Still trying every minute."
            } else {
                "Reconnect timed out. Tap to retry."
            }
        }
    }

    override fun sendMouseMove(dx: Int, dy: Int, buttons: Int, vScroll: Int, hScroll: Int) {
        currentInputConnection.sendMouseMove(dx, dy, buttons, vScroll, hScroll)
    }

    override fun sendMouseButton(buttons: Int) {
        currentInputConnection.sendMouseButton(buttons)
    }

    override fun sendKeyPress(keyCode: Byte, modifiers: Byte) {
        currentInputConnection.sendKeyPress(keyCode, modifiers)
    }

    override fun sendKeyRelease(keyCode: Byte, modifiers: Byte) {
        currentInputConnection.sendKeyRelease(keyCode, modifiers)
    }

    override fun sendKeyStroke(keyCode: Byte, modifiers: Byte) {
        currentInputConnection.sendKeyStroke(keyCode, modifiers)
    }

    override fun sendMediaKey(usageCode: Int) {
        currentInputConnection.sendMediaKey(usageCode)
    }

    override fun sendText(text: String) {
        currentInputConnection.sendText(text)
    }

    override fun sendPenEvent(action: String, xRatio: Float, yRatio: Float, pressure: Float, isEraser: Boolean) {
        currentCompanionConnection.sendPenEvent(action, xRatio, yRatio, pressure, isEraser)
    }

    override fun queryMedia(includePreview: Boolean, includeVideoPreview: Boolean) {
        currentCompanionConnection.queryMedia(includePreview, includeVideoPreview)
    }

    /** Ask for one screen picture sized for the link in use (Bluetooth gets a smaller one). */
    fun requestScreenFrame(resume: Boolean = false) {
        when (activeTransport.value) {
            ActiveTransport.BLUETOOTH_RFCOMM -> bluetoothRfcommManager.queryScreen(640, 22000, resume)
            ActiveTransport.REMOTE_WAN -> remoteWanManager.queryScreen(1280, 52000, resume)
            ActiveTransport.WIFI -> wifiClientManager.queryScreen(1280, 52000, resume)
            else -> currentCompanionConnection.queryScreen(960, 40000, resume)
        }
    }

    override fun queryScreen(maxWidth: Int, maxBytes: Int, resume: Boolean) {
        currentCompanionConnection.queryScreen(maxWidth, maxBytes, resume)
    }

    override fun sendMediaSeek(position: Float) {
        currentCompanionConnection.sendMediaSeek(position)
    }

    override fun sendMediaToggle() {
        if (wifiClientManager.isConnected.value ||
            remoteWanManager.isConnected.value ||
            bluetoothRfcommManager.isConnected.value) {
            currentCompanionConnection.sendMediaToggle()
        } else {
            sendMediaKey(com.btmouse.app.data.HidConstants.CONSUMER_PLAY_PAUSE)
        }
    }

    override fun sendMediaNext() {
        if (wifiClientManager.isConnected.value ||
            remoteWanManager.isConnected.value ||
            bluetoothRfcommManager.isConnected.value) {
            currentCompanionConnection.sendMediaNext()
        } else {
            sendMediaKey(com.btmouse.app.data.HidConstants.CONSUMER_SCAN_NEXT)
        }
    }

    override fun sendMediaPrev() {
        if (wifiClientManager.isConnected.value ||
            remoteWanManager.isConnected.value ||
            bluetoothRfcommManager.isConnected.value) {
            currentCompanionConnection.sendMediaPrev()
        } else {
            sendMediaKey(com.btmouse.app.data.HidConstants.CONSUMER_SCAN_PREV)
        }
    }

    override fun setVolume(volume: Float) {
        currentCompanionConnection.setVolume(volume)
    }

    override fun setMute(mute: Boolean) {
        currentCompanionConnection.setMute(mute)
    }

    override fun launchApp(appId: String) {
        currentCompanionConnection.launchApp(appId)
    }

    override fun systemPower(action: String) {
        currentCompanionConnection.systemPower(action)
    }

    override fun querySystemStats() {
        currentCompanionConnection.querySystemStats()
    }

    override fun queryRunningProcesses() {
        currentCompanionConnection.queryRunningProcesses()
    }

    override fun killProcess(pid: Int) {
        currentCompanionConnection.killProcess(pid)
    }

    override fun queryInstalledApps() {
        currentCompanionConnection.queryInstalledApps()
    }

    /** Ask for icons in small batches; the PC answers up to 60 per request. */
    override fun queryAppIcons(items: List<String>) {
        val missing = items.filter { it.isNotBlank() && appIcons.value[it].isNullOrBlank() }.distinct()
        missing.chunked(40).forEach { currentCompanionConnection.queryAppIcons(it) }
    }

    override fun updateQuickApps(paths: List<String>) {
        currentCompanionConnection.updateQuickApps(paths)
    }

    override fun queryRecordings() {
        currentCompanionConnection.queryRecordings()
    }

    override fun downloadRecording(id: String) {
        currentCompanionConnection.downloadRecording(id)
    }

    override fun streamRecording(id: String) {
        currentCompanionConnection.streamRecording(id)
    }

    override fun stopRecordingStream() {
        currentCompanionConnection.stopRecordingStream()
    }

    override fun deleteRecording(id: String) {
        currentCompanionConnection.deleteRecording(id)
    }

    override fun deleteSnapshot(path: String): Boolean {
        return currentCompanionConnection.deleteSnapshot(path)
    }

    override fun startPcLiveAudio(record: Boolean) {
        currentCompanionConnection.startPcLiveAudio(record)
    }

    override fun stopPcLiveAudio() {
        wifiClientManager.stopPcLiveAudio()
        bluetoothRfcommManager.stopPcLiveAudio()
        remoteWanManager.stopPcLiveAudio()
    }

    override fun startPhoneLiveAudio(record: Boolean): Boolean = currentCompanionConnection.startPhoneLiveAudio(record)

    override fun stopPhoneLiveAudio() {
        wifiClientManager.stopPhoneLiveAudio()
        bluetoothRfcommManager.stopPhoneLiveAudio()
        remoteWanManager.stopPhoneLiveAudio()
    }

    override fun requestCameraSnapshot() {
        currentCompanionConnection.requestCameraSnapshot()
    }

    override fun startCameraStream() {
        currentCompanionConnection.startCameraStream()
    }

    override fun stopCameraStream() {
        wifiClientManager.stopCameraStream()
        bluetoothRfcommManager.stopCameraStream()
        remoteWanManager.stopCameraStream()
    }

    override fun startAudioRecord() {
        currentCompanionConnection.startAudioRecord()
    }

    override fun stopAudioRecord() {
        wifiClientManager.stopAudioRecord()
        bluetoothRfcommManager.stopAudioRecord()
        remoteWanManager.stopAudioRecord()
    }

    override val isPhoneCameraStreaming: StateFlow<Boolean> = phoneCameraStreamer.isStreaming
    override val phoneCameraLocalPreview: StateFlow<ByteArray?> = phoneCameraStreamer.localPreviewFrame
    override val phoneCameraStats: StateFlow<PhoneCameraTelemetry> = phoneCameraStreamer.telemetry
    override val phoneCameraLens: StateFlow<String> = phoneCameraStreamer.currentLens
    override val phoneCameraResolution: StateFlow<Pair<Int, Int>> = phoneCameraStreamer.currentResolution
    override val phoneCameraFps: StateFlow<Int> = phoneCameraStreamer.targetFps
    override val phoneCameraOrientation: StateFlow<String> = phoneCameraStreamer.orientationMode
    override val phoneCameraRotation: StateFlow<Int> = phoneCameraStreamer.rotationOffset
    override val phoneCameraStatusMessage: StateFlow<String> = phoneCameraStreamer.statusMessage

    override fun startPhoneCameraStream(lens: String, width: Int, height: Int, fps: Int) {
        val mode = phoneCameraStreamer.orientationMode.value
        val outW = if (mode == "portrait") minOf(width, height) else maxOf(width, height)
        val outH = if (mode == "portrait") maxOf(width, height) else minOf(width, height)
        currentCompanionConnection.startPhoneCameraStream(lens, outW, outH, fps)
        phoneCameraStreamer.start(lens, width, height, fps)
        if (phoneCameraStreamer.isStreaming.value) {
            CompanionSessionService.start(context)
        }
    }

    override fun switchPhoneCameraLens() {
        phoneCameraStreamer.switchLens()
        if (phoneCameraStreamer.isStreaming.value) {
            val mode = phoneCameraStreamer.orientationMode.value
            val (w, h) = phoneCameraStreamer.currentResolution.value
            val outW = if (mode == "portrait") minOf(w, h) else maxOf(w, h)
            val outH = if (mode == "portrait") maxOf(w, h) else minOf(w, h)
            currentCompanionConnection.startPhoneCameraStream(
                phoneCameraStreamer.currentLens.value,
                outW,
                outH,
                phoneCameraStreamer.targetFps.value
            )
        }
    }

    override fun setPhoneCameraResolution(width: Int, height: Int) {
        phoneCameraStreamer.setResolution(width, height)
        if (phoneCameraStreamer.isStreaming.value) {
            val mode = phoneCameraStreamer.orientationMode.value
            val outW = if (mode == "portrait") minOf(width, height) else maxOf(width, height)
            val outH = if (mode == "portrait") maxOf(width, height) else minOf(width, height)
            currentCompanionConnection.startPhoneCameraStream(
                phoneCameraStreamer.currentLens.value,
                outW,
                outH,
                phoneCameraStreamer.targetFps.value
            )
        }
    }

    override fun setPhoneCameraFps(fps: Int) {
        phoneCameraStreamer.setTargetFps(fps)
    }

    override fun setPhoneCameraOrientation(mode: String) {
        phoneCameraStreamer.setOrientationMode(mode)
        if (phoneCameraStreamer.isStreaming.value) {
            val (w, h) = phoneCameraStreamer.currentResolution.value
            val outW = if (mode == "portrait") minOf(w, h) else maxOf(w, h)
            val outH = if (mode == "portrait") maxOf(w, h) else minOf(w, h)
            currentCompanionConnection.startPhoneCameraStream(
                phoneCameraStreamer.currentLens.value,
                outW,
                outH,
                phoneCameraStreamer.targetFps.value
            )
        }
    }

    override fun rotatePhoneCamera90() {
        phoneCameraStreamer.rotate90()
    }

    override fun stopPhoneCameraStream() {
        phoneCameraStreamer.stop()
        wifiClientManager.stopPhoneCameraStream()
        bluetoothRfcommManager.stopPhoneCameraStream()
        remoteWanManager.stopPhoneCameraStream()
        CompanionSessionService.update(context)
    }

    override fun sendPhoneCameraFrame(data: ByteArray, width: Int, height: Int, lens: String) {
        currentCompanionConnection.sendPhoneCameraFrame(data, width, height, lens)
    }

    fun cleanup() {
        if (activeInstance === this) {
            activeInstance = null
        }
        isInitialized = false
        cancelReconnect()
        transportTransitionJob?.cancel()
        sessionMonitorJob?.cancel()
        supervisionJob?.cancel()
        phoneCameraStreamer.stop()
        CompanionSessionService.stop(context)
        remoteWanManager.disconnect()
        bluetoothRfcommManager.disconnect()
        bluetoothHidManager.cleanup()
        wifiClientManager.cleanup()
    }

    companion object {
        @Volatile
        var activeInstance: ConnectionCoordinator? = null
            internal set
    }
}
