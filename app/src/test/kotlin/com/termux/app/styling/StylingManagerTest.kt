package com.termux.app.styling

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
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
class StylingManagerTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var canonicalFont: File
    private lateinit var fontsDirectory: File
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        root = createTempDirectory("styling-manager-").toFile()
        canonicalFont = File(root, "font.ttf")
        fontsDirectory = File(root, "fonts")
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope) {
            File(root, "styling.preferences_pb")
        }
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        root.deleteRecursively()
    }

    @Test
    fun `initialize without saved selection preserves valid manual canonical font`() = runTest {
        val manualFont = assetBytes("fonts/JetBrainsMono-Regular.ttf")
        canonicalFont.parentFile?.mkdirs()
        canonicalFont.writeBytes(manualFont)

        val manager = newManager()
        manager.initialize()

        assertEquals("custom", manager.getCurrentSettings().fontName)
        assertArrayEquals(manualFont, canonicalFont.readBytes())
    }

    @Test
    fun `initialize keeps saved custom selection and canonical font`() = runTest {
        val manualFont = assetBytes("fonts/JetBrainsMono-Regular.ttf")
        canonicalFont.parentFile?.mkdirs()
        canonicalFont.writeBytes(manualFont)
        val firstManager = newManager()
        assertTrue(firstManager.setFont("custom") is FontManager.ApplyResult.Success)

        val restoredManager = newManager()
        restoredManager.initialize()

        assertEquals("custom", restoredManager.getCurrentSettings().fontName)
        assertArrayEquals(manualFont, canonicalFont.readBytes())
    }

    @Test
    fun `initialize restores saved bundled font when canonical font is absent`() = runTest {
        val firstManager = newManager()
        firstManager.setFont("fira_code")
        assertTrue(canonicalFont.delete())

        val restoredManager = newManager()
        restoredManager.initialize()

        assertEquals("fira_code", restoredManager.getCurrentSettings().fontName)
        assertArrayEquals(assetBytes("fonts/FiraCode-Regular.ttf"), canonicalFont.readBytes())
    }

    @Test
    fun `initialize corrects saved selection to custom without overwriting manual font`() = runTest {
        val firstManager = newManager()
        firstManager.setFont("fira_code")
        val manualFont = assetBytes("fonts/JetBrainsMono-Regular.ttf")
        canonicalFont.writeBytes(manualFont)

        val restoredManager = newManager()
        restoredManager.initialize()

        assertEquals("custom", restoredManager.getCurrentSettings().fontName)
        assertArrayEquals(manualFont, canonicalFont.readBytes())
    }

    @Test
    fun `initialize resets invalid canonical font when no selection exists`() = runTest {
        canonicalFont.parentFile?.mkdirs()
        canonicalFont.writeText("not a font")

        val manager = newManager()
        manager.initialize()

        assertEquals("default", manager.getCurrentSettings().fontName)
        assertFalse(canonicalFont.exists())
    }

    private fun newManager(): StylingManager {
        val fontManager = FontManager(context, canonicalFont, fontsDirectory)
        return StylingManager(context, fontManager, dataStore)
    }

    private fun assetBytes(path: String): ByteArray = context.assets.open(path).use { it.readBytes() }
}
