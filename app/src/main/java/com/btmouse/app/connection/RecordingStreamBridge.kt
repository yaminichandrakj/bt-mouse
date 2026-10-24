package com.btmouse.app.connection

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread

/** Serves an authenticated remote recording transfer to Android's media player as a local chunked HTTP stream. */
class RecordingStreamBridge(extension: String) {
    private val secret = UUID.randomUUID().toString().replace("-", "")
    private val contentType = when (extension.lowercase()) {
        "avi" -> "video/x-msvideo"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        else -> "application/octet-stream"
    }
    private val chunks = Channel<ByteArray>(8)
    private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null

    val url: String

    init {
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server = socket
        url = "http://127.0.0.1:${socket.localPort}/$secret"
        thread(name = "RecordingStreamHttp", isDaemon = true) { acceptAndServe(socket) }
    }

    fun offer(bytes: ByteArray): Boolean = chunks.trySend(bytes).isSuccess

    fun finish() {
        chunks.close()
    }

    fun close() {
        chunks.close()
        try { client?.close() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        client = null
        server = null
    }

    private fun acceptAndServe(listener: ServerSocket) {
        try {
            listener.soTimeout = 1000
            var accepted: Socket? = null
            while (server === listener && accepted == null) {
                try { accepted = listener.accept() } catch (_: java.net.SocketTimeoutException) {}
            }
            val socket = accepted ?: return
            client = socket
            socket.soTimeout = 5000
            val input = BufferedInputStream(socket.getInputStream())
            val request = readRequest(input)
            if (!request.startsWith("GET /$secret ")) return

            val output = BufferedOutputStream(socket.getOutputStream())
            output.write(("HTTP/1.1 200 OK\r\n" +
                "Content-Type: $contentType\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            output.flush()
            runBlocking {
                for (chunk in chunks) {
                    output.write((chunk.size.toString(16) + "\r\n").toByteArray(Charsets.US_ASCII))
                    output.write(chunk)
                    output.write("\r\n".toByteArray(Charsets.US_ASCII))
                    output.flush()
                }
            }
            output.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
        } catch (_: Exception) {
        } finally {
            try { client?.close() } catch (_: Exception) {}
            client = null
            try { listener.close() } catch (_: Exception) {}
            if (server === listener) server = null
        }
    }

    private fun readRequest(input: BufferedInputStream): String {
        val bytes = ArrayList<Byte>(512)
        var matched = 0
        val end = byteArrayOf(13, 10, 13, 10)
        while (bytes.size < 8192) {
            val next = input.read()
            if (next < 0) break
            bytes.add(next.toByte())
            matched = if (next.toByte() == end[matched]) matched + 1 else if (next.toByte() == end[0]) 1 else 0
            if (matched == end.size) break
        }
        return bytes.toByteArray().toString(Charsets.US_ASCII)
    }
}
