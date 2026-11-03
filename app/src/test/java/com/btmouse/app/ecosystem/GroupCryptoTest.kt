package com.btmouse.app.ecosystem

import com.btmouse.app.ecosystem.GroupCrypto.b64
import com.btmouse.app.ecosystem.GroupCrypto.unb64
import com.btmouse.app.ecosystem.GroupCrypto.unhex
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GroupCrypto must produce exactly what pc_server/group.py does. The vectors come from
 * pc_server/make_group_vectors.py; a mismatch here means a phone and the PC can't pair.
 */
class GroupCryptoTest {
    private val v: JsonObject = JsonParser.parseString(
        javaClass.classLoader!!.getResourceAsStream("group_vectors.json")!!.bufferedReader().readText()
    ).asJsonObject

    private val words: List<String> = listOf("src/main/assets/bip39_english.txt", "app/src/main/assets/bip39_english.txt")
        .map { File(it) }.first { it.exists() }.readLines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun s(key: String) = v.get(key).asString

    @Test
    fun hkdfMatchesRfc5869Case1() {
        val out = GroupCrypto.hkdf(unhex("0b".repeat(22)), unhex("f0f1f2f3f4f5f6f7f8f9"), 42, unhex("000102030405060708090a0b0c"))
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", GroupCrypto.hex(out))
    }

    @Test
    fun wordsAndRecoveryKeysMatchThePc() {
        val secret = unhex(s("secret"))
        assertEquals(v.getAsJsonObject("hkdf").get("out").asString, GroupCrypto.hex(GroupCrypto.hkdf(secret, "lanyard-test".toByteArray())))
        assertEquals(s("words"), GroupCrypto.toWords(secret, words).joinToString(" "))
        assertEquals(s("words_zero"), GroupCrypto.toWords(ByteArray(32), words).joinToString(" "))
        assertArrayEquals(secret, GroupCrypto.fromWords(s("words"), words))
        val swapped = s("words").split(" ").toMutableList().also { val a = it[0]; it[0] = it[1]; it[1] = a }
        assertNull(GroupCrypto.fromWords(swapped.joinToString(" "), words))
        val rk = GroupCrypto.recoveryKeys(secret)
        assertEquals(s("group_id"), rk.groupId)
        assertEquals(s("rec_sign_pub"), b64(GroupCrypto.signPub(rk.signSeed)))
        assertEquals(s("rec_dh_pub"), b64(GroupCrypto.x25519Pub(rk.dhPriv)))
        assertEquals(s("backup_id"), GroupCrypto.backupId(rk.groupId))
        assertEquals(s("epoch_check"), GroupCrypto.epochCheck(unhex(s("epoch_key"))))
    }

    @Test
    fun rosterBytesAndSignatureMatchThePc() {
        val roster = v.getAsJsonObject("roster")
        assertEquals(s("roster_bytes"), String(GroupCrypto.rosterBytes(roster), Charsets.UTF_8))
        // Ed25519 is deterministic: same seed and bytes, same signature.
        assertEquals(roster.get("sig").asString, GroupCrypto.signRoster(roster, "pc", unhex(s("seed_a"))).get("sig").asString)
        assertTrue(GroupCrypto.rosterSignedBy(roster, GroupCrypto.signPub(unhex(s("seed_a")))))
        val tampered = roster.deepCopy().apply { addProperty("version", 4) }
        assertFalse(GroupCrypto.rosterSignedBy(tampered, GroupCrypto.signPub(unhex(s("seed_a")))))
    }

    @Test
    fun opensWhatThePcSealed() {
        val sealed = v.getAsJsonObject("sealed")
        val plain = GroupCrypto.openFrom(unhex(s("dh_a")), unb64(sealed.get("blob").asString), sealed.get("info").asString.toByteArray())
        assertEquals(sealed.get("plain").asString, String(plain))
        // And the other way round.
        val mine = GroupCrypto.sealTo(GroupCrypto.x25519Pub(unhex(s("dh_a"))), "hi".toByteArray(), "x".toByteArray())
        assertEquals("hi", String(GroupCrypto.openFrom(unhex(s("dh_a")), mine, "x".toByteArray())))
    }

    @Test
    fun handshakeAndJoinKeysMatchThePc() {
        assertEquals(s("hello_bytes"), String(GroupCrypto.helloBytes(s("group_id"), "a1b2", "Y2xpZW50")))
        val t = GroupCrypto.transcript(s("group_id"), "a1b2", "pc", "Y2xpZW50", "c2VydmVy")
        assertEquals(s("transcript"), String(t))
        assertEquals(s("session_key"), GroupCrypto.hex(GroupCrypto.sessionKey(unhex(s("shared")), t)))
        val token = unhex(s("token"))
        assertEquals(s("token_id"), GroupCrypto.tokenId(token))
        val member = v.getAsJsonObject("roster").getAsJsonArray("members")[1].asJsonObject
        val jt = GroupCrypto.joinTranscript(GroupCrypto.tokenId(token), "aW52", "am9pbg==", "a1b2",
            member.get("sign").asString, member.get("dh").asString)
        assertEquals(s("join_transcript"), String(jt))
        assertEquals(s("join_key"), GroupCrypto.hex(GroupCrypto.joinKey(unhex(s("shared")), token, jt)))
        assertEquals(s("join_proof"), GroupCrypto.hex(GroupCrypto.hmac(token, jt)))
    }
}
