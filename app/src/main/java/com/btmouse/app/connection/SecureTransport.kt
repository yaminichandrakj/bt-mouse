package com.btmouse.app.connection

import android.util.Base64
import com.google.crypto.tink.subtle.X25519
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The phone's side of pc_server/secure.py.
 *
 * When this phone is in a device group with the PC, [begin] sends the group handshake
 * instead (key_exchange v2: this device's signed ephemeral key, answered by the PC's
 * signature, both checked against the group's member list), and nothing but the handshake
 * goes out unencrypted: [wrap] returns null for anything else until the session is up. The
 * PIN then plays no part.
 * After a link connects, [begin] produces a
 * key_exchange (fresh X25519 key, MACed with the pairing PIN); [onAck] checks the PC's
 * answer and derives the session key. From then on [wrap] seals every outgoing packet
 * with ChaCha20-Poly1305 and [unwrap] opens incoming ones. Before the exchange completes
 * (or with an older PC that never answers) packets go as plain JSON, as before.
 */
class SecureTransport(private val pin: () -> String) {
    private class Session(val sid: String, val key: ByteArray)

    @Volatile private var session: Session? = null
    @Volatile private var pendingPrivate: ByteArray? = null
    @Volatile private var pendingPublic: ByteArray? = null
    @Volatile private var pendingHello: com.btmouse.app.ecosystem.Group.Hello? = null
    /** Set when the PC said this device isn't in its group. */
    @Volatile var notMemberMessage: String? = null
        private set

    val isActive: Boolean get() = session != null

    /** In a device group with the PC: only member sessions are accepted, so no plain packets. */
    val groupMode: Boolean get() = com.btmouse.app.ecosystem.Group.hasPc()

    /** Plain packets allowed before the session is up. */
    private val handshakeTypes = setOf("connect", "client_connect", "key_exchange", "disconnect", "auth")

    fun reset() {
        session = null
        pendingPrivate = null
        pendingPublic = null
        pendingHello = null
    }

    /** The key_exchange message to send right after connecting, or null when crypto is unavailable. */
    fun begin(): JsonObject? = runCatching {
        if (groupMode) {
            val hello = com.btmouse.app.ecosystem.Group.hello() ?: return null
            pendingHello = hello
            session = null
            return hello.message
        }
        val private = X25519.generatePrivateKey()
        val public = X25519.publicFromPrivate(private)
        pendingPrivate = private
        pendingPublic = public
        session = null
        JsonObject().apply {
            addProperty("type", "key_exchange")
            addProperty("pub", Base64.encodeToString(public, Base64.NO_WRAP))
            addProperty("mac", hmacHex(pinKey(), "c".toByteArray(), public))
        }
    }.getOrNull()

    /** The PC's key_exchange_ack. Returns true when the session is up. */
    fun onAck(ack: JsonObject): Boolean {
        if (ack.get("v")?.asInt == 2) {
            val hello = pendingHello ?: return false
            if (ack.get("ok")?.asBoolean != true) {
                notMemberMessage = ack.get("message")?.asString ?: "This device isn't in this PC's group. Scan the QR on the PC's Connect page."
                return false
            }
            val key = com.btmouse.app.ecosystem.Group.finishHello(hello, ack) ?: return false
            session = Session(ack.get("sid")?.asString ?: return false, key)
            pendingHello = null
            notMemberMessage = null
            return true
        }
        val private = pendingPrivate ?: return false
        val public = pendingPublic ?: return false
        return runCatching {
            val serverPub = Base64.decode(ack.get("pub").asString, Base64.DEFAULT)
            val pk = pinKey()
            val expected = hmacHex(pk, "s".toByteArray(), public, serverPub)
            if (!MessageDigest.isEqual(expected.toByteArray(), (ack.get("mac")?.asString ?: "").toByteArray())) return false
            val shared = X25519.computeSharedSecret(private, serverPub)
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(pk, "HmacSHA256")) }
            val key = mac.doFinal(shared + public + serverPub)
            session = Session(ack.get("sid").asString, key)
            pendingPrivate = null
            pendingPublic = null
            true
        }.getOrDefault(false)
    }

    /**
     * Seal a packet's JSON text if a session is up; otherwise return it unchanged, or null
     * (don't send) when the PC only accepts member sessions and this isn't a handshake.
     */
    fun wrap(jsonText: String): String? {
        val s = session
        if (s == null) {
            if (!groupMode) return jsonText
            val type = runCatching { JsonParser.parseString(jsonText).asJsonObject.get("type")?.asString }.getOrNull()
            return if (type in handshakeTypes) jsonText else null
        }
        return runCatching {
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(s.key, "ChaCha20"), IvParameterSpec(nonce))
            val sealed = nonce + cipher.doFinal(jsonText.toByteArray())
            JsonObject().apply {
                addProperty("enc", s.sid)
                addProperty("c", Base64.encodeToString(sealed, Base64.NO_WRAP))
            }.toString()
        }.getOrDefault(jsonText)
    }

    /** Open a sealed packet; plain packets pass through. Null when a sealed one can't be read. */
    fun unwrap(packet: JsonObject): JsonObject? {
        if (!packet.has("enc")) return packet
        val s = session ?: return null
        return runCatching {
            val blob = Base64.decode(packet.get("c").asString, Base64.DEFAULT)
            val cipher = Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(s.key, "ChaCha20"), IvParameterSpec(blob.copyOfRange(0, 12)))
            val plain = cipher.doFinal(blob, 12, blob.size - 12)
            JsonParser.parseString(String(plain)).asJsonObject
        }.getOrNull()
    }

    private fun pinKey(): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("lanyard-v1|${pin().trim()}".toByteArray())

    private fun hmacHex(key: ByteArray, vararg parts: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        parts.forEachIndexed { i, part ->
            if (i > 0) mac.update('|'.code.toByte())
            mac.update(part)
        }
        return mac.doFinal().joinToString("") { "%02x".format(it) }
    }
}
