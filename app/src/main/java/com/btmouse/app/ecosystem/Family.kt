package com.btmouse.app.ecosystem

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The key shared by this person's devices, now provided by their device group (Group.kt):
 * [id] is the group id and [key] the current epoch key, which changes when a device is
 * removed. Devices join a group by scanning a member's live QR; there's no PIN pairing.
 */
object Family {
    private const val PREFS = "bt_mouse_prefs"
    private const val KEY_PEER_TOKENS = "family_peer_tokens"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        Group.init(appContext)
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun id(): String = if (Group.ready) Group.groupId else ""
    fun key(): String = if (Group.ready) Group.epochKeyHex() else ""
    /** True when this device's group includes a PC. */
    fun fromPc(): Boolean = Group.hasPc()

    /** Old PC builds sent their family key in the hello answer; groups only change by joining. */
    @Suppress("UNUSED_PARAMETER")
    fun adoptFromPc(id: String, key: String) = Unit

    /** PIN pairing no longer hands out the group key. */
    @Suppress("UNUSED_PARAMETER")
    fun adoptFromPeer(id: String, key: String): Boolean = false

    /** Peers in another family that were paired by PIN keep using that PIN. */
    fun peerToken(deviceId: String): String? = tokens().get(deviceId)?.asString

    fun rememberPeer(deviceId: String, token: String) {
        val t = tokens()
        t.addProperty(deviceId, token)
        prefs().edit().putString(KEY_PEER_TOKENS, t.toString()).apply()
    }

    fun knownPeers(): Set<String> = tokens().keySet()

    private fun tokens(): JsonObject = runCatching {
        JsonParser.parseString(prefs().getString(KEY_PEER_TOKENS, "{}") ?: "{}").asJsonObject
    }.getOrDefault(JsonObject())
}
