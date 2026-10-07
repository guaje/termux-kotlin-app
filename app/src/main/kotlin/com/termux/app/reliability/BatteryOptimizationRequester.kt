package com.termux.app.reliability

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.termux.R
import com.termux.shared.activity.ActivityUtils
import com.termux.shared.interact.ShareUtils
import com.termux.shared.logger.Logger

/** Opens public Android battery-optimization controls for explicit user requests. */
class BatteryOptimizationRequester private constructor() {

    companion object {
        private const val LOG_TAG = "BatteryOptimizationRequester"

        /** Internal action used by the notification to route an explicit request through SettingsActivity. */
        internal const val ACTION_REQUEST_EXEMPTION =
            "com.termux.app.reliability.action.REQUEST_BATTERY_OPTIMIZATION_EXEMPTION"

        /**
         * Requests a battery-optimization exemption for this app.
         *
         * If the direct request cannot be shown, the general battery-optimization settings screen is
         * tried. If neither screen can be shown, an ADB fallback command is copied to the clipboard.
         *
         * @return `true` if a system settings UI was shown, otherwise `false`.
         */
        @JvmStatic
        @SuppressLint("BatteryLife")
        fun requestExemption(context: Context): Boolean {
            val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addNewTaskFlagIfNeeded(context)
            }
            if (ActivityUtils.startActivity(context, requestIntent) == null) {
                Logger.logDebug(LOG_TAG, "Opened the battery optimization exemption request")
                return true
            }

            Logger.logWarn(LOG_TAG, "Direct battery optimization exemption request failed; opening settings")
            if (openBatteryOptimizationSettings(context)) return true

            copyAdbFallbackToClipboard(context)
            return false
        }

        /**
         * Opens the general Android battery-optimization settings screen without showing a modal request.
         *
         * @return `true` if the settings UI was shown, otherwise `false`.
         */
        @JvmStatic
        fun openBatteryOptimizationSettings(context: Context): Boolean {
            val settingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addNewTaskFlagIfNeeded(context)
            }
            val error = ActivityUtils.startActivity(context, settingsIntent)
            if (error != null) {
                Logger.logWarn(LOG_TAG, "Failed to open battery optimization settings")
                return false
            }

            Logger.logDebug(LOG_TAG, "Opened battery optimization settings")
            return true
        }

        /** Copies the package-specific ADB battery-optimization fallback command to the clipboard. */
        @JvmStatic
        fun copyAdbFallbackToClipboard(context: Context) {
            val command = context.getString(
                R.string.termux_reliability_battery_optimization_adb_command,
                context.packageName
            )
            ShareUtils.copyTextToClipboard(
                context,
                command,
                context.getString(R.string.termux_reliability_battery_optimization_request_failed_fallback)
            )
            Logger.logDebug(LOG_TAG, "Copied the battery optimization ADB fallback command")
        }

        private fun Intent.addNewTaskFlagIfNeeded(context: Context) {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
