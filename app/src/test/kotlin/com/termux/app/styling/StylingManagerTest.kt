package com.termux.app.styling

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

    @Test
    fun `optional font download installs applies and persists selection`() = runTest {
        val bytes = assetBytes("fonts/FiraCode-Regular.ttf")
        val client = FakeDownloadClient(bytes)
        val manager = newManager(client)

        val result = manager.downloadInstallAndApplyNerdFont("0xproto")

        assertTrue(result is OptionalFontResult.Success)
        assertEquals(1, client.calls)
        assertEquals("nerd_0xproto", manager.getCurrentSettings().fontName)
        assertArrayEquals(bytes, canonicalFont.readBytes())
        assertTrue(File(fontsDirectory, "nerd_0xproto.ttf").isFile)
    }

    @Test
    fun `optional font download failure preserves selected font and canonical bytes`() = runTest {
        val manager = newManager(FakeDownloadClient(failure = IllegalStateException("offline")))
        assertTrue(manager.setFont("fira_code") is FontManager.ApplyResult.Success)
        val before = canonicalFont.readBytes()

        val result = manager.downloadInstallAndApplyNerdFont("0xproto")

        assertTrue(result is OptionalFontResult.Error)
        assertEquals("fira_code", manager.getCurrentSettings().fontName)
        assertArrayEquals(before, canonicalFont.readBytes())
        assertFalse(File(fontsDirectory, "nerd_0xproto.ttf").exists())
    }

    @Test
    fun `initialize never downloads a missing persisted optional font`() = runTest {
        val firstClient = FakeDownloadClient(assetBytes("fonts/FiraCode-Regular.ttf"))
        val firstManager = newManager(firstClient)
        assertTrue(firstManager.downloadInstallAndApplyNerdFont("0xproto") is OptionalFontResult.Success)
        assertTrue(File(fontsDirectory, "nerd_0xproto.ttf").delete())
        assertTrue(canonicalFont.delete())

        val restoreClient = FakeDownloadClient(failure = IllegalStateException("must not run"))
        val restoredManager = newManager(restoreClient)
        restoredManager.initialize()

        assertEquals(0, restoreClient.calls)
        assertEquals("default", restoredManager.getCurrentSettings().fontName)
        assertFalse(canonicalFont.exists())
    }

    @Test
    fun `optional status snapshot and installed apply cover every state`() = runTest {
        val manager = newManager(FakeDownloadClient(assetBytes("fonts/FiraCode-Regular.ttf")))
        assertTrue(manager.downloadInstallAndApplyNerdFont("0xproto") is OptionalFontResult.Success)
        val source = File(root, "agave.ttf").apply {
            writeBytes(assetBytes("fonts/JetBrainsMono-Regular.ttf"))
        }
        assertTrue(FontManager(context, canonicalFont, fontsDirectory).installFont(source, "nerd_agave"))

        val statuses = manager.getNerdFontStatuses()
        assertEquals(NerdFontStatus.Selected, statuses["0xproto"])
        assertEquals(NerdFontStatus.Installed, statuses["agave"])
        assertEquals(NerdFontStatus.Downloadable, statuses["3270"])
        assertEquals(NerdFontStatus.Unsupported, statuses["nerdfontssymbolsonly"])

        assertTrue(manager.applyNerdFont("agave") is OptionalFontResult.Success)
        assertEquals("nerd_agave", manager.getCurrentSettings().fontName)
        assertTrue(manager.applyNerdFont("3270") is OptionalFontResult.Error)
    }

    @Test
    fun `removing selected optional font resets persisted selection to default first`() = runTest {
        val manager = newManager(FakeDownloadClient(assetBytes("fonts/FiraCode-Regular.ttf")))
        assertTrue(manager.downloadInstallAndApplyNerdFont("0xproto") is OptionalFontResult.Success)

        val result = manager.removeNerdFont("0xproto")

        assertTrue(result is OptionalFontResult.Success)
        assertEquals("default", manager.getCurrentSettings().fontName)
        assertFalse(canonicalFont.exists())
        assertFalse(File(fontsDirectory, "nerd_0xproto.ttf").exists())
    }

    @Test
    fun `concurrent requests for same optional font download once`() = runTest {
        val client = FakeDownloadClient(assetBytes("fonts/FiraCode-Regular.ttf"), delayMillis = 10)
        val manager = newManager(client)

        val results = listOf(
            async { manager.downloadInstallAndApplyNerdFont("0xproto") },
            async { manager.downloadInstallAndApplyNerdFont("0xproto") }
        ).awaitAll()

        assertTrue(results.all { it is OptionalFontResult.Success })
        assertEquals(1, client.calls)
    }

    private fun newManager(downloadClient: NerdFontDownloadClient? = null): StylingManager {
        val fontManager = FontManager(context, canonicalFont, fontsDirectory)
        return if (downloadClient == null) {
            StylingManager(context, fontManager, dataStore)
        } else {
            StylingManager(context, fontManager, dataStore, downloadClient)
        }
    }

    private inner class FakeDownloadClient(
        private val bytes: ByteArray? = null,
        private val failure: Exception? = null,
        private val delayMillis: Long = 0
    ) : NerdFontDownloadClient {
        var calls = 0

        override suspend fun download(entry: NerdFontCatalogEntry): Result<DownloadedNerdFont> {
            calls++
            if (delayMillis > 0) delay(delayMillis)
            failure?.let { return Result.failure(it) }
            val file = File.createTempFile("downloaded-font-", ".tmp", root)
            file.writeBytes(requireNotNull(bytes))
            return Result.success(DownloadedNerdFont(file, "ttf"))
        }
    }

    private fun assetBytes(path: String): ByteArray = context.assets.open(path).use { it.readBytes() }
}
