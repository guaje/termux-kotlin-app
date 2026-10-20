package com.termux.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.termux.anchor.AnchorSupervisionPolicy.Action.HOLD
import com.termux.anchor.AnchorSupervisionPolicy.Action.RESTART
import com.termux.anchor.AnchorSupervisionPolicy.Action.WAIT
import com.termux.anchor.AnchorSupervisionPolicy.RestartPolicy

/**
 * The rules that decide whether the anchor wakes Termux.
 *
 * These are the rules the handset will be judged by, and the cost of getting one wrong is either a
 * session the user has to restart by hand or a phone that throws a Termux window over what they are
 * doing. Both are discovered on a device, at night, by someone who was not expecting it, which is the
 * reason they are here instead of inline in the service.
 *
 * Everything is expressed as an age of the last heartbeat rather than as absolute times, because that is
 * the only quantity the anchor can actually observe: it never sees the app, only reports from it.
 */
class AnchorSupervisionPolicyTest {

    private fun secondsAgo(seconds: Long): Long = NOW - seconds * 1000L

    private fun report(
        lastHeartbeatMs: Long = 0L,
        attempts: Int = 0,
        supervisionEnabled: Boolean = true,
        policy: RestartPolicy = RestartPolicy.ALWAYS,
        bootId: String = BOOT_AFTER,
        lastAttemptBootId: String = "",
        lastAttemptMs: Long = 0L
    ) = AnchorSupervisionPolicy.decide(
        AnchorSupervisionPolicy.Watch(
            nowMs = NOW,
            lastHeartbeatMs = lastHeartbeatMs,
            supervisionEnabled = supervisionEnabled,
            consecutiveRestartAttempts = attempts,
            policyFromApp = policy,
            bootId = bootId,
            lastAttemptBootId = lastAttemptBootId,
            lastAttemptMs = lastAttemptMs
        )
    )

    private fun assertExplains(report: AnchorSupervisionPolicy.Report, fragment: String) {
        assertTrue("\"${report.reason}\" did not mention \"$fragment\"", report.reason.contains(fragment))
    }

    @Test
    fun nothingIsRestartedBeforeAHeartbeatHasEverArrived() {
        // The state the anchor is in the moment it is installed. "Termux is down" and "Termux was never
        // installed, or never held the permission to report" are the same observation from here, and
        // acting on that would have a freshly installed anchor launch Termux on a phone whose owner has
        // not enabled anything yet.
        val report = report()
        assertEquals(WAIT, report.action)
        assertExplains(report, "never")
    }

    @Test
    fun aHeartbeatInsideTheStaleWindowOnlyWaits() {
        val report = report(lastHeartbeatMs = secondsAgo(15))
        assertEquals(WAIT, report.action)
        assertEquals(30_000L, report.retryAfterMs)
    }

    @Test
    fun aStaleHeartbeatRestartsOnceSupervisionIsEnabled() {
        val report = report(lastHeartbeatMs = secondsAgo(46))
        assertEquals(RESTART, report.action)
        assertExplains(report, "46s")
        assertEquals(45_000L, report.retryAfterMs)
    }

    @Test
    fun policyNeverRefusesEvenAfterAnHourOfSilence() {
        val report = report(lastHeartbeatMs = secondsAgo(3600), policy = RestartPolicy.NEVER)
        assertEquals(HOLD, report.action)
        assertExplains(report, "policy 2")
    }

    @Test
    fun policyOnlyWhenUserEnabledHoldsUntilTheSwitchIsFlipped() {
        val off = report(
            lastHeartbeatMs = secondsAgo(600),
            supervisionEnabled = false,
            policy = RestartPolicy.ONLY_WHEN_USER_ENABLED
        )
        assertEquals(HOLD, off.action)
        assertExplains(off, "supervision is switched off")

        val on = report(
            lastHeartbeatMs = secondsAgo(600),
            supervisionEnabled = true,
            policy = RestartPolicy.ONLY_WHEN_USER_ENABLED
        )
        assertEquals(RESTART, on.action)
    }

    @Test
    fun anUnknownPolicyNumberIsStillSupervisionRatherThanSilence() {
        // An anchor and an app from different revisions of these names will disagree here, and the
        // failure worth guarding against is the quiet one: a supervisor that reads an unrecognised
        // number as "do not restart" stops doing its job without ever saying so.
        assertEquals(RestartPolicy.ALWAYS, RestartPolicy.fromWire(7))
        assertEquals(RestartPolicy.ALWAYS, RestartPolicy.fromWire(RestartPolicy.WIRE_ALWAYS))
        assertEquals(RestartPolicy.ONLY_WHEN_USER_ENABLED, RestartPolicy.fromWire(RestartPolicy.WIRE_ONLY_WHEN_USER_ENABLED))
        assertEquals(RestartPolicy.NEVER, RestartPolicy.fromWire(RestartPolicy.WIRE_NEVER))
    }

    @Test
    fun theBackoffDoublesFromTheStaleThresholdAndRestsOnThirtyMinutes() {
        assertEquals(45_000L, AnchorSupervisionPolicy.backoffMs(0))
        assertEquals(90_000L, AnchorSupervisionPolicy.backoffMs(1))
        assertEquals(180_000L, AnchorSupervisionPolicy.backoffMs(2))
        assertEquals(360_000L, AnchorSupervisionPolicy.backoffMs(3))
        assertEquals(1_800_000L, AnchorSupervisionPolicy.backoffMs(6))
        assertEquals(1_800_000L, AnchorSupervisionPolicy.backoffMs(400))
    }

