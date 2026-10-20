package com.termux.app.devkit

/**
 * The rules that decide whether an APK may replace the running app, with nothing Android in them.
 *
 * Kept separate from [DevKitShipCoordinator] because the coordinator needs a Context, a package
 * manager and a real file, while these rules are the part actually worth testing: they run on the JVM
 * here, and the same answers are then produced on the handset.
 *
 * Every refusal carries a reason a human can act on. That matters more here than almost anywhere else
 * in this feature, because the caller is usually a script standing just before a replacement that
 * will kill it, and it will not be alive to read a follow-up explanation.
 *
 * The checks are a list rather than a ladder of `if`s so that their order is stated once, at the
 * bottom, instead of being implied by control flow that a reader has to trace. Order is not arbitrary:
 * the cheapest and most certain facts come first, and anything about the file itself is settled before
 * anything about what the caller claimed.
 */
object DevKitShipPolicy {

    /** What the app currently running actually is, as reported by the package manager. */
    data class Installed(
        val packageName: String,
        val versionCode: Long,
        val signerSha256: String?,
        val abi: String
    )

    /** What the candidate APK actually contains. Read from the file, never taken on trust. */
    data class Candidate(
        val path: String,
        val packageName: String?,
        val versionCode: Long,
        val versionName: String,
        val signerSha256: String?,
        val nativeAbis: List<String>,
        val sizeBytes: Long
    )

    /** What the caller said about the candidate. Optional, and checked against [Candidate] when present. */
    data class Claims(
        val versionCode: Long? = null,
        val signerSha256: String? = null,
        val abis: List<String> = emptyList()
    )

    /** A verdict plus the sentence that explains it. */
    data class Decision(val accepted: Boolean, val reason: String)

    /**
     * The first check that returns a refusal decides the outcome; null means "this check is satisfied".
     *
     * The version comparison is deliberately strict. An equal version code is refused rather than
     * allowed through, because replacing a package with the same version code does not change anything
     * a caller could observe, so it would report a ship that shipped nothing. There is no downgrade
     * path at all: this exists to move a running system forward, and a tool that could push an older
     * prefix layout under a live data directory is a data-corruption tool.
     */
    fun decide(installed: Installed, candidate: Candidate, claims: Claims): Decision =
        sequenceOf(
            sizeProblem(candidate),
            parseProblem(candidate),
            packageProblem(installed, candidate),
            certificateProblem(candidate),
            signerProblem(installed, candidate),
            claimProblem(candidate, claims),
            versionProblem(installed, candidate),
            abiProblem(installed, candidate, claims)
        ).filterNotNull().firstOrNull() ?: Decision(true, verified(installed, candidate))

    private fun verified(installed: Installed, candidate: Candidate): String =
        "verified ${candidate.packageName} ${candidate.versionName} (${candidate.versionCode}) " +
            "against running ${installed.versionCode}, signed by ${candidate.signerSha256}"

    private fun sizeProblem(candidate: Candidate): Decision? =
        if (candidate.sizeBytes <= 0L) reject("the apk is empty") else null

    private fun parseProblem(candidate: Candidate): Decision? =
        if (candidate.packageName == null) reject("the apk could not be parsed as an Android package") else null

    /**
     * A foreign package would be *added* rather than installed over this one, and the ledger would
     * then reconcile a version code belonging to some other application.
     */
    private fun packageProblem(installed: Installed, candidate: Candidate): Decision? =
        if (candidate.packageName == installed.packageName) {
            null
        } else {
            reject(
                "the apk is for ${candidate.packageName}, not the running ${installed.packageName}; " +
                    "installing it would add a second app rather than replace this one"
            )
        }

    private fun certificateProblem(candidate: Candidate): Decision? =
        if (candidate.signerSha256 == null) {
            reject("no signing certificate could be read from the apk, so its lineage cannot be proved")
        } else {
            null
        }

    /**
     * Lineage is a hard gate because getting it wrong does not fail safe. A differently signed APK over
     * a live package is `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and the recovery everyone reaches for is
     * an uninstall - which deletes the prefix, every session, and every agent inside it.
     */
    private fun signerProblem(installed: Installed, candidate: Candidate): Decision? {
        if (candidate.signerSha256.equals(installed.signerSha256, ignoreCase = true)) {
            return null
        }
        return reject(
            "the apk is signed by ${candidate.signerSha256} but this app is signed by " +
                "${installed.signerSha256 ?: "unknown"}; that combination is " +
                "INSTALL_FAILED_UPDATE_INCOMPATIBLE, and the usual way out of it is an uninstall, " +
                "which deletes the prefix with every session and agent inside it"
        )
    }

    /**
     * The caller's claims are evidence about intent, not about the file. When they disagree with what
     * was read from disk, the ledger the caller is about to write would describe an APK that does not
     * exist - which is the exact failure this feature exists to make impossible.
     */
    private fun claimProblem(candidate: Candidate, claims: Claims): Decision? {
        val signerMismatch = claims.signerSha256 != null &&
            !claims.signerSha256.equals(candidate.signerSha256, ignoreCase = true)
        val versionMismatch = claims.versionCode != null && claims.versionCode != candidate.versionCode
        val signerIssue = if (signerMismatch) {
            reject(
                "the caller claimed signer ${claims.signerSha256} but the apk is signed by " +
                    "${candidate.signerSha256}; one of them is not what it pretends to be"
            )
        } else {
            null
        }
        val versionIssue = if (versionMismatch) {
            reject(
                "the caller claimed versionCode ${claims.versionCode} but the apk says " +
                    "${candidate.versionCode}; the ledger would record the claim, not the fact"
            )
        } else {
            null
        }
        return signerIssue ?: versionIssue
    }

    private fun versionProblem(installed: Installed, candidate: Candidate): Decision? {
        val downgrade = if (candidate.versionCode < installed.versionCode) {
            reject(
                "the apk is versionCode ${candidate.versionCode}, below the running " +
                    "${installed.versionCode}; downgrading a live install is never automatic"
            )
        } else {
            null
        }
        val noChange = if (candidate.versionCode == installed.versionCode) {
            reject(
                "the apk is versionCode ${candidate.versionCode}, the same as the running app; " +
                    "a replacement with an equal version code changes nothing a caller could observe"
            )
        } else {
            null
        }
        return downgrade ?: noChange
    }

    /**
     * An APK carrying no `lib/` directories has no native code to mismatch, which is a normal and
     * correct shape - refusing those would refuse the universal APK on every device.
     */
    private fun abiProblem(installed: Installed, candidate: Candidate, claims: Claims): Decision? {
        val missingAbi = if (candidate.nativeAbis.isNotEmpty() && !candidate.nativeAbis.contains(installed.abi)) {
            reject(
                "the apk carries native libraries for ${candidate.nativeAbis.joinToString()} but this " +
                    "device runs ${installed.abi}; the install would leave the app unable to start"
            )
        } else {
            null
        }
        val claimMismatch = if (claims.abis.isNotEmpty() && claims.abis != candidate.nativeAbis) {
            reject(
                "the caller claimed ABIs ${claims.abis.joinToString()} but the apk carries " +
                    "${candidate.nativeAbis.joinToString().ifEmpty { "none" }}"
            )
        } else {
            null
        }
        return missingAbi ?: claimMismatch
    }

    private fun reject(reason: String): Decision = Decision(false, reason)
}
