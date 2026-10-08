package com.termux.app.reliability

/**
 * Determines whether the service can stop. Every argument is a veto; the service must never stop
 * itself while anything is still outstanding.
 */
object TermuxServiceStopPolicy {
    fun shouldStopService(
        wakeLockWanted: Boolean,
        wakeLockHeld: Boolean,
        hasSessions: Boolean,
        hasTasks: Boolean,
        hasPendingPluginCommands: Boolean,
        restorationPending: Boolean,
        tearingDown: Boolean
    ): Boolean = !tearingDown &&
        !restorationPending &&
        !wakeLockWanted &&
        !wakeLockHeld &&
        !hasSessions &&
        !hasTasks &&
        !hasPendingPluginCommands
}