    @Test
    fun aSecondAttemptWaitsOutTheLadderInsteadOfHammeringADeviceThatIsNotAnswering() {
        // The first attempt was made at the earliest moment it was allowed, 45 s after the last
        // heartbeat, so 60 s after that heartbeat the ladder still owes 30 s.
        val report = report(lastHeartbeatMs = secondsAgo(60), attempts = 1)
        assertEquals(HOLD, report.action)
        assertEquals(30_000L, report.retryAfterMs)
        assertExplains(report, "backoff")
    }

    @Test
    fun aThirdAttemptIsAllowedOnceNinetyMoreSecondsHavePassed() {
        // 45 s of stale window plus the 45 s and 90 s the first two attempts each paid for.
        val report = report(lastHeartbeatMs = secondsAgo(180), attempts = 2)
        assertEquals(RESTART, report.action)
        assertEquals(180_000L, report.retryAfterMs)
    }

    @Test
    fun theCeilingIsStatedOutLoudAndIsNotGivingUp() {
        val report = report(lastHeartbeatMs = secondsAgo(60), attempts = 40)
        assertEquals(HOLD, report.action)
        assertExplains(report, "ceiling")
        assertTrue(report.retryAfterMs > 0L)
    }

    @Test
    fun aHeartbeatAfterAStalePeriodReturnsToWaitingWhateverTheAttemptCount() {
        // Three unanswered attempts on the record and then the app reported again. The ladder is
        // measured from the heartbeat, so a fresh one answers every rung of it: this is the state in
        // which the anchor must say nothing and do nothing, not the one in which it works out whether
        // it is allowed to try again.
        val report = report(lastHeartbeatMs = secondsAgo(5), attempts = 3)
        assertEquals(WAIT, report.action)
        assertEquals(40_000L, report.retryAfterMs)
    }

    @Test
    fun aRebootClearsTheLadderBecauseTheDeviceHasAlreadyRestartedEverything() {
        val sameBoot = report(
            lastHeartbeatMs = secondsAgo(50),
            attempts = 4,
            bootId = BOOT_AFTER,
            lastAttemptBootId = BOOT_AFTER
        )
        assertEquals(HOLD, sameBoot.action)

        val rebooted = report(
            lastHeartbeatMs = secondsAgo(50),
            attempts = 4,
            bootId = BOOT_AFTER,
            lastAttemptBootId = BOOT_BEFORE
        )
        assertEquals(RESTART, rebooted.action)
        assertExplains(rebooted, "0 unanswered attempt")
    }

    @Test
    fun anAnchorWithNoBootIdOfItsOwnDoesNotInventAReboot() {
        // Both sides read /proc/sys/kernel/random/boot_id, but a read can fail and return "unknown", and
        // two different failures of that read must not be read as "the device rebooted", which would
        // reset the ladder every tick and turn the backoff into a fifteen second retry loop.
        val report = report(
            lastHeartbeatMs = secondsAgo(50),
            attempts = 4,
            bootId = "unknown",
            lastAttemptBootId = "unknown"
        )
        assertEquals(HOLD, report.action)
    }

    private companion object {
        /** Some moment that is not a real timestamp; only differences of it are ever read. */
        private const val NOW = 1_700_000_000_000L

        private const val BOOT_BEFORE = "11111111-2222-3333-4444-555555555555"
        private const val BOOT_AFTER = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    }

    /**
     * The supervisor is a foreground service on a phone under memory pressure, so it can be killed and
     * resurrected between attempts. A ladder read only off the heartbeat cannot see that it already
     * asked seconds ago, and the collapse it produces is a restart every service restart - hammering
     * the one app the backoff exists to give a rest.
     */
    @Test
    fun aResurrectedSupervisorDoesNotRestartAgainBeforeThePauseItOwedHasPassed() {
        // Two attempts already made, and the second one happened 10 seconds ago.
        val report = report(lastHeartbeatMs = secondsAgo(3600), attempts = 2, lastAttemptMs = secondsAgo(10))
        assertEquals(HOLD, report.action)
        // 45s + 90s of backoff is owed after attempt 2, and only 10s of it has been served.
        assertTrue(
            "expected roughly 80s left of the 135s pause, got ${report.retryAfterMs}: ${report.reason}",
            report.retryAfterMs in (75L * 1000L)..(85L * 1000L)
        )
        assertTrue(
            "the reason has to name the attempt as the anchor, not the heartbeat: ${report.reason}",
            report.reason.contains("attempt itself")
        )
    }

    /** The same silence, long enough ago that the pause really has been served: the attempt must fire. */
    @Test
    fun aLongOverdueAttemptIsStillAnswered() {
        val report = report(lastHeartbeatMs = secondsAgo(3600), attempts = 2, lastAttemptMs = secondsAgo(600))
        assertEquals(RESTART, report.action)
    }

    /**
     * A heartbeat in the future means the device clock went backwards while Termux was running - set by
     * hand, corrected by the network, or a time zone and a manual date together. Every duration the
     * anchor computes is the difference of two readings of that one clock, so a moved clock makes the
     * arithmetic meaningless, and "meaningless" must never be read as "restart the app".
     */
    @Test
    fun aClockBehindItsOwnRecordAsksForNothing() {
        val report = report(lastHeartbeatMs = NOW + 10L * 60L * 1000L)
        assertEquals(WAIT, report.action)
        assertTrue(
            "the reason has to blame the clock, not Termux: ${report.reason}",
            report.reason.contains("clock")
        )
        // The retry is one check interval, not the nonsense interval the arithmetic would suggest.
        assertEquals(AnchorSupervisionPolicy.CHECK_INTERVAL_MS, report.retryAfterMs)
    }
}
