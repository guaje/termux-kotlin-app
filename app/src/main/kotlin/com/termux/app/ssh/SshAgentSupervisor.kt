package com.termux.app.ssh

import android.system.Os
import com.termux.shared.file.FileUtils
import com.termux.shared.file.filesystem.FileType
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** State of the built-in OpenSSH agent for the current [com.termux.app.TermuxService] lifetime. */
enum class SshAgentStatus {
    DISABLED,
    UNAVAILABLE,
    RUNNING,
    FAILED
}

/** Small test seam around process and socket operations. */
internal interface SshAgentRuntime {
    fun isAvailable(): Boolean
    fun isAgentLive(): Boolean
    fun removeStaleSocket(): Boolean
    fun startAgent(): Boolean
    fun stopOwnedAgent()
}

/** Coordinates an app-wide agent without touching user shell configuration. */
internal class SshAgentSupervisor(private val runtime: SshAgentRuntime) {
    @Synchronized
    fun ensureRunning(enabled: Boolean): SshAgentStatus {
        if (!enabled) {
            runtime.stopOwnedAgent()
            return SshAgentStatus.DISABLED
        }
        if (!runtime.isAvailable()) return SshAgentStatus.UNAVAILABLE
        if (runtime.isAgentLive()) return SshAgentStatus.RUNNING

        if (!runtime.removeStaleSocket()) return SshAgentStatus.FAILED
        return if (runtime.startAgent()) SshAgentStatus.RUNNING else SshAgentStatus.FAILED
    }

    @Synchronized
    fun stop() {
        runtime.stopOwnedAgent()
    }
}

/**
 * Owns the fixed OpenSSH agent socket used by every Termux terminal session.
 *
 * OpenSSH itself keeps identities only in memory. This object intentionally starts an empty
 * agent and never invokes ssh-add, an askpass program, or any credential store.
 */
object TermuxSshAgent {
    private const val LOG_TAG = "TermuxSshAgent"

    private val supervisor = SshAgentSupervisor(ProcessSshAgentRuntime())
    private val executor = Executors.newSingleThreadExecutor()
    private val startQueued = AtomicBoolean(false)

    /** Queue lifecycle work off the foreground service main thread. */
    @JvmStatic
    fun ensureRunningAsync(enabled: Boolean) {
        if (!startQueued.compareAndSet(false, true)) return
        executor.execute {
            try {
                val status = supervisor.ensureRunning(enabled)
                Logger.logDebug(LOG_TAG, "ssh-agent status: $status")
            } finally {
                startQueued.set(false)
            }
        }
    }

    @JvmStatic
    fun stop() {
        // Share the serial executor with startup so a queued start cannot outlive a stop request.
        executor.execute { supervisor.stop() }
    }
}

private class ProcessSshAgentRuntime : SshAgentRuntime {
    private val prefixDir = TermuxConstants.TERMUX_PREFIX_DIR
    private val socketDir = TermuxConstants.TERMUX_RUN_PREFIX_DIR
    private val socketFile = TermuxConstants.TERMUX_SSH_AGENT_SOCKET
    private val sshAgentFile = File(TermuxConstants.TERMUX_BIN_PREFIX_DIR, "ssh-agent")
    private val sshAddFile = File(TermuxConstants.TERMUX_BIN_PREFIX_DIR, "ssh-add")

    private var ownedAgentProcess: Process? = null

    override fun isAvailable(): Boolean {
        return sshAgentFile.canExecute() && sshAddFile.canExecute()
    }

    private var staleSocketMayBeRemoved = false

    override fun isAgentLive(): Boolean {
        staleSocketMayBeRemoved = false
        if (isProcessRunning(ownedAgentProcess)) return true
        if (FileUtils.getFileType(socketFile.absolutePath, false) != FileType.SOCKET) {
            staleSocketMayBeRemoved = true
            return false
        }

        return try {
            val process = ProcessBuilder(sshAddFile.absolutePath, "-l")
                .directory(prefixDir)
                .redirectErrorStream(true)
                .apply { setupEnvironment(environment()) }
                .start()
            process.inputStream.bufferedReader().use { it.readText() }
            when (process.waitFor()) {
                0, 1 -> true
                2 -> {
                    staleSocketMayBeRemoved = true
                    false
                }
                else -> false
            }
        } catch (e: Exception) {
            Logger.logWarn(LOG_TAG, "Unable to probe ssh-agent socket: ${e.message}")
            false
        }
    }

