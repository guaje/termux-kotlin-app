package com.termux.app.reliability

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.termux.shared.android.FeatureFlagUtils
import com.termux.shared.android.PhantomProcessUtils
import com.termux.shared.android.SettingsProviderUtils
import com.termux.shared.android.SettingsProviderUtils.SettingNamespace
import com.termux.shared.android.SettingsProviderUtils.SettingType
import java.io.File

/**
 * A snapshot of the device-wide phantom-process limit and Termux's own process contribution.
 *
 * The warning level is deliberately not part of it, since it additionally depends on the preferences of
 * the user, which [PhantomProcessPolicy.evaluate] is given by whoever wants to act on the measurements.
 *
 * @property maxPhantomProcesses The configured device-wide limit, or `null` when unavailable.
 * @property ownProcessCount The number of other processes with this app's real UID, or `null` when unavailable.
 */
data class PhantomProcessSnapshotUiState(
    val maxPhantomProcesses: Int?,
    val ownProcessCount: Int?
)

/** Reads Android phantom-process state without starting a process for `/proc` enumeration. */
object PhantomProcessProbe {

    /**
     * The AOSP default of the device-wide limit. An unreadable limit does not mean that phantom
     * processes are unlimited on this device, it means that the default applies, so warning about a
     * low limit must not silently switch itself off just because a regular app may not read the value.
     */
    private const val AOSP_DEFAULT_MAX_PHANTOM_PROCESSES = 32

    /**
     * How long the read limit is kept. The limit only changes through a settings write, but the value is
     * read whenever a warning is evaluated, so it is not asked for that often, while a limit the user
     * raised with adb still takes effect by itself after a short moment.
     */
    private const val CACHE_DURATION_MS = 5 * 60 * 1000L

    /** The dumpsys fallback needs privileged access and spawns a process, so its answer is cached. */
    @Volatile
    private var cachedMaxPhantomProcesses: Int? = null

    @Volatile
    private var cachedMaxPhantomProcessesTimestampMs: Long = 0

    /**
     * Reads the device-wide phantom-process limit.
     *
     * The `Settings.Global` value the documented recovery writes is read first, then the DeviceConfig
     * copy of it, and only then the enforced value, which needs the privileged `DUMP` permission and
     * runs a shell.
     */
    fun readMaxPhantomProcesses(context: Context): Int? {
        val nowMs = SystemClock.uptimeMillis()
        if (nowMs - cachedMaxPhantomProcessesTimestampMs < CACHE_DURATION_MS) {
            cachedMaxPhantomProcesses?.let { return it }
        } else {
            cachedMaxPhantomProcesses = null
        }

        val maxPhantomProcesses = readMaxPhantomProcessesWithoutCache(context) ?: return null
        cachedMaxPhantomProcesses = maxPhantomProcesses
        cachedMaxPhantomProcessesTimestampMs = nowMs
        return maxPhantomProcesses
    }

    private fun readMaxPhantomProcessesWithoutCache(context: Context): Int? {
        // Before Android 12 nothing is accounted as a phantom process and switching the monitoring off
        // turns the killer off entirely, so no limit is reported in either case and nothing is warned.
        if (!PhantomProcessUtils.isPhantomProcessKillingRelevant()) return null
        if (PhantomProcessUtils.getFeatureFlagMonitorPhantomProcsValueString(context) == FeatureFlagUtils.FeatureFlagValue.FALSE) return null

        // The value that `settings put global max_phantom_processes 32768` writes, so that the warning
        // disappears after the user applied the documented recovery.
        readSettingsValueAsInt(context, PhantomProcessUtils.KEY_MAX_PHANTOM_PROCESSES)?.let { return it }

        // The copy of the same value that DeviceConfig keeps, which is what ActivityManager applies.
        readSettingsValueAsInt(context, PhantomProcessUtils.DEVICE_CONFIG_SETTINGS_GLOBAL_KEY_MAX_PHANTOM_PROCESSES)?.let { return it }

        // The actually enforced value, which needs privileged access and a shell, so it is last.
        PhantomProcessUtils.getActivityManagerMaxPhantomProcesses(context)?.let { if (it != 0) return it }

        // Nothing could be read while phantom process killing is relevant and monitored, which means
        // that the platform default applies.
        return AOSP_DEFAULT_MAX_PHANTOM_PROCESSES
    }

    private fun readSettingsValueAsInt(context: Context, key: String): Int? {
        val value = SettingsProviderUtils.getSettingsValue(
            context,
            SettingNamespace.GLOBAL,
            SettingType.INT,
            key,
            null
        ) as? Int ?: return null

        // A value of 0 means that the setting is unset and carries no information.
        return if (value == 0) null else value
    }

    /** Forget the cached limit, so that the next read asks the platform again. */
    fun clearCache() {
        cachedMaxPhantomProcesses = null
        cachedMaxPhantomProcessesTimestampMs = 0
    }

    /**
     * Counts other thread-group leaders whose real UID matches this app by reading `/proc` directly.
     *
     * No command or subprocess is started. Returns `null` if the `/proc` directory cannot be read.
     */
    fun countOwnProcesses(): Int? {
        val procEntries = try {
            File("/proc").listFiles()
        } catch (_: Exception) {
            null
        } ?: return null
        val ownPid = Process.myPid()
        val ownUid = Process.myUid()
        var count = 0

        for (entry in procEntries) {
            val pid = entry.name.toIntOrNull() ?: continue
            if (pid == ownPid) continue

            val statusLines = try {
                File(entry, "status").readLines()
            } catch (_: Exception) {
                continue
            }

            val realUid = statusLines.firstOrNull { it.startsWith("Uid:") }
                ?.substringAfter(':')
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.firstOrNull()
                ?.toIntOrNull()
            val threadGroupId = statusLines.firstOrNull { it.startsWith("Tgid:") }
                ?.substringAfter(':')
                ?.trim()
                ?.toIntOrNull()

            if (realUid == ownUid && threadGroupId == pid) count++
        }

        return count
    }

    /**
     * Reads the phantom-process measurements.
     *
     * This performs file and settings I/O and must only be called off the main thread.
     */
    fun snapshot(context: Context): PhantomProcessSnapshotUiState {
        return PhantomProcessSnapshotUiState(
            maxPhantomProcesses = readMaxPhantomProcesses(context),
            ownProcessCount = countOwnProcesses()
        )
    }
}
