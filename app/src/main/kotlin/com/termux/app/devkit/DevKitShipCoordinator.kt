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
            !mayInstallPackages() -> Report(false, "refused", NEED_INSTALL_RIGHT, candidate, null)
            else -> stage(candidate, decision.reason)
        }
    }

    /**
     * Whether Android will let this app start an install at all.
     *
     * This gate was added on a guess, removed on a better guess, and put back because the handset
     * settled it. With the right, staging produces the platform's "Do you want to update this app?"
     * dialog and the update goes through. Without it the session commits, reports
     * STATUS_PENDING_USER_ACTION, an installer component resolves, startActivity() returns without
     * complaining - and nothing ever appears on screen, because the platform's installer will not run a
     * confirmation flow for an app that may not install packages. The silent failure is the problem: the
     * honest answer is to refuse up front and say which build and setting are needed.
     */
    private fun mayInstallPackages(): Boolean = runCatching {
        context.packageManager.canRequestPackageInstalls()
    }.getOrDefault(false)

    /** Which component Android will actually show the confirmation in, for the log and the status file. */
    private fun consentComponent(dialog: Intent): String =
        runCatching {
            dialog.resolveActivity(context.packageManager)?.flattenToShortString() ?: "no installer resolves this"
        }.getOrElse { error -> "unresolvable (${error.javaClass.simpleName})" }

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
            // Named for the platform's observers. This was removed once already on a guess that it was
            // pushing the session onto the wrong code path; the documented purpose is narrower ("provide
            // this value when known, so that observers can communicate installing apps to users") and
            // removing it is what made the platform's own observer log "empty pkg name true".
            params.setAppPackageName(candidate.packageName)
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

    /**
     * The PendingIntent the installer reports its verdict through, which must be mutable.
     *
     * This single flag caused a whole feature to be misdiagnosed. The platform does not hand you a
     * finished result Intent: it takes the intent inside this PendingIntent and *fills in* EXTRA_STATUS,
     * EXTRA_STATUS_MESSAGE and EXTRA_SESSION_ID as it sends. Fill-in values are ignored for an immutable
     * PendingIntent - "any additional intent argument passed to the send methods to fill in unpopulated
     * properties of the intent will be ignored" - and commit() only refuses an immutable statusReceiver
     * outright when the caller targets SDK 35 or higher. This app targets 28, so there was no exception,
     * no extras and no dialog; every answer arrived stripped to nothing and the endpoint read its own
     * getIntExtra defaults as if the platform had spoken. AOSP's own installer tests use FLAG_MUTABLE for
     * precisely this receiver.
     */
    private fun pendingResult(): PendingIntent {
        val intent = Intent(DevKitShipReceiver.ACTION_SHIP_RESULT).setPackage(context.packageName)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        // Below API 31 mutability is not a flag to set: an unspecified PendingIntent is mutable already.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
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
        // Named for what it proves and nothing more: that the intent was handed over without throwing.
        // Whether a dialog actually appeared is a separate question this cannot answer from here, which
        // is exactly the confusion that cost the investigation its waymark.
        val consentIntentStarted = dialog?.let { showConsentDialog(it) } ?: false
        val json = buildString {
            appendLine("{")
            appendLine("  " + quote("verdict") + ": ${quote(verdict)},")
            appendLine("  " + quote("status") + ": $status,")
            appendLine("  " + quote("succeeded") + ": $succeeded,")
            appendLine("  " + quote("message") + ": ${quote(message ?: "")},")
            appendLine("  " + quote("legacyStatus") + ": $legacyStatus,")
            appendLine("  " + quote("sessionId") + ": $sessionId,")
            appendLine("  " + quote("consentIntentStarted") + ": $consentIntentStarted,")
            appendLine("  " + quote("consentComponent") + ": ${quote(dialog?.let { consentComponent(it) } ?: "none")},")
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
                "consentIntentStarted=$consentIntentStarted): $message"
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
    /**
     * What the platform still says about the session, if it kept it.
     *
     * Everything in here is wrapped because it is only ever called to explain a failure: on the handset
     * this function threw "Session is not marked as staged" out of isStagedSessionFailed - the staged
     * accessors are illegal on an ordinary session - and took the receiver down with it, deleting the
     * one answer that had finally arrived. A diagnostic that can kill the thing it observes is worse than
     * no diagnostic.
     */
    private fun describeSession(sessionId: Int): String? = runCatching {
        val info = context.packageManager.packageInstaller.getSessionInfo(sessionId) ?: return null
        buildString {
            append("{ mode=").append(info.mode)
            append(", size=").append(info.size)
            append(", installer=").append(info.installerPackageName ?: "null")
            append(", app=").append(info.appPackageName ?: "null")
            append(", requireUserAction=").append(info.requireUserAction)
            append(", active=").append(info.isActive)
            append(", committed=").append(info.isCommitted)
            append(", sealed=").append(info.isSealed)
            append(", staged=").append(info.isStaged)
            // These four are only legal on a staged session; ask before reading them.
            if (info.isStaged) {
                append(", stagedReady=").append(info.isStagedSessionReady)
                append(", stagedActive=").append(info.isStagedSessionActive)
                append(", stagedFailed=").append(info.isStagedSessionFailed)
                append(", stagedError=").append(info.stagedSessionErrorMessage?.take(120) ?: "null")
            }
            append(" }")
        }
    }.getOrElse { error -> "unreadable(${error.javaClass.simpleName}: ${error.message?.take(120)})" }



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

    /*
     * There used to be a setRequestUpdateOwnership(true) call here. Its javadoc makes it a lock placed
     * on a package for the benefit of *future* installs, not a privilege for the caller: "The update
     * ownership enforcement can only be enabled on initial installation. Set this to true on package
     * update is a no-op", and enabling it requires the ENFORCE_UPDATE_OWNERSHIP permission this app does
     * not hold. On a self-update it could never have done anything, so it is gone rather than kept as
     * decoration.
     */

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
            // Whether Android will let this app install at all. Without it no confirmation dialog is
            // ever shown, which is why --confirm refuses rather than staging into silence.
            appendLine("  " + quote("installRight") + ": ${mayInstallPackages()},")
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

        /** The dev build must declare REQUEST_INSTALL_PACKAGES and be allowed to use it. */
        private const val NEED_INSTALL_RIGHT =
            "the apk passed every check, but Android shows no install dialog for an app that may not " +
                "install packages, so staging would hang silently. Install a dev build (which declares " +
                "REQUEST_INSTALL_PACKAGES) and allow it: `appops set com.termux " +
                "REQUEST_INSTALL_PACKAGES allow`, or grant it under Settings > Install unknown apps."

        /** The key behind the framework's non-public EXTRA_LEGACY_STATUS constant. */
        private const val KEY_LEGACY_STATUS = "android.content.pm.extra.LEGACY_STATUS"

        private val LIB_ENTRY = Regex("lib/([^/]+)/.+")

        /** Kept in memory so reports stay monotonic even when the file is unwritable. */
        private val ENDPOINT_GENERATION = AtomicLong(0)

        private val ISO_8601 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    }
}