    override fun removeStaleSocket(): Boolean {
        if (!staleSocketMayBeRemoved) return false
        staleSocketMayBeRemoved = false

        val fileType = FileUtils.getFileType(socketFile.absolutePath, false)
        if (fileType == FileType.NO_EXIST) return true
        if (fileType != FileType.SOCKET && fileType != FileType.REGULAR) {
            Logger.logError(LOG_TAG, "Refusing to remove unexpected ssh-agent path type: ${fileType.typeName}")
            return false
        }

        val error = FileUtils.deleteFile(
            "stale ssh-agent",
            socketFile.absolutePath,
            true,
            false,
            FileType.SOCKET.value + FileType.REGULAR.value
        )
        if (error != null) {
            Logger.logErrorExtended(LOG_TAG, error.toString())
            return false
        }
        return true
    }

    override fun startAgent(): Boolean {
        if (!ensureSocketDirectory()) return false
        if (isProcessRunning(ownedAgentProcess)) return true

        return try {
            val process = ProcessBuilder(
                sshAgentFile.absolutePath,
                "-D",
                "-a",
                socketFile.absolutePath
            )
                .directory(prefixDir)
                .redirectErrorStream(true)
                .apply { setupEnvironment(environment()) }
                .start()
            ownedAgentProcess = process
            drainOutput(process)
            waitForSocket(process)
        } catch (e: Exception) {
            Logger.logError(LOG_TAG, "Failed to start ssh-agent: ${e.message}")
            false
        }
    }

    override fun stopOwnedAgent() {
        ownedAgentProcess?.let { process ->
            try {
                process.destroy()
            } catch (e: Exception) {
                Logger.logWarn(LOG_TAG, "Failed to stop ssh-agent: ${e.message}")
            }
        }
        ownedAgentProcess = null
    }

    private fun ensureSocketDirectory(): Boolean {
        val error = FileUtils.createDirectoryFile(socketDir.absolutePath)
        if (error != null) {
            Logger.logErrorExtended(LOG_TAG, error.toString())
            return false
        }

        return try {
            // Socket permissions rely on this parent directory being private to the Termux UID.
            Os.chmod(socketDir.absolutePath, 448)
            true
        } catch (e: Exception) {
            Logger.logError(LOG_TAG, "Failed to secure ssh-agent directory: ${e.message}")
            false
        }
    }

    private fun setupEnvironment(environment: MutableMap<String, String>) {
        environment["HOME"] = TermuxConstants.TERMUX_HOME_DIR_PATH
        environment["PREFIX"] = TermuxConstants.TERMUX_PREFIX_DIR_PATH
        environment["PATH"] = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
        environment["LD_LIBRARY_PATH"] = TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH
        environment["TMPDIR"] = TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH
        environment["SSH_AUTH_SOCK"] = socketFile.absolutePath
    }

    private fun waitForSocket(process: Process): Boolean {
        // A cold agent may still need to load libcrypto before binding the socket.
        repeat(200) {
            if (isProcessRunning(process) && FileUtils.getFileType(socketFile.absolutePath, false) == FileType.SOCKET) {
                return true
            }
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        Logger.logError(LOG_TAG, "ssh-agent exited or did not create its socket")
        stopOwnedAgent()
        return false
    }

    private fun drainOutput(process: Process) {
        Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { Logger.logDebug(LOG_TAG, "ssh-agent: $it") }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun isProcessRunning(process: Process?): Boolean {
        if (process == null) return false
        return try {
            process.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    companion object {
        private const val LOG_TAG = "ProcessSshAgentRuntime"
    }
}
