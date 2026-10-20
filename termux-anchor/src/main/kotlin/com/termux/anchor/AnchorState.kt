package com.termux.anchor

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * What the anchor has seen and done, in its own private storage.
 *
 * Two files, because they answer two different questions and one of them must not grow.
 * `supervision.json` is the current state that [AnchorSupervisionPolicy] reads, and it is replaced
 * whole through a temporary file plus a rename - the same doctrine as `DevKitReloadCoordinator` in the
 * app, for the same reason: a reader that polls this path must never observe half a document, and the
 * anchor's reader is the loop that decides whether the user's session is coming back.
 *
 * `supervision-ledger.jsonl` is the history, one JSON line per event, appended rather than rewritten.
 * An append of a few hundred bytes is the one write that does not need the rename dance, and a
 * supervisor whose only record is a file it rewrites from scratch loses the truth about what happened
 * before the crash it is meant to explain. That is why the ledger is truncated rather than rewritten
 * here, and truncation is bounded: 600 lines triggers a rewrite that keeps the last 500.
 *
 * The user's own switch lives in SharedPreferences rather than in the JSON file because it is the
 * anchor's answer to the user, not the app's answer to the anchor, and because [AnchorActivity] and
 * the supervisor loop both read it through this class so that the key exists once.
 */
class AnchorState(context: Context) {

    private val stateFile = File(context.filesDir, STATE_FILE_NAME)
    private val ledgerFile = File(context.filesDir, LEDGER_FILE_NAME)
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** The state as last recorded. Field order and JSON keys are the same on purpose. */
    data class Snapshot(
        val lastHeartbeatMs: Long = 0L,
        val lastVersionCode: Long = 0L,
        val bootId: String = "",
        val attempts: Int = 0,
        val attemptBootId: String = "",
        val lastAttemptMs: Long = 0L,
        val policyFromApp: Int = AnchorSupervisionPolicy.RestartPolicy.WIRE_ALWAYS
    ) {
        /** The same facts in the shape [AnchorSupervisionPolicy] reads. */
        fun toWatch(
            nowMs: Long,
            supervisionEnabled: Boolean,
            currentBootId: String
        ): AnchorSupervisionPolicy.Watch = AnchorSupervisionPolicy.Watch(
            nowMs = nowMs,
            lastHeartbeatMs = lastHeartbeatMs,
            supervisionEnabled = supervisionEnabled,
            consecutiveRestartAttempts = attempts,
            policyFromApp = AnchorSupervisionPolicy.RestartPolicy.fromWire(policyFromApp),
            bootId = currentBootId,
            lastAttemptBootId = attemptBootId,
            lastAttemptMs = lastAttemptMs
        )
    }

    /** The user's switch, read live so that flipping it takes effect on the next tick. */
    val supervisionEnabled: Boolean
        get() = preferences.getBoolean(KEY_SUPERVISION_ENABLED, false)

    /** Read the state file, or an empty snapshot when it does not exist or cannot be parsed. */
    fun read(): Snapshot {
        val json = runCatching { stateFile.readText() }.getOrNull() ?: return Snapshot()
        return Snapshot(
            lastHeartbeatMs = number(json, "lastHeartbeatMs", 0L),
            lastVersionCode = number(json, "lastVersionCode", 0L),
            bootId = text(json, "bootId"),
            attempts = number(json, "attempts", 0L).toInt(),
            attemptBootId = text(json, "attemptBootId"),
            lastAttemptMs = number(json, "lastAttemptMs", 0L),
            policyFromApp = number(json, "policyFromApp", 0L).toInt()
        )
    }

    /**
     * Book a heartbeat. The attempt counter returns to zero here, because a heartbeat is the only
     * evidence that a previous restart attempt worked, and the ladder has nothing left to space out
     * once the app is reporting again.
     */
    fun recordHeartbeat(nowMs: Long, versionCode: Long, bootId: String, policyFromApp: Int) {
        val snapshot = Snapshot(
            lastHeartbeatMs = nowMs,
            lastVersionCode = versionCode,
            bootId = bootId,
            attempts = 0,
            attemptBootId = "",
            policyFromApp = policyFromApp
        )
        save(snapshot)
        append(
            EVENT_HEARTBEAT,
            snapshot,
            bootId,
            "heartbeat from versionCode $versionCode, policy $policyFromApp"
        )
    }

    /**
     * Book a restart attempt and return the new count.
     *
     * Called whether or not the platform accepted the request, because the ladder exists to space out
     * attempts, not successes: an attempt that threw is exactly the kind that must not be repeated
     * fifteen seconds later forever.
     */
    fun recordRestartAttempt(snapshot: Snapshot, bootId: String, reason: String): Int {
        val attempts = snapshot.attempts + 1
        save(snapshot.copy(attempts = attempts, attemptBootId = bootId, lastAttemptMs = System.currentTimeMillis()))
        append(EVENT_RESTART, snapshot.copy(attempts = attempts), bootId, reason)
        return attempts
    }

    /** Record a decision not to act, with the reason, so that a silent night is explainable. */
    fun recordHold(snapshot: Snapshot, bootId: String, reason: String) {
        append(EVENT_HOLD, snapshot, bootId, reason)
    }

