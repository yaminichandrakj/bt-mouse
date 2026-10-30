package com.btmouse.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * PC app icons kept on the phone, so quick apps show their real icons at once,
 * even before the PC answers or when it is offline. Keys are app paths or names.
 */
class AppIconStore(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, "app_icons.json")
    private val lock = Any()
    private val _icons = MutableStateFlow<Map<String, String>>(emptyMap())
    val icons: StateFlow<Map<String, String>> = _icons.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            val saved = runCatching {
                if (file.isFile) Gson().fromJson<Map<String, String>>(file.readText(), object : TypeToken<Map<String, String>>() {}.type)
                else null
            }.onFailure { Log.w(TAG, "Could not read saved app icons", it) }.getOrNull().orEmpty()
            synchronized(lock) { _icons.value = saved + _icons.value }
        }
    }

    /** Add icons that arrived from the PC; writes to disk only when something changed. */
    fun remember(fresh: Map<String, String>) {
        val updated = synchronized(lock) {
            val current = _icons.value
            val changes = fresh.filter { (key, value) -> value.isNotBlank() && current[key] != value }
            if (changes.isEmpty()) return
            val next = (current + changes).entries.toList().takeLast(MAX_ENTRIES).associate { it.key to it.value }
            _icons.value = next
            next
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                val temp = File(file.parentFile, "${file.name}.tmp")
                temp.writeText(Gson().toJson(updated))
                if (!temp.renameTo(file)) {
                    file.delete()
                    temp.renameTo(file)
                }
            }.onFailure { Log.w(TAG, "Could not save app icons", it) }
        }
    }

    private companion object {
        const val TAG = "AppIconStore"
        const val MAX_ENTRIES = 2000
    }
}
