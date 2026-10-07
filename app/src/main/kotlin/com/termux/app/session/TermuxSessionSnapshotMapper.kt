package com.termux.app.session

import com.termux.app.TermuxService
import com.termux.app.terminal.TermuxTerminalSessionActivityClient
import com.termux.shared.logger.Logger
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.shell.command.ExecutionCommand.Runner
import com.termux.shared.termux.shell.TermuxShellManager
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession

/**
 * Maps the [TermuxSession] instances managed by a [TermuxService] to [TermuxSessionSnapshot] records
 * that can be persisted with [TermuxSessionSnapshotCodec], and back.
 */
object TermuxSessionSnapshotMapper {

    private const val LOG_TAG = "TermuxSessionSnapshotMapper"

    /**
     * Snapshot the live sessions of a [TermuxService].
     *
     * Failsafe sessions are skipped since they only exist to recover from a broken shell startup and
     * restoring them would resurrect a session the user never asked for.
     *
     * @param sessions The [TermuxSession] list to snapshot.
     * @return Returns the [TermuxSessionSnapshot] list in the same order as [sessions].
     */
    fun from(sessions: List<TermuxSession>): List<TermuxSessionSnapshot> {
        val snapshots = mutableListOf<TermuxSessionSnapshot>()

        for (termuxSession in sessions) {
            val executionCommand = termuxSession.executionCommand

            if (executionCommand.isFailsafe) {
                Logger.logDebug(LOG_TAG, "Skipping failsafe \"${executionCommand.getCommandIdAndLabelLogString()}\" TermuxSession in session snapshot")
                continue
            }

            snapshots.add(
                toSnapshot(
                    executable = executionCommand.executable,
                    arguments = executionCommand.arguments,
                    workingDirectory = termuxSession.terminalSession.getCwd() ?: executionCommand.workingDirectory,
                    sessionName = termuxSession.terminalSession.mSessionName
                )
            )
        }

        return snapshots
    }

    /**
     * Derive the snapshot of a single session from the command it was started with and its current
     * working directory and name.
     *
     * This is separate from [from] and free of any Android and [TermuxSession] dependency so that the
     * argv[0] rule below is covered by unit tests: getting its offset wrong by one silently eats the
     * first argument of every restored session.
     *
     * TermuxSession.execute() overwrites arguments[0] with (login ? "-" : "") + basename(executable),
     * so argv[0] is derived from the executable and must not be stored: it would be passed as the first
     * argument on top of the derived argv[0], which the shell would parse as an option, like "--bash".
     * The "-" of the derived argv[0] is the login shell marker that TermuxSession.execute() sets while
     * resolving the default shell for a null executable, so it is kept as a flag and the executable is
     * restored as null to re-resolve the default login shell.
     */
    fun toSnapshot(executable: String?, arguments: Array<String>?, workingDirectory: String?, sessionName: String?): TermuxSessionSnapshot {
        val asDefaultLoginShell = arguments?.firstOrNull()?.startsWith("-") == true

        return TermuxSessionSnapshot(
            executable = if (asDefaultLoginShell) null else executable,
            arguments = arguments?.drop(1) ?: emptyList(),
            workingDirectory = workingDirectory,
            sessionName = sessionName,
            asDefaultLoginShell = asDefaultLoginShell
        )
    }

    /**
     * Re-create the sessions described by [snapshots] in [service] by going through
     * [TermuxService.createTermuxSession], the same creation path used by the activity and by plugin
     * commands, so that none of its setup is duplicated here.
     *
     * The commands passed are plain terminal session commands: the runner is
     * [Runner.TERMINAL_SESSION], they are no plugin execution commands and they carry no session
     * action, so restoring them from the background cannot launch
     * [com.termux.app.TermuxActivity] or switch the session of a running activity. Restoring stops
     * at [TermuxTerminalSessionActivityClient.MAX_SESSIONS] sessions, just like the user facing code
     * does.
     *
     * @param service The [TermuxService] to create the sessions in.
     * @param snapshots The [TermuxSessionSnapshot] list to re-create.
     * @return Returns the number of [TermuxSession] instances that were created.
     */
    fun applyTo(service: TermuxService, snapshots: List<TermuxSessionSnapshot>): Int {
        val maxSessions = TermuxTerminalSessionActivityClient.MAX_SESSIONS

        var createdSessions = 0
        for (snapshot in snapshots) {
            if (service.termuxSessionsSize >= maxSessions) {
                Logger.logWarn(LOG_TAG, "Stopping session restore since only $maxSessions sessions are supported")
                break
            }

            // The isPluginExecutionCommand and sessionAction fields are intentionally left at their
            // default false/null values, see above.
            val executionCommand = ExecutionCommand(
                TermuxShellManager.getNextShellId(),
                snapshot.executable,
                snapshot.arguments.toTypedArray(),
                null,
                snapshot.workingDirectory,
                Runner.TERMINAL_SESSION.getName(),
                false
            )
            executionCommand.shellName = snapshot.sessionName

            if (service.createTermuxSession(executionCommand) != null) createdSessions++
        }

        return createdSessions
    }
}
