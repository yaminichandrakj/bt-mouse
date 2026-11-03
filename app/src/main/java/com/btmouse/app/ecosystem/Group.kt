package com.btmouse.app.ecosystem

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.btmouse.app.ecosystem.GroupCrypto.b64
import com.btmouse.app.ecosystem.GroupCrypto.unb64
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One device in the group, as the roster lists it. */
data class GroupMember(val id: String, val name: String, val kind: String)

/**
 * This device's identity and its device group (docs/device_groups.md, pc_server/group.py).
 *
 * Every device has its own keys; the group is a signed member list plus an epoch key all
 * members share. The epoch key is what [Family.key] hands out: it seals phone-to-phone links,
 * the relay and PC calls, and it is replaced when a device is removed. A device joins by
 * scanning a member's live QR; the private keys are kept encrypted by the Android Keystore.
 */
object Group {
    private const val TAG = "Group"
    private const val PREFS = "lanyard_group"
    private const val KEYSTORE_ALIAS = "lanyard_group_v1"
    const val OFFER_LIFETIME_MS = 60_000L
    const val OFFER_REFRESH_MS = 20_000L

    private lateinit var appContext: Context
    private val lock = Any()

    private lateinit var signSeed: ByteArray
    private lateinit var dhPriv: ByteArray
    private lateinit var epochKey: ByteArray
    private lateinit var backupSeed: ByteArray
    private lateinit var roster: JsonObject
    private var pendingKeys = JsonObject()
    @Volatile var pendingWords: String? = null
        private set
    @Volatile var relayUrl: String = ""
        private set

    private class Offer(val token: ByteArray, val eph: ByteArray, val at: Long)
    private val offers = HashMap<String, Offer>()

    private val _members = MutableStateFlow<List<GroupMember>>(emptyList())
    val members: StateFlow<List<GroupMember>> = _members.asStateFlow()

    private val _wordsPending = MutableStateFlow(false)
    val wordsPending: StateFlow<Boolean> = _wordsPending.asStateFlow()

