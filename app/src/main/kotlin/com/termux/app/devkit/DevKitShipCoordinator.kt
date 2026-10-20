package com.termux.app.devkit

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import com.termux.BuildConfig
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile

/**
 * Reads a candidate APK, decides whether it may replace the running app, and - only when told to -
 * hands it to the system installer.
 *
 * The reason this half exists at all is that a script in the prefix cannot ask the package manager
 * anything. `termux-ship` is handed a file path and a version code by a human and can check neither:
 * Termux has no `aapt`, and `pm` is refused to this uid. This coordinator can, because it is the app,
 * so it re-reads the candidate from disk and rules on what it finds rather than on what it was told
 * ([DevKitShipPolicy]). A claim that disagrees with the file is refused out loud.
 *
 * Verification is the default and has no side effects. Actual installation happens only with
 * `confirm`, and even then it goes through the system's own confirmation flow: this package holds no
 * `INSTALL_PACKAGES` permission, and asking for one so that a phone could be re-flashed from a
 * background receiver would be the wrong trade.
 */
class DevKitShipCoordinator(private val context: Context) {

    /** What this endpoint did, and what it says it did. */
    data class Report(
        val accepted: Boolean,
        val action: String,
        val reason: String,
        val candidate: DevKitShipPolicy.Candidate?,
        val sessionId: Long?
    )

    private val statusDir = File("$TERMUX_PREFIX_DIR_PATH/var/lib/termux-kotlin/devkit")
    private val statusFile = File(statusDir, "last-ship.json")
    private val resultFile = File(statusDir, "last-ship-result.json")
    private val generationFile = File(statusDir, "ship-endpoint-generation")

    fun ship(requestId: String, apkPath: String, claims: DevKitShipPolicy.Claims, confirm: Boolean): Report {
        val installed = installedFacts()
        val candidate = inspect(apkPath)
        val report = verdict(apkPath, installed, candidate, claims, confirm)
        writeStatus(requestId, report, installed, claims)
        return report
    }

    /**
     * What to do about a candidate, before any of it has been written down.
     */
    private fun verdict(
        apkPath: String,
        installed: DevKitShipPolicy.Installed?,
        candidate: DevKitShipPolicy.Candidate?,
        claims: DevKitShipPolicy.Claims,
        confirm: Boolean
    ): Report = when {
        installed == null -> Report(
            false,
            "refused",
            "the package manager would not describe this app to itself",
            candidate,
            null
        )

        candidate == null -> Report(false, "refused", "no readable apk at $apkPath", null, null)

        else -> verdictOnFacts(installed, candidate, claims, confirm)
    }

    /**
     * Verification is the whole behaviour unless [confirm] is set. That default is deliberate: staging
     * hands the user a confirmation dialog they did not ask for, and an unexpected "install this app?"
     * prompt from a background tool is worse than no answer at all.
     */
    private fun verdictOnFacts(
        installed: DevKitShipPolicy.Installed,
        candidate: DevKitShipPolicy.Candidate,
        claims: DevKitShipPolicy.Claims,
        confirm: Boolean
    ): Report {
        val decision = DevKitShipPolicy.decide(installed, candidate, claims)
        if (!decision.accepted) {
            Logger.logWarn(LOG_TAG, "ship refused: ${decision.reason}")
        }
        return when {
            !decision.accepted -> Report(false, "refused", decision.reason, candidate, null)
            !confirm -> Report(true, "verified", decision.reason, candidate, null)
            else -> stage(candidate, decision.reason)
        }
    }

    /**
     * Whether this app currently holds the right to install without asking.
     *
     * Recorded, never required. Holding that right is what makes a commit *silent*, and a non-system
     * app is not allowed to replace itself silently: on the handset, with the right granted through
     * `appops`, commit() came back STATUS_FAILURE with no message in under a tenth of a second.
     * Without it the platform routes the same session through its own confirmation dialog. So the
     * presence of the right decides which path the platform takes, and refusing here would rule out
     * the only path that can work.
     */
    private fun mayInstallWithoutAsking(): Boolean = runCatching {
        context.packageManager.canRequestPackageInstalls()
    }.getOrDefault(false)

