package com.termux.app.reliability

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.termux.R
import com.termux.app.activities.SettingsActivity
import com.termux.shared.android.PermissionUtils
import com.termux.shared.logger.Logger
import com.termux.shared.notification.NotificationUtils
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences

/** Posts and persists dismissals for the combined background-reliability warning. */
class ReliabilityWarningNotifier private constructor() {

    companion object {
        private const val LOG_TAG = "ReliabilityWarningNotifier"
        private const val REQUEST_CODE_CONTENT = 13430
        private const val REQUEST_CODE_DELETE = 13431

        @Volatile
        private var lastPostedKey: String? = null

        /**
         * Re-evaluates background-reliability state and posts, updates, or cancels the warning.
         *
         * This reads `/proc` and preferences and must be called off the main thread.
         */
        @JvmStatic
        @Synchronized
        @SuppressLint("BatteryLife", "MissingPermission")
        fun update(context: Context) {
            try {
                val appContext = context.applicationContext
                val preferences = TermuxAppSharedPreferences.build(appContext, true)
                if (preferences == null) {
                    Logger.logWarn(LOG_TAG, "Cannot update reliability warning because preferences are unavailable")
                    cancel(appContext)
                    return
                }

                val decisions = evaluate(appContext, preferences)
                if (!decisions.warnBattery && !decisions.warnPhantom) {
                    cancel(appContext)
                    return
                }

                if (!PermissionUtils.checkNotificationPermission(appContext)) {
                    Logger.logDebug(LOG_TAG, "Not posting reliability warning because notification permission is unavailable")
                    cancel(appContext)
                    return
                }

                val postedKey = "battery=${decisions.warnBattery};phantom=${decisions.warnPhantom}"
                if (lastPostedKey == postedKey) return

                val notificationText = appContext.getString(
                    when {
                        decisions.warnBattery && decisions.warnPhantom -> R.string.termux_reliability_notification_both_text
                        decisions.warnBattery -> R.string.termux_reliability_notification_battery_text
                        else -> R.string.termux_reliability_notification_phantom_text
                    }
                )

                NotificationUtils.setupNotificationChannel(
                    appContext,
                    TermuxConstants.TERMUX_RELIABILITY_NOTIFICATION_CHANNEL_ID,
                    TermuxConstants.TERMUX_RELIABILITY_NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                )

                val requestBatteryExemptionIntent = Intent(appContext, SettingsActivity::class.java).setAction(
                    BatteryOptimizationRequester.ACTION_REQUEST_EXEMPTION
                )
                val openSettingsIntent = Intent(appContext, SettingsActivity::class.java)
                val contentIntent = PendingIntent.getActivity(
                    appContext,
                    REQUEST_CODE_CONTENT,
                    // Only the battery warning can be acted on with a single tap, and its dialog belongs
                    // in front of the user. The phantom process limit needs the adb recovery, which the
                    // background reliability settings page explains, so that is what is opened instead of
                    // a battery dialog that could not help.
                    if (decisions.warnBattery) requestBatteryExemptionIntent else openSettingsIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val deleteIntent = PendingIntent.getBroadcast(
                    appContext,
                    REQUEST_CODE_DELETE,
                    Intent(appContext, ReliabilityWarningReceiver::class.java).setAction(
                        TermuxConstants.TERMUX_APP.ACTION_RELIABILITY_WARNING_DISMISSED
                    ),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val builder = NotificationUtils.geNotificationBuilder(
                    appContext,
                    TermuxConstants.TERMUX_RELIABILITY_NOTIFICATION_CHANNEL_ID,
                    Notification.PRIORITY_LOW,
                    appContext.getString(R.string.termux_reliability_notification_title),
                    notificationText,
                    notificationText,
                    contentIntent,
                    deleteIntent,
                    NotificationUtils.NOTIFICATION_MODE_SILENT
                ) ?: return

                builder.setShowWhen(false)
                builder.setSmallIcon(R.drawable.ic_service_notification)
                builder.setColor(0xFF607D8B.toInt())
                builder.setOngoing(false)
                builder.addAction(
                    android.R.drawable.ic_menu_manage,
                    appContext.getString(
                        if (decisions.warnBattery) {
                            R.string.termux_reliability_notification_action
                        } else {
                            R.string.termux_reliability_notification_action_open_settings
                        }
                    ),
                    contentIntent
                )

                NotificationUtils.getNotificationManager(appContext)?.notify(
                    TermuxConstants.TERMUX_RELIABILITY_NOTIFICATION_ID,
                    builder.build()
                )
                lastPostedKey = postedKey
                Logger.logDebug(LOG_TAG, "Posted reliability warning: $postedKey")
            } catch (e: Exception) {
                Logger.logError(LOG_TAG, "Failed to update reliability warning: ${e.message}")
            }
        }

        /**
         * Persists dismissal for each warning represented by the current state, then cancels the
         * combined notification. This performs I/O and must be called off the main thread.
         */
        @JvmStatic
        @Synchronized
        fun dismissAndPersist(context: Context) {
            try {
                val appContext = context.applicationContext
                val preferences = TermuxAppSharedPreferences.build(appContext, true) ?: return
                val decisions = evaluate(appContext, preferences)

                if (decisions.warnBattery) {
                    preferences.setBatteryOptimizationWarningDismissed(true)
                }
                if (decisions.warnPhantom) {
                    preferences.setPhantomProcessWarningDismissed(true)
                }

                Logger.logDebug(
                    LOG_TAG,
                    "Persisted reliability warning dismissal: battery=${decisions.warnBattery}; " +
                        "phantom=${decisions.warnPhantom}"
                )
                cancel(appContext)
            } catch (e: Exception) {
                Logger.logError(LOG_TAG, "Failed to persist reliability warning dismissal: ${e.message}")
            }
        }

        /** Cancels the combined background-reliability notification. */
        @JvmStatic
        @Synchronized
        fun cancel(context: Context) {
            NotificationUtils.getNotificationManager(context)
                ?.cancel(TermuxConstants.TERMUX_RELIABILITY_NOTIFICATION_ID)
            lastPostedKey = null
        }

        private fun evaluate(
            context: Context,
            preferences: TermuxAppSharedPreferences
        ): WarningDecisions {
            val isIgnoringBatteryOptimizations =
                PermissionUtils.checkIfBatteryOptimizationsDisabled(context)
            val warnBattery = BatteryOptimizationWarningPolicy.shouldWarn(
                isIgnoringBatteryOptimizations,
                preferences.isBatteryOptimizationWarningEnabled(),
                preferences.isBatteryOptimizationWarningDismissed()
            )

            val snapshot = PhantomProcessProbe.snapshot(context)
            val phantomLevel = PhantomProcessPolicy.evaluate(
                snapshot.maxPhantomProcesses,
                snapshot.ownProcessCount,
                preferences.isPhantomProcessWarningEnabled(),
                preferences.isPhantomProcessWarningDismissed()
            )
            return WarningDecisions(warnBattery, PhantomProcessPolicy.shouldWarn(phantomLevel))
        }

        private data class WarningDecisions(
            val warnBattery: Boolean,
            val warnPhantom: Boolean
        )
    }
}
