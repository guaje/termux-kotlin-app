package com.termux.app.devkit

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.termux.BuildConfig
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH
import java.io.File

/**
 * Tells the supervisor anchor (`com.termux.anchor`) that this app is alive, once every 15 seconds.
 *
 * The anchor is a second APK with its own uid, installed once, that waits for this app to go quiet and
 * brings it back. It has to be a second uid because nothing inside this one can watch itself: a package
 * replace kills the whole uid and cgroup (`ProcessList.killProcessGroup(uid)`), not the process tree, so
 * `nohup`, `setsid`, double-forking and reparenting to init buy nothing - measured on the handset
 * (MEMORY.md §16C), a setsid marker with ppid=1 stopped within 1-2 s of the install and the app was back
 * about 5 s later. The anchor therefore restarts the app rather than trying to protect it, and this class
 * is the only thing that tells it the difference between "replaced a moment ago" and "gone".
 *
 * Three properties this file exists to guarantee:
 *
 * * No anchor, no noise. It is not part of any release yet, so its absence is the normal case, not an
 *   error, and a heartbeat that logs a failure every 15 seconds would make the log of every phone
 *   without one unreadable.
 * * Nothing escapes to the service. The call site is `TermuxService.onCreate()`; a supervisor whose
 *   reporting channel could throw would take the app down with the thing it is supposed to be watching.
 * * Dev builds only, twice over. [start] returns immediately unless `BuildConfig.DEVKIT_HOST`, and only
 *   `app/src/dev/AndroidManifest.xml` declares the signature-level permission the anchor's receiver
 *   requires, so a release build neither sends the broadcast nor holds the right to.
 *
 * The action and the extra names are literals repeated from `termux-anchor/src/main/kotlin/com/termux/
 * anchor/AnchorMessages.kt`, which is the file to change first. They are not shared through a module
 * dependency because that would put a second application APK on this one's classpath for four strings.
 */
class DevKitHeartbeat(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())

    private val policyFile = File("$TERMUX_PREFIX_DIR_PATH/var/lib/termux-kotlin/devkit/supervision-policy")

    /** null until the first probe, then whether the anchor was installed at that moment. */
    private var anchorPresent: Boolean? = null

    /** Set by [stop]; checked inside the loop because [stop] is not required to run on this thread. */
    @Volatile
    private var stopped = false

    private val beat = object : Runnable {
        override fun run() {
            if (stopped) return
            // The decision about whether anyone is listening is made every beat rather than once at
            // start-up, see [start].
            if (anchorInstalled()) sendOnce()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    /**
     * Begin reporting. Never throws, and does nothing at all on a build that may not speak to the anchor.
     *
     * The loop is scheduled whether or not an anchor is installed right now. It used not to be, which
     * made the receiver's own comment a lie: the anchor is installed into a running app by design -
     * that is the whole point of a supervisor that arrives after the thing it supervises - and a check
     * made once at `TermuxService.onCreate()` meant supervision silently needed a Termux restart before
     * it existed. One `getPackageInfo` per 15 s on a dev build is the whole cost of fixing that.
     */
    fun start() {
        if (!BuildConfig.DEVKIT_HOST) return
        stopped = false
        Logger.logInfo(LOG_TAG, "heartbeat loop scheduled, one beat every ${INTERVAL_MS / 1000}s")
        handler.post(beat)
    }

    /**
     * Stop reporting. Safe to call whether or not [start] did anything.
     *
     * [stopped] rather than only `removeCallbacks`, because a beat already running on the main looper
     * would re-post itself after the removal and keep this instance - and the `TermuxService` context it
     * holds - alive forever. Today the only caller is `onDestroy()` on that same looper, so the removal
     * alone happens to be enough, and "happens to" is not a reason to leave the trap in place.
     */
    fun stop() {
        stopped = true
        handler.removeCallbacks(beat)
    }

    /**
     * Whether `com.termux.anchor` is installed, logged only when the answer changes.
     *
     * Re-probed on every beat rather than cached forever, because the common path through this feature
     * is installing the anchor into a running app and wanting supervision immediately afterwards; a
     * `getPackageInfo` refusal on a package that is not there is a binder call and an exception, which at
     * one per 15 seconds is nothing.
     */
    private fun anchorInstalled(): Boolean {
        val present = runCatching { context.packageManager.getPackageInfo(ANCHOR_PACKAGE, 0) }.isSuccess
        if (present != anchorPresent) {
            anchorPresent = present
            Logger.logInfo(
                LOG_TAG,
                if (present) "anchor $ANCHOR_PACKAGE is installed, reporting this app as alive" else
                    "no anchor installed, heartbeat loop idle"
            )
        }
        return present
    }

    /** One beat, delivered or not. Failures are logged at debug because they are expected and repetitive. */
    private fun sendOnce() {
        runCatching {
            val heartbeat = Intent(ACTION_HEARTBEAT)
            // setPackage rather than an implicit broadcast: without it the intent would be delivered to
            // any receiver that declared this action and held nothing back, and with it only the anchor
            // can be addressed even if something else ever claims the action.
            heartbeat.setPackage(ANCHOR_PACKAGE)
            heartbeat.putExtra(EXTRA_VERSION_CODE, versionCode())
            heartbeat.putExtra(EXTRA_BOOT_ID, bootId())
            heartbeat.putExtra(EXTRA_POLICY, supervisionPolicy())
            context.sendBroadcast(heartbeat)
        }.onFailure { Logger.logDebug(LOG_TAG, "heartbeat not delivered: ${it.message}") }
    }

    /** The app's own version code, so that the anchor can name the build it last saw. */
    private fun versionCode(): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    /**
     * The same boot id the anchor reads from the same path, which is how a reboot becomes a fact for both
     * of them instead of an inference. Sent on every beat rather than once, because the receiver that
     * gets it may have been started before the reboot that produced it.
     */
    private fun bootId(): String = runCatching {
        File(BOOT_ID_PATH).readText().trim()
    }.getOrDefault("unknown")

    /**
     * The app's answer to "may you restart me", from the prefix, so that it is a file a script or the
     * user can set without rebuilding an APK. Three values, defined in the anchor's
     * AnchorSupervisionPolicy.RestartPolicy: 0 restart this app whenever it goes quiet, 1 restart it only
     * if the user has enabled supervision in the anchor's own screen, 2 never restart it.
     *
     * An unreadable file reads as [POLICY_ALWAYS_RESTART]. That is the same default the anchor assumes
     * for an absent extra, and the reasoning is the same on both sides: a missing file is the absence of
     * an instruction, not an instruction, and a supervision mode that failed closed would mean that the
     * simplest failure - a prefix that is not readable yet - silently switched the feature off.
     */
    private fun supervisionPolicy(): Int = runCatching {
        policyFile.readText().trim().toIntOrNull()
    }.getOrNull() ?: POLICY_ALWAYS_RESTART

    companion object {
        private const val LOG_TAG = "DevKitHeartbeat"

        private const val INTERVAL_MS = 15_000L

        private const val ANCHOR_PACKAGE = "com.termux.anchor"
        private const val ACTION_HEARTBEAT = "com.termux.anchor.HEARTBEAT"
        private const val EXTRA_VERSION_CODE = "com.termux.anchor.extra.VERSION_CODE"
        private const val EXTRA_BOOT_ID = "com.termux.anchor.extra.BOOT_ID"
        private const val EXTRA_POLICY = "com.termux.anchor.extra.POLICY"

        private const val BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id"

        /** Restart on request, whatever the anchor's own switch says. */
        private const val POLICY_ALWAYS_RESTART = 0
    }
}
