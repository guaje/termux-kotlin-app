package com.termux.app.reliability

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants

/** Receives the user's swipe dismissal of the background-reliability warning. */
class ReliabilityWarningReceiver : BroadcastReceiver() {

    /** Persists warning dismissal without performing preference or `/proc` I/O on the main thread. */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TermuxConstants.TERMUX_APP.ACTION_RELIABILITY_WARNING_DISMISSED) {
            Logger.logWarn(LOG_TAG, "Ignoring unexpected action: ${intent.action}")
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                ReliabilityWarningNotifier.dismissAndPersist(appContext)
            } catch (e: Exception) {
                Logger.logError(LOG_TAG, "Failed to dismiss reliability warning: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    companion object {
        private const val LOG_TAG = "ReliabilityWarningReceiver"
    }
}
