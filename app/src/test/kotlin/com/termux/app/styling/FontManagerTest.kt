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
            listOf("default", "fira_code", "hack", "jetbrains_mono"),
            manager.getAvailableFonts().map { it.name }
        )
    }

    @Test
    fun `applying bundled font writes byte identical canonical font`() {
        val expected = context.assets.open("fonts/FiraCode-Regular.ttf").use { it.readBytes() }

        val result = manager.applyFont("fira_code")

        assertTrue(result is FontManager.ApplyResult.Success)
        assertArrayEquals(expected, canonicalFont.readBytes())
    }

    @Test
    fun `applying bundled Hack writes byte identical canonical font`() {
        val expected = context.assets.open("fonts/Hack-Regular.ttf").use { it.readBytes() }

        val result = manager.applyFont("hack")

        assertTrue(result is FontManager.ApplyResult.Success)
        assertArrayEquals(expected, canonicalFont.readBytes())
    }

    @Test
    fun `default removes canonical font`() {
        applyFiraCode()

        val result = manager.applyFont("default")

        assertTrue(result is FontManager.ApplyResult.Success)
        assertFalse(canonicalFont.exists())
    }

    @Test
    fun `missing unknown and corrupt user fonts do not replace canonical font`() {
        applyFiraCode()
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
        applyFiraCode()
        val original = canonicalFont.readBytes()

        val result = manager.applyFont("custom")

        assertTrue(result is FontManager.ApplyResult.Success)
        assertArrayEquals(original, canonicalFont.readBytes())
    }

    @Test
    fun `restoring saved font keeps saved name when canonical bytes match`() {
        val firaCode = context.assets.open("fonts/FiraCode-Regular.ttf").use { it.readBytes() }
        canonicalFont.parentFile?.mkdirs()
        canonicalFont.writeBytes(firaCode)

        val result = manager.restoreSavedFont("fira_code")

        assertTrue(result is FontManager.ApplyResult.Success)
        assertEquals("fira_code", (result as FontManager.ApplyResult.Success).name)
        assertArrayEquals(firaCode, canonicalFont.readBytes())
    }

    @Test
    fun `restoring saved font preserves a different valid manual canonical font`() {
        val jetBrainsMono = context.assets.open("fonts/JetBrainsMono-Regular.ttf").use { it.readBytes() }
        canonicalFont.parentFile?.mkdirs()
        canonicalFont.writeBytes(jetBrainsMono)

        val result = manager.restoreSavedFont("fira_code")

        assertTrue(result is FontManager.ApplyResult.Success)
        assertEquals("custom", (result as FontManager.ApplyResult.Success).name)
        assertArrayEquals(jetBrainsMono, canonicalFont.readBytes())
    }

    @Test
    fun `restoring saved font installs it when canonical font is absent`() {
        val expected = context.assets.open("fonts/FiraCode-Regular.ttf").use { it.readBytes() }

        val result = manager.restoreSavedFont("fira_code")

        assertTrue(result is FontManager.ApplyResult.Success)
        assertEquals("fira_code", (result as FontManager.ApplyResult.Success).name)
        assertArrayEquals(expected, canonicalFont.readBytes())
    }

    @Test
    fun `corrupt font install preserves existing installed font`() {
        val source = File(root, "source.ttf")
        val validFont = context.assets.open("fonts/FiraCode-Regular.ttf").use { it.readBytes() }
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
        val bytes = context.assets.open("fonts/FiraCode-Regular.ttf").use { it.readBytes() }
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
        source.writeBytes(context.assets.open("fonts/FiraCode-Regular.ttf").use { it.readBytes() })

        assertFalse(manager.installFont(source, "../escape"))
        assertFalse(manager.installFont(source, "hack"))
        assertFalse(manager.removeFont("../escape"))
        assertFalse(File(root.parentFile, "escape.ttf").exists())
    }

    private fun applyFiraCode() {
        assertTrue(manager.applyFont("fira_code") is FontManager.ApplyResult.Success)
    }
}
