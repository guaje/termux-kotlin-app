package com.termux.app.reliability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxServiceStopPolicyTest {
    @Test
    fun `all false allows the service to stop`() {
        assertTrue(shouldStop())
    }

    @Test
    fun `wake lock wanted but not held blocks the stop`() {
        assertFalse(shouldStop(wakeLockWanted = true))
    }

    @Test
    fun `held wake lock blocks the stop`() {
        assertFalse(shouldStop(wakeLockHeld = true))
    }

    @Test
    fun `active session blocks the stop`() {
        assertFalse(shouldStop(hasSessions = true))
    }

    @Test
    fun `active task blocks the stop`() {
        assertFalse(shouldStop(hasTasks = true))
    }

    @Test
    fun `pending plugin command blocks the stop`() {
        assertFalse(shouldStop(hasPendingPluginCommands = true))
    }

    @Test
    fun `pending restoration blocks the stop`() {
        assertFalse(shouldStop(restorationPending = true))
    }

    @Test
    fun `teardown in progress blocks the stop`() {
        assertFalse(shouldStop(tearingDown = true))
    }

    private fun shouldStop(
        wakeLockWanted: Boolean = false,
        wakeLockHeld: Boolean = false,
        hasSessions: Boolean = false,
        hasTasks: Boolean = false,
        hasPendingPluginCommands: Boolean = false,
        restorationPending: Boolean = false,
        tearingDown: Boolean = false
    ): Boolean = TermuxServiceStopPolicy.shouldStopService(
        wakeLockWanted,
        wakeLockHeld,
        hasSessions,
        hasTasks,
        hasPendingPluginCommands,
        restorationPending,
        tearingDown
    )

    // ------------------------------------------------------------------ user exit versus death

    /**
     * The bug these four cover: closing Termux by typing `exit` in every session stopped the service
     * through a different door than the drawer's Exit button, and only that second door told the
     * supervisor that the user had closed the app. So the app was closed, the supervisor saw silence,
     * read it as a crash, and brought it back.
     */
    @Test
    fun aShellLeftOnPurposeIsTheUserClosingTheApp() {
        assertTrue(TermuxServiceStopPolicy.countsAsUserClosingTheApp(false, 0))
    }

    @Test
    fun theExitActionCountsEvenWithNoShellEverHavingExited() {
        assertTrue(TermuxServiceStopPolicy.countsAsUserClosingTheApp(true, null))
    }

    /** A shell that died is not a user who left, and the rescue has to stay armed for it. */
    @Test
    fun aShellThatDiedIsNotSomebodyLeaving() {
        assertFalse(TermuxServiceStopPolicy.countsAsUserClosingTheApp(false, 137))
        assertFalse(TermuxServiceStopPolicy.countsAsUserClosingTheApp(false, 1))
    }

    /** Nothing ever ran, so nobody closed anything. */
    @Test
    fun aServiceThatStoppedWithoutEverRunningAShellIsNotAUserExit() {
        assertFalse(TermuxServiceStopPolicy.countsAsUserClosingTheApp(false, null))
    }
}
