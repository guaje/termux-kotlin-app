package com.termux.app.x11.service

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * A loopback-only WebSocket to TCP bridge for noVNC.
 *
 * The bridge deliberately has no Android dependencies so its protocol handling can be tested on
 * the JVM. Each WebSocket client receives an independent connection to the target TCP service.
 */
class LoopbackWebSocketProxy(
    private val targetPort: Int,
    private val listenPort: Int = DEFAULT_PORT,
    private val diagnostics: ((String) -> Unit)? = null
) : Closeable {
    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    @Volatile
    private var activeBoundPort = 0

    private val sockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val outputLocks = ConcurrentHashMap<Socket, Any>()
    private val clientPermits = Semaphore(MAX_CLIENTS)

    /** The port currently bound by this bridge, or zero when it is stopped. */
    val boundPort: Int
        get() = activeBoundPort

    /** Alias for [boundPort] for callers which need the selected ephemeral port. */
    val actualPort: Int
        get() = boundPort

    @Synchronized
    fun start() {
        if (running) return

        val listener = ServerSocket()
        try {
            listener.reuseAddress = true
            listener.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), listenPort))
            serverSocket = listener
            activeBoundPort = listener.localPort
            running = true
            daemonThread("LoopbackWebSocketProxy-accept") { acceptClients(listener) }.start()
            diagnostic("Listening on $LOOPBACK_HOST:$activeBoundPort for TCP port $targetPort")
        } catch (error: Exception) {
            running = false
            serverSocket = null
            activeBoundPort = 0
            closeQuietly(listener)
            throw error
        }
    }

    fun stop() = close()

    @Synchronized
    override fun close() {
        if (!running && serverSocket == null) return
        running = false
        val listener = serverSocket
        serverSocket = null
        activeBoundPort = 0
        closeQuietly(listener)
        sockets.toList().forEach(::closeQuietly)
        sockets.clear()
        outputLocks.clear()
        diagnostic("Stopped")
    }

    private fun acceptClients(listener: ServerSocket) {
        while (running) {
            try {
                val client = listener.accept()
                client.tcpNoDelay = true
                client.soTimeout = HANDSHAKE_TIMEOUT_MS
                sockets.add(client)
                outputLocks[client] = Any()
                if (!clientPermits.tryAcquire()) {
                    sendServiceUnavailable(client)
                    sockets.remove(client)
                    outputLocks.remove(client)
                    closeQuietly(client)
                    continue
                }
                daemonThread("LoopbackWebSocketProxy-client") {
                    try {
                        handleClient(client)
                    } finally {
                        clientPermits.release()
                    }
                }.start()
            } catch (error: IOException) {
                if (running) diagnostic("Accept failed: ${error.message}")
                break
            }
        }
    }

    private fun handleClient(client: Socket) {
        var backend: Socket? = null
        try {
            val input = client.getInputStream()
            val output = client.getOutputStream()
            val handshake = readHandshake(client, input)
            val subprotocol = validateHandshake(handshake)
            writeHandshake(output, handshake.key, subprotocol)
            client.soTimeout = 0

            backend = Socket()
            sockets.add(backend)
            backend.tcpNoDelay = true
            backend.connect(
                InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), targetPort),
                CONNECT_TIMEOUT_MS
            )

            val outputLock = outputLocks[client] ?: Any()
            val backendSocket = backend
            daemonThread("LoopbackWebSocketProxy-backend") {
                copyBackendToWebSocket(backendSocket, output, outputLock, client)
            }.start()
            copyWebSocketToBackend(input, backend.getOutputStream(), output, outputLock)
        } catch (error: HandshakeException) {
            sendHttpError(client, error.message ?: "Invalid WebSocket handshake")
        } catch (error: ProtocolException) {
            sendClose(client, error.closeCode, error.message.orEmpty())
        } catch (error: IOException) {
            if (running) diagnostic("Client connection failed: ${error.message}")
        } catch (error: Exception) {
            if (running) diagnostic("Unexpected client error: ${error.message}")
        } finally {
            sockets.remove(client)
            outputLocks.remove(client)
            closeQuietly(client)
            backend?.let {
                sockets.remove(it)
                closeQuietly(it)
            }
        }
    }

    private fun copyWebSocketToBackend(
        input: InputStream,
        backendOutput: OutputStream,
        clientOutput: OutputStream,
        outputLock: Any
    ) {
        var fragmented = false
        var messageSize = 0L

        while (running) {
            val first = readByte(input)
            val second = readByte(input)
            val final = first and FIN_BIT != 0
            val rsv = first and RSV_BITS
            val opcode = first and OPCODE_MASK
            if (rsv != 0) throw ProtocolException(1002, "RSV bits are not supported")
            if (second and MASK_BIT == 0) throw ProtocolException(1002, "Client frames must be masked")

            val payloadLength = readPayloadLength(second and LENGTH_MASK, input)
            if (payloadLength > MAX_PAYLOAD_BYTES) {
                throw ProtocolException(1009, "Frame is too large")
            }
            if (opcode >= 8 && (!final || payloadLength > MAX_CONTROL_PAYLOAD_BYTES)) {
                throw ProtocolException(1002, "Invalid control frame")
            }

            val maskingKey = ByteArray(4)
            input.readFully(maskingKey)
            val payload = ByteArray(payloadLength.toInt())
            input.readFully(payload)
            payload.indices.forEach { index ->
                payload[index] = (payload[index].toInt() xor maskingKey[index % maskingKey.size].toInt()).toByte()
            }

            when (opcode) {
                OPCODE_BINARY -> {
                    if (fragmented) throw ProtocolException(1002, "New data frame during fragmentation")
                    messageSize = payloadLength
                    if (messageSize > MAX_PAYLOAD_BYTES) throw ProtocolException(1009, "Message is too large")
                    backendOutput.write(payload)
                    backendOutput.flush()
                    fragmented = !final
                }
                OPCODE_CONTINUATION -> {
                    if (!fragmented) throw ProtocolException(1002, "Unexpected continuation frame")
                    messageSize += payloadLength
                    if (messageSize > MAX_PAYLOAD_BYTES) throw ProtocolException(1009, "Message is too large")
                    backendOutput.write(payload)
                    backendOutput.flush()
                    if (final) {
                        fragmented = false
                        messageSize = 0
                    }
                }
                OPCODE_CLOSE -> {
                    when {
                        payloadLength == 1L -> throw ProtocolException(1002, "Invalid close frame length")
                        payloadLength >= 2L -> {
                            val code = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                            if (!isValidCloseCode(code)) {
                                throw ProtocolException(1002, "Invalid close code")
                            }
                            if (payloadLength > 2L) {
                                val reason = payload.copyOfRange(2, payload.size)
                                if (!isValidUtf8(reason)) {
                                    throw ProtocolException(1007, "Invalid close reason UTF-8")
                                }
                            }
                        }
                    }
                    synchronized(outputLock) {
                        writeFrame(clientOutput, OPCODE_CLOSE, payload)
                    }
                    return
                }
                OPCODE_PING -> synchronized(outputLock) {
                    writeFrame(clientOutput, OPCODE_PONG, payload)
                }
                OPCODE_PONG -> Unit
                OPCODE_TEXT -> throw ProtocolException(1003, "Text frames are unsupported")
                else -> throw ProtocolException(1002, "Unsupported WebSocket opcode")
            }
        }
    }

    private fun copyBackendToWebSocket(
        backend: Socket,
        clientOutput: OutputStream,
        outputLock: Any,
        client: Socket
    ) {
        try {
            val buffer = ByteArray(BACKEND_BUFFER_SIZE)
            val input = backend.getInputStream()
            while (running) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                synchronized(outputLock) {
                    writeFrame(clientOutput, OPCODE_BINARY, buffer.copyOf(count))
                }
            }
        } catch (error: IOException) {
            if (running) diagnostic("Backend connection failed: ${error.message}")
        } finally {
            closeQuietly(client)
        }
    }

    private fun readHandshake(client: Socket, input: InputStream): Handshake {
        val bytes = ArrayList<Byte>(512)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HANDSHAKE_TIMEOUT_MS.toLong())
        var matched = 0
        while (bytes.size < MAX_HEADER_BYTES) {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) throw HandshakeException("WebSocket handshake timed out")
            client.soTimeout = TimeUnit.NANOSECONDS.toMillis(remainingNanos)
                .coerceIn(1L, Int.MAX_VALUE.toLong())
                .toInt()
            val value = input.read()
            if (value < 0) throw HandshakeException("Unexpected end of HTTP headers")
            bytes.add(value.toByte())
            matched = when {
                value == HEADER_END[matched].code -> matched + 1
                value == HEADER_END[0].code -> 1
                else -> 0
            }
            if (matched == HEADER_END.length) break
        }
        if (matched != HEADER_END.length) throw HandshakeException("HTTP headers are too large")

        val text = String(bytes.toByteArray(), Charsets.ISO_8859_1)
        val lines = text.removeSuffix("\r\n\r\n").split("\r\n")
        if (lines.isEmpty() || lines.first() != "GET /websockify HTTP/1.1") {
            throw HandshakeException("Only GET /websockify HTTP/1.1 is allowed")
        }

        val headers = linkedMapOf<String, MutableList<String>>()
        lines.drop(1).forEach { line ->
            val separator = line.indexOf(':')
            if (separator <= 0 || line.substring(0, separator).any { !it.isLetterOrDigit() && it != '-' }) {
                throw HandshakeException("Malformed HTTP header")
            }
            val name = line.substring(0, separator).lowercase()
            val value = line.substring(separator + 1).trim()
            headers.getOrPut(name) { mutableListOf() }.add(value)
        }
        return Handshake(headers)
    }

    private fun validateHandshake(handshake: Handshake): String? {
        fun exactlyOne(name: String): String = handshake.headers[name]?.singleOrNull()
            ?: throw HandshakeException("Missing or repeated $name header")

        val hostValues = handshake.headers["host"]
            ?: throw HandshakeException("Missing Host header")
        if (hostValues.size != 1 || hostValues[0].isBlank()) {
            throw HandshakeException("Invalid Host header")
        }

        if (!exactlyOne("upgrade").equals("websocket", ignoreCase = true)) {
            throw HandshakeException("Upgrade must be websocket")
        }
        val connectionValues = handshake.headers["connection"]
            ?: throw HandshakeException("Missing Connection header")
        val connectionTokens = connectionValues.flatMap { it.split(',') }.map { it.trim() }
        if (connectionTokens.none { it.equals("upgrade", ignoreCase = true) }) {
            throw HandshakeException("Connection must include Upgrade")
        }
        if (exactlyOne("sec-websocket-version") != "13") {
            throw HandshakeException("Unsupported WebSocket version")
        }
        val key = exactlyOne("sec-websocket-key")
        val decodedKey = try {
            Base64.getDecoder().decode(key)
        } catch (_: IllegalArgumentException) {
            throw HandshakeException("Invalid Sec-WebSocket-Key")
        }
        if (decodedKey.size != 16 || Base64.getEncoder().encodeToString(decodedKey) != key) {
            throw HandshakeException("Invalid Sec-WebSocket-Key")
        }
        handshake.key = key

        return handshake.headers["sec-websocket-protocol"]
            ?.flatMap { it.split(',') }
            ?.map { it.trim() }
            ?.firstOrNull { it == "binary" }
    }

    private fun writeHandshake(output: OutputStream, key: String, subprotocol: String?) {
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).toByteArray(Charsets.ISO_8859_1))
        )
        val response = buildString {
            append("HTTP/1.1 101 Switching Protocols\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Accept: ").append(accept).append("\r\n")
            if (subprotocol != null) append("Sec-WebSocket-Protocol: binary\r\n")
            append("\r\n")
        }
        output.write(response.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun sendHttpError(client: Socket, message: String) {
        try {
            val body = "$message\n"
            val response = "HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: ${body.length}\r\n\r\n$body"
            client.getOutputStream().write(response.toByteArray(Charsets.ISO_8859_1))
            client.getOutputStream().flush()
        } catch (_: IOException) {
        }
    }

    private fun sendServiceUnavailable(client: Socket) {
        try {
            val body = "Service Unavailable\n"
            val response = "HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\nContent-Length: ${body.length}\r\n\r\n$body"
            client.getOutputStream().write(response.toByteArray(Charsets.ISO_8859_1))
            client.getOutputStream().flush()
        } catch (_: IOException) {
        }
    }

    private fun sendClose(client: Socket, code: Int, reason: String) {
        try {
            val encodedReason = reason.toByteArray(Charsets.UTF_8)
            val reasonBytes = if (encodedReason.size > MAX_CONTROL_PAYLOAD_BYTES - 2) {
                encodedReason.copyOf(MAX_CONTROL_PAYLOAD_BYTES - 2)
            } else {
                encodedReason
            }
            val payload = ByteArray(reasonBytes.size + 2)
            payload[0] = (code ushr 8).toByte()
            payload[1] = code.toByte()
            reasonBytes.copyInto(payload, 2)
            synchronized(outputLocks[client] ?: client) {
                writeFrame(client.getOutputStream(), OPCODE_CLOSE, payload)
            }
        } catch (_: IOException) {
        }
    }

    private fun writeFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
        output.write(FIN_BIT or opcode)
        when {
            payload.size <= 125 -> output.write(payload.size)
            payload.size <= 0xffff -> {
                output.write(126)
                output.write(payload.size ushr 8)
                output.write(payload.size)
            }
            else -> {
                output.write(127)
                val length = payload.size.toLong()
                for (shift in 56 downTo 0 step 8) output.write((length ushr shift).toInt())
            }
        }
        output.write(payload)
        output.flush()
    }

    private fun readPayloadLength(firstLength: Int, input: InputStream): Long = when (firstLength) {
        in 0..125 -> firstLength.toLong()
        126 -> ((readByte(input) shl 8) or readByte(input)).toLong()
        127 -> {
            var length = 0L
            repeat(8) { index ->
                val value = readByte(input)
                if (index == 0 && value and 0x80 != 0) throw ProtocolException(1002, "Invalid 64-bit frame length")
                length = (length shl 8) or value.toLong()
                if (length > MAX_PAYLOAD_BYTES) throw ProtocolException(1009, "Frame is too large")
            }
            length
        }
        else -> throw ProtocolException(1002, "Invalid frame length")
    }

    private fun readByte(input: InputStream): Int {
        val value = input.read()
        if (value < 0) throw EOFException()
        return value
    }

    private fun InputStream.readFully(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val count = read(bytes, offset, bytes.size - offset)
            if (count < 0) throw EOFException()
            offset += count
        }
    }

    private fun isValidCloseCode(code: Int): Boolean {
        return when (code) {
            1000, 1001, 1002, 1003, 1007, 1008, 1009, 1010, 1011 -> true
            in 3000..4999 -> true
            else -> false
        }
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: CharacterCodingException) {
            false
        }
    }

    private fun daemonThread(name: String, block: () -> Unit): Thread = Thread({ block() }, name).apply {
        isDaemon = true
    }

    private fun diagnostic(message: String) {
        try {
            diagnostics?.invoke(message)
        } catch (_: Exception) {
        }
    }

    private class Handshake(val headers: Map<String, List<String>>) {
        lateinit var key: String
    }

    private class HandshakeException(message: String) : IOException(message)

    private class ProtocolException(val closeCode: Int, message: String) : IOException(message)

    companion object {
        const val DEFAULT_PORT = 6080
        const val MAX_CLIENTS = 8
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val HANDSHAKE_TIMEOUT_MS = 5_000
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_PAYLOAD_BYTES = 1024L * 1024L
        private const val MAX_CONTROL_PAYLOAD_BYTES = 125
        private const val BACKEND_BUFFER_SIZE = 16 * 1024
        private const val FIN_BIT = 0x80
        private const val RSV_BITS = 0x70
        private const val OPCODE_MASK = 0x0f
        private const val MASK_BIT = 0x80
        private const val LENGTH_MASK = 0x7f
        private const val OPCODE_CONTINUATION = 0
        private const val OPCODE_TEXT = 1
        private const val OPCODE_BINARY = 2
        private const val OPCODE_CLOSE = 8
        private const val OPCODE_PING = 9
        private const val OPCODE_PONG = 10
        private const val HEADER_END = "\r\n\r\n"
        private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        private fun closeQuietly(closeable: Closeable?) {
            try {
                closeable?.close()
            } catch (_: IOException) {
            }
        }
    }
}
