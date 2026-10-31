package com.btmouse.app.ecosystem

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.SecureRandom

/**
 * The secret shared by all of one person's devices. The PC hands its key to every phone
 * and tablet it pairs with; devices that never met a PC make their own, and a one-time
 * PIN pairing passes it on. Devices with the same key find and trust each other on the
 * network by themselves.
 */
object Family {
    private const val PREFS = "bt_mouse_prefs"
    private const val KEY_ID = "family_id"
    private const val KEY_KEY = "family_key"
    private const val KEY_FROM_PC = "family_from_pc"
    private const val KEY_PEER_TOKENS = "family_peer_tokens"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        if (key().isEmpty()) {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val idBytes = ByteArray(6).also { SecureRandom().nextBytes(it) }
            prefs().edit()
                .putString(KEY_KEY, bytes.joinToString("") { "%02x".format(it) })
                .putString(KEY_ID, idBytes.joinToString("") { "%02x".format(it) })
                .putBoolean(KEY_FROM_PC, false)
                .apply()
        }
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun id(): String = prefs().getString(KEY_ID, "") ?: ""
    fun key(): String = prefs().getString(KEY_KEY, "") ?: ""
    fun fromPc(): Boolean = prefs().getBoolean(KEY_FROM_PC, false)

    /** The PC's key always wins, so every device paired with the PC ends up in one family. */
    fun adoptFromPc(id: String, key: String) {
        if (id.isBlank() || key.length != 64 || (id == id() && key == key())) return
        prefs().edit().putString(KEY_ID, id).putString(KEY_KEY, key).putBoolean(KEY_FROM_PC, true).apply()
        AndroidHub.onFamilyChanged()
    }

    /** Joining another device by its PIN: take its family unless we already belong to a PC's. */
    fun adoptFromPeer(id: String, key: String): Boolean {
        if (fromPc() || id.isBlank() || key.length != 64) return false
        prefs().edit().putString(KEY_ID, id).putString(KEY_KEY, key).apply()
        AndroidHub.onFamilyChanged()
        return true
    }

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
