package com.termux.app.x11.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LoopbackWebSocketProxyTest {
    @Test
    fun validHandshakeReturnsExpectedAccept() {
        val proxy = LoopbackWebSocketProxy(unusedPort(), 0)
        try {
            proxy.start()
            Socket(loopback, proxy.boundPort).use { client ->
                client.soTimeout = SOCKET_TIMEOUT_MS
                client.getOutputStream().write(handshakeRequest().toByteArray())
                val response = readHttpResponse(client)
                assertTrue(response.startsWith("HTTP/1.1 101 Switching Protocols"))
                assertTrue(response.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo="))
                assertFalse(response.contains("Sec-WebSocket-Protocol:"))
            }
        } finally {
            proxy.close()
        }
    }

    @Test
    fun invalidPathVersionAndHeadersAreRejected() {
        val proxy = LoopbackWebSocketProxy(unusedPort(), 0)
        try {
            proxy.start()
            listOf(
                handshakeRequest().replace("/websockify", "/other"),
                handshakeRequest().replace("Sec-WebSocket-Version: 13", "Sec-WebSocket-Version: 12"),
                handshakeRequest().replace("Connection: Upgrade", "Connection: keep-alive")
            ).forEach { request ->
                Socket(loopback, proxy.boundPort).use { client ->
                    client.soTimeout = SOCKET_TIMEOUT_MS
                    client.getOutputStream().write(request.toByteArray())
                    assertTrue(readHttpResponse(client).startsWith("HTTP/1.1 400 Bad Request"))
                }
            }
        } finally {
            proxy.close()
        }
    }

    @Test
    fun missingHostHeaderIsRejected() {
        val proxy = LoopbackWebSocketProxy(unusedPort(), 0)
        try {
            proxy.start()
            val request = handshakeRequest().lines()
                .filter { !it.startsWith("Host:", ignoreCase = true) }
                .joinToString("\r\n") + "\r\n"
            Socket(loopback, proxy.boundPort).use { client ->
                client.soTimeout = SOCKET_TIMEOUT_MS
                client.getOutputStream().write(request.toByteArray())
                val response = readHttpResponse(client)
                assertTrue(response.startsWith("HTTP/1.1 400 Bad Request"))
            }
        } finally {
            proxy.close()
        }
    }

    @Test
    fun blankHostHeaderIsRejected() {
        val proxy = LoopbackWebSocketProxy(unusedPort(), 0)
        try {
            proxy.start()
            val request = handshakeRequest().replace("Host: localhost", "Host:   ")
            Socket(loopback, proxy.boundPort).use { client ->
                client.soTimeout = SOCKET_TIMEOUT_MS
                client.getOutputStream().write(request.toByteArray())
                val response = readHttpResponse(client)
                assertTrue(response.startsWith("HTTP/1.1 400 Bad Request"))
            }
        } finally {
            proxy.close()
        }
    }

    @Test
    fun repeatedConnectionHeaderWithUpgradeTokenIsAccepted() {
        val proxy = LoopbackWebSocketProxy(unusedPort(), 0)
        try {
            proxy.start()
            val base = handshakeRequest().lines()
            val request = buildString {
                base.forEach { line ->
                    when {
                        line.startsWith("Connection:", ignoreCase = true) -> {
                            append("Connection: keep-alive\r\n")
                            append("Connection: Upgrade\r\n")
                        }
                        else -> append(line).append("\r\n")
                    }
                }
            }
            Socket(loopback, proxy.boundPort).use { client ->
                client.soTimeout = SOCKET_TIMEOUT_MS
                client.getOutputStream().write(request.toByteArray())
                val response = readHttpResponse(client)
                assertTrue(response.startsWith("HTTP/1.1 101 Switching Protocols"))
            }
        } finally {
            proxy.close()
        }
    }

    @Test
    fun repeatedConnectionHeaderWithoutUpgradeTokenIsRejected() {
        val proxy = LoopbackWebSocketProxy(unusedPort(), 0)
        try {
            proxy.start()
            val base = handshakeRequest().lines()
            val request = buildString {
                base.forEach { line ->
                    when {
                        line.startsWith("Connection:", ignoreCase = true) -> {
                            append("Connection: keep-alive\r\n")
                            append("Connection: close\r\n")
                        }
                        else -> append(line).append("\r\n")
                    }
                }
            }
            Socket(loopback, proxy.boundPort).use { client ->
                client.soTimeout = SOCKET_TIMEOUT_MS
                client.getOutputStream().write(request.toByteArray())
                val response = readHttpResponse(client)
                assertTrue(response.startsWith("HTTP/1.1 400 Bad Request"))
            }
        } finally {
            proxy.close()
        }
    }

    @Test
    fun maskedBinaryFramesRoundTripWithBackendAndPingIsNotForwarded() {
        val received = ByteArrayOutputStream()
        val backendReady = CountDownLatch(1)
        val backendDone = CountDownLatch(1)
        val backend = ServerSocket(0, 1, loopback)
        val backendThread = Thread {
            backend.use { listener ->
                listener.accept().use { socket ->
                    socket.soTimeout = SOCKET_TIMEOUT_MS
                    backendReady.countDown()
                    received.write(readExactly(socket, 3))
                    socket.getOutputStream().write(byteArrayOf(9, 8, 7))
                    socket.getOutputStream().flush()
                    backendDone.countDown()
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        val proxy = LoopbackWebSocketProxy(backend.localPort, 0)
        try {
            proxy.start()
            Socket(loopback, proxy.boundPort).use { client ->
                client.soTimeout = SOCKET_TIMEOUT_MS
                client.getOutputStream().write(handshakeRequest("binary").toByteArray())
                val response = readHttpResponse(client)
                assertTrue(response.contains("Sec-WebSocket-Protocol: binary"))
                assertTrue(backendReady.await(SOCKET_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS))

                writeMaskedFrame(client, 9, byteArrayOf(1, 2))
                val pong = readFrame(client)
                assertEquals(10, pong.opcode)
                assertArrayEquals(byteArrayOf(1, 2), pong.payload)

                writeMaskedFrame(client, 2, byteArrayOf(4, 5, 6))
                val reply = readFrame(client)
                assertEquals(2, reply.opcode)
                assertArrayEquals(byteArrayOf(9, 8, 7), reply.payload)
                assertTrue(backendDone.await(SOCKET_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS))
                assertArrayEquals(byteArrayOf(4, 5, 6), received.toByteArray())
            }
        } finally {
            proxy.close()
            backend.close()
            backendThread.join(SOCKET_TIMEOUT_MS.toLong())
        }
    }

    @Test
    fun unmaskedAndOversizedFramesAreClosed() {
        val backend = HoldingBackend()
        val proxy = LoopbackWebSocketProxy(backend.port, 0)
        try {
            proxy.start()
            listOf<(Socket) -> Unit>(
                { client -> client.getOutputStream().write(byteArrayOf(0x82.toByte(), 0x00)) },
                { client ->
                    client.getOutputStream().write(
                        byteArrayOf(0x82.toByte(), 0xff.toByte(), 0, 0, 0, 0, 0, 0x10, 0, 1)
                    )
                }
            ).forEach { writeInvalidFrame ->
                Socket(loopback, proxy.boundPort).use { client ->
                    client.soTimeout = SOCKET_TIMEOUT_MS
                    client.getOutputStream().write(handshakeRequest().toByteArray())
                    readHttpResponse(client)
                    assertTrue(backend.accepted.await(SOCKET_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS))
                    writeInvalidFrame(client)
                    assertEquals(8, readFrame(client).opcode)
                }
            }
        } finally {
            proxy.close()
            backend.close()
        }
    }

    @Test
    fun closeFrameWithPayloadLengthOneIsRejectedWith1002() {
        val backend = HoldingBackend()
        val proxy = LoopbackWebSocketProxy(backend.port, 0)
        try {
            proxy.start()
            Socket(loopback, proxy.boundPort).use { client ->
                client.soTimeout = SOCKET_TIMEOUT_MS
                client.getOutputStream().write(handshakeRequest().toByteArray())
                readHttpResponse(client)
                assertTrue(backend.accepted.await(SOCKET_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS))
                // Masked close frame with payload length 1
                val mask = byteArrayOf(1, 2, 3, 4)
                val output = client.getOutputStream()
                output.write(0x88) // FIN + CLOSE
                output.write(0x81) // MASK + length 1
                output.write(mask)
                output.write(byteArrayOf((0x00.toInt() xor mask[0].toInt()).toByte()))
                output.flush()
                val frame = readFrame(client)
                assertEquals(8, frame.opcode)
                assertEquals(2, frame.payload.size)
                assertEquals(0x03, frame.payload[0].toInt() and 0xFF)
                assertEquals(0xEA, frame.payload[1].toInt() and 0xFF)
            }
        } finally {
            proxy.close()
            backend.close()
        }
    }

    @Test
    fun closeFrameWithInvalidCodeIsRejectedWith1002() {
        val backend = HoldingBackend()
        val proxy = LoopbackWebSocketProxy(backend.port, 0)
        try {
            proxy.start()
            Socket(loopback, proxy.boundPort).use { client ->
                client.soTimeout = SOCKET_TIMEOUT_MS
                client.getOutputStream().write(handshakeRequest().toByteArray())
                readHttpResponse(client)
                assertTrue(backend.accepted.await(SOCKET_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS))
                // Close code 1005 is reserved/invalid
                writeMaskedFrame(client, 8, byteArrayOf(0x03, 0xED.toByte()))
                val frame = readFrame(client)
                assertEquals(8, frame.opcode)
                assertEquals(2, frame.payload.size)
                assertEquals(0x03, frame.payload[0].toInt() and 0xFF)
                assertEquals(0xEA, frame.payload[1].toInt() and 0xFF)
            }
        } finally {
            proxy.close()
            backend.close()
        }
    }

    @Test
    fun closeFrameWithInvalidUtf8ReasonIsRejectedWith1007() {
        val backend = HoldingBackend()
        val proxy = LoopbackWebSocketProxy(backend.port, 0)
        try {
            proxy.start()
            Socket(loopback, proxy.boundPort).use { client ->
                client.soTimeout = SOCKET_TIMEOUT_MS
                client.getOutputStream().write(handshakeRequest().toByteArray())
                readHttpResponse(client)
                assertTrue(backend.accepted.await(SOCKET_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS))
                // Valid close code 1000 followed by invalid UTF-8 (0xC0, 0x80 is overlong encoding)
                val payload = byteArrayOf(0x03, 0xE8.toByte(), 0xC0.toByte(), 0x80.toByte())
                writeMaskedFrame(client, 8, payload)
                val frame = readFrame(client)
                assertEquals(8, frame.opcode)
                assertEquals(2, frame.payload.size)
                assertEquals(0x03, frame.payload[0].toInt() and 0xFF)
                assertEquals(0xEF, frame.payload[1].toInt() and 0xFF)
            }
        } finally {
            proxy.close()
            backend.close()
        }
    }

    @Test
    fun closeFrameWithValidCodeAndUtf8IsEchoed() {
        val backend = HoldingBackend()
        val proxy = LoopbackWebSocketProxy(backend.port, 0)
        try {
            proxy.start()
            Socket(loopback, proxy.boundPort).use { client ->
                client.soTimeout = SOCKET_TIMEOUT_MS
                client.getOutputStream().write(handshakeRequest().toByteArray())
                readHttpResponse(client)
                assertTrue(backend.accepted.await(SOCKET_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS))
                // Valid close code 1000 with UTF-8 reason "done"
                val payload = byteArrayOf(0x03, 0xE8.toByte(), 'd'.code.toByte(), 'o'.code.toByte(), 'n'.code.toByte(), 'e'.code.toByte())
                writeMaskedFrame(client, 8, payload)
                val frame = readFrame(client)
                assertEquals(8, frame.opcode)
                assertArrayEquals(payload, frame.payload)
            }
        } finally {
            proxy.close()
            backend.close()
        }
    }

    @Test
    fun closeFrameWith3000CodeIsAccepted() {
        val backend = HoldingBackend()
        val proxy = LoopbackWebSocketProxy(backend.port, 0)
        try {
            proxy.start()
            Socket(loopback, proxy.boundPort).use { client ->
                client.soTimeout = SOCKET_TIMEOUT_MS
                client.getOutputStream().write(handshakeRequest().toByteArray())
                readHttpResponse(client)
                assertTrue(backend.accepted.await(SOCKET_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS))
                // Valid application-defined close code 3000
                val payload = byteArrayOf(0x0B, 0xB8.toByte())
                writeMaskedFrame(client, 8, payload)
                val frame = readFrame(client)
                assertEquals(8, frame.opcode)
                assertArrayEquals(payload, frame.payload)
            }
        } finally {
            proxy.close()
            backend.close()
        }
    }

    @Test
    fun stopClosesStalledHandshake() {
        val proxy = LoopbackWebSocketProxy(unusedPort(), 0)
        proxy.start()
        val client = Socket()
        try {
            client.connect(InetSocketAddress(loopback, proxy.boundPort), SOCKET_TIMEOUT_MS)
            // Intentionally send nothing so the handshake stalls on the server
            Thread.sleep(100)
            val before = System.currentTimeMillis()
            proxy.stop()
            val elapsed = System.currentTimeMillis() - before
            assertTrue("stop() should close stalled handshake promptly, took ${elapsed}ms", elapsed < 1_000)
        } finally {
            proxy.close()
            closeQuietly(client)
        }
    }

    @Test
    fun stopReleasesListener() {
        val proxy = LoopbackWebSocketProxy(unusedPort(), 0)
        proxy.start()
        val port = proxy.boundPort
        proxy.stop()
        ServerSocket().use { replacement ->
            replacement.bind(InetSocketAddress(loopback, port))
            assertEquals(port, replacement.localPort)
        }
    }

    @Test
    fun excessClientsReceive503() {
        val backend = HoldingBackend()
        val proxy = LoopbackWebSocketProxy(backend.port, 0)
        try {
            proxy.start()
            val clients = mutableListOf<Socket>()
            // Fill all permits and hold them by stalling the handshake on the backend
            repeat(LoopbackWebSocketProxy.MAX_CLIENTS) {
                val client = Socket()
                client.connect(InetSocketAddress(loopback, proxy.boundPort), SOCKET_TIMEOUT_MS)
                clients.add(client)
            }
            // Wait for all clients to be accepted
            Thread.sleep(300)
            // Now try one more client
            Socket(loopback, proxy.boundPort).use { extraClient ->
                extraClient.soTimeout = SOCKET_TIMEOUT_MS
                extraClient.getOutputStream().write(handshakeRequest().toByteArray())
                val response = readHttpResponse(extraClient)
                assertTrue(response.startsWith("HTTP/1.1 503 Service Unavailable"))
            }
            clients.forEach { closeQuietly(it) }
        } finally {
            proxy.close()
            backend.close()
        }
    }

    private class HoldingBackend : AutoCloseable {
        private val listener = ServerSocket(0, 4, loopback)
        val port: Int = listener.localPort
        val accepted = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val thread = Thread {
            try {
                while (!listener.isClosed) {
                    val socket = listener.accept()
                    accepted.countDown()
                    Thread { socket.use { release.await() } }.apply {
                        isDaemon = true
                        start()
                    }
                }
            } catch (_: Exception) {
            }
        }.apply {
            isDaemon = true
            start()
        }

        override fun close() {
            release.countDown()
            listener.close()
            thread.join(SOCKET_TIMEOUT_MS.toLong())
        }
    }

    private data class Frame(val opcode: Int, val payload: ByteArray)

    private fun handshakeRequest(protocol: String? = null): String = buildString {
        append("GET /websockify HTTP/1.1\r\n")
        append("Host: localhost\r\n")
        append("Upgrade: websocket\r\n")
        append("Connection: Upgrade\r\n")
        append("Sec-WebSocket-Version: 13\r\n")
        append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
        if (protocol != null) append("Sec-WebSocket-Protocol: $protocol\r\n")
        append("\r\n")
    }

    private fun readHttpResponse(socket: Socket): String {
        val bytes = ByteArrayOutputStream()
        var ending = 0
        while (ending < 4) {
            val value = socket.getInputStream().read()
            check(value >= 0)
            bytes.write(value)
            ending = when {
                value == "\r\n\r\n"[ending].code -> ending + 1
                value == '\r'.code -> 1
                else -> 0
            }
        }
        return bytes.toString(Charsets.ISO_8859_1.name())
    }

    private fun writeMaskedFrame(socket: Socket, opcode: Int, payload: ByteArray) {
        require(payload.size <= 125)
        val mask = byteArrayOf(1, 2, 3, 4)
        val output = socket.getOutputStream()
        output.write(0x80 or opcode)
        output.write(0x80 or payload.size)
        output.write(mask)
        output.write(payload.mapIndexed { index, byte -> (byte.toInt() xor mask[index % mask.size].toInt()).toByte() }.toByteArray())
        output.flush()
    }

    private fun readFrame(socket: Socket): Frame {
        val input = socket.getInputStream()
        val first = input.read()
        val second = input.read()
        check(first >= 0 && second >= 0)
        assertEquals(0, second and 0x80)
        val length = second and 0x7f
        check(length <= 125)
        return Frame(first and 0x0f, readExactly(socket, length))
    }

    private fun readExactly(socket: Socket, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = socket.getInputStream().read(bytes, offset, length - offset)
            check(count >= 0)
            offset += count
        }
        return bytes
    }

    private fun unusedPort(): Int = ServerSocket(0, 1, loopback).use { it.localPort }

    private fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val SOCKET_TIMEOUT_MS = 2_000
        private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    }
}