    /**
     * Copy the apk into a session and commit it with an IntentSender, which is the path an
     * unprivileged installer is allowed to use: the system puts the confirmation in front of the user
     * and reports the outcome back through [DevKitShipReceiver].
     *
     * The reported state is `confirming`, never `installed`. Whether anything was replaced is decided
     * afterwards by `termux-ship --recover`, from the version code the app reports when it next
     * starts. Anything else would be a claim about an event that has not happened yet.
     */
    private fun stage(candidate: DevKitShipPolicy.Candidate, reason: String): Report {
        val file = File(candidate.path)
        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setSize(candidate.sizeBytes)
            // No setAppPackageName(): the package comes from the apk itself. Naming it here asks the
            // platform to treat the session as belonging to an existing package, which is the
            // install-existing and staged-install path, and it is not what a full install is.
            claimUpdateOwnership(params)
            requireUserAction(params)

            // The platform answers an Int session id. It is carried here as a Long so that "no
            // session was opened" can be written as null rather than as a plausible-looking 0.
            val sessionId: Long = installer.createSession(params).toLong()
            installer.openSession(sessionId.toInt()).use { session: PackageInstaller.Session ->
                FileInputStream(file).use { input: InputStream ->
                    val output: OutputStream = session.openWrite(APK_ENTRY, 0L, candidate.sizeBytes)
                    try {
                        input.copyTo(output)
                        // fsync, not flush: the entry has to reach stable storage before commit, and
                        // Session exposes no flush() on modern SDKs at all.
                        session.fsync(output)
                    } finally {
                        // The stream must be closed before commit, or the session is committed with a
                        // half-written entry.
                        output.close()
                    }
                }
                session.commit(pendingResult().intentSender)
            }
            val detail = "$reason; copied into installer session $sessionId, which the platform " +
                "applies only after showing you its own dialog; nothing here is installed until that " +
                "dialog is answered, and this process group dies if it is"
            Report(true, "confirming", detail, candidate, sessionId)
        } catch (e: Exception) {
            val detail = "verification passed but staging failed: ${e.message ?: e.javaClass.simpleName}"
            Logger.logError(LOG_TAG, detail)
            Report(false, "refused", detail, candidate, null)
        }
    }

    private fun pendingResult(): PendingIntent {
        val intent = Intent(DevKitShipReceiver.ACTION_SHIP_RESULT).setPackage(context.packageName)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    /** What the running app actually is, straight from the package manager. */
    private fun installedFacts(): DevKitShipPolicy.Installed? {
        return try {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            DevKitShipPolicy.Installed(
                packageName = context.packageName,
                versionCode = info.longVersionCode,
                signerSha256 = certificateSha256(info.signingInfo?.apkContentsSigners),
                abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: ""
            )
        } catch (e: Exception) {
            Logger.logError(LOG_TAG, "could not describe this package: ${e.message}")
            null
        }
    }

    /**
     * What the candidate file actually contains.
     *
     * Note this reads the file, not the caller's description of it. `getPackageArchiveInfo` answers
     * null for anything it cannot parse, which is how a truncated transfer or a text file named
     * `.apk` is turned away before a session is ever opened for it.
     */
    private fun inspect(apkPath: String): DevKitShipPolicy.Candidate? {
        val file = File(apkPath)
        if (!file.isFile || !file.canRead()) {
            return null
        }
        return runCatching { archiveOf(file) }
            .onFailure { Logger.logError(LOG_TAG, "could not read $apkPath: ${it.message}") }
            .getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun archiveOf(file: File): DevKitShipPolicy.Candidate? {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: return null
        val signatures = info.signingInfo?.apkContentsSigners ?: info.signatures
        return DevKitShipPolicy.Candidate(
                path = file.absolutePath,
                packageName = info.packageName,
                versionCode = info.longVersionCode,
                versionName = info.versionName ?: "",
                signerSha256 = certificateSha256(signatures),
            nativeAbis = nativeAbis(file),
            sizeBytes = file.length()
        )
    }

    /**
     * ABIs the apk ships native libraries for, read from the zip entries rather than assumed.
     *
     * Empty is meaningful and not an error: it means the build carries no native code.
     */
    private fun nativeAbis(file: File): List<String> = runCatching {
        ZipFile(file).use { zip ->
            zip.entries().asSequence()
                .mapNotNull { entry -> LIB_ENTRY.matchEntire(entry.name)?.groupValues?.get(1) }
                .distinct()
                .sorted()
                .toList()
        }
    }.getOrDefault(emptyList())

    private fun certificateSha256(signatures: Array<android.content.pm.Signature>?): String? =
        signatures?.firstOrNull()?.let { DevKitDigest.sha256Hex(it.toByteArray()) }

    /**
     * Record what the system installer reported, for `--recover` and for whoever is debugging at 1am.
     *
     * `EXTRA_STATUS` alone is worthless on its own: a self-replacement refusal arrives as status 1
     * with an empty message. The platform also supplies the install error code and the legacy
     * `INSTALL_FAILED_*` value, and those are the numbers that actually identify the cause.
     */
    fun recordInstallerResult(intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        // The framework's EXTRA_LEGACY_STATUS constant is not public API, but the key it names is an
        // ordinary bundle string, and the value is the number that actually identifies the failure.
        val legacyStatus = runCatching {
            intent.extras?.getInt(KEY_LEGACY_STATUS) ?: 0
        }.getOrDefault(0)
        val succeeded = status == PackageInstaller.STATUS_SUCCESS
        val rawExtras = describeExtras(intent.extras)
        val session = if (sessionId >= 0) describeSession(sessionId) else null
        // A real installer result always carries EXTRA_STATUS. When it does not, whatever arrived is
        // not a verdict, and saying "failed" would be as wrong as saying "installed".
        val verdict = if (intent.hasExtra(PackageInstaller.EXTRA_STATUS)) {
            "status-$status"
        } else {
            "absent"
        }
        val dialog = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) consentDialog(intent) else null
        val dialogShown = dialog?.let { showConsentDialog(it) } ?: false
        val json = buildString {
            appendLine("{")
            appendLine("  " + quote("verdict") + ": ${quote(verdict)},")
            appendLine("  " + quote("status") + ": $status,")
            appendLine("  " + quote("succeeded") + ": $succeeded,")
            appendLine("  " + quote("message") + ": ${quote(message ?: "")},")
            appendLine("  " + quote("legacyStatus") + ": $legacyStatus,")
            appendLine("  " + quote("sessionId") + ": $sessionId,")
            appendLine("  " + quote("dialogShown") + ": $dialogShown,")
            appendLine("  " + quote("rawExtras") + ": ${quote(rawExtras)},")
            appendLine("  " + quote("session") + ": ${session ?: "null"},")
            appendLine("  " + quote("buildType") + ": ${quote(BuildConfig.BUILD_TYPE)},")
            append("  " + quote("at") + ": " + quote(ISO_8601.format(Date())))
            append("}")
        }
        writeAtomically(resultFile, json)
        Logger.logInfo(
            LOG_TAG,
            "installer reported status $status (succeeded=$succeeded, legacyStatus=$legacyStatus, " +
                "dialogShown=$dialogShown): $message"
        )
    }

    /**
     * Every extra the installer actually put on the result, as text.
     *
     * This exists because a result that looked like "status 1, no message" turned out to be the
     * defaults this class substitutes when the extras are missing, which is a very different fact and
     * one that is invisible unless the raw contents are kept.
     */
    private fun describeExtras(extras: Bundle?): String = when {
        extras == null -> "(no extras)"
        extras.isEmpty -> "(empty)"
        else -> extras.keySet().sorted().joinToString(", ") { key ->
            val value = runCatching { extras.get(key)?.toString() }.getOrDefault("?") ?: "?"
            "$key=${value.take(60)}"
        }.take(600)
    }


    /**
     * What the platform still says about the session, if it kept it.
     *
     * SessionInfo carries a human-readable message that the result broadcast often leaves out, so this
     * is usually the only place the real reason appears.
     */
    private fun describeSession(sessionId: Int): String? {
        val info = runCatching {
            context.packageManager.packageInstaller.getSessionInfo(sessionId)
        }.getOrNull() ?: return null
        return buildString {
            append("{ ")
            append("mode=").append(info.mode)
            append(", size=").append(info.size)
            append(", installer=").append(info.installerPackageName ?: "null")
            append(", installerUid=").append(info.installerUid)
            append(", app=").append(info.appPackageName ?: "null")
            append(", requireUserAction=").append(info.requireUserAction)
            append(", active=").append(info.isActive)
            append(", committed=").append(info.isCommitted)
            append(", sealed=").append(info.isSealed)
            append(", staged=").append(info.isStaged)
            append(", stagedFailed=").append(info.isStagedSessionFailed)
            append(", stagedError=").append(info.stagedSessionErrorCode)
            append(", stagedMessage=").append(info.stagedSessionErrorMessage?.take(120) ?: "null")
            append(" }")
        }
    }


    /**
     * The confirmation activity the platform wants shown, when it wants one shown.
     *
     * A caller without the install right is not shown anything by the platform on its own: the result
     * arrives as STATUS_PENDING_USER_ACTION with the dialog intent attached, and starting it is the
     * caller's job. An implementation that only logs the status stages a session that then sits there
     * invisibly forever, which is what happened here.
     */
    @Suppress("DEPRECATION")
    private fun consentDialog(intent: Intent): Intent? = runCatching {
        intent.extras?.getParcelable<Intent>(Intent.EXTRA_INTENT)
    }.getOrNull()

    /**
     * Tell the platform that this app is updating itself.
     *
     * Without this the session looks like one app installing over another, and the platform is then
     * free to refuse it on the updating app's behalf - which is what happened: commit() answered
     * STATUS_FAILURE immediately. Claiming ownership is also what lets this app keep control of the
     * update flow instead of being killed at an arbitrary moment.
     */
    private fun claimUpdateOwnership(params: PackageInstaller.SessionParams) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { params.setRequestUpdateOwnership(true) }
                .onFailure { Logger.logWarn(LOG_TAG, "could not claim update ownership: ${it.message}") }
        }
    }

    /**
     * Ask for the user's confirmation rather than leaving it unspecified.
     *
     * This app is not a system installer, so nothing can be installed here without a human agreeing to
     * it. Leaving the requirement unspecified let the platform pick the silent path and then fail on
     * it; asking explicitly makes the outcome the dialog, whose intent the result handler starts.
     */
    private fun requireUserAction(params: PackageInstaller.SessionParams) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }.onFailure { Logger.logWarn(LOG_TAG, "could not require user action: ${it.message}") }
        }
    }

    /** Start the platform's dialog. It needs a task, and this only works while Termux is visible. */
    private fun showConsentDialog(dialog: Intent): Boolean = runCatching {
        dialog.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(dialog)
    }.isSuccess

    private fun writeStatus(
        requestId: String,
        report: Report,
        installed: DevKitShipPolicy.Installed?,
        claims: DevKitShipPolicy.Claims
    ) {
        val json = buildString {
            appendLine("{")
            appendLine("  " + quote("requestId") + ": " + quote(requestId) + ",")
            appendLine("  " + quote("generation") + ": ${nextGeneration()},")
            appendLine("  " + quote("buildType") + ": " + quote(BuildConfig.BUILD_TYPE) + ",")
            appendLine("  " + quote("accepted") + ": ${report.accepted},")
            // Which path the platform will take: with the right it installs without asking (and a
            // non-system app may not replace itself that way), without it the user must confirm.
            appendLine("  " + quote("silentInstallRight") + ": ${mayInstallWithoutAsking()},")
            appendLine("  " + quote("action") + ": " + quote(report.action) + ",")
            appendLine("  " + quote("reason") + ": " + quote(report.reason) + ",")
            appendLine("  " + quote("at") + ": " + quote(ISO_8601.format(Date())) + ",")
            appendLine("  " + quote("installed") + ": " + installedJson(installed) + ",")
            appendLine("  " + quote("claimed") + ": " + claimsJson(claims) + ",")
            append("  " + quote("apk") + ": " + candidateJson(report.candidate, report.sessionId))
            append("}")
        }
        writeAtomically(statusFile, json)
    }

    private fun installedJson(installed: DevKitShipPolicy.Installed?): String {
        if (installed == null) {
            return "null"
        }
        return "{ ${quote("versionCode")}: ${installed.versionCode}, " +
            "${quote("signerSha256")}: ${quote(installed.signerSha256 ?: "")}, " +
            "${quote("abi")}: ${quote(installed.abi)} }"
    }

    private fun claimsJson(claims: DevKitShipPolicy.Claims): String {
        val version = claims.versionCode?.toString() ?: "null"
        return "{ ${quote("versionCode")}: $version, " +
            "${quote("signerSha256")}: ${quote(claims.signerSha256 ?: "")}, " +
            "${quote("abis")}: ${claims.abis.joinToString(prefix = "[", postfix = "]") { quote(it) }} }"
    }

    private fun candidateJson(candidate: DevKitShipPolicy.Candidate?, sessionId: Long?): String {
        if (candidate == null) {
            return "null"
        }
        val session = sessionId?.toString() ?: "null"
        return "{ ${quote("path")}: ${quote(candidate.path)}, " +
            "${quote("packageName")}: ${quote(candidate.packageName ?: "")}, " +
            "${quote("versionCode")}: ${candidate.versionCode}, " +
            "${quote("versionName")}: ${quote(candidate.versionName)}, " +
            "${quote("signerSha256")}: ${quote(candidate.signerSha256 ?: "")}, " +
            "${quote("nativeAbis")}: ${candidate.nativeAbis.joinToString(prefix = "[", postfix = "]") { quote(it) }}, " +
            "${quote("sizeBytes")}: ${candidate.sizeBytes}, " +
            "${quote("sessionId")}: $session }"
    }

    /**
     * Temp file plus rename, so a script polling this path sees either the previous report or the new
     * one and never half of either. Same discipline as the reload status file.
     */
    private fun writeAtomically(target: File, json: String) {
        try {
            if (!statusDir.isDirectory) {
                statusDir.mkdirs()
            }
            val temp = File(statusDir, target.name + ".tmp")
            temp.writeText(json)
            if (!temp.renameTo(target)) {
                target.writeText(json)
                temp.delete()
            }
        } catch (e: Exception) {
            Logger.logError(LOG_TAG, "could not write ${target.absolutePath}: ${e.message}")
        }
    }

    private fun nextGeneration(): Long {
        val previous = runCatching { generationFile.readText().trim().toLong() }.getOrNull()
            ?: ENDPOINT_GENERATION.get()
        val next = previous + 1L
        ENDPOINT_GENERATION.set(next)
        runCatching { generationFile.writeText(next.toString()) }
        return next
    }

    private fun quote(value: String): String = "\"${escape(value)}\""

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val LOG_TAG = "DevKitShipCoordinator"
        private const val APK_ENTRY = "base.apk"
        private const val REQUEST_CODE = 0x5DB1

        /** The key behind the framework's non-public EXTRA_LEGACY_STATUS constant. */
        private const val KEY_LEGACY_STATUS = "android.content.pm.extra.LEGACY_STATUS"

        private val LIB_ENTRY = Regex("lib/([^/]+)/.+")

        /** Kept in memory so reports stay monotonic even when the file is unwritable. */
        private val ENDPOINT_GENERATION = AtomicLong(0)

        private val ISO_8601 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    }
}
