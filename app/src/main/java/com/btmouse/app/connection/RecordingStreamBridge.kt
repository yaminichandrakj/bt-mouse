package com.btmouse.app.connection

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Localhost HTTP media server for Android VideoView / MediaPlayer playback.
 * Supports multi-connection HTTP Range requests (206 Partial Content & 200 OK) when backed by a local File
 * so Android's MediaExtractor can freely probe MP4 moov atoms and seek without ever failing.
 */
class RecordingStreamBridge private constructor(
    extension: String,
    private val backingFile: File?
) {
    constructor(extension: String) : this(extension, null)
    constructor(file: File) : this(file.extension.ifBlank { "mp4" }, file)

    private val secret = UUID.randomUUID().toString().replace("-", "")
    private val cleanExt = extension.lowercase().ifBlank { "mp4" }
    private val contentType = when (cleanExt) {
        "avi" -> "video/x-msvideo"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        else -> "video/mp4"
    }
    private val chunks = Channel<ByteArray>(Channel.UNLIMITED)
    @Volatile private var server: ServerSocket? = null
    private val activeClients = mutableListOf<Socket>()

    val url: String

    init {
        val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = socket
        url = "http://127.0.0.1:${socket.localPort}/$secret.$cleanExt"
        thread(name = "RecordingStreamHttp", isDaemon = true) {
            if (backingFile != null) {
                acceptAndServeFileLoop(socket, backingFile)
            } else {
                acceptAndServeChunked(socket)
            }
        }
    }

    fun offer(bytes: ByteArray): Boolean = chunks.trySend(bytes).isSuccess

    fun finish() {
        chunks.close()
    }

    fun close() {
        chunks.close()
        val srv = server
        server = null
        try { srv?.close() } catch (_: Exception) {}
        synchronized(activeClients) {
            activeClients.forEach {
                try { it.close() } catch (_: Exception) {}
            }
            activeClients.clear()
        }
    }

    private fun acceptAndServeFileLoop(listener: ServerSocket, file: File) {
        try {
            listener.soTimeout = 1000
            while (server === listener) {
                val client = try {
                    listener.accept()
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                }
                synchronized(activeClients) { activeClients.add(client) }
                thread(name = "RecordingFileClient", isDaemon = true) {
                    serveFileRequest(client, file)
                }
            }
        } finally {
            try { listener.close() } catch (_: Exception) {}
        }
    }

    private fun serveFileRequest(socket: Socket, file: File) {
        try {
            socket.soTimeout = 10000
            val input = BufferedInputStream(socket.getInputStream())
            val request = readRequest(input)
            if (request.isBlank()) return
            val firstLine = request.lineSequence().firstOrNull().orEmpty()
            if (!firstLine.startsWith("GET /$secret") && !firstLine.startsWith("HEAD /$secret")) return
            val isHead = firstLine.startsWith("HEAD ")

            val totalLength = file.length()
            if (!file.exists() || totalLength <= 0L) return

            var rangeStart = 0L
            var rangeEnd = totalLength - 1L
            var isPartial = false

            val rangeHeader = request.lineSequence()
                .firstOrNull { it.startsWith("Range:", ignoreCase = true) }
                ?.substringAfter(":")?.trim().orEmpty()

            if (rangeHeader.startsWith("bytes=", ignoreCase = true)) {
                val spec = rangeHeader.substringAfter("=").substringBefore(",").trim()
                val startStr = spec.substringBefore("-").trim()
                val endStr = spec.substringAfter("-", "").trim()
                if (startStr.isNotEmpty()) {
                    rangeStart = startStr.toLongOrNull()?.coerceIn(0L, totalLength - 1L) ?: 0L
                    if (endStr.isNotEmpty()) {
                        rangeEnd = endStr.toLongOrNull()?.coerceIn(rangeStart, totalLength - 1L) ?: (totalLength - 1L)
                    }
                    isPartial = true
                } else if (endStr.isNotEmpty()) {
                    val suffix = endStr.toLongOrNull() ?: 0L
                    if (suffix > 0) {
                        rangeStart = (totalLength - suffix).coerceAtLeast(0L)
                        rangeEnd = totalLength - 1L
                        isPartial = true
                    }
                }
            }

            val contentLength = (rangeEnd - rangeStart + 1L).coerceAtLeast(0L)
            val output = BufferedOutputStream(socket.getOutputStream())
            val headers = buildString {
                if (isPartial) {
                    append("HTTP/1.1 206 Partial Content\r\n")
                    append("Content-Range: bytes $rangeStart-$rangeEnd/$totalLength\r\n")
                } else {
                    append("HTTP/1.1 200 OK\r\n")
                }
                append("Content-Type: $contentType\r\n")
                append("Content-Length: $contentLength\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(headers.toByteArray(Charsets.US_ASCII))
            output.flush()

            if (!isHead && contentLength > 0L) {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(rangeStart)
                    val buffer = ByteArray(64 * 1024)
                    var remaining = contentLength
                    while (remaining > 0L && server != null) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = raf.read(buffer, 0, toRead)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
                output.flush()
            }
        } catch (_: Exception) {
        } finally {
            synchronized(activeClients) { activeClients.remove(socket) }
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun acceptAndServeChunked(listener: ServerSocket) {
        try {
            listener.soTimeout = 1000
            var accepted: Socket? = null
            while (server === listener && accepted == null) {
                try { accepted = listener.accept() } catch (_: java.net.SocketTimeoutException) {}
            }
            val socket = accepted ?: return
            synchronized(activeClients) { activeClients.add(socket) }
            socket.soTimeout = 5000
            val input = BufferedInputStream(socket.getInputStream())
            val request = readRequest(input)
            if (!request.startsWith("GET /$secret")) return

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
