package com.termux.app.styling

import android.content.Context
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
class FontManagerTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var canonicalFont: File
    private lateinit var fontsDirectory: File
    private lateinit var manager: FontManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        root = createTempDirectory("font-manager-").toFile()
        canonicalFont = File(root, "font.ttf")
        fontsDirectory = File(root, "fonts")
        manager = FontManager(context, canonicalFont, fontsDirectory)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `catalog contains only packaged built in fonts`() {
        assertEquals(
            listOf("default", "hack"),
            manager.getAvailableFonts().map { it.name }
        )
    }

    @Test
    fun `applying bundled Hack Nerd Font writes byte identical canonical font`() {
        val expected = context.assets.open("fonts/HackNerdFontMono-Regular.ttf").use { it.readBytes() }

        val result = manager.applyFont("hack")

        assertTrue(result is FontManager.ApplyResult.Success)
        assertArrayEquals(expected, canonicalFont.readBytes())
    }

    @Test
    fun `default deletes canonical font`() {
        applyHack()

        val result = manager.applyFont("default")

        assertTrue(result is FontManager.ApplyResult.Success)
        assertFalse(canonicalFont.exists())
    }

    @Test
    fun `missing unknown and corrupt user fonts do not replace canonical font`() {
        applyHack()
        val original = canonicalFont.readBytes()

        assertTrue(manager.applyFont("missing-font") is FontManager.ApplyResult.Error)
        assertArrayEquals(original, canonicalFont.readBytes())

        fontsDirectory.mkdirs()
        File(fontsDirectory, "corrupt.ttf").writeText("not a font")
        assertTrue(manager.applyFont("corrupt") is FontManager.ApplyResult.Error)
        assertArrayEquals(original, canonicalFont.readBytes())
    }

    @Test
    fun `custom validates existing canonical font without rewriting it`() {
        applyHack()
        val original = canonicalFont.readBytes()

        val result = manager.applyFont("custom")

        assertTrue(result is FontManager.ApplyResult.Success)
        assertArrayEquals(original, canonicalFont.readBytes())
    }

    @Test
    fun `restoring saved font keeps saved name when canonical bytes match`() {
        val hackNerdFont = context.assets.open("fonts/HackNerdFontMono-Regular.ttf").use { it.readBytes() }
        canonicalFont.parentFile?.mkdirs()
        canonicalFont.writeBytes(hackNerdFont)

        val result = manager.restoreSavedFont("hack")

        assertTrue(result is FontManager.ApplyResult.Success)
        assertEquals("hack", (result as FontManager.ApplyResult.Success).name)
        assertArrayEquals(hackNerdFont, canonicalFont.readBytes())
    }

    @Test
    fun `restoring saved font preserves a different valid manual canonical font`() {
        val jetBrainsMono = fixtureBytes("JetBrainsMono-Regular.ttf")
        canonicalFont.parentFile?.mkdirs()
        canonicalFont.writeBytes(jetBrainsMono)

        val result = manager.restoreSavedFont("fira_code")

        assertTrue(result is FontManager.ApplyResult.Success)
        assertEquals("custom", (result as FontManager.ApplyResult.Success).name)
        assertArrayEquals(jetBrainsMono, canonicalFont.readBytes())
    }

    @Test
    fun `restoring saved font installs it when canonical font is absent`() {
        val expected = context.assets.open("fonts/HackNerdFontMono-Regular.ttf").use { it.readBytes() }

        val result = manager.restoreSavedFont("hack")

        assertTrue(result is FontManager.ApplyResult.Success)
        assertEquals("hack", (result as FontManager.ApplyResult.Success).name)
        assertArrayEquals(expected, canonicalFont.readBytes())
    }

    @Test
    fun `restoring saved Hack upgrades legacy plain Hack to the bundled Nerd Font`() {
        canonicalFont.parentFile?.mkdirs()
        canonicalFont.writeBytes(fixtureBytes("Hack-Regular.ttf"))
        assertTrue(manager.canonicalFontIsLegacyHack())

        val result = manager.restoreSavedFont("hack")

        assertTrue(result is FontManager.ApplyResult.Success)
        assertEquals("hack", (result as FontManager.ApplyResult.Success).name)
        assertArrayEquals(
            context.assets.open("fonts/HackNerdFontMono-Regular.ttf").use { it.readBytes() },
            canonicalFont.readBytes()
        )
    }

    @Test
    fun `legacy Hack detection ignores other fonts and missing files`() {
        canonicalFont.parentFile?.mkdirs()
        canonicalFont.writeBytes(fixtureBytes("FiraCode-Regular.ttf"))
        assertFalse(manager.canonicalFontIsLegacyHack())

        canonicalFont.delete()
        assertFalse(manager.canonicalFontIsLegacyHack())
    }

    @Test
    fun `corrupt font install preserves existing installed font`() {
        val source = File(root, "source.ttf")
        val validFont = fixtureBytes("FiraCode-Regular.ttf")
        source.writeBytes(validFont)
        assertTrue(manager.installFont(source, "installed"))
        val destination = File(fontsDirectory, "installed.ttf")
        val original = destination.readBytes()

        source.writeText("not a font")

        assertFalse(manager.installFont(source, "installed"))
        assertArrayEquals(original, destination.readBytes())
    }

    @Test
    fun `downloaded Nerd font is hidden from custom list but can be applied and removed`() {
        val source = File(root, "source.ttf")
        val bytes = fixtureBytes("FiraCode-Regular.ttf")
        source.writeBytes(bytes)

        assertTrue(manager.installFont(source, "nerd_0xproto", "ttf"))
        assertTrue(manager.installFont(source, "nerd_agave", "ttf"))
        assertFalse(manager.getAvailableFonts().any { it.name == "nerd_0xproto" })
        assertEquals(setOf("nerd_0xproto", "nerd_agave"), manager.getInstalledNerdFontNames())
        assertTrue(manager.isInstalledFont("nerd_0xproto"))
        assertTrue(manager.applyFont("nerd_0xproto") is FontManager.ApplyResult.Success)
        assertArrayEquals(bytes, canonicalFont.readBytes())
        assertTrue(manager.removeFont("nerd_0xproto"))
        assertTrue(manager.removeFont("nerd_agave"))
        assertFalse(manager.isInstalledFont("nerd_0xproto"))
    }

    @Test
    fun `font install and removal reject unsafe and reserved names`() {
        val source = File(root, "source.ttf")
        source.writeBytes(fixtureBytes("FiraCode-Regular.ttf"))

        assertFalse(manager.installFont(source, "../escape"))
        assertFalse(manager.installFont(source, "hack"))
        // Fira Code is no longer a bundled built-in, so the name is free for user fonts.
        assertTrue(manager.installFont(source, "fira_code"))
        assertFalse(manager.removeFont("../escape"))
        assertFalse(File(root.parentFile, "escape.ttf").exists())
    }

    private fun applyHack() {
        assertTrue(manager.applyFont("hack") is FontManager.ApplyResult.Success)
    }

    private fun fixtureBytes(name: String): ByteArray =
        requireNotNull(javaClass.classLoader.getResourceAsStream("fonts/$name")).use { it.readBytes() }
}
