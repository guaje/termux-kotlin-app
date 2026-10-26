package com.termux.app

import com.termux.app.UiEntryPoint.Route.CLIENT
import com.termux.app.UiEntryPoint.Route.NONE
import com.termux.app.UiEntryPoint.Route.SELF_ACTIVITY
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The order in which a terminal is found, and in particular the last row.
 *
 * A server that stops shipping a terminal activity is a design goal, not an accident, so "nothing can show a
 * terminal" is a state the code has to reach on purpose rather than one it falls into. Every notification,
 * widget tap and session action in the app used to answer this question separately and each answered it with a
 * class literal, which is the kind of code that compiles happily until the morning it does not compile. With
 * the decision in one place, these four rows are the whole policy, and a reviewer can read them.
 */
class UiEntryPointTest {

    @Test
    fun whileThisAppShipsAnActivityItIsStillTheTerminal() {
        assertEquals(SELF_ACTIVITY, UiEntryPoint.routeFor(selfActivityPresent = true, clientLaunchIntentPresent = false))
    }

    /** The ordering that matters: a client is a substitute, not a takeover, so it loses while the app has its own UI. */
    @Test
    fun theAppsOwnActivityWinsOverAnInstalledClient() {
        assertEquals(SELF_ACTIVITY, UiEntryPoint.routeFor(selfActivityPresent = true, clientLaunchIntentPresent = true))
    }

    @Test
    fun aClientIsUsedOnceThisAppHasNoTerminalOfItsOwn() {
        assertEquals(CLIENT, UiEntryPoint.routeFor(selfActivityPresent = false, clientLaunchIntentPresent = true))
    }

    /** And the headless case answers "nothing" instead of throwing or opening the wrong thing. */
    @Test
    fun withNeitherTheAnswerIsNothingRatherThanAnException() {
        assertEquals(NONE, UiEntryPoint.routeFor(selfActivityPresent = false, clientLaunchIntentPresent = false))
    }
}
