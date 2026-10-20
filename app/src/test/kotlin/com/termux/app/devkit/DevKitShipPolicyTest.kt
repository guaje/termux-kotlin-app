package com.termux.app.devkit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that gate an APK replacement.
 *
 * These tests exist because the alternative is learning a rule was wrong from a handset that has
 * already been replaced - or half replaced. Everything here is the decision itself, so it runs on the
 * JVM; the parts that need a package manager are deliberately thin wrappers around it.
 */
class DevKitShipPolicyTest {

    private val installed = DevKitShipPolicy.Installed(
        packageName = "com.termux",
        versionCode = 20503004L,
        signerSha256 = SIGNER,
        abi = "arm64-v8a"
    )

    private fun candidate(
        versionCode: Long = 20503005L,
        packageName: String? = "com.termux",
        signerSha256: String? = SIGNER,
        nativeAbis: List<String> = listOf("arm64-v8a"),
        sizeBytes: Long = 157_000_000L
    ) = DevKitShipPolicy.Candidate(
        path = "/data/local/tmp/termux-app_dev_universal.apk",
        packageName = packageName,
        versionCode = versionCode,
        versionName = "2.5.3-alpha.5",
        signerSha256 = signerSha256,
        nativeAbis = nativeAbis,
        sizeBytes = sizeBytes
    )

    private fun refuseReason(decision: DevKitShipPolicy.Decision): String {
        assertFalse("expected a refusal, got: ${decision.reason}", decision.accepted)
        assertTrue("a refusal must explain itself", decision.reason.isNotBlank())
        return decision.reason
    }

    @Test
    fun strictlyNewerBuildFromTheSameSignerIsAccepted() {
        val decision = DevKitShipPolicy.decide(installed, candidate(), DevKitShipPolicy.Claims())
        assertTrue(decision.reason, decision.accepted)
        assertTrue(decision.reason, decision.reason.contains("20503005"))
    }

    @Test
    fun apkForAnotherPackageIsRefused() {
        // Installing a foreign package would add an app rather than replace this one, and the ledger
        // would later reconcile against the wrong version code entirely.
        val reason = refuseReason(
            DevKitShipPolicy.decide(
                installed,
                candidate(packageName = "com.other.app"),
                DevKitShipPolicy.Claims()
            )
        )
        assertTrue(reason, reason.contains("com.other.app"))
    }

    @Test
    fun equalVersionCodeIsRefused() {
        val reason = refuseReason(
            DevKitShipPolicy.decide(installed, candidate(versionCode = 20503004L), DevKitShipPolicy.Claims())
        )
        assertTrue(reason, reason.contains("same as the running app"))
    }

    @Test
    fun downgradeIsRefused() {
        val reason = refuseReason(
            DevKitShipPolicy.decide(installed, candidate(versionCode = 20503003L), DevKitShipPolicy.Claims())
        )
        assertTrue(reason, reason.contains("downgrading"))
    }

