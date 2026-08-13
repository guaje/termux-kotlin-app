package com.termux.app.styling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NerdFontCatalogTest {
    @Test
    fun `catalog is the complete pinned v3_5_0 manifest`() {
        assertEquals(72, NerdFontCatalog.entries.size)
        assertEquals(69, NerdFontCatalog.optionalEntries.size)
        assertEquals(expectedArchives, NerdFontCatalog.entries.map { it.archiveName }.toSet())
        assertEquals(
            setOf("fira_code", "hack", "jetbrains_mono"),
            NerdFontCatalog.entries.filter { it.bundled }.map { it.id }.toSet()
        )
    }

    @Test
    fun `every checksum and byte size matches independent official release fixture`() {
        val fixture = requireNotNull(javaClass.classLoader?.getResourceAsStream("nerd-fonts-v3.5.0-assets.txt"))
            .bufferedReader()
            .useLines { lines ->
                lines.filterNot { it.isBlank() || it.startsWith('#') }
                    .associate { line ->
                        val (archive, sha256, bytes) = line.split(' ')
                        archive to (sha256 to bytes.toLong())
                    }
            }
        val actual = NerdFontCatalog.entries.associate { entry ->
            entry.archiveName to (entry.archiveSha256 to entry.archiveBytes)
        }

        assertEquals(fixture, actual)
    }

    @Test
    fun `catalog pins are complete and identifiers are safe and unique`() {
        val entries = NerdFontCatalog.entries
        assertEquals(entries.size, entries.map { it.id }.distinct().size)
        assertEquals(entries.size, entries.map { it.storageName }.distinct().size)

        entries.forEach { entry ->
            assertTrue(entry.id, SAFE_ID.matches(entry.id))
            assertTrue(entry.storageName, SAFE_STORAGE_NAME.matches(entry.storageName))
            assertTrue(entry.archiveName, entry.archiveName.endsWith(".zip"))
            assertTrue(entry.archiveSha256, SHA_256.matches(entry.archiveSha256))
            assertFalse(entry.archiveSha256.all { it == '0' })
            assertTrue(entry.archiveBytes > 0)
            assertEquals(
                "https://github.com/ryanoasis/nerd-fonts/releases/download/v3.5.0/${entry.archiveName}",
                entry.archiveUrl
            )
        }
    }

    @Test
    fun `catalog preserves upstream monospace metadata and disables symbols only`() {
        assertEquals(
            setOf("Arimo", "HeavyData", "OpenDyslexic", "Tinos", "Ubuntu"),
            NerdFontCatalog.entries.filterNot { it.isMonospaced }.map { it.family }.toSet()
        )
        val symbols = NerdFontCatalog.entries.single { it.archiveName == "NerdFontsSymbolsOnly.zip" }
        assertFalse(symbols.primaryFontSupported)
        assertNotNull(NerdFontCatalog.find(symbols.id))
        assertTrue(NerdFontCatalog.entries.filterNot { it == symbols }.all { it.primaryFontSupported })
    }

    private companion object {
        val SAFE_ID = Regex("[a-z0-9][a-z0-9_]{0,127}")
        val SAFE_STORAGE_NAME = Regex("nerd_[a-z0-9][a-z0-9_]{0,127}")
        val SHA_256 = Regex("[0-9a-f]{64}")

        val expectedArchives = """
            0xProto.zip
            3270.zip
            AdwaitaMono.zip
            Agave.zip
            AnnotationMono.zip
            AnonymousPro.zip
            Arimo.zip
            AtkinsonHyperlegibleMono.zip
            AurulentSansMono.zip
            BigBlueTerminal.zip
            BitstreamVeraSansMono.zip
            IBMPlexMono.zip
            CascadiaCode.zip
            CascadiaMono.zip
            CodeNewRoman.zip
            ComicShannsMono.zip
            CommitMono.zip
            Cousine.zip
            D2Coding.zip
            DaddyTimeMono.zip
            DejaVuSansMono.zip
            DepartureMono.zip
            DroidSansMono.zip
            EnvyCodeR.zip
            FantasqueSansMono.zip
            FiraCode.zip
            FiraMono.zip
            GeistMono.zip
            Go-Mono.zip
            Gohu.zip
            GoogleSansCode.zip
            Hack.zip
            Hasklig.zip
            HeavyData.zip
            Hermit.zip
            iA-Writer.zip
            Inconsolata.zip
            InconsolataGo.zip
            InconsolataLGC.zip
            IntelOneMono.zip
            Iosevka.zip
            IosevkaTerm.zip
            IosevkaTermSlab.zip
            JetBrainsMono.zip
            Lekton.zip
            LiberationMono.zip
            Lilex.zip
            MartianMono.zip
            Meslo.zip
            Monaspace.zip
            Monofur.zip
            Monoid.zip
            Mononoki.zip
            MPlus.zip
            Noto.zip
            OpenDyslexic.zip
            Overpass.zip
            ProFont.zip
            ProggyClean.zip
            Recursive.zip
            RobotoMono.zip
            ShareTechMono.zip
            SourceCodePro.zip
            SpaceMono.zip
            NerdFontsSymbolsOnly.zip
            Terminus.zip
            Tinos.zip
            Ubuntu.zip
            UbuntuMono.zip
            UbuntuSans.zip
            VictorMono.zip
            ZedMono.zip
        """.trimIndent().lineSequence().filter { it.isNotBlank() }.toSet()
    }
}
