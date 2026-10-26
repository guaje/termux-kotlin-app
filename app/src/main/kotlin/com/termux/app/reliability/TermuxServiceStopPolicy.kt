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

    /**
     * Whether the service coming to an end counts as *the user closed Termux*, which is the whole
     * difference between a supervisor leaving it closed and a supervisor resurrecting it.
     *
     * Silence cannot carry that meaning. A supervisor sees only the absence of heartbeats, and an
     * absence looks identical whether the user typed `exit` or the ROM killed a shell, so the app has
     * to say which it was as it goes - and it can only say that while it is still alive to speak.
     *
     * The distinction lives in the exit status and nowhere else. A shell returning 0 is one somebody
     * left on purpose; a shell returning anything else was killed, crashed, or was never a shell. So
     * anything that is not an unambiguous 0 means "not a goodbye", which errs toward the rescue: a
     * wrong guess this way costs a notification the user can dismiss, while a wrong guess the other way
     * costs a dead agent harness nobody notices until morning.
     *
     * A null [lastSessionExitStatus] means no session ever ended, so the service stopped with nothing
     * ever having run in it. That is not somebody closing an app.
     */
    fun countsAsUserClosingTheApp(explicitExitRequested: Boolean, lastSessionExitStatus: Int?): Boolean =
        explicitExitRequested || lastSessionExitStatus == GRACEFUL_SHELL_EXIT_STATUS

    /** What `exit` leaves behind, and the only status treated as a deliberate goodbye. */
    const val GRACEFUL_SHELL_EXIT_STATUS: Int = 0
}
