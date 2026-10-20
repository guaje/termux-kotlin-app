package com.termux.anchor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Starts the supervisor when the device does.
 *
 * A reboot is one of the two ways the app stays down that the anchor can actually see - the other being
 * a package replace - and BOOT_COMPLETED is the only signal an app gets for it. The anchor survives the
 * reboot precisely because it is not in com.termux's uid: the kill a package replace performs is
 * uid-scoped, which is the measurement (MEMORY.md §16C: a setsid process reparented to PID 1 was still
 * swept within 1-2 s, ~5 s of downtime before the app returned) that made the anchor a separate
 * package instead of a detached helper inside the app.
 *
 * It does not restart Termux from here. The app starts itself on boot if the user asked it to, and the
 * anchor's job is the case where that did not happen, which it can only judge once heartbeats have had
 * time to either arrive or not.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val bootId = AnchorState.readBootId()
        AnchorState(context).recordBoot(bootId)

        val supervisor = Intent(context, AnchorSupervisorService::class.java)
        try {
            ContextCompat.startForegroundService(context, supervisor)
        } catch (failure: Exception) {
            // Wrapped exactly like SystemEventReceiver wraps its own boot-time start in the app. A
            // foreground service may not be started by an application the platform counts as being in
            // the background, that rule is enforced by throwing rather than by dropping the start, and
            // vendor ROMs tighten it further while the device is still booting. Nothing is lost when it
            // happens: the boot is already recorded above, and the loop is started again by the first
            // heartbeat accepted and by the status screen, so this is a note and not a failure.
            //
            // It still has to be caught. An exception leaving a BroadcastReceiver on the boot path is a
            // crash dialog the user dismisses once and never opens again, and a supervisor nobody opens
            // is a supervisor nobody can enable.
            Log.w(TAG, "boot recorded on $bootId, supervisor could not be started: ${failure.message}")
        }
    }

    private companion object {
        private const val TAG = "AnchorBoot"
    }
}
