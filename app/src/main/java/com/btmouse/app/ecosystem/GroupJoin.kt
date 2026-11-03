package com.btmouse.app.ecosystem

import com.btmouse.app.data.SettingsRepository
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scanning a live join code ("lanyard_join"), shown by Lanyard for PC or by another phone
 * under Devices › Add a device. Joins that device's group; for the PC it also saves how to
 * reach it (the same details the old pairing QR carried, without the PIN).
 */
object GroupJoin {
    private const val DEFAULT_UDP_PORT = 53530
    private const val DEFAULT_WS_PORT = 53531

    /** Null on success, otherwise a message for the user. */
    suspend fun join(qr: JsonObject, settings: SettingsRepository): String? = withContext(Dispatchers.IO) {
        val ip = qr.get("ip")?.asString.orEmpty()
        if (ip.isBlank()) return@withContext "That code has no address. Join the same Wi-Fi as the other device and scan again."
        val isPc = qr.get("pc")?.asBoolean == true
        val port = qr.get("port")?.asInt ?: if (isPc) DEFAULT_UDP_PORT else AndroidHub.PORT
        val error = Group.joinOverUdp(qr, ip, port)
        if (error != null) return@withContext error
        if (isPc) {
            val wsPort = qr.get("ws_port")?.asInt ?: DEFAULT_WS_PORT
            settings.savePairedPc(qr.get("name")?.asString.orEmpty(), ip, port, "", qr.get("url")?.asString ?: "ws://$ip:$wsPort")
            qr.getAsJsonObject("rv")?.let { rv ->
                settings.setRendezvous(rv.get("worker")?.asString.orEmpty(), rv.get("id")?.asString.orEmpty(), rv.get("pub")?.asString.orEmpty())
            }
        }
        Group.relayUrl.takeIf { it.isNotBlank() }?.let { settings.setRelayUrl(it) }
        null
    }
}