    val ready: Boolean get() = ::roster.isInitialized

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        synchronized(lock) { if (!load()) createGroup(GroupCrypto.randomBytes(32), freshKeys = true) }
    }

    private fun selfId() = DeviceIdentity.id(appContext)
    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val words: List<String> by lazy {
        appContext.assets.open("bip39_english.txt").bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    // --- views ---------------------------------------------------------------------
    val groupId: String get() = roster.get("group_id").asString
    val version: Int get() = roster.get("version").asInt
    val epoch: Int get() = roster.get("epoch").asInt
    fun epochKeyHex(): String = synchronized(lock) { GroupCrypto.hex(epochKey) }
    fun hasPc(): Boolean = ready && GroupCrypto.member(roster, "pc") != null
    fun isMember(id: String): Boolean = ready && GroupCrypto.member(roster, id) != null
    fun signPubOf(id: String): ByteArray? = GroupCrypto.member(roster, id)?.get("sign")?.asString?.let { unb64(it) }

    fun me(): JsonObject = JsonObject().apply {
        addProperty("id", selfId())
        addProperty("name", DeviceIdentity.name(appContext))
        addProperty("kind", if (DeviceIdentity.isTablet(appContext)) "tablet" else "phone")
        addProperty("sign", b64(GroupCrypto.signPub(signSeed)))
        addProperty("dh", b64(GroupCrypto.x25519Pub(dhPriv)))
    }

    fun setRelayUrl(url: String) {
        if (url.isBlank() || url == relayUrl) return
        synchronized(lock) { relayUrl = url; save() }
    }

    // --- storage ---------------------------------------------------------------------
    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    private fun protect(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keystoreKey())
        return b64(c.iv + c.doFinal(plain))
    }

    private fun unprotect(text: String): ByteArray {
        val blob = unb64(text)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    private fun load(): Boolean = runCatching {
        val p = prefs()
        val state = JsonParser.parseString(p.getString("state", null) ?: return false).asJsonObject
        val s = JsonParser.parseString(String(unprotect(p.getString("secrets", null) ?: return false))).asJsonObject
        signSeed = unb64(s.get("sign").asString)
        dhPriv = unb64(s.get("dh").asString)
        epochKey = unb64(s.get("epoch").asString)
        backupSeed = unb64(s.get("backup").asString)
        roster = state.getAsJsonObject("roster")
        pendingKeys = state.getAsJsonObject("pending_keys") ?: JsonObject()
        pendingWords = state.get("pending_words")?.takeIf { !it.isJsonNull }?.asString
        relayUrl = state.get("relay_url")?.asString ?: ""
        if (GroupCrypto.member(roster, selfId()) == null) return false
        publish()
        true
    }.getOrElse {
        Log.w(TAG, "Could not load the device group", it)
        false
    }

    private fun save() {
        val s = JsonObject().apply {
            addProperty("sign", b64(signSeed)); addProperty("dh", b64(dhPriv))
            addProperty("epoch", b64(epochKey)); addProperty("backup", b64(backupSeed))
        }
        val state = JsonObject().apply {
            add("roster", roster); add("pending_keys", pendingKeys)
            pendingWords?.let { addProperty("pending_words", it) }
            addProperty("relay_url", relayUrl)
        }
        prefs().edit().putString("secrets", protect(s.toString().toByteArray())).putString("state", state.toString()).apply()
        publish()
    }

    private fun publish() {
        _members.value = roster.getAsJsonArray("members").map { it.asJsonObject }.map {
            GroupMember(it.get("id").asString, it.get("name")?.asString ?: "Device", it.get("kind")?.asString ?: "phone")
        }
        _wordsPending.value = pendingWords != null
    }

    /** A new group with only this device: first run, or after being removed from one. */
    private fun createGroup(secret: ByteArray, freshKeys: Boolean) {
        if (freshKeys || !::signSeed.isInitialized) {
            signSeed = GroupCrypto.randomBytes(32)
            dhPriv = GroupCrypto.newX25519()
        }
        val rk = GroupCrypto.recoveryKeys(secret)
        epochKey = GroupCrypto.randomBytes(32)
        backupSeed = GroupCrypto.randomBytes(32)
        roster = GroupCrypto.signRoster(JsonObject().apply {
            addProperty("group_id", rk.groupId); addProperty("version", 1); addProperty("epoch", 1)
            addProperty("epoch_check", GroupCrypto.epochCheck(epochKey))
            addProperty("rec_sign", b64(GroupCrypto.signPub(rk.signSeed)))
            addProperty("rec_dh", b64(GroupCrypto.x25519Pub(rk.dhPriv)))
            add("members", JsonArray().apply { add(me()) })
        }, selfId(), signSeed)
        pendingKeys = JsonObject()
        pendingWords = GroupCrypto.toWords(secret, words).joinToString(" ")
        save()
    }

    fun wordsSeen() = synchronized(lock) { pendingWords = null; save() }

    private fun changed() {
        AndroidHub.onFamilyChanged()
    }

    /** Rebuild links with the current key (after this device removed someone or restored). */
    fun relink() = changed()

    // --- link handshake with the PC (client side) --------------------------------------
    class Hello(val message: JsonObject, val ephPriv: ByteArray, val ephB64: String)

    fun hello(): Hello? = synchronized(lock) {
        if (!hasPc()) return null
        val eph = GroupCrypto.newX25519()
        val ephB64 = b64(GroupCrypto.x25519Pub(eph))
        val id = selfId()
        Hello(JsonObject().apply {
            addProperty("type", "key_exchange"); addProperty("v", 2)
            addProperty("id", id); addProperty("g", groupId); addProperty("eph", ephB64); addProperty("rv", version)
            addProperty("sig", b64(GroupCrypto.sign(signSeed, GroupCrypto.helloBytes(groupId, id, ephB64))))
        }, eph, ephB64)
    }

    /** Check the PC's answer; returns the session key, or null if the PC isn't the group's PC. */
    fun finishHello(hello: Hello, ack: JsonObject): ByteArray? {
        if (ack.get("ok")?.asBoolean != true) return null
        ack.getAsJsonObject("update")?.let { applyUpdate(it) }
        val serverId = ack.get("id")?.asString ?: return null
        val serverEph = ack.get("eph")?.asString ?: return null
        val pub = signPubOf(serverId) ?: return null
        val t = GroupCrypto.transcript(groupId, selfId(), serverId, hello.ephB64, serverEph)
        if (!GroupCrypto.verify(pub, t, unb64(ack.get("sig")?.asString))) return null
        return GroupCrypto.sessionKey(GroupCrypto.x25519(hello.ephPriv, unb64(serverEph)), t)
    }

    // --- joining (scanner side) ----------------------------------------------------------
    /**
     * Join the group of the device whose live QR was scanned. Talks to it over UDP at
     * [host]:[port] (the PC's port, or a phone's hub port). Returns null on success,
     * otherwise a message for the user.
     */
    fun joinOverUdp(qr: JsonObject, host: String, port: Int): String? {
        val token = unb64(qr.get("t")?.asString)
        val inviterEph = qr.get("eph")?.asString ?: return "That isn't a Lanyard code."
        if (token.size != 16) return "That isn't a Lanyard code."
        val tid = GroupCrypto.tokenId(token)
        val me = synchronized(lock) { me() }
        val eph = GroupCrypto.newX25519()
        val ephB64 = b64(GroupCrypto.x25519Pub(eph))
        val t = GroupCrypto.joinTranscript(tid, inviterEph, ephB64, me.get("id").asString, me.get("sign").asString, me.get("dh").asString)
        val request = me.deepCopy().apply {
            addProperty("type", "group_join"); addProperty("t_id", tid); addProperty("eph", ephB64)
            addProperty("proof", GroupCrypto.hex(GroupCrypto.hmac(token, t)))
        }
        val reply = runCatching { udpExchange(request, host, port) }.getOrNull()
            ?: return "No answer from the other device. Are both on the same Wi-Fi?"
        if (reply.get("ok")?.asBoolean != true) return reply.get("message")?.asString ?: "The code was refused. Scan a fresh one."
        val state = runCatching {
            val key = GroupCrypto.joinKey(GroupCrypto.x25519(eph, unb64(inviterEph)), token, t)
            JsonParser.parseString(String(GroupCrypto.aeadOpen(key, unb64(reply.get("box").asString)))).asJsonObject
        }.getOrNull() ?: return "The answer couldn't be read. Scan a fresh code."
        return if (adopt(state, qr.get("by")?.asString ?: "")) null else "The group's details didn't check out."
    }

    private fun udpExchange(request: JsonObject, host: String, port: Int): JsonObject? {
        DatagramSocket().use { socket ->
            socket.soTimeout = 1500
            val bytes = request.toString().toByteArray()
            val target = InetSocketAddress(host, port)
            repeat(3) {
                socket.send(DatagramPacket(bytes, bytes.size, target))
                val buf = ByteArray(65535)
                val packet = DatagramPacket(buf, buf.size)
                val got = runCatching { socket.receive(packet); true }.getOrDefault(false)
                if (got) {
                    val reply = runCatching { JsonParser.parseString(String(packet.data, 0, packet.length)).asJsonObject }.getOrNull()
                    if (reply?.get("type")?.asString == "group_join_ack") return reply
                }
            }
        }
        return null
    }

    private fun adopt(state: JsonObject, inviterId: String): Boolean = synchronized(lock) {
        val newRoster = state.getAsJsonObject("roster") ?: return false
        val key = unb64(state.get("epoch_key")?.asString)
        val inviterPub = GroupCrypto.member(newRoster, inviterId)?.get("sign")?.asString?.let { unb64(it) } ?: return false
        if (!GroupCrypto.rosterSignedBy(newRoster, inviterPub)) return false
        if (GroupCrypto.member(newRoster, selfId())?.get("sign")?.asString != b64(GroupCrypto.signPub(signSeed))) return false
        if (GroupCrypto.epochCheck(key) != newRoster.get("epoch_check").asString) return false
        roster = newRoster
        epochKey = key
        backupSeed = unb64(state.get("backup_seed")?.asString).takeIf { it.size == 32 } ?: GroupCrypto.randomBytes(32)
        relayUrl = state.get("relay_url")?.asString?.takeIf { it.isNotBlank() } ?: relayUrl
        pendingKeys = JsonObject()
        pendingWords = null
        save()
        changed()
        true
    }

    // --- joining (inviter side: this phone shows the live QR) ------------------------------
    /** A fresh one-time code for the live QR. */
    fun newOffer(): JsonObject = synchronized(lock) {
        val now = System.currentTimeMillis()
        offers.entries.removeIf { now - it.value.at > OFFER_LIFETIME_MS }
        val token = GroupCrypto.randomBytes(16)
        val eph = GroupCrypto.newX25519()
        offers[GroupCrypto.tokenId(token)] = Offer(token, eph, now)
        JsonObject().apply {
            addProperty("by", selfId())
            addProperty("eph", b64(GroupCrypto.x25519Pub(eph)))
            addProperty("t", b64(token))
        }
    }

    /** A scanner's group_join. Returns (reply, the new member's name or null). */
    fun acceptJoin(data: JsonObject): Pair<JsonObject, String?> = synchronized(lock) {
        fun fail(error: String, message: String? = null) = JsonObject().apply {
            addProperty("type", "group_join_ack"); addProperty("ok", false); addProperty("error", error)
            message?.let { addProperty("message", it) }
        } to null
        val tid = data.get("t_id")?.asString ?: return fail("bad_request")
        val offer = offers[tid]
        if (offer == null || System.currentTimeMillis() - offer.at > OFFER_LIFETIME_MS) return fail("expired", "That code expired. Scan the QR again.")
        val id = data.get("id")?.asString?.take(64) ?: return fail("bad_request")
        val sign = data.get("sign")?.asString ?: ""
        val dh = data.get("dh")?.asString ?: ""
        val jEph = data.get("eph")?.asString ?: ""
        if (id.isEmpty() || id == selfId() || unb64(sign).size != 32 || unb64(dh).size != 32 || unb64(jEph).size != 32) return fail("bad_request")
        val inviterEph = b64(GroupCrypto.x25519Pub(offer.eph))
        val t = GroupCrypto.joinTranscript(tid, inviterEph, jEph, id, sign, dh)
        val proof = data.get("proof")?.asString ?: ""
        if (!AndroidHub.constantTimeEquals(proof, GroupCrypto.hex(GroupCrypto.hmac(offer.token, t)))) return fail("bad_proof")
        offers.remove(tid)
        val kind = when (data.get("kind")?.asString) { "tablet" -> "tablet"; "pc" -> "pc"; else -> "phone" }
        val name = data.get("name")?.asString?.take(60) ?: "Device"
        val joiner = JsonObject().apply {
            addProperty("id", id); addProperty("name", name); addProperty("kind", kind)
            addProperty("sign", sign); addProperty("dh", dh)
        }
        val members = JsonArray().apply {
            roster.getAsJsonArray("members").forEach { if (it.asJsonObject.get("id").asString != id) add(it) }
            add(joiner)
        }
        roster = GroupCrypto.signRoster(roster.deepCopy().apply {
            addProperty("version", version + 1); add("members", members)
        }, selfId(), signSeed)
        save()
        val state = JsonObject().apply {
            addProperty("group_id", groupId); addProperty("epoch_key", b64(epochKey)); add("roster", roster)
            addProperty("relay_url", relayUrl); addProperty("backup_seed", b64(backupSeed))
        }
        val key = GroupCrypto.joinKey(GroupCrypto.x25519(offer.eph, unb64(jEph)), offer.token, t)
        val reply = JsonObject().apply {
            addProperty("type", "group_join_ack"); addProperty("ok", true); addProperty("t_id", tid)
            addProperty("box", b64(GroupCrypto.aeadSeal(key, state.toString().toByteArray())))
        }
        reply to name
    }

    /** The roster as it stands, for every other member after a join. */
    fun rosterUpdate(): JsonObject = synchronized(lock) {
        JsonObject().apply { addProperty("type", "group_update"); add("roster", roster.deepCopy()) }
    }

    // --- removing a device and updates from others ----------------------------------------
    /** Remove [deviceId] and start a new epoch it never learns. Returns the group_update to send. */
    fun remove(deviceId: String): JsonObject? = synchronized(lock) {
        if (deviceId == selfId() || !isMember(deviceId)) return null
        val members = JsonArray().apply {
            roster.getAsJsonArray("members").forEach { if (it.asJsonObject.get("id").asString != deviceId) add(it) }
        }
        rotate(members, selfId(), signSeed)
    }

    private fun rotate(members: JsonArray, signer: String, seed: ByteArray): JsonObject {
        epochKey = GroupCrypto.randomBytes(32)
        val newEpoch = epoch + 1
        val keys = JsonObject()
        members.map { it.asJsonObject }.filter { it.get("id").asString != selfId() }.forEach { m ->
            keys.addProperty(m.get("id").asString,
                b64(GroupCrypto.sealTo(unb64(m.get("dh").asString), epochKey, GroupCrypto.epochInfo(groupId, newEpoch))))
        }
        roster = GroupCrypto.signRoster(roster.deepCopy().apply {
            addProperty("version", version + 1); addProperty("epoch", newEpoch)
            addProperty("epoch_check", GroupCrypto.epochCheck(epochKey)); add("members", members)
        }, signer, seed)
        pendingKeys = keys
        save()
        // The caller sends the update over the current links first, then calls relink().
        return JsonObject().apply { addProperty("type", "group_update"); add("roster", roster.deepCopy()); add("keys", keys.deepCopy()) }
    }

    /** "applied", "removed" (this device was taken out) or null when it isn't valid or not newer. */
    fun applyUpdate(update: JsonObject): String? = synchronized(lock) {
        val new = update.getAsJsonObject("roster") ?: return null
        if (!GroupCrypto.rosterFollows(roster, new)) return null
        if (GroupCrypto.member(new, selfId()) == null) {
            createGroup(GroupCrypto.randomBytes(32), freshKeys = false)
            changed()
            return "removed"
        }
        val newEpoch = new.get("epoch").asInt
        if (newEpoch != epoch) {
            val sealed = update.getAsJsonObject("keys")?.get(selfId())?.asString ?: return null
            val key = runCatching {
                GroupCrypto.openFrom(dhPriv, unb64(sealed), GroupCrypto.epochInfo(new.get("group_id").asString, newEpoch))
            }.getOrNull() ?: return null
            if (GroupCrypto.epochCheck(key) != new.get("epoch_check").asString) return null
            epochKey = key
            pendingKeys = update.getAsJsonObject("keys")?.deepCopy() ?: JsonObject()
        }
        val epochChanged = newEpoch != roster.get("epoch").asInt
        roster = new
        save()
        if (epochChanged) changed()
        "applied"
    }

    /** What a member that may have missed a rotation needs. */
    fun updateFor(deviceId: String): JsonObject = synchronized(lock) {
        rosterUpdate().apply { pendingKeys.get(deviceId)?.let { k -> add("keys", JsonObject().apply { add(deviceId, k) }) } }
    }

    // --- recovery --------------------------------------------------------------------------
    fun backupRecord(): JsonObject = synchronized(lock) {
        val plain = JsonObject().apply {
            add("roster", roster); addProperty("epoch_key", b64(epochKey)); addProperty("backup_seed", b64(backupSeed))
        }.toString().toByteArray()
        val blob = b64(GroupCrypto.sealTo(unb64(roster.get("rec_dh").asString), plain, "lanyard-backup|$groupId".toByteArray()))
        val id = GroupCrypto.backupId(groupId)
        JsonObject().apply {
            addProperty("id", id); addProperty("blob", blob); addProperty("version", version)
            addProperty("pub", b64(GroupCrypto.signPub(backupSeed)))
            addProperty("sig", b64(GroupCrypto.sign(backupSeed, "$id|$version|$blob".toByteArray())))
        }
    }

    /** The group id the words belong to (to fetch the backup), or null if the words are wrong. */
    fun groupIdForWords(text: String): String? = GroupCrypto.fromWords(text, words)?.let { GroupCrypto.recoveryKeys(it).groupId }

    /** Rejoin with the words and the Worker's backup; starts a new epoch. Returns the update to send, or null. */
    fun restore(text: String, backupBlob: String): JsonObject? = synchronized(lock) {
        val secret = GroupCrypto.fromWords(text, words) ?: return null
        val rk = GroupCrypto.recoveryKeys(secret)
        val state = runCatching {
            JsonParser.parseString(String(GroupCrypto.openFrom(rk.dhPriv, unb64(backupBlob), "lanyard-backup|${rk.groupId}".toByteArray()))).asJsonObject
        }.getOrNull() ?: return null
        val r = state.getAsJsonObject("roster") ?: return null
        if (r.get("group_id")?.asString != rk.groupId || r.get("rec_sign")?.asString != b64(GroupCrypto.signPub(rk.signSeed))) return null
        roster = r
        epochKey = unb64(state.get("epoch_key").asString)
        backupSeed = unb64(state.get("backup_seed")?.asString).takeIf { it.size == 32 } ?: GroupCrypto.randomBytes(32)
        val members = JsonArray().apply {
            r.getAsJsonArray("members").forEach { if (it.asJsonObject.get("id").asString != selfId()) add(it) }
            add(me())
        }
        pendingWords = null
        rotate(members, "recovery", rk.signSeed)
    }
}
