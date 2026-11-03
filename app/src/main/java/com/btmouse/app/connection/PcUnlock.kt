package com.btmouse.app.connection

import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** The PC's answer to an unlock request; codes come from pc_server/unlock.py. */
data class UnlockResult(val code: String, val success: Boolean, val message: String)

/** Carries unlock_result packets from whichever link is in use to the screen that asked. */
object PcUnlock {
    private val _results = MutableSharedFlow<UnlockResult>(extraBufferCapacity = 4)
    val results: SharedFlow<UnlockResult> = _results

    fun handleIncoming(json: JsonObject): Boolean {
        if (json.get("type")?.asString != "unlock_result") return false
        val code = json.get("code")?.takeIf { !it.isJsonNull }?.asString ?: "error"
        _results.tryEmit(
            UnlockResult(
                code = code,
                success = json.get("success")?.takeIf { !it.isJsonNull }?.asBoolean ?: (code == "ok"),
                message = json.get("message")?.takeIf { !it.isJsonNull }?.asString ?: "Couldn't unlock the PC"
            )
        )
        return true
    }
}
