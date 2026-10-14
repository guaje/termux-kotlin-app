package com.termux.app.devkit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.Activity
import com.termux.BuildConfig
import com.termux.shared.logger.Logger

/**
 * Entry point for "re-read configuration in the already running app".
 *
 * Reachability is the whole security model, so it is spelled out: this receiver is
 * `android:exported="false"`, which means the only senders are this uid. That is exactly the set we
 * want, because the developer's own shell lives in that uid and can already do anything inside the
 * prefix, while a foreign app - or `adb shell`, which the platform refuses to deliver to app
 * receivers - cannot reach it at all. No runtime permission is declared here for that reason: our
 * own uid already holds any permission this package defines, so a permission attribute would filter
 * nothing while adding a way for the request to be silently dropped.
 *
 * The endpoint only exists in `dev` and `debug` builds (`BuildConfig.DEVKIT_HOST`). A released APK
 * answers nothing, and answering nothing is the correct behaviour for a remote-control surface.
 */
class DevKitReloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID)?.trim()
        // One guard, one exit: isNullOrEmpty() carries a Kotlin contract, so requestId is still
        // smart-cast to a non-null String below.
        if (intent == null || !BuildConfig.DEVKIT_HOST || requestId.isNullOrEmpty()) {
            // Nothing is written to the status file here on purpose: a stale file from an earlier
            // request would otherwise look like the answer to this one.
            Logger.logWarn(
                LOG_TAG,
                "ignoring reload request: intent=${if (intent == null) "absent" else "present"}, " +
                    "devkitHost=${BuildConfig.DEVKIT_HOST}, requestId=$requestId"
            )
            return
        }

        val scopes = intent.getStringExtra(EXTRA_SCOPES)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?.ifEmpty { null }
            ?: DevKitReloadCoordinator.DEFAULT_SCOPES

        Logger.logInfo(LOG_TAG, "reload request $requestId scopes=$scopes")

        val results = DevKitReloadCoordinator(context).reload(requestId, scopes)
        val failed = results.any { it.outcome == DevKitReloadCoordinator.Outcome.FAILED }
        setResultCode(if (failed) Activity.RESULT_CANCELED else Activity.RESULT_OK)
    }

    companion object {
        private const val LOG_TAG = "DevKitReloadReceiver"

        /** Action sent by `termux-reload`; keep in sync with pkg/termux-devkit/usr/bin/termux-reload. */
        const val ACTION_RELOAD = "com.termux.app.devkit.reload"

        /** Correlates the request with the status file so a caller can wait for its own result. */
        const val EXTRA_REQUEST_ID = "com.termux.app.devkit.request_id"

        /** Comma separated scope list, see [DevKitReloadCoordinator]. */
        const val EXTRA_SCOPES = "com.termux.app.devkit.scopes"
    }
}
