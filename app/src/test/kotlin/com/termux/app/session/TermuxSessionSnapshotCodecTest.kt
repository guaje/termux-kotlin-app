package com.termux.app.session

import org.junit.Assert.assertEquals
import org.junit.Test

class TermuxSessionSnapshotCodecTest {
    @Test
    fun `plain snapshot round trips`() {
        val sessions = listOf(
            TermuxSessionSnapshot(
                executable = "/data/data/com.termux/files/usr/bin/bash",
                arguments = listOf("--login", "-c", "echo hello"),
                workingDirectory = "/data/data/com.termux/files/home",
                sessionName = "shell",
                asDefaultLoginShell = false
            )
        )

        assertEquals(
            TermuxSessionSnapshotCodec.DecodeResult.Success(sessions),
            TermuxSessionSnapshotCodec.decode(TermuxSessionSnapshotCodec.encode(sessions))
        )
    }

    @Test
    fun `pathological values and empty values round trip`() {
        val sessions = listOf(
            TermuxSessionSnapshot(
                executable = "/path/with\u001Fseparator",
                arguments = listOf(
                    "tab\tvalue",
                    "record\u001Eseparated",
                    "line\nfeed",
                    "back\\slash",
                    "100%",
                    "中文 😀",
                    ""
                ),
                workingDirectory = "/work\rdir",
                sessionName = "name\u001Fwith\u001Econtrols",
                asDefaultLoginShell = true
            ),
            TermuxSessionSnapshot(
                executable = "",
                arguments = emptyList(),
                workingDirectory = "",
                sessionName = "",
                asDefaultLoginShell = false
            ),
            TermuxSessionSnapshot(
                executable = null,
                arguments = emptyList(),
                workingDirectory = null,
                sessionName = null,
                asDefaultLoginShell = true
            )
        )

        assertEquals(
            TermuxSessionSnapshotCodec.DecodeResult.Success(sessions),
            TermuxSessionSnapshotCodec.decode(TermuxSessionSnapshotCodec.encode(sessions))
        )
    }

    @Test
    fun `corrupted record fails instead of restoring partially`() {
        val data = "${TermuxSessionSnapshotCodec.HEADER}\nonly-one-field"

        assertEquals(
            TermuxSessionSnapshotCodec.DecodeResult.Failed("malformed"),
            TermuxSessionSnapshotCodec.decode(data)
        )
    }

    @Test
    fun `unsupported header reports bad header`() {
        assertEquals(
            TermuxSessionSnapshotCodec.DecodeResult.Failed("bad_header"),
            TermuxSessionSnapshotCodec.decode("termux-sessions/v2")
        )
    }

    @Test
    fun `null and empty snapshots report empty`() {
        val expected = TermuxSessionSnapshotCodec.DecodeResult.Failed("empty")

        assertEquals(expected, TermuxSessionSnapshotCodec.decode(null))
        assertEquals(expected, TermuxSessionSnapshotCodec.decode(""))
    }

    @Test
    fun `encoding nine sessions keeps exactly the last eight`() {
        val sessions = (1..9).map { index ->
            TermuxSessionSnapshot(
                executable = "shell-$index",
                arguments = listOf(index.toString()),
                workingDirectory = null,
                sessionName = "session-$index",
                asDefaultLoginShell = false
            )
        }

        assertEquals(
            TermuxSessionSnapshotCodec.DecodeResult.Success(sessions.takeLast(8)),
            TermuxSessionSnapshotCodec.decode(TermuxSessionSnapshotCodec.encode(sessions))
        )
    }

    @Test
    fun `unknown escape reports malformed`() {
        val separator = '\u001F'
        val data = TermuxSessionSnapshotCodec.HEADER + "\nv\\q${separator}n${separator}n${separator}n${separator}n"

        assertEquals(
            TermuxSessionSnapshotCodec.DecodeResult.Failed("malformed"),
            TermuxSessionSnapshotCodec.decode(data)
        )
    }
}
