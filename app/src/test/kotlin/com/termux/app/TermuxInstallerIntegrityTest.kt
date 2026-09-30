package com.termux.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for deciding whether the app-managed bundled packages are still correct. The installed
 * Termux:API helper must target the receiver built into this APK: a package index can offer the
 * expected version whose helper still broadcasts to the standalone Termux:API app, which breaks
 * every `termux-*` command without changing any version number.
 */
class TermuxInstallerIntegrityTest {
    @Test
    fun `an intact integrated package is left alone`() {
        assertEquals(
            TermuxInstaller.BundledApiPackageAction.NONE,
            TermuxInstaller.decideBundledApiPackageAction(
                isPackageInstalled = true,
                doesInstalledHelperTargetIntegratedApi = true,
                isInstalledVersionAtLeastBundled = true
            )
        )
    }

    @Test
    fun `a missing package is installed from the verified asset`() {
        assertEquals(
            TermuxInstaller.BundledApiPackageAction.INSTALL_BUNDLED,
            TermuxInstaller.decideBundledApiPackageAction(
                isPackageInstalled = false,
                doesInstalledHelperTargetIntegratedApi = false,
                isInstalledVersionAtLeastBundled = false
            )
        )
    }

    @Test
    fun `the standalone app build is replaced by the integrated one`() {
        assertEquals(
            TermuxInstaller.BundledApiPackageAction.INSTALL_BUNDLED,
            TermuxInstaller.decideBundledApiPackageAction(
                isPackageInstalled = true,
                doesInstalledHelperTargetIntegratedApi = false,
                isInstalledVersionAtLeastBundled = false
            )
        )
    }

    @Test
    fun `a newer wrong package is reported and never downgraded`() {
        assertEquals(
            TermuxInstaller.BundledApiPackageAction.WARN_ONLY,
            TermuxInstaller.decideBundledApiPackageAction(
                isPackageInstalled = true,
                doesInstalledHelperTargetIntegratedApi = false,
                isInstalledVersionAtLeastBundled = true
            )
        )
    }

    @Test
    fun `only a helper targeting the integrated receiver passes`() {
        assertTrue(TermuxInstaller.doesHelperTargetIntegratedApi("am broadcast -n com.termux/.api.TermuxApiReceiver"))
        assertFalse(TermuxInstaller.doesHelperTargetIntegratedApi("am broadcast -n com.termux.api/.TermuxApiReceiver"))
        assertFalse(
            TermuxInstaller.doesHelperTargetIntegratedApi(
                "com.termux/.api.TermuxApiReceiver and com.termux.api/.TermuxApiReceiver"
            )
        )
        assertFalse(TermuxInstaller.doesHelperTargetIntegratedApi(""))
    }

    @Test
    fun `a removed openssh is reinstalled despite the migration marker`() {
        assertFalse(TermuxInstaller.shouldInstallBundledSshPackages(isMarkerPresent = true, isSshAgentAvailable = true))
        assertTrue(TermuxInstaller.shouldInstallBundledSshPackages(isMarkerPresent = true, isSshAgentAvailable = false))
        assertTrue(TermuxInstaller.shouldInstallBundledSshPackages(isMarkerPresent = false, isSshAgentAvailable = true))
        assertTrue(TermuxInstaller.shouldInstallBundledSshPackages(isMarkerPresent = false, isSshAgentAvailable = false))
    }

    @Test
    fun `distinguishes a held package from a merely installed one`() {
        val status = """
            Package: termux-api
            Status: hold ok installed
            Version: 1:0.59.1-1

            Package: openssh
            Status: install ok installed
            Version: 10.5p1
        """.trimIndent()

        assertEquals("Status: hold ok installed", TermuxInstaller.parseInstalledPackageStatus(status, "termux-api"))
        assertEquals("Status: install ok installed", TermuxInstaller.parseInstalledPackageStatus(status, "openssh"))
        assertNull(TermuxInstaller.parseInstalledPackageStatus(status, "util-linux"))
    }

    @Test
    fun `reads the package version encoded in the asset file name`() {
        assertEquals(
            "1:0.59.1-1",
            TermuxInstaller.parseBundledPackageVersionFromFileName("termux-api_1%3a0.59.1-1_aarch64.deb", "aarch64")
        )
        assertNull(
            TermuxInstaller.parseBundledPackageVersionFromFileName("termux-api_1%3a0.59.1-1_aarch64.deb", "arm")
        )
        assertNull(TermuxInstaller.parseBundledPackageVersionFromFileName("openssh_10.5p1_arm.deb", "arm"))
    }
}
