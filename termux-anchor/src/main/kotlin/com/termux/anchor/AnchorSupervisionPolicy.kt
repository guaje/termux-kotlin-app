package com.termux.anchor

/**
 * The rules that decide whether the anchor asks the platform to bring Termux back, with nothing
 * Android in them - the same split as `DevKitShipPolicy` in the app: the rules are the part worth
 * testing, so they run on the JVM, and the service that calls them stays a thin wrapper over a
 * Context, a Handler and a file.
 *
 * What this object is not is a guarantee about the app. The anchor cannot observe a running Termux; it
 * can only observe the absence of a heartbeat. Every answer below therefore describes what was seen
 * and never asserts what is true - in particular HOLD and WAIT must not be read as "Termux is fine",
 * only as "what is on record does not justify a restart yet".
 *
 * The one asymmetry worth stating out loud: a missed restart costs a session the user has to start
 * again by hand, while a spurious one costs a Termux window that appears while the user is doing
 * something else. The rules below are conservative for that reason, and the stale threshold is a
 * delay rather than a judgement.
 */
object AnchorSupervisionPolicy {

    /** How long a heartbeat has to be missing before "quiet" is treated as "down". */
    const val STALE_MS: Long = 45_000L

    /** The ceiling of the doubling backoff, so a hopeless night costs one attempt per half hour. */
    const val MAX_BACKOFF_MS: Long = 30L * 60L * 1000L

    /** The period the supervisor loop runs on; also the answer given when nothing is known yet. */
    const val CHECK_INTERVAL_MS: Long = 15_000L

    /** One stale window is enough time for a visible rescue request to remain available. */
    const val OVERLAY_TTL_MS: Long = 45_000L

    /**
     * 45 s * 2^6 = 48 min, already past the ceiling, so rung 6 is the last one the ladder can need and
     * the shift below never reaches a width where it could overflow.
     */
    private const val BACKOFF_CEILING_RUNGS: Int = 6

    /** What to do next. There is no "give up" here; HOLD with the ceiling reached is what that looks like. */
    enum class Action {
        RESTART,
        HOLD,
        WAIT
    }

    /** The platform route available for an attempt, based only on the right currently held. */
    enum class RescueMethod {
        OVERLAY_AND_LAUNCH,
        LAUNCH_ONLY
    }

    /** A visible non-app window is the route that makes a background launch eligible. */
    fun rescueMethod(canDrawOverlays: Boolean): RescueMethod = if (canDrawOverlays) {
        RescueMethod.OVERLAY_AND_LAUNCH
    } else {
        RescueMethod.LAUNCH_ONLY
    }

    /**
     * Keep a rescue window only while the same unanswered heartbeat is latest and its short lifetime
     * has not elapsed.
     *
     * Both timestamps come from the device clock. A negative age, or a heartbeat later than the clock
     * reading used to judge it, means that clock moved and cannot justify leaving a window on screen.
     */
    fun overlayStillWanted(
        nowMs: Long,
        shownAtMs: Long,
        lastHeartbeatMs: Long,
        lastHeartbeatAtShowMs: Long
    ): Boolean = nowMs >= shownAtMs &&
        nowMs - shownAtMs in 0L until OVERLAY_TTL_MS &&
        lastHeartbeatMs <= nowMs &&
        lastHeartbeatMs == lastHeartbeatAtShowMs

    /**
     * What the app said about whether it may be restarted, carried in every heartbeat as
     * [AnchorMessages.EXTRA_POLICY].
     *
     * The value comes from the app because the app knows why it is quiet. A user who stopped the
     * service from the notification is not a crash, and a supervisor that disagreed with that would be
     * a fault the user could not switch off.
     */
    enum class RestartPolicy {

        /** Restart whenever the heartbeat stops, whatever the anchor's own switch says. */
        ALWAYS,

        /** Restart only if the user turned supervision on in the anchor's own UI. */
        ONLY_WHEN_USER_ENABLED,

        /** Never restart: this app is being stopped on purpose. */
        NEVER;

        companion object {

            /** The wire value for [ALWAYS], and the value assumed when the extra is absent. */
            const val WIRE_ALWAYS: Int = 0

            /** The wire value for [ONLY_WHEN_USER_ENABLED]. */
            const val WIRE_ONLY_WHEN_USER_ENABLED: Int = 1

            /** The wire value for [NEVER]. */
            const val WIRE_NEVER: Int = 2

            /**
             * Anything unknown reads as [ALWAYS]. An unrecognised number means an anchor and an app
             * built from different revisions of this file are talking, and the failure worth protecting
             * against in that case is the silent one: a supervisor that quietly stops supervising.
             */
            fun fromWire(value: Int): RestartPolicy = when (value) {
                WIRE_ONLY_WHEN_USER_ENABLED -> ONLY_WHEN_USER_ENABLED
                WIRE_NEVER -> NEVER
                else -> ALWAYS
            }
        }
    }

