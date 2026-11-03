package com.btmouse.app.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps each PC's Windows password for phone unlock. The password is sealed with an
 * Android Keystore key that only works right after a fingerprint, face or screen-lock
 * check, so the stored bytes are useless without the phone's owner. Adding a new
 * fingerprint retires the key and every saved password with it.
 */
class PcPasswordVault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pc_unlock", Context.MODE_PRIVATE)

    fun has(pc: String): Boolean = prefs.contains(slot(pc))

    fun forget(pc: String) {
        prefs.edit().remove(slot(pc)).apply()
    }

    fun forgetAll() {
        prefs.edit().clear().apply()
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    /** A cipher that seals a new password once the owner is checked; null when the Keystore refuses. */
    fun sealer(): Cipher? = runCatching { encryptCipher() }.recoverCatching {
        if (it !is KeyPermanentlyInvalidatedException) throw it
        forgetAll()
        encryptCipher()
    }.getOrNull()

    /** A cipher that opens this PC's password once the owner is checked; null when none can be opened. */
    fun opener(pc: String): Cipher? {
        val stored = prefs.getString(slot(pc), null) ?: return null
        val iv = Base64.decode(stored.substringBefore(':'), Base64.NO_WRAP)
        return try {
            val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: return null.also { forget(pc) }
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            forgetAll()
            null
        } catch (_: Exception) {
            null
        }
    }

    fun save(pc: String, sealer: Cipher, password: String) {
        val sealed = sealer.doFinal(password.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(slot(pc),
            Base64.encodeToString(sealer.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(sealed, Base64.NO_WRAP)
        ).apply()
    }

    fun open(pc: String, opener: Cipher): String? {
        val stored = prefs.getString(slot(pc), null) ?: return null
        return runCatching {
            String(opener.doFinal(Base64.decode(stored.substringAfter(':'), Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun slot(pc: String) = "pw:" + pc.trim().lowercase()

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun encryptCipher(): Cipher {
        val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: createKey()
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    private fun createKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "lanyard_pc_unlock"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** What the prompt may accept: screen lock too where the Keystore allows it with a key (Android 11+). */
        val authenticators: Int
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            } else {
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
            }
    }
}
