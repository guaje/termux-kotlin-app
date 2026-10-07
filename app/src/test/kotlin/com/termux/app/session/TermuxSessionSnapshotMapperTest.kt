package com.termux.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the argv[0] rule of [TermuxSessionSnapshotMapper.toSnapshot].
 *
 * TermuxSession.execute() replaces arguments[0] with (login ? "-" : "") + basename(executable) before
 * the process is started, so that entry is derived state and must never be restored as an argument.
 */
class TermuxSessionSnapshotMapperTest {

    @Test
    fun `default login shell is stored as a flag instead of as an argument`() {
        val snapshot = TermuxSessionSnapshotMapper.toSnapshot(
            executable = "/data/data/com.termux/files/usr/bin/bash",
            arguments = arrayOf("-bash"),
            workingDirectory = "/data/data/com.termux/files/home",
            sessionName = "Shell"
        )

        assertEquals(true, snapshot.asDefaultLoginShell)
        assertNull(snapshot.executable)
        assertEquals(emptyList<String>(), snapshot.arguments)
        assertEquals("/data/data/com.termux/files/home", snapshot.workingDirectory)
        assertEquals("Shell", snapshot.sessionName)
    }

    @Test
    fun `derived argv zero of a non login shell is dropped`() {
        val snapshot = TermuxSessionSnapshotMapper.toSnapshot(
            executable = "/data/data/com.termux/files/usr/bin/bash",
            arguments = arrayOf("bash", "--login", "-c", "echo hello"),
            workingDirectory = null,
            sessionName = null
        )

        assertEquals(false, snapshot.asDefaultLoginShell)
        assertEquals("/data/data/com.termux/files/usr/bin/bash", snapshot.executable)
        assertEquals(listOf("--login", "-c", "echo hello"), snapshot.arguments)
    }

    @Test
    fun `session without arguments keeps its executable and gets no arguments`() {
        val snapshot = TermuxSessionSnapshotMapper.toSnapshot(
            executable = "/data/data/com.termux/files/usr/bin/zsh",
            arguments = arrayOf("zsh"),
            workingDirectory = "/tmp",
            sessionName = "zsh"
        )

        assertEquals("/data/data/com.termux/files/usr/bin/zsh", snapshot.executable)
        assertEquals(emptyList<String>(), snapshot.arguments)
        assertEquals(false, snapshot.asDefaultLoginShell)
    }

    @Test
    fun `session without any arguments at all does not fail`() {
        val snapshot = TermuxSessionSnapshotMapper.toSnapshot(
            executable = "/data/data/com.termux/files/usr/bin/zsh",
            arguments = null,
            workingDirectory = null,
            sessionName = null
        )

        assertEquals("/data/data/com.termux/files/usr/bin/zsh", snapshot.executable)
        assertEquals(emptyList<String>(), snapshot.arguments)
        assertEquals(false, snapshot.asDefaultLoginShell)
        assertNull(snapshot.workingDirectory)
        assertNull(snapshot.sessionName)
    }

    @Test
    fun `options of the program itself are kept`() {
        val snapshot = TermuxSessionSnapshotMapper.toSnapshot(
            executable = "/data/data/com.termux/files/usr/bin/vim",
            arguments = arrayOf("vim", "-R", "notes.md"),
            workingDirectory = "/data/data/com.termux/files/home",
            sessionName = "vim"
        )

        assertEquals(listOf("-R", "notes.md"), snapshot.arguments)
        assertEquals(false, snapshot.asDefaultLoginShell)
    }
}