    /**
     * Everything the rules read, all of it already recorded by [AnchorState].
     *
     * [lastHeartbeatMs] is 0 when no heartbeat has ever arrived, which is a different state from a
     * stale one and the only one this anchor refuses to act on.
     */
    data class Watch(
        val nowMs: Long,
        val lastHeartbeatMs: Long = 0L,
        val supervisionEnabled: Boolean = false,
        val consecutiveRestartAttempts: Int = 0,
        val policyFromApp: RestartPolicy = RestartPolicy.ALWAYS,
        val bootId: String = "",
        val lastAttemptBootId: String = "",
        /**
         * When the most recent restart was actually requested, from the same device clock as
         * [lastHeartbeatMs]. 0 when there never was one.
         *
         * This exists because a ladder derived purely from the heartbeat forgets what it is owed. The
         * supervisor is a foreground service on a phone under memory pressure, so it can be killed
         * between attempts and restarted by `START_STICKY` at an arbitrary moment; with only the
         * heartbeat on record, the deadline it computes is already in the past and it restarts again
         * immediately, which turns backoff into hammering exactly during the failure it is meant to
         * space out.
         */
        val lastAttemptMs: Long = 0L
    )

    /** The decision, plus the sentence a human reads in the notification and in the ledger. */
    data class Report(val action: Action, val reason: String, val retryAfterMs: Long)

    /**
     * The first rule that has an opinion decides the outcome; null means "this rule is satisfied".
     *
     * Order matters and is stated once, here, instead of being implied by a ladder of calls: the
     * observations that could not possibly justify a restart come first, then the app's own
     * instructions, and only then the arithmetic about when the next attempt is allowed.
     */
    fun decide(watch: Watch): Report {
        val attempts = attemptsToActOn(watch)
        return noHeartbeatEverSeen(watch)
            ?: clockBehindLastHeartbeat(watch)
            ?: heartbeatStillCurrent(watch)
            ?: theAppSaidNo(watch)
            ?: backoffNotElapsedYet(watch, attempts)
            ?: restartIsDue(watch, attempts)
    }

    /**
     * The pause taken *after* attempt number [attempt], counting from 0: 45 s, 90 s, 180 s, and then
     * [MAX_BACKOFF_MS] from rung 6 onward.
     */
    fun backoffMs(attempt: Int): Long = minOf(STALE_MS shl attempt.coerceIn(0, BACKOFF_CEILING_RUNGS), MAX_BACKOFF_MS)

    /**
     * How old the last heartbeat has to be before attempt number [attempts] + 1 is allowed, ignoring
     * when the previous attempt actually happened.
     *
     * This is one of two constraints; see [backoffNotElapsedYet] for why one is not enough.
     */
    private fun restartDeadlineAgeMs(attempts: Int): Long {
        if (attempts <= 0) return STALE_MS
        val owed = (0 until minOf(attempts, BACKOFF_CEILING_RUNGS)).sumOf { backoffMs(it) }
        val pastCeiling = (attempts - BACKOFF_CEILING_RUNGS).coerceAtLeast(0) * MAX_BACKOFF_MS
        return STALE_MS + owed + pastCeiling
    }

    /**
     * The attempt count to act on, which is not always the one on the record.
     *
     * A reboot clears the ladder. The restart that is being waited out may be the one the user asked
     * for by powering the phone back on, and the platform has by then already done the thing the
     * backoff exists to space out: every process is gone and boot has completed. Carrying the count
     * across that would leave the anchor sitting on a 30 minute ceiling for a device that just
     * restarted itself.
     */
    fun attemptsToActOn(watch: Watch): Int {
        val rebootedSince = watch.lastAttemptBootId.isNotBlank() &&
            watch.bootId.isNotBlank() &&
            watch.lastAttemptBootId != watch.bootId
        return if (rebootedSince) 0 else watch.consecutiveRestartAttempts
    }

    private fun noHeartbeatEverSeen(watch: Watch): Report? {
        if (watch.lastHeartbeatMs > 0L) return null
        return Report(
            Action.WAIT,
            "no heartbeat has ever arrived, and \"Termux is down\" cannot be told apart from " +
                "\"Termux was never installed or never granted the permission to report\"; not restarting",
            STALE_MS
        )
    }

