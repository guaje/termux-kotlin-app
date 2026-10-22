package com.termux.anchor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * The one place the anchor learns that com.termux is alive.
 *
 * Authorisation is not done in here, and that is deliberate: `android:permission` on the receiver in
 * the manifest is checked by the platform before this code is entered, so an app that is not signed
 * with this package's certificate never reaches [onReceive] at all. Anything verified in here would
 * be a second, weaker check of a fact the platform already enforced, and a second check is a place to
 * be wrong.
 *
 * That is also why this receiver trusts its extras completely. A forged heartbeat is the one attack
 * that would switch the supervisor off rather than trigger it, and the signature permission is what
 * makes forging it require the release key.
 */
class HeartbeatReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != AnchorMessages.ACTION_HEARTBEAT) return

        val versionCode = intent.getLongExtra(AnchorMessages.EXTRA_VERSION_CODE, 0L)
        val bootId = intent.getStringExtra(AnchorMessages.EXTRA_BOOT_ID).orEmpty()
        val policy = intent.getIntExtra(
            AnchorMessages.EXTRA_POLICY,
            AnchorSupervisionPolicy.RestartPolicy.WIRE_ALWAYS
        )
        // Absent means "still running", not "unknown": the extra is only ever put on the beat that is sent
        // on the way out, so a build too old to know about it keeps the behaviour it always had.
        val userStopped = intent.getBooleanExtra(AnchorMessages.EXTRA_USER_STOPPED, false)
        AnchorState(context).recordHeartbeat(System.currentTimeMillis(), versionCode, bootId, policy, userStopped)

        scheduleNextCheck(context)
    }

    /**
     * Make sure the loop that reads the state exists.
     *
     * Not the only path that starts it - the tick reschedules itself, and BOOT_COMPLETED and the status
     * screen start it too - but the one that revives supervision after the service died while the app
     * kept reporting, which is otherwise a heartbeat arriving into nothing forever.
     *
     * This may be refused. The anchor is in the background whenever it is not the app on screen, and a
     * background application may not start a foreground service on Android 8+; the platform says so by
     * throwing here rather than by dropping the start quietly. It costs little: the heartbeat is already
     * on disk by then, and the loop that a boot or the status screen started reads it. A refused start
     * therefore delays a reaction instead of losing a fact, which is why this is logged at info level
     * rather than treated as a failure of the heartbeat.
     */
    private fun scheduleNextCheck(context: Context) {
        val loop = Intent(context, AnchorSupervisorService::class.java)
        runCatching { ContextCompat.startForegroundService(context, loop) }
            .onFailure { Log.i(TAG, "heartbeat recorded, supervisor loop not started: ${it.message}") }
    }

    private companion object {
        private const val TAG = "AnchorHeartbeat"
    }
}
