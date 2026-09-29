package com.termux.app.ssh

import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshAgentEnvironmentTest {
    @Test
    fun `agent socket is exported only for enabled installs with OpenSSH`() {
        assertTrue(TermuxShellEnvironment.shouldExportSshAuthSock(false, true))
        assertFalse(TermuxShellEnvironment.shouldExportSshAuthSock(true, true))
        assertFalse(TermuxShellEnvironment.shouldExportSshAuthSock(false, false))
    }
}
