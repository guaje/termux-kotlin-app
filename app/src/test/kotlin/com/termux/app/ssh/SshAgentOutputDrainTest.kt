package com.termux.app.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream

/**
 * Regression coverage for the teardown crash: stopping the app-owned agent calls
 * `Process.destroy()`, which closes the descriptor under the blocked reader thread. That used to
 * surface as `java.io.InterruptedIOException: read interrupted by close() on another thread` on an
 * uncaught daemon thread, which killed the whole app.
 */
class SshAgentOutputDrainTest {
    @Test
    fun `delivers every line while the stream stays open`() {
        val lines = mutableListOf<String>()

        val endedCleanly = drainProcessOutput("first\nsecond\n".byteInputStream()) { lines.add(it) }

        assertTrue(endedCleanly)
        assertEquals(listOf("first", "second"), lines)
    }

    @Test
    fun `swallows the io error raised when the process is destroyed mid read`() {
        val lines = mutableListOf<String>()

        val endedCleanly = drainProcessOutput(StreamThatBreaksAfterOneLine()) { lines.add(it) }

        assertFalse(endedCleanly)
        assertEquals(listOf("started"), lines)
    }

    private class StreamThatBreaksAfterOneLine : InputStream() {
        private val prefix = "started\n".toByteArray()
        private var index = 0

        override fun read(): Int {
            if (index < prefix.size) return prefix[index++].toInt() and 0xff
            throw IOException("read interrupted by close() on another thread")
        }
    }
}
