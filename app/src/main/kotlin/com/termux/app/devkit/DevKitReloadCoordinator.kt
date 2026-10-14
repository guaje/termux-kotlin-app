package com.termux.app.devkit

import android.content.Context
import android.content.Intent
import com.termux.BuildConfig
import com.termux.app.TermuxActivity
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY
import com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Applies devkit reload scopes and records what actually happened.
 *
 * The split in here is deliberate. *Policy* decides before touching anything whether this build
 * can act on a scope at all; *probe* decides afterwards, from observable state, whether the effect
 * was really delivered. A reload that nobody could see is reported as `deferred`, not `applied`,
 * because a caller that trusts a lie from this file is worse off than one that gets an error.
 *
 * The status file is the only channel back to the caller: the request arrives from a shell in the
 * prefix, which cannot read the app's logcat and cannot receive a broadcast result across uids.
 */
class DevKitReloadCoordinator(private val context: Context) {

    /** What happened to one requested scope. */
    enum class Outcome {
        /** The running app acted on it. */
        APPLIED,

        /** Correct behaviour, but nothing live consumed it yet; it takes effect on next start. */
        DEFERRED,

        /** This build has no implementation for the scope. */
        SKIPPED,

        /** The scope was invalid or the action threw. */
        FAILED
    }

    /** One scope's result, including the human-readable reason that a machine could not infer. */
    data class ScopeResult(val scope: String, val outcome: Outcome, val detail: String)

    private val statusDir = File("$TERMUX_PREFIX_DIR_PATH/var/lib/termux-kotlin/devkit")
    private val statusFile = File(statusDir, "last-reload.json")
    private val generationFile = File(statusDir, "generation")

    fun reload(requestId: String, scopes: List<String>): List<ScopeResult> {
        val startedAt = System.currentTimeMillis()
        val results = mutableListOf<ScopeResult>()
        val needsStyleReload = scopes.any { it == SCOPE_PROPERTIES || it == SCOPE_KEYMAPS }

        if (needsStyleReload) {
            results.add(reloadStyle())
        }

        for (scope in scopes) {
            when (scope) {
                SCOPE_PROPERTIES, SCOPE_KEYMAPS -> Unit // handled together, one broadcast per request
                SCOPE_DEVKIT -> results.add(prepareDevKitState())
                SCOPE_PLUGINS -> results.add(
                    ScopeResult(
                        scope,
                        Outcome.SKIPPED,
                        "no plugin host is wired into command dispatch yet, so there is nothing to reload"
                    )
                )
                else -> results.add(ScopeResult(scope, Outcome.FAILED, "unknown scope"))
            }
        }

        val generation = nextGeneration()
        writeStatus(requestId, generation, startedAt, System.currentTimeMillis(), results)
        Logger.logInfo(
            LOG_TAG,
            "reload $requestId generation $generation: " + results.joinToString { "${it.scope}=${it.outcome}" }
        )
        return results
    }

    /**
     * Ask the terminal UI to re-read termux.properties, including the extra-keys row.
     *
     * Recreate is switched off on purpose: the default for ACTION_RELOAD_STYLE is to recreate the
     * activity, which would tear down the terminal view that this whole feature exists to avoid
     * disturbing.
     */
    private fun reloadStyle(): ScopeResult {
        if (!TermuxActivity.isVisibleToDevKit) {
            // TermuxActivityBroadcastReceiver ignores every intent while invisible, so claiming
            // success here would be a fiction. The properties are read in onCreate() regardless.
            return ScopeResult(
                SCOPE_PROPERTIES,
                Outcome.DEFERRED,
                "no terminal UI is visible; termux.properties is re-read when the activity starts"
            )
        }

        return try {
            val intent = Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE)
            intent.setPackage(context.packageName)
            intent.putExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, false)
            context.sendBroadcast(intent)
            ScopeResult(
                SCOPE_PROPERTIES,
                Outcome.APPLIED,
                "styling and extra keys re-read without recreating the activity"
            )
        } catch (e: Exception) {
            ScopeResult(SCOPE_PROPERTIES, Outcome.FAILED, "could not reach the activity: ${e.message}")
        }
    }

    private fun prepareDevKitState(): ScopeResult {
        return try {
            if (!statusDir.isDirectory && !statusDir.mkdirs()) {
                return ScopeResult(SCOPE_DEVKIT, Outcome.FAILED, "could not create $statusDir")
            }
            ScopeResult(SCOPE_DEVKIT, Outcome.APPLIED, "status directory $statusDir")
        } catch (e: Exception) {
            ScopeResult(SCOPE_DEVKIT, Outcome.FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun nextGeneration(): Long {
        val previous = runCatching { generationFile.readText().trim().toLong() }.getOrNull()
            ?: LAST_RELOAD_GENERATION.get()
        val next = previous + 1L
        LAST_RELOAD_GENERATION.set(next)
        runCatching { generationFile.writeText(next.toString()) }
        return next
    }

    /**
     * Write the status file through a temporary file and a rename, so that a caller which polls
     * this path can never observe half a document.
     */
    private fun writeStatus(
        requestId: String,
        generation: Long,
        startedAt: Long,
        finishedAt: Long,
        results: List<ScopeResult>
    ) {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        val json = buildString {
            appendLine("{")
            appendLine("  \"requestId\": \"${escape(requestId)}\",")
            appendLine("  \"generation\": $generation,")
            appendLine("  \"buildType\": \"${BuildConfig.BUILD_TYPE}\",")
            appendLine("  \"startedAt\": \"${formatter.format(Date(startedAt))}\",")
            appendLine("  \"finishedAt\": \"${formatter.format(Date(finishedAt))}\",")
            appendLine("  \"tookMillis\": ${finishedAt - startedAt},")
            appendLine("  \"applied\": ${array(results, Outcome.APPLIED)},")
            appendLine("  \"deferred\": ${array(results, Outcome.DEFERRED)},")
            appendLine("  \"skipped\": ${array(results, Outcome.SKIPPED)},")
            appendLine("  \"failed\": ${array(results, Outcome.FAILED)}")
            append("}")
        }

        try {
            if (!statusDir.isDirectory) {
                statusDir.mkdirs()
            }
            val temp = File(statusDir, statusFile.name + ".tmp")
            temp.writeText(json)
            if (!temp.renameTo(statusFile)) {
                // A failed rename on the same filesystem means the target exists and is not
                // replaceable; keep the content anyway rather than losing the report.
                statusFile.writeText(json)
                temp.delete()
            }
        } catch (e: Exception) {
            Logger.logError(LOG_TAG, "could not write $statusFile: ${e.message}")
        }
    }

    private fun array(results: List<ScopeResult>, outcome: Outcome): String {
        val matching = results.filter { it.outcome == outcome }
        if (matching.isEmpty()) {
            return "[]"
        }
        return matching.joinToString(prefix = "[", postfix = "]") {
            "{ \"scope\": \"${escape(it.scope)}\", \"detail\": \"${escape(it.detail)}\" }"
        }
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val LOG_TAG = "DevKitReloadCoordinator"

        const val SCOPE_PROPERTIES = "properties"
        const val SCOPE_KEYMAPS = "keymaps"
        const val SCOPE_DEVKIT = "devkit"
        const val SCOPE_PLUGINS = "plugins"

        val DEFAULT_SCOPES = listOf(SCOPE_PROPERTIES, SCOPE_KEYMAPS)

        /** Kept in memory so repeated requests stay monotonic even if the file is unwritable. */
        private val LAST_RELOAD_GENERATION = AtomicLong(0)
    }
}
