package com.btmouse.app.ecosystem

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.subtle.X25519
import com.google.gson.JsonObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The byte-exact formats of a device group, mirrored from pc_server/group.py (see
 * docs/device_groups.md). Plain JVM code with no Android calls, so the unit test can check
 * it against pc_server/group_vectors.json.
 */
object GroupCrypto {
    private val random = SecureRandom()

    fun b64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)
    fun unb64(text: String?): ByteArray = runCatching { Base64.getDecoder().decode(text ?: "") }.getOrDefault(ByteArray(0))
    fun hex(data: ByteArray): String = data.joinToString("") { "%02x".format(it) }
    fun unhex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }
    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    /** RFC 5869 HKDF-SHA256 (empty salt = 32 zero bytes, as in group.py). */
    fun hkdf(ikm: ByteArray, info: ByteArray, length: Int = 32, salt: ByteArray = ByteArray(0)): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = java.io.ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            block = hmac(prk, block + info + byteArrayOf(counter.toByte()))
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    // Android's provider names it ChaCha20/Poly1305/NoPadding; the desktop JDK (unit tests) ChaCha20-Poly1305.
    private fun chacha(): Cipher =
        runCatching { Cipher.getInstance("ChaCha20/Poly1305/NoPadding") }.getOrElse { Cipher.getInstance("ChaCha20-Poly1305") }

    fun aeadSeal(key: ByteArray, plain: ByteArray): ByteArray {
        val nonce = randomBytes(12)
        val c = chacha()
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
        return nonce + c.doFinal(plain)
    }

    fun aeadOpen(key: ByteArray, blob: ByteArray): ByteArray {
        val c = chacha()
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(blob.copyOfRange(0, 12)))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    fun x25519Pub(private: ByteArray): ByteArray = X25519.publicFromPrivate(private)
    fun x25519(private: ByteArray, public: ByteArray): ByteArray = X25519.computeSharedSecret(private, public)
    fun newX25519(): ByteArray = X25519.generatePrivateKey()

    /** Encrypt to an X25519 public key: eph_pub(32) || nonce(12) || ChaCha20-Poly1305. */
    fun sealTo(dhPub: ByteArray, plain: ByteArray, info: ByteArray): ByteArray {
        val eph = newX25519()
        val ephPub = x25519Pub(eph)
        val key = hkdf(x25519(eph, dhPub), info + ephPub + dhPub)
        return ephPub + aeadSeal(key, plain)
    }

    fun openFrom(dhPriv: ByteArray, blob: ByteArray, info: ByteArray): ByteArray {
        val ephPub = blob.copyOfRange(0, 32)
        val key = hkdf(x25519(dhPriv, ephPub), info + ephPub + x25519Pub(dhPriv))
        return aeadOpen(key, blob.copyOfRange(32, blob.size))
    }

    fun sign(seed: ByteArray, message: ByteArray): ByteArray = Ed25519Sign(seed).sign(message)
    fun signPub(seed: ByteArray): ByteArray = Ed25519Sign.KeyPair.newKeyPairFromSeed(seed).publicKey
    fun verify(pub: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        runCatching { Ed25519Verify(pub).verify(signature, message); true }.getOrDefault(false)

    // --- recovery words (BIP-39) ------------------------------------------------
    fun toWords(secret: ByteArray, list: List<String>): List<String> {
        val bits = StringBuilder()
        secret.forEach { bits.append(String.format("%8s", Integer.toBinaryString(it.toInt() and 0xff)).replace(' ', '0')) }
        bits.append(String.format("%8s", Integer.toBinaryString(sha256(secret)[0].toInt() and 0xff)).replace(' ', '0'))
        return (0 until bits.length step 11).map { list[bits.substring(it, it + 11).toInt(2)] }
    }

    fun fromWords(text: String, list: List<String>): ByteArray? {
        val words = text.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size != 24) return null
        val index = list.withIndex().associate { it.value to it.index }
        val bits = StringBuilder()
        for (w in words) {
            val i = index[w] ?: return null
            bits.append(String.format("%11s", Integer.toBinaryString(i)).replace(' ', '0'))
        }
        val secret = ByteArray(32) { bits.substring(it * 8, it * 8 + 8).toInt(2).toByte() }
        val check = String.format("%8s", Integer.toBinaryString(sha256(secret)[0].toInt() and 0xff)).replace(' ', '0')
        return if (check == bits.substring(256)) secret else null
    }

    class RecoveryKeys(val groupId: String, val signSeed: ByteArray, val dhPriv: ByteArray)

    fun recoveryKeys(secret: ByteArray) = RecoveryKeys(
        hex(hkdf(secret, "lanyard-group-id".toByteArray(), 8)),
        hkdf(secret, "lanyard-recovery-sign".toByteArray()),
        hkdf(secret, "lanyard-recovery-dh".toByteArray())
    )

    fun backupId(groupId: String): String = hex(sha256("lanyard-backup|$groupId".toByteArray())).take(32)
    fun epochCheck(key: ByteArray): String = hex(hkdf(key, "lanyard-epoch-check".toByteArray(), 16))
    fun epochInfo(groupId: String, epoch: Int): ByteArray = "lanyard-epoch|$groupId|$epoch".toByteArray()

    // --- roster -------------------------------------------------------------------
    fun rosterBytes(r: JsonObject): ByteArray {
        val sb = StringBuilder()
        listOf("lanyard-roster-v1", r.get("group_id").asString, r.get("version").asInt.toString(),
            r.get("epoch").asInt.toString(), r.get("epoch_check").asString, r.get("rec_sign").asString,
            r.get("rec_dh").asString).forEach { sb.append(it).append('\n') }
        r.getAsJsonArray("members").map { it.asJsonObject }.sortedBy { it.get("id").asString }.forEach { m ->
            sb.append(listOf(
                m.get("id").asString, m.get("kind")?.asString ?: "phone", m.get("sign").asString, m.get("dh").asString,
                b64((m.get("name")?.asString ?: "").toByteArray(Charsets.UTF_8))
            ).joinToString("\t")).append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun signRoster(r: JsonObject, signer: String, seed: ByteArray): JsonObject {
        val out = r.deepCopy()
        out.remove("sig")
        out.addProperty("signer", signer)
        out.addProperty("sig", b64(sign(seed, rosterBytes(out))))
        return out
    }

    fun member(r: JsonObject, id: String): JsonObject? =
        r.getAsJsonArray("members")?.map { it.asJsonObject }?.firstOrNull { it.get("id").asString == id }

    /** True when [new] may follow [current]: same group, newer, same recovery keys, signed by a member or the recovery key. */
    fun rosterFollows(current: JsonObject, new: JsonObject): Boolean = runCatching {
        if (new.get("group_id").asString != current.get("group_id").asString) return false
        if (new.get("version").asInt <= current.get("version").asInt) return false
        if (new.get("rec_sign").asString != current.get("rec_sign").asString ||
            new.get("rec_dh").asString != current.get("rec_dh").asString) return false
        val signer = new.get("signer").asString
        val pub = if (signer == "recovery") unb64(current.get("rec_sign").asString)
        else unb64(member(current, signer)?.get("sign")?.asString ?: return false)
        verify(pub, rosterBytes(new), unb64(new.get("sig").asString))
    }.getOrDefault(false)

    fun rosterSignedBy(r: JsonObject, signPub: ByteArray): Boolean =
        runCatching { verify(signPub, rosterBytes(r), unb64(r.get("sig").asString)) }.getOrDefault(false)

    // --- handshake and join ---------------------------------------------------------
    fun helloBytes(groupId: String, deviceId: String, ephB64: String) = "lanyard-hs-c|$groupId|$deviceId|$ephB64".toByteArray()

    fun transcript(groupId: String, clientId: String, serverId: String, clientEph: String, serverEph: String) =
        "lanyard-hs|v2|$groupId|$clientId|$serverId|$clientEph|$serverEph".toByteArray()

    fun sessionKey(shared: ByteArray, t: ByteArray) = hkdf(shared, "lanyard-session|".toByteArray() + t)

    fun joinTranscript(tokenId: String, inviterEph: String, joinerEph: String, id: String, sign: String, dh: String) =
        listOf("lanyard-join-v1", tokenId, inviterEph, joinerEph, id, sign, dh).joinToString("|").toByteArray()

    fun joinKey(shared: ByteArray, token: ByteArray, t: ByteArray) = hkdf(shared, "lanyard-join|".toByteArray() + token + t)

    fun tokenId(token: ByteArray): String = hex(sha256(token)).take(16)
}