    /** Record that the device started, which is also the event that resets the ladder by boot id. */
    fun recordBoot(bootId: String) {
        append(EVENT_BOOT, read(), bootId, "boot completed on $bootId")
    }

    /** Record a change of the user's own switch. */
    fun setSupervisionEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_SUPERVISION_ENABLED, enabled).apply()
        append(EVENT_POLICY, read(), readBootId(), "supervision switched " + if (enabled) "on" else "off")
    }

    /** The tail of the ledger, oldest first, for the status screen. */
    fun lastLedgerLines(count: Int): List<String> = runCatching {
        ledgerFile.readLines().takeLast(count).filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    private fun save(snapshot: Snapshot) {
        val json = buildString {
            appendLine("{")
            appendLine("  \"lastHeartbeatMs\": ${snapshot.lastHeartbeatMs},")
            appendLine("  \"lastVersionCode\": ${snapshot.lastVersionCode},")
            appendLine("  \"bootId\": ${quote(snapshot.bootId)},")
            appendLine("  \"attempts\": ${snapshot.attempts},")
            appendLine("  \"attemptBootId\": ${quote(snapshot.attemptBootId)},")
            appendLine("  \"lastAttemptMs\": ${snapshot.lastAttemptMs},")
            appendLine("  \"policyFromApp\": ${snapshot.policyFromApp}")
            append("}")
        }
        runCatching { writeAtomically(stateFile, json) }
    }

    private fun append(event: String, snapshot: Snapshot, bootId: String, reason: String) {
        val line = "{" +
            "\"event\": ${quote(event)}, " +
            "\"now\": ${System.currentTimeMillis()}, " +
            "\"lastHeartbeat\": ${snapshot.lastHeartbeatMs}, " +
            "\"versionCode\": ${snapshot.lastVersionCode}, " +
            "\"bootId\": ${quote(bootId)}, " +
            "\"attempt\": ${snapshot.attempts}, " +
            "\"reason\": ${quote(reason)}" +
            "}"
        runCatching {
            ledgerFile.appendText("$line\n")
            trimLedger()
        }
    }

    /**
     * Keep the ledger bounded.
     *
     * The anchor runs for months on a phone that is restarted by an agent loop every few minutes, and a
     * log nobody reads that eventually fills the partition would take the app down with it: the two
     * packages share the device, not the uid, but they share the disk. The read is not cheap, so it is
     * only done once the file has passed the trigger size.
     */
    private fun trimLedger() {
        val lines = ledgerFile.readLines()
        if (lines.size <= LEDGER_TRIM_AT_LINES) return
        writeAtomically(ledgerFile, lines.takeLast(LEDGER_KEEP_LINES).joinToString("\n", postfix = "\n"))
    }

    /**
     * Replace a file through a temporary file and a rename, so that no reader can see a partial write.
     *
     * A failed rename means the target exists and would not be replaced - which on this filesystem
     * means a stale handle or a policy refusal, not a full disk (that shows up as an IOException in
     * `writeText` above). The content is written directly in that case rather than lost: half a state
     * file is worse than an old one, and an unrecorded event is worse than neither.
     */
    private fun writeAtomically(target: File, content: String) {
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(content)
        if (!temp.renameTo(target)) {
            target.writeText(content)
            temp.delete()
        }
    }

    private fun number(json: String, key: String, default: Long): Long =
        field(json, key)?.toLongOrNull() ?: default

    private fun text(json: String, key: String): String = field(json, key) ?: ""

    /**
     * The reader for a document this class wrote itself, so it is a lookup and not a parser: flat
     * object, one value per key, quoted or not.
     *
     * It tolerates a truncated tail by returning null for whatever it cannot see, which is what makes
     * [read] return an empty snapshot rather than throwing at the loop that is supposed to survive.
     */
    private fun field(json: String, key: String): String? =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(\"[^\"]*\"|-?[0-9]+)")
            .find(json)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.removeSurrounding("\"")

    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {

        private const val STATE_FILE_NAME = "supervision.json"
        private const val LEDGER_FILE_NAME = "supervision-ledger.jsonl"

        private const val PREFERENCES_NAME = "supervision"
        private const val KEY_SUPERVISION_ENABLED = "supervision_enabled"

        const val EVENT_HEARTBEAT: String = "HEARTBEAT"
        const val EVENT_RESTART: String = "RESTART"
        const val EVENT_HOLD: String = "HOLD"
        const val EVENT_BOOT: String = "BOOT"
        const val EVENT_POLICY: String = "POLICY"

        /** Rewrite rather than grow past this many lines... */
        private const val LEDGER_TRIM_AT_LINES = 600

        /** ...keeping this many, which is what a human debugging this wants and no more. */
        private const val LEDGER_KEEP_LINES = 500

        /**
         * The device's identity for this boot, read the same way `DevKitReloadCoordinator` reads it.
         *
         * It is what proves a reboot happened, and it is read from the same file on both sides, so the
         * app's copy in the heartbeat and the anchor's own copy are the same string by construction.
         */
        fun readBootId(): String = runCatching {
            File("/proc/sys/kernel/random/boot_id").readText().trim()
        }.getOrDefault("unknown")
    }
}
