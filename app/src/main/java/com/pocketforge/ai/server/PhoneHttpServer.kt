package com.pocketforge.ai.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/** Parsed request: request line + headers + full body (Content-Length transport only). */
class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

internal class HeaderTooLargeException : IOException("header block too large")

/**
 * Minimal hand-rolled HTTP/1.1 listener on a ServerSocket. One connection per
 * request, always `Connection: close`, no keep-alive. Header block max 16 KB,
 * body max 256 KB, chunked bodies rejected. The accept loop polls with a socket
 * timeout so [stop] is always responsive; each connection is handled on
 * [scope] (Dispatchers.IO) with per-read timeouts so a stuck client cannot
 * wedge the listener.
 */
class PhoneHttpServer(
    private val scope: CoroutineScope,
    private val listener: ServerSocket,
    val lanAccess: Boolean,
    private val route: suspend (HttpRequest, Socket) -> Unit,
) {

    val boundPort: Int get() = listener.localPort

    private val connections = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile
    private var running = false

    fun start() {
        synchronized(this) {
            if (running) return
            running = true
        }
        listener.soTimeout = ACCEPT_POLL_MS
        scope.launch { acceptLoop() }
    }

    /** Closes the listener and every active connection; safe to call twice. */
    fun stop() {
        running = false
        runCatching { listener.close() }
        connections.forEach { runCatching { it.close() } }
        connections.clear()
    }

    private suspend fun acceptLoop() {
        while (running) {
            val sock = try {
                listener.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                if (!running) break
                delay(50)
                continue
            } catch (_: Throwable) {
                break
            }
            runCatching { sock.tcpNoDelay = true }
            connections.add(sock)
            scope.launch {
                try {
                    handleConnection(sock)
                } finally {
                    connections.remove(sock)
                    runCatching { sock.close() }
                }
            }
        }
    }

    private suspend fun handleConnection(sock: Socket) {
        try {
            sock.soTimeout = IO_TIMEOUT_MS
            val input = sock.getInputStream().buffered(8192)
            val output = sock.getOutputStream()
            val req = parseRequest(input, output) ?: return
            route(req, sock)
        } catch (_: HeaderTooLargeException) {
            // parseRequest failed before writing anything
            runCatching {
                writeJsonResponse(
                    sock.getOutputStream(), 400,
                    PhoneServerContract.errorJson("header block too large", 400),
                )
            }
        } catch (_: SocketTimeoutException) {
            // client stalled while sending the request; drop the connection
        } catch (_: IOException) {
            // client went away
        } catch (t: Throwable) {
            runCatching {
                writeJsonResponse(
                    sock.getOutputStream(), 500,
                    PhoneServerContract.errorJson("internal error", 500),
                )
            }
        }
    }

    /** Returns null after writing a protocol error (or on EOF); throws on oversized headers. */
    private fun parseRequest(input: InputStream, output: OutputStream): HttpRequest? {
        var budget = MAX_HEADER_BYTES

        fun readLine(): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return null
                budget--
                if (budget < 0) throw HeaderTooLargeException()
                if (b == '\n'.code) {
                    var s = sb.toString()
                    if (s.endsWith("\r")) s = s.substring(0, s.length - 1)
                    return s
                }
                sb.append(b.toChar())
            }
        }

        val requestLine = readLine() ?: return null
        val parts = requestLine.split(' ')
        if (parts.size != 3) {
            writeJsonResponse(output, 400, PhoneServerContract.errorJson("malformed request line", 400))
            return null
        }
        val method = parts[0].uppercase()
        val version = parts[2]
        if (version != "HTTP/1.1" && version != "HTTP/1.0") {
            writeJsonResponse(output, 400, PhoneServerContract.errorJson("unsupported HTTP version", 400))
            return null
        }
        val path = parts[1].substringBefore('?')
        if (!path.startsWith("/")) {
            writeJsonResponse(output, 400, PhoneServerContract.errorJson("malformed path", 400))
            return null
        }
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine() ?: return null
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) {
                writeJsonResponse(output, 400, PhoneServerContract.errorJson("malformed header", 400))
                return null
            }
            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        if (headers.containsKey("transfer-encoding")) {
            writeJsonResponse(output, 400, PhoneServerContract.errorJson("chunked transfer encoding is not supported", 400))
            return null
        }
        var contentLength = 0L
        headers["content-length"]?.let { raw ->
            val cl = raw.toLongOrNull()
            if (cl == null || cl < 0) {
                writeJsonResponse(output, 400, PhoneServerContract.errorJson("invalid content-length", 400))
                return null
            }
            if (cl > MAX_BODY_BYTES) {
                writeJsonResponse(output, 413, PhoneServerContract.errorJson("request body too large", 413))
                return null
            }
            contentLength = cl
        }
        val body = if (contentLength > 0) {
            ByteArray(contentLength.toInt()).also { arr ->
                var off = 0
                while (off < arr.size) {
                    val n = input.read(arr, off, arr.size - off)
                    if (n < 0) return null
                    off += n
                }
            }
        } else {
            ByteArray(0)
        }
        return HttpRequest(method, path, headers, body)
    }

    companion object {
        private const val ACCEPT_POLL_MS = 1000
        private const val IO_TIMEOUT_MS = 30_000
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_BODY_BYTES = 256 * 1024

        private val reasons = mapOf(
            200 to "OK",
            204 to "No Content",
            400 to "Bad Request",
            401 to "Unauthorized",
            404 to "Not Found",
            409 to "Conflict",
            413 to "Payload Too Large",
            429 to "Too Many Requests",
            500 to "Internal Server Error",
            503 to "Service Unavailable",
        )

        fun writeJsonResponse(out: OutputStream, status: Int, json: String) {
            writeRaw(out, status, "Content-Type: application/json\r\n", json.toByteArray(Charsets.UTF_8))
        }

        fun writeNoContent(out: OutputStream) {
            out.write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
        }

        fun startSse(out: OutputStream) {
            out.write(
                "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII),
            )
            out.flush()
        }

        fun writeSseData(out: OutputStream, data: String) {
            out.write("data: $data\n\n".toByteArray(Charsets.UTF_8))
            out.flush()
        }

        fun writeSseError(out: OutputStream, message: String) {
            out.write(
                "event: error\ndata: ${PhoneServerContract.errorJson(message, 500)}\n\n".toByteArray(Charsets.UTF_8),
            )
            out.flush()
        }

        private fun writeRaw(out: OutputStream, status: Int, extraHeaders: String, body: ByteArray) {
            val head = "HTTP/1.1 $status ${reasons[status] ?: "OK"}\r\n$extraHeaders" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.flush()
        }
    }
}
