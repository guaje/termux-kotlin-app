package com.termux.app.devkit

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What this installed app actually is: version code, version name, signer fingerprint, whether it is
 * debuggable, the abi, the boot it was written in, and when.
 *
 * The reason a file is needed at all is that a script in the prefix cannot ask the package manager
 * anything - `pm` is refused to this uid - yet a ship must not begin unless the candidate APK is signed
 * by the same key that is installed and carries a higher version code. So the app records those facts
 * here and the tooling reads them, instead of each script guessing from a file name.
 *
 * There is a second, smaller file here: [userStoppedFile], the app's own account of *why* it stopped.
 * It used to travel as a broadcast flag to the supervisor APK, and the APK is gone. A file was kept
 * rather than the fact, because a fact only the living can broadcast is a fact nobody can read
 * afterwards, and because a marker on disk costs nothing while a heartbeat costs four broadcasts a
 * minute forever. Whoever supervises next - the client, most likely - reads this and does not have to
 * infer intent from silence, which is the mistake that made the app come back after `exit`.
 *
 * Written when the service starts, not only when something asks for it. That is a measured correction:
 * with the file produced only by `--scope devkit` and the ship gates, it went stale in the ordinary
 * course of events, and `termux-anchor --status` printed host facts for 2.5.3-alpha.18 while 2.5.3-alpha.20
 * was installed, carrying a boot id from before a reboot. Nothing was *wrong* - #43 made PREPARE ask the
 * live app before recording an attempt - but a file that is only right when somebody remembers to refresh
 * it is a trap for the next reader, and this project has already paid twice for exactly that shape of
 * staleness.
 *
 * Write is still temp-file-then-rename, because the file is read by a shell script while it can be being
 * replaced, and a half-written JSON is indistinguishable from a hostile one to a gate that parses it.
 */
object DevKitHostFacts {

    private const val LOG_TAG = "DevKitHostFacts"

    /** The devkit's own state directory, shared with the reload and ship records. */
    private val statusDir = File("$TERMUX_PREFIX_DIR_PATH/var/lib/termux-kotlin/devkit")

    /** Where the facts land. Public so the writer and the readers cannot drift to different paths. */
    fun file(): File = File(statusDir, "host.json")

    /** The kernel's boot id, which changes on every reboot and is what makes a recorded fact time-bound. */
    fun readBootId(): String = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrDefault("unknown")

    /**
     * Record the facts and return the signer fingerprint, or null when the platform would not supply a
     * signing certificate. Nothing modern reaches that path, and it is not worth pretending otherwise
     * about a platform that added `apkContentsSigners` precisely because rot happens.
     */
    fun write(context: Context): String? = try {
        val facts = render(context)
        if (facts == null) null else if (store(facts.first)) facts.second else null
    } catch (e: Exception) {
        Logger.logError(LOG_TAG, "could not write host facts: ${e.message ?: e.javaClass.simpleName}")
        null
    }

    /** The document to publish, and the fingerprint in it; null when the platform gave no certificate. */
    private fun render(context: Context): Pair<String, String>? {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES
        )
        val certificate = info.signingInfo?.apkContentsSigners?.firstOrNull()
            ?: info.signingInfo?.signingCertificateHistory?.firstOrNull()
            ?: return null
        // DevKitDigest rather than "%02x".format(): the format honours the default locale, and a
        // locale with non-ASCII digits would render a fingerprint that cannot match the same
        // bytes anywhere else.
        val signer = DevKitDigest.sha256Hex(certificate.toByteArray()) ?: error("no SHA-256 on this platform")
        val flags = info.applicationInfo?.flags ?: 0
        val debuggable = flags.and(ApplicationInfo.FLAG_DEBUGGABLE) != 0

        val json = buildString {
            appendLine("{")
            appendLine("  " + quote("versionCode") + ": ${info.longVersionCode},")
            appendLine("  " + quote("versionName") + ": ${quote(info.versionName ?: "")},")
            appendLine("  " + quote("signerSha256") + ": ${quote(signer)},")
            appendLine("  " + quote("debuggable") + ": $debuggable,")
            appendLine("  " + quote("abi") + ": ${quote(android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "")},")
            appendLine("  " + quote("bootId") + ": ${quote(readBootId())},")
            appendLine("  " + quote("writtenAt") + ": ${quote(TIMESTAMP.format(Date()))}")
            append("}")
        }
        return json to signer
    }

    /** temp file then rename, and false if neither worked. */
    private fun store(json: String): Boolean {
        if (!statusDir.isDirectory && !statusDir.mkdirs()) {
            Logger.logWarn(LOG_TAG, "could not create $statusDir, so host facts were not written")
            return false
        }
        file().parentFile?.let { it.isDirectory || it.mkdirs() }
        userStoppedFile().delete()  // a start means the user opened it again; see [userStoppedFile]
        val target = file()
        val temp = File(statusDir, target.name + ".tmp")
        return runCatching {
            temp.writeText(json)
            if (!temp.renameTo(target)) {
                target.writeText(json)
                temp.delete()
            }
        }.onFailure { Logger.logWarn(LOG_TAG, "host facts could not be written to $target: ${it.message}") }
            .isSuccess
    }

    /**
     * The app's own account that it was closed by the user rather than killed. Exists between a goodbye
     * and the next service start, and nothing else; deleted by [write], because a start is the user
     * having opened the app again and the claim does not survive that.
     *
     * Contains the boot id and the moment, so a reader can tell "the user closed it during this boot"
     * from "somebody closed it before the phone was restarted" - after a reboot, not closing it again is
     * not consent to start it.
     */
    fun userStoppedFile(): File = File(statusDir, "user-stopped")

    /** Record that the user closed this app on purpose. See [userStoppedFile] for how long that lasts. */
    fun markUserStopped(): Boolean {
        if (!statusDir.isDirectory && !statusDir.mkdirs()) return false
        val line = "${readBootId()} ${TIMESTAMP.format(Date())}"
        return runCatching { userStoppedFile().writeText(line) }
            .onFailure { Logger.logWarn(LOG_TAG, "could not record the user stop: ${it.message}") }
            .isSuccess
    }

    private val TIMESTAMP = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    /** Quote one string as JSON. Kept here because the whole point is that the output parses. */
    internal fun quote(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
