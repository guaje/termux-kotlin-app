package com.termux.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TermuxInstallerPackageStatusTest {
    @Test
    fun `reads the version of an installed package`() {
        val status = """
            Package: openssh
            Status: install ok installed
            Version: 10.5p1

            Package: libedit
            Status: install ok installed
            Version: 20260512-3.1-0
        """.trimIndent()

        assertEquals("10.5p1", TermuxInstaller.parseInstalledPackageVersion(status, "openssh"))
    }

    @Test
    fun `treats a held package as installed to prevent downgrade`() {
        val status = """
            Package: libedit
            Status: hold ok installed
            Version: 20270000-1
        """.trimIndent()

        assertEquals("20270000-1", TermuxInstaller.parseInstalledPackageVersion(status, "libedit"))
    }

    @Test
    fun `does not treat config files only package as installed`() {
        val status = """
            Package: openssh
            Status: deinstall ok config-files
            Version: 10.5p1
        """.trimIndent()

        assertNull(TermuxInstaller.parseInstalledPackageVersion(status, "openssh"))
    }
}