    /**
     * A heartbeat stamped in the future is not a heartbeat that will arrive later.
     *
     * Both timestamps come from this device's own clock, so they can only disagree like this if that
     * clock moved backwards while Termux was running - a manual change, an NTP correction, a trip
     * across time zones with the date set by hand. Nothing may be concluded about Termux from an
     * arithmetic comparison that the clock itself has invalidated, and the failure to avoid is the
     * loud one: restarting the app because a setting changed.
     */
    private fun clockBehindLastHeartbeat(watch: Watch): Report? {
        val ageMs = watch.nowMs - watch.lastHeartbeatMs
        if (watch.lastHeartbeatMs <= 0L || ageMs >= 0L) return null
        return Report(
            Action.WAIT,
            "this device's clock is ${-ageMs / 1000}s behind the last heartbeat it recorded, so the " +
                "silence cannot be measured; asking for no restart until the clock and the record agree",
            CHECK_INTERVAL_MS
        )
    }

    private fun heartbeatStillCurrent(watch: Watch): Report? {
        val ageMs = watch.nowMs - watch.lastHeartbeatMs
        if (ageMs >= STALE_MS) return null
        return Report(
            Action.WAIT,
            "heartbeat ${ageMs / 1000}s ago, inside the ${STALE_MS / 1000}s stale window",
            STALE_MS - ageMs
        )
    }

    private fun theAppSaidNo(watch: Watch): Report? {
        val refusal = when (watch.policyFromApp) {
            RestartPolicy.ALWAYS -> null
            RestartPolicy.NEVER -> "the app reports policy ${RestartPolicy.WIRE_NEVER}: it is being " +
                "stopped on purpose, and a supervisor does not get to overrule that"
            RestartPolicy.ONLY_WHEN_USER_ENABLED -> if (watch.supervisionEnabled) {
                null
            } else {
                "the app reports policy ${RestartPolicy.WIRE_ONLY_WHEN_USER_ENABLED}: restart only " +
                    "what the user asked for, and supervision is switched off in the anchor"
            }
        }
        return refusal?.let { Report(Action.HOLD, it, CHECK_INTERVAL_MS) }
    }

    /**
     * Holds the restart until both deadlines have passed: the one implied by the silence, and the one
     * implied by the last attempt itself.
     *
     * The older code had only the first, and defended it by saying a ladder is a pure function of the
     * attempt count so nothing needed remembering. That is true arithmetic and wrong as a design: it
     * presumes every previous attempt happened exactly when it was scheduled. The supervisor can be
     * killed between attempts, so the real interval can be seconds, and a rule that only looks at the
     * heartbeat cannot see that it just restarted the app 8 seconds ago.
     */
    private fun backoffNotElapsedYet(watch: Watch, attempts: Int): Report? {
        val byHeartbeatMs = watch.lastHeartbeatMs + restartDeadlineAgeMs(attempts)
        val byAttemptMs = if (attempts > 0 && watch.lastAttemptMs > 0L) {
            watch.lastAttemptMs + backoffMs(attempts - 1)
        } else {
            0L
        }
        val remainingMs = maxOf(byHeartbeatMs, byAttemptMs) - watch.nowMs
        if (remainingMs <= 0L) return null
        val anchoredOnAttempt = byAttemptMs > byHeartbeatMs
        val why = if (backoffMs(attempts - 1) >= MAX_BACKOFF_MS) {
            "the backoff has hit its ${MAX_BACKOFF_MS / 60000} minute ceiling and stays there; still " +
                "retrying, because a phone that comes back on its own at 04:00 is worth one wake per half hour"
        } else {
            "attempt ${attempts} got no heartbeat back, so the next one waits out " +
                "${backoffMs(attempts - 1) / 1000}s of backoff instead of hammering a device that is not answering" +
                if (anchoredOnAttempt) {
                    ", counted from the attempt itself - the supervisor is a service that can be killed " +
                    "between attempts, and a pause measured only from the heartbeat would be forgotten"
                } else {
                    ""
                }
        }
        return Report(Action.HOLD, why, remainingMs)
    }

    private fun restartIsDue(watch: Watch, attempts: Int): Report {
        val ageMs = watch.nowMs - watch.lastHeartbeatMs
        return Report(
            Action.RESTART,
            "no heartbeat for ${ageMs / 1000}s, past the ${STALE_MS / 1000}s stale window, with " +
                "$attempts unanswered attempt(s) before it",
            backoffMs(attempts)
        )
    }
}
