package com.termux.app.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshAgentSupervisorTest {
    @Test
    fun `disabled agent stops only an app-owned agent`() {
        val runtime = FakeRuntime()
        val supervisor = SshAgentSupervisor(runtime)

        assertEquals(SshAgentStatus.DISABLED, supervisor.ensureRunning(false))
        assertTrue(runtime.stopped)
        assertFalse(runtime.started)
        assertFalse(runtime.staleSocketRemoved)
    }

    @Test
    fun `missing OpenSSH does not create or remove runtime files`() {
        val runtime = FakeRuntime(available = false)
        val supervisor = SshAgentSupervisor(runtime)

        assertEquals(SshAgentStatus.UNAVAILABLE, supervisor.ensureRunning(true))
        assertFalse(runtime.started)
        assertFalse(runtime.staleSocketRemoved)
    }

    @Test
    fun `live agent is reused without deleting its socket`() {
        val runtime = FakeRuntime(live = true)
        val supervisor = SshAgentSupervisor(runtime)

        assertEquals(SshAgentStatus.RUNNING, supervisor.ensureRunning(true))
        assertFalse(runtime.started)
        assertFalse(runtime.staleSocketRemoved)
    }

    @Test
    fun `stale socket is removed before starting the fixed agent`() {
        val runtime = FakeRuntime()
        val supervisor = SshAgentSupervisor(runtime)

        assertEquals(SshAgentStatus.RUNNING, supervisor.ensureRunning(true))
        assertTrue(runtime.staleSocketRemoved)
        assertTrue(runtime.started)
    }

    @Test
    fun `failed stale socket cleanup prevents an agent start`() {
        val runtime = FakeRuntime(removeStaleSocketResult = false)
        val supervisor = SshAgentSupervisor(runtime)

        assertEquals(SshAgentStatus.FAILED, supervisor.ensureRunning(true))
        assertFalse(runtime.started)
    }

    private class FakeRuntime(
        private val available: Boolean = true,
        private val live: Boolean = false,
        private val removeStaleSocketResult: Boolean = true
    ) : SshAgentRuntime {
        var staleSocketRemoved = false
        var started = false
        var stopped = false

        override fun isAvailable(): Boolean = available

        override fun isAgentLive(): Boolean = live

        override fun removeStaleSocket(): Boolean {
            staleSocketRemoved = true
            return removeStaleSocketResult
        }

        override fun startAgent(): Boolean {
            started = true
            return true
        }

        override fun stopOwnedAgent() {
            stopped = true
        }
    }
}
