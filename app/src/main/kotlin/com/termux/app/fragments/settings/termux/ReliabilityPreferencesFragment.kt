package com.termux.app.fragments.settings.termux

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.Keep
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import com.termux.R
import com.termux.app.TermuxService
import com.termux.app.reliability.BatteryOptimizationRequester
import com.termux.app.reliability.PhantomProcessProbe
import com.termux.app.reliability.ReliabilityWarningNotifier
import com.termux.shared.android.PermissionUtils
import com.termux.shared.interact.ShareUtils
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences

/** Legacy preference screen for background-service and warning reliability controls. */
@Keep
class ReliabilityPreferencesFragment : PreferenceFragmentCompat() {

    /** Installs the shared-preference data store and configures dynamic state rows. */
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = context ?: return

        preferenceManager.preferenceDataStore = ReliabilityPreferencesDataStore.getInstance(context)
        setPreferencesFromResource(R.xml.termux_reliability_preferences, rootKey)

        configureBatteryOptimizationPreference(context)
        configurePhantomProcessesPreference(context)
    }

    /** Refreshes state summaries after returning from a system settings screen. */
    override fun onResume() {
        super.onResume()
        val context = context ?: return
        updateBatteryOptimizationSummary(context)
        // The user may have just run the recovery command from a shell while this screen was open, so
        // the limit is read again instead of served from what an earlier read found.
        PhantomProcessProbe.clearCache()
        updatePhantomProcessesSummary(context)
    }

    private fun configureBatteryOptimizationPreference(context: Context) {
        val preference = findPreference<Preference>("battery_optimization_state") ?: return
        preference.setOnPreferenceClickListener {
            if (PermissionUtils.checkIfBatteryOptimizationsDisabled(context)) {
                BatteryOptimizationRequester.openBatteryOptimizationSettings(context)
            } else {
                BatteryOptimizationRequester.requestExemption(context)
            }
            true
        }
    }

    private fun configurePhantomProcessesPreference(context: Context) {
        val preference = findPreference<Preference>("phantom_processes_state") ?: return
        preference.setOnPreferenceClickListener {
            ShareUtils.copyTextToClipboard(
                context,
                context.getString(R.string.termux_reliability_phantom_process_adb_command),
                context.getString(R.string.termux_reliability_copied_to_clipboard)
            )
            true
        }
    }

    private fun updateBatteryOptimizationSummary(context: Context) {
        val preference = findPreference<Preference>("battery_optimization_state") ?: return
        val appContext = context.applicationContext
        Thread {
            val stateRes = if (PermissionUtils.checkIfBatteryOptimizationsDisabled(appContext)) {
                R.string.termux_reliability_battery_optimization_state_exempted
            } else {
                R.string.termux_reliability_battery_optimization_state_not_exempted
            }
            val summary = appContext.getString(stateRes) + " " +
                appContext.getString(R.string.termux_reliability_battery_optimization_state_tap_to_change)
            runOnUiThreadIfAttached { preference.summary = summary }
        }.start()
    }

    private fun updatePhantomProcessesSummary(context: Context) {
        val preference = findPreference<Preference>("phantom_processes_state") ?: return
        val appContext = context.applicationContext
        Thread {
            // Reads /proc and the settings providers, which is why it does not run on the main thread.
            val snapshot = PhantomProcessProbe.snapshot(appContext)
            val summary = if (snapshot.maxPhantomProcesses != null && snapshot.ownProcessCount != null) {
                appContext.getString(
                    R.string.termux_reliability_phantom_processes_state_value,
                    snapshot.maxPhantomProcesses,
                    snapshot.ownProcessCount
                )
            } else {
                appContext.getString(R.string.termux_reliability_phantom_processes_state_unknown)
            }
            runOnUiThreadIfAttached { preference.summary = summary }
        }.start()
    }

    /**
     * Run [action] on the main thread, but only if the fragment is still attached. The summary updates
     * above finish whenever the disk and the settings providers answer, which can be after the user
     * left the screen.
     */
    private fun runOnUiThreadIfAttached(action: () -> Unit) {
        val activity = activity ?: return
        if (!isAdded) return
        activity.runOnUiThread { if (isAdded) action() }
    }
}

/** Preference data store backed by [TermuxAppSharedPreferences]. */
class ReliabilityPreferencesDataStore private constructor(context: Context) : PreferenceDataStore() {

    private val mContext: Context = context.applicationContext
    private val mPreferences: TermuxAppSharedPreferences? = TermuxAppSharedPreferences.build(context, true)

    /** Persists one of the four switches exposed by the reliability preference screen. */
    override fun putBoolean(key: String?, value: Boolean) {
        if (mPreferences == null || key == null) return

        when (key) {
            // The service writes this preference itself when it acquires or releases the locks, so the
            // request is delegated to it instead of only storing the value, otherwise a switch flipped
            // while the service is running would only take effect on its next start.
            "wake_lock_enabled" -> {
                mPreferences.setWakeLockEnabled(value)
                requestWakeLockStateFromService(value)
            }
            "restore_sessions_enabled" -> mPreferences.setRestoreSessionsEnabled(value)
            "battery_optimization_warning_enabled" -> {
                mPreferences.setBatteryOptimizationWarningEnabled(value)
                // Dismissing the warning only silences it, so re-enabling it must clear the dismissal
                // here, otherwise this switch could never bring the warning back.
                if (value) mPreferences.setBatteryOptimizationWarningDismissed(false)
                updateWarningAsync()
            }
            "phantom_process_warning_enabled" -> {
                mPreferences.setPhantomProcessWarningEnabled(value)
                if (value) mPreferences.setPhantomProcessWarningDismissed(false)
                updateWarningAsync()
            }
        }
    }

    /** Returns the persisted value for one of the four reliability switches. */
    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        if (mPreferences == null) return defValue

        return when (key) {
            "wake_lock_enabled" -> mPreferences.isWakeLockEnabled()
            "restore_sessions_enabled" -> mPreferences.isRestoreSessionsEnabled()
            "battery_optimization_warning_enabled" -> mPreferences.isBatteryOptimizationWarningEnabled()
            "phantom_process_warning_enabled" -> mPreferences.isPhantomProcessWarningEnabled()
            else -> defValue
        }
    }

    private fun updateWarningAsync() {
        Thread { ReliabilityWarningNotifier.update(mContext) }.start()
    }

    /**
     * Asks [TermuxService] to acquire or release the wake locks so that the switch applies to a
     * service that is already running. The service is started when it does not run yet, since wanting
     * the wake locks held means wanting the service to run.
     */
    private fun requestWakeLockStateFromService(value: Boolean) {
        try {
            val action = if (value) TERMUX_SERVICE.ACTION_WAKE_LOCK else TERMUX_SERVICE.ACTION_WAKE_UNLOCK
            mContext.startService(Intent(mContext, TermuxService::class.java).setAction(action))
        } catch (e: Exception) {
            // The value is stored either way, so the state still converges on the next service start.
            Logger.logDebug(TAG, "Failed to apply the wake lock switch to the running service: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ReliabilityPreferencesDataStore"

        @Volatile
        private var instance: ReliabilityPreferencesDataStore? = null

        /** Returns the process-wide reliability preference data store. */
        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): ReliabilityPreferencesDataStore {
            return instance ?: ReliabilityPreferencesDataStore(context).also { instance = it }
        }
    }
}
