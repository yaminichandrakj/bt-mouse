package com.btmouse.app.ecosystem

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File

/**
 * This device's recordings and snapshots as part of the shared library. The PC asks
 * what is here (library_list) and fetches what it is missing (library_fetch), so it
 * holds a copy of everything and every device can browse and play it from there.
 */
object Library {
    private val audio = setOf("wav", "m4a", "mp3", "aac", "ogg")
    private val image = setOf("jpg", "jpeg", "png", "webp")

    private fun base(context: Context): File = context.getExternalFilesDir(null) ?: context.filesDir

    fun folderFor(context: Context, name: String): File {
        val ext = name.substringAfterLast('.', "").lowercase()
        val folder = when (ext) {
            in audio -> "Audios"
            in image -> "Snapshots"
            else -> "Videos"
        }
        return File(base(context), folder).apply { mkdirs() }
    }

    private fun localFiles(context: Context): List<File> =
        listOf("Videos", "Audios", "Snapshots").flatMap { dir ->
            File(base(context), dir).listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && it.length() > 0 } ?: emptyList()
        }

    fun answerList(context: Context, request: JsonObject, send: (JsonObject) -> Unit) {
        val items = JsonArray()
        localFiles(context).sortedByDescending { it.lastModified() }.take(400).forEach { f ->
            items.add(JsonObject().apply {
                addProperty("id", f.name)
                addProperty("name", f.name)
                addProperty("size", f.length())
                addProperty("modified", f.lastModified() / 1000)
                addProperty("type", when (f.parentFile?.name) { "Audios" -> "audio"; "Snapshots" -> "snapshot"; else -> "video" })
            })
        }
        val reply = JsonObject().apply {
            addProperty("type", "library_items")
            add("items", items)
        }
        request.get("from")?.takeIf { !it.isJsonNull }?.asString?.let { if (it != Ecosystem.PC_ID) reply.addProperty("to", it) }
        send(reply)
    }

    fun answerFetch(context: Context, request: JsonObject) {
        val name = File(request.get("id")?.asString ?: return).name
        val file = localFiles(context).firstOrNull { it.name == name } ?: return
        val to = request.get("from")?.takeIf { !it.isJsonNull }?.asString ?: Ecosystem.PC_ID
        Ecosystem.files.sendLibraryFile(to, file)
    }
}
