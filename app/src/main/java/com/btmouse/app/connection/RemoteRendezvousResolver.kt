package com.btmouse.app.connection

import android.util.Base64
import android.util.Log
import com.btmouse.app.data.SettingsRepository
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Resolves the PC's current WAN WebSocket URL from the Cloudflare rendezvous Worker.
 *
 * The record is signed by the PC's Ed25519 identity key; the phone verifies it with the
 * public key captured from the QR code, so a tampered or spoofed record is rejected and a
 * stale one (older than [MAX_AGE_MS]) is ignored. This is what lets the tunnel URL change on
 * every PC restart without ever re-scanning the QR.
 */
object RemoteRendezvousResolver {

    private const val TAG = "RvResolver"
    private const val MAX_AGE_MS = 10 * 60 * 1000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    data class Rendezvous(val worker: String, val id: String, val pub: String) {
        val isComplete: Boolean
            get() = worker.startsWith("https://") && id.isNotBlank() && pub.isNotBlank()
    }

    fun fromSettings(settings: SettingsRepository): Rendezvous =
        Rendezvous(settings.rvWorker.value.trim(), settings.rvId.value.trim(), settings.rvPub.value.trim())

    sealed class Result {
        data class Ok(val url: String) : Result()
        data class Error(val message: String) : Result()
    }

    /** Fetch, verify and freshness-check the record. Safe to call off the main thread. */
    suspend fun resolve(rv: Rendezvous): Result = withContext(Dispatchers.IO) {
        if (!rv.isComplete) return@withContext Result.Error("No remote rendezvous configured")
        val endpoint = "${rv.worker.trimEnd('/')}/r/${rv.id}"
        try {
            val body = client.newCall(Request.Builder().url(endpoint).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.Error(
                        if (resp.code == 404) "PC is offline (no current address published)"
                        else "Lookup failed (HTTP ${resp.code})"
                    )
                }
                resp.body?.string().orEmpty()
            }

            val envelope = gson.fromJson(body, JsonObject::class.java)
            val data = envelope.get("data")?.asString ?: return@withContext Result.Error("Malformed record")
            val sig = envelope.get("sig")?.asString ?: return@withContext Result.Error("Record not signed")

            val pubBytes = Base64.decode(rv.pub, Base64.DEFAULT)
            val sigBytes = Base64.decode(sig, Base64.DEFAULT)
            try {
                Ed25519Verify(pubBytes).verify(sigBytes, data.toByteArray(Charsets.UTF_8))
            } catch (e: Exception) {
                Log.w(TAG, "Signature verification failed", e)
                return@withContext Result.Error("Address failed signature check — ignored")
            }

            val record = gson.fromJson(data, JsonObject::class.java)
            val url = record.get("url")?.asString ?: return@withContext Result.Error("Record has no URL")
            val ts = record.get("ts")?.asLong ?: 0L
            val ageMs = System.currentTimeMillis() - ts * 1000L
            if (ts <= 0L || ageMs > MAX_AGE_MS) {
                return@withContext Result.Error("PC address is stale — is the companion awake?")
            }
            Result.Ok(url)
        } catch (e: Exception) {
            Log.w(TAG, "Resolve failed", e)
            Result.Error("Could not reach the lookup service")
        }
    }
}
