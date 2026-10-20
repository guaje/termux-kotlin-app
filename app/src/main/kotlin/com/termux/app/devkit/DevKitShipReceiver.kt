package com.termux.app.devkit

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.termux.BuildConfig
import com.termux.shared.logger.Logger

/**
 * Entry point for "replace this app with that apk".
 *
 * Reachability is the security model, exactly as in [DevKitReloadReceiver]: `android:exported="false"`,
 * so the only senders are this app's own uid - the developer's shell lives there and can already do
 * anything inside the prefix, while a foreign app or `adb shell` cannot reach this at all. No
 * permission attribute, because our own uid implicitly holds every permission this package declares,
 * so one would filter nothing while adding a way for a request to be dropped silently.
 *
 * What makes this endpoint worth having is not the installing. It is that only the app can read a
 * candidate apk through the package manager, so only the app can say whether that apk is really for
 * this package, really carries a higher version code, and really is signed by the key already
 * installed. A script can be told those things; this checks them.
 *
 * Present in every build so that `termux-ship` gets a real answer instead of a timeout, but inert
 * unless `BuildConfig.DEVKIT_HOST`: on a release build it writes nothing and installs nothing.
 */
class DevKitShipReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || !BuildConfig.DEVKIT_HOST) {
            return
        }

        when (intent.action) {
            ACTION_SHIP -> handleShip(context, intent)
            ACTION_SHIP_RESULT -> DevKitShipCoordinator(context).recordInstallerResult(
                intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            )
            else -> Logger.logWarn(LOG_TAG, "ignoring action ${intent.action}")
        }
    }

    private fun handleShip(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)?.trim()
        val apkPath = intent.getStringExtra(EXTRA_APK)?.trim()
        // Both are required. Without a request id there is nothing to correlate the status file
        // against, and a stale report from an earlier attempt would read like the answer to this one.
        if (requestId.isNullOrEmpty() || apkPath.isNullOrEmpty()) {
            Logger.logWarn(LOG_TAG, "ignoring ship request: requestId=$requestId apk=$apkPath")
            return
        }

        val claims = DevKitShipPolicy.Claims(
            versionCode = readLong(intent, EXTRA_CLAIMED_VERSION_CODE),
            signerSha256 = intent.getStringExtra(EXTRA_CLAIMED_SIGNER)?.trim()?.ifEmpty { null },
            abis = intent.getStringArrayExtra(EXTRA_CLAIMED_ABIS)?.toList()?.filter { it.isNotEmpty() }
                ?: emptyList()
        )
        val confirm = intent.getBooleanExtra(EXTRA_CONFIRM, false)
        Logger.logInfo(LOG_TAG, "ship request $requestId apk=$apkPath confirm=$confirm claims=$claims")

        val report = DevKitShipCoordinator(context).ship(requestId, apkPath, claims, confirm)
        setResultCode(if (report.accepted) Activity.RESULT_OK else Activity.RESULT_CANCELED)
    }

    /**
     * `termux-am` can only deliver a 32-bit `--ei`, while version codes are longs, and a hand-typed
     * broadcast may carry a String. Accept whichever arrived rather than silently reading 0 and then
     * reporting a version mismatch about a number that was never there.
     */
    private fun readLong(intent: Intent, name: String): Long? {
        return when (val value = intent.extras?.get(name)) {
            is Long -> value
            is Int -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }
    }

    companion object {
        private const val LOG_TAG = "DevKitShipReceiver"

        /** Action sent by `termux-ship`; keep in sync with pkg/termux-devkit/usr/bin/termux-ship. */
        const val ACTION_SHIP = "com.termux.app.devkit.ship"

        /** Delivered by the system installer with the outcome of a staged session. */
        const val ACTION_SHIP_RESULT = "com.termux.app.devkit.ship.result"

        /** Correlates this request with the status file, so a caller can wait for its own answer. */
        const val EXTRA_REQUEST_ID = "com.termux.app.devkit.request_id"

        /** Path to the candidate apk, read by the app rather than by the caller. */
        const val EXTRA_APK = "com.termux.app.devkit.apk"

        /** Version code the caller believes the apk carries; verified, not trusted. */
        const val EXTRA_CLAIMED_VERSION_CODE = "com.termux.app.devkit.claimed_version_code"

        /** Signer the caller believes signed the apk; verified, not trusted. */
        const val EXTRA_CLAIMED_SIGNER = "com.termux.app.devkit.claimed_signer"

        /** ABIs the caller believes the apk carries; verified, not trusted. */
        const val EXTRA_CLAIMED_ABIS = "com.termux.app.devkit.claimed_abis"

        /** When true, stage the apk and hand the system its confirmation. Default false: verify only. */
        const val EXTRA_CONFIRM = "com.termux.app.devkit.confirm"
    }
}