    @Test
    fun signerMismatchNamesTheDataLossItPrevents() {
        val reason = refuseReason(
            DevKitShipPolicy.decide(installed, candidate(signerSha256 = "a".repeat(64)), DevKitShipPolicy.Claims())
        )
        assertTrue(reason, reason.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
        assertTrue(reason, reason.contains("uninstall"))
    }

    @Test
    fun apkWithoutACertificateIsRefused() {
        val reason = refuseReason(
            DevKitShipPolicy.decide(installed, candidate(signerSha256 = null), DevKitShipPolicy.Claims())
        )
        assertTrue(reason, reason.contains("lineage"))
    }

    @Test
    fun claimThatDisagreesWithTheFileIsRefused() {
        // The whole point of verifying in the app: a caller that mis-states its own target would
        // otherwise write a ledger entry describing an apk that does not exist.
        val claims = DevKitShipPolicy.Claims(versionCode = 20503099L)
        val reason = refuseReason(DevKitShipPolicy.decide(installed, candidate(versionCode = 20503005L), claims))
        assertTrue(reason, reason.contains("20503099") && reason.contains("20503005"))
    }

    @Test
    fun claimAboutTheSignerThatDisagreesIsRefused() {
        val claims = DevKitShipPolicy.Claims(signerSha256 = "b".repeat(64))
        val reason = refuseReason(DevKitShipPolicy.decide(installed, candidate(), claims))
        assertTrue(reason, reason.contains("not what it pretends to be"))
    }

    @Test
    fun matchingClaimsAreAcceptedAndCaseDoesNotMatter() {
        val claims = DevKitShipPolicy.Claims(
            versionCode = 20503005L,
            signerSha256 = SIGNER.uppercase(),
            abis = listOf("arm64-v8a")
        )
        assertTrue(DevKitShipPolicy.decide(installed, candidate(), claims).accepted)
    }

    @Test
    fun apkWithoutTheRunningAbiIsRefused() {
        val reason = refuseReason(
            DevKitShipPolicy.decide(installed, candidate(nativeAbis = listOf("armeabi-v7a")), DevKitShipPolicy.Claims())
        )
        assertTrue(reason, reason.contains("armeabi-v7a") && reason.contains("arm64-v8a"))
    }

    @Test
    fun apkWithNoNativeCodeAtAllIsAccepted() {
        // A pure-Java build carries no lib/ entries. That is a valid shape, not a mismatch, and
        // refusing it would refuse the universal apk on every device.
        assertTrue(
            DevKitShipPolicy.decide(installed, candidate(nativeAbis = emptyList()), DevKitShipPolicy.Claims())
                .accepted
        )
    }

    @Test
    fun universalApkCoveringSeveralAbisIsAccepted() {
        val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        assertTrue(
            DevKitShipPolicy.decide(installed, candidate(nativeAbis = abis), DevKitShipPolicy.Claims(abis = abis))
                .accepted
        )
    }

    @Test
    fun emptyFileIsRefused() {
        val reason = refuseReason(
            DevKitShipPolicy.decide(installed, candidate(sizeBytes = 0L), DevKitShipPolicy.Claims())
        )
        assertTrue(reason, reason.contains("empty"))
    }

    @Test
    fun unparseableFileIsRefused() {
        val reason = refuseReason(
            DevKitShipPolicy.decide(installed, candidate(packageName = null), DevKitShipPolicy.Claims())
        )
        assertTrue(reason, reason.contains("could not be parsed"))
    }

    /**
     * Regression test for the rule this feature shipped with, which compared the claimed ABI list to
     * the apk's list for equality. A universal apk carries four ABIs, so a caller honestly saying
     * "this covers arm64-v8a" was refused for describing part of the truth. The handset found this;
     * the earlier version of this test asserted the wrong rule and passed.
     */
    @Test
    fun claimingOneAbiOfAUniversalApkIsAccepted() {
        val universal = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        val claims = DevKitShipPolicy.Claims(abis = listOf("arm64-v8a"))
        assertTrue(DevKitShipPolicy.decide(installed, candidate(nativeAbis = universal), claims).accepted)
    }

    @Test
    fun claimAboutAnAbiTheApkDoesNotCarryIsRefused() {
        val claims = DevKitShipPolicy.Claims(abis = listOf("x86"))
        val reason = refuseReason(
            DevKitShipPolicy.decide(installed, candidate(nativeAbis = listOf("arm64-v8a")), claims)
        )
        assertTrue(reason, reason.contains("x86"))
        assertTrue(reason, reason.contains("does not"))
    }

    @Test
    fun installedAppWithoutAKnownSignerCannotBeReplaced() {
        val unknown = installed.copy(signerSha256 = null)
        val reason = refuseReason(
            DevKitShipPolicy.decide(unknown, candidate(), DevKitShipPolicy.Claims())
        )
        assertTrue(reason, reason.contains("unknown"))
    }

    private companion object {
        private const val SIGNER = "b71c8ff3ce3ce4cb55efd20b34048329721d40fcec540f2cbc9cfb4ee5fb2d63"
    }
}
