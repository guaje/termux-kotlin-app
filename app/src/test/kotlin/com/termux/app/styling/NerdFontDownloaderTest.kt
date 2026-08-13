package com.termux.app.styling

import android.content.Context
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class NerdFontDownloaderTest {
    private lateinit var context: Context
    private lateinit var cacheDirectory: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        cacheDirectory = File(context.cacheDir, "nerd-fonts")
        cacheDirectory.deleteRecursively()
    }

    @After
    fun tearDown() {
        cacheDirectory.deleteRecursively()
    }

    @Test
    fun `download URL policy accepts only exact HTTPS GitHub release hosts`() {
        requireAllowedNerdFontDownloadUrl(URL("https://github.com/ryanoasis/nerd-fonts/releases"))
        requireAllowedNerdFontDownloadUrl(URL("https://release-assets.githubusercontent.com/file"))

        assertUrlRejected("http://github.com/file")
        assertUrlRejected("https://github.com.evil.example/file")
        assertUrlRejected("https://objects.githubusercontent.com/file")
        assertUrlRejected("https://example.com/file")
    }

    @Test
    fun `HTTP provider follows an allowed redirect and writes exact bytes`() = runTest {
        val body = "archive".toByteArray()
        val requested = mutableListOf<URL>()
        val connections = ArrayDeque(
            listOf(
                FakeHttpConnection(302, location = "https://release-assets.githubusercontent.com/archive"),
                FakeHttpConnection(200, body = body)
            )
        )
        val provider = HttpNerdFontArchiveProvider(NerdFontConnectionFactory { url ->
            requested += url
            connections.removeFirst()
        })
        val destination = File(context.cacheDir, "http-provider.zip")

        provider.download(entryFor(body), destination)

        assertArrayEquals(body, destination.readBytes())
        assertEquals(listOf("github.com", "release-assets.githubusercontent.com"), requested.map { it.host })
        assertTrue(connections.isEmpty())
        destination.delete()
    }

    @Test
    fun `HTTP provider rejects unsafe and malformed redirects`() = runTest {
        val entry = entryFor(byteArrayOf())
        val scenarios = listOf(
            FakeHttpConnection(302, location = "https://evil.example/archive"),
            FakeHttpConnection(302, location = null)
        )

        scenarios.forEach { response ->
            val provider = HttpNerdFontArchiveProvider(NerdFontConnectionFactory { response })
            val result = runCatching { provider.download(entry, File(context.cacheDir, "redirect.zip")) }
            assertTrue(result.isFailure)
        }
    }

    @Test
    fun `HTTP provider enforces redirect limit and successful status`() = runTest {
        val redirects = ArrayDeque(
            List(6) { FakeHttpConnection(302, location = "https://github.com/next") }
        )
        val redirectProvider = HttpNerdFontArchiveProvider(NerdFontConnectionFactory { redirects.removeFirst() })
        assertTrue(
            runCatching {
                redirectProvider.download(entryFor(byteArrayOf()), File(context.cacheDir, "redirect-limit.zip"))
            }.isFailure
        )

        val errorProvider = HttpNerdFontArchiveProvider(
            NerdFontConnectionFactory { FakeHttpConnection(503, body = "unavailable".toByteArray()) }
        )
        assertTrue(
            runCatching {
                errorProvider.download(entryFor(byteArrayOf()), File(context.cacheDir, "http-error.zip"))
            }.isFailure
        )
    }

    @Test
    fun `HTTP provider rejects short and oversized bodies`() = runTest {
        val expected = "expected".toByteArray()
        listOf("short".toByteArray(), "expected-extra".toByteArray()).forEachIndexed { index, body ->
            val provider = HttpNerdFontArchiveProvider(
                NerdFontConnectionFactory { FakeHttpConnection(200, body = body) }
            )
            val destination = File(context.cacheDir, "body-$index.zip")

            val result = runCatching { provider.download(entryFor(expected), destination) }

            assertTrue(result.isFailure)
            destination.delete()
        }
    }

    @Test
    fun `valid pinned archive extracts regular Mono face and cleans archive`() = runTest {
        val expected = assetBytes("fonts/FiraCode-Regular.ttf")
        val archive = zipOf("nested/TestNerdFontMono-Regular.ttf" to expected)
        val provider = BytesProvider(archive)
        val downloader = NerdFontDownloader(context, provider)

        val result = downloader.download(entryFor(archive))

        assertTrue(result.isSuccess)
        val downloaded = result.getOrThrow()
        assertEquals("ttf", downloaded.extension)
        assertArrayEquals(expected, downloaded.file.readBytes())
        assertEquals(1, provider.calls)
        assertEquals(listOf(downloaded.file), cacheDirectory.listFiles()?.toList())
        downloaded.close()
        assertFalse(downloaded.file.exists())
        assertCacheEmpty()
    }

    @Test
    fun `Mono face wins deterministically over alphabetically earlier fallback`() = runTest {
        val fallback = assetBytes("fonts/JetBrainsMono-Regular.ttf")
        val expected = assetBytes("fonts/FiraCode-Regular.ttf")
        val archive = zipOf(
            "AardvarkNerdFont-Regular.ttf" to fallback,
            "ZuluNerdFontMono-Regular.ttf" to expected
        )

        val downloaded = NerdFontDownloader(context, BytesProvider(archive))
            .download(entryFor(archive))
            .getOrThrow()

        downloaded.use { assertArrayEquals(expected, it.file.readBytes()) }
        assertCacheEmpty()
    }

    @Test
    fun `proportional regular fallback is supported when archive has no Mono face`() = runTest {
        val expected = assetBytes("fonts/Hack-Regular.ttf")
        val archive = zipOf("ArimoNerdFont-Regular.ttf" to expected)

        val downloaded = NerdFontDownloader(context, BytesProvider(archive))
            .download(entryFor(archive, isMonospaced = false))
            .getOrThrow()

        downloaded.use { assertArrayEquals(expected, it.file.readBytes()) }
        assertCacheEmpty()
    }

    @Test
    fun `SHA mismatch is rejected and temporary files are cleaned`() = runTest {
        val archive = zipOf("TestNerdFontMono-Regular.ttf" to assetBytes("fonts/FiraCode-Regular.ttf"))
        val entry = entryFor(archive).copy(archiveSha256 = "f".repeat(64))

        val result = NerdFontDownloader(context, BytesProvider(archive)).download(entry)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("SHA-256"))
        assertCacheEmpty()
    }

    @Test
    fun `corrupt non ZIP response is rejected and cleaned`() = runTest {
        val response = "not a zip archive".toByteArray()

        val result = NerdFontDownloader(context, BytesProvider(response)).download(entryFor(response))

        assertTrue(result.isFailure)
        assertCacheEmpty()
    }

    @Test
    fun `archive without regular font is rejected and cleaned`() = runTest {
        val archive = zipOf("README.md" to "license".toByteArray())

        val result = NerdFontDownloader(context, BytesProvider(archive)).download(entryFor(archive))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("no regular Nerd Font"))
        assertCacheEmpty()
    }

    @Test
    fun `unsafe archive path is rejected even when valid font exists`() = runTest {
        val archive = zipOf(
            "../escape.txt" to "escape".toByteArray(),
            "TestNerdFontMono-Regular.ttf" to assetBytes("fonts/FiraCode-Regular.ttf")
        )

        val result = NerdFontDownloader(context, BytesProvider(archive)).download(entryFor(archive))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("unsafe entry path"))
        assertFalse(File(context.cacheDir.parentFile, "escape.txt").exists())
        assertCacheEmpty()
    }

    @Test
    fun `oversized extracted font is rejected and cleaned`() = runTest {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("HugeNerdFontMono-Regular.ttf"))
            val block = ByteArray(8192)
            repeat((33 * 1024 * 1024) / block.size) { zip.write(block) }
            zip.closeEntry()
        }
        val archive = output.toByteArray()

        val result = NerdFontDownloader(context, BytesProvider(archive)).download(entryFor(archive))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("too large"))
        assertCacheEmpty()
    }

    @Test
    fun `symbols only and bundled entries never call provider`() = runTest {
        val provider = BytesProvider(byteArrayOf())
        val downloader = NerdFontDownloader(context, provider)
        val normal = entryFor(byteArrayOf())

        assertTrue(downloader.download(normal.copy(primaryFontSupported = false)).isFailure)
        assertTrue(downloader.download(normal.copy(bundled = true)).isFailure)
        assertEquals(0, provider.calls)
    }

    private fun entryFor(bytes: ByteArray, isMonospaced: Boolean = true) = NerdFontCatalogEntry(
        id = "test",
        family = "Test",
        archiveName = "Test.zip",
        archiveSha256 = sha256(bytes),
        archiveBytes = bytes.size.toLong(),
        isMonospaced = isMonospaced
    )

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun assetBytes(path: String): ByteArray = context.assets.open(path).use { it.readBytes() }

    private fun assertCacheEmpty() {
        assertTrue(cacheDirectory.listFiles().isNullOrEmpty())
    }

    private fun assertUrlRejected(value: String) {
        var rejected = false
        try {
            requireAllowedNerdFontDownloadUrl(URL(value))
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(value, rejected)
    }

    private class FakeHttpConnection(
        private val status: Int,
        private val body: ByteArray = byteArrayOf(),
        private val location: String? = null
    ) : HttpURLConnection(URL("https://github.com/test")) {
        override fun getResponseCode(): Int = status
        override fun getHeaderField(name: String?): String? = if (name.equals("Location", true)) location else null
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun connect() = Unit
    }

    private class BytesProvider(private val bytes: ByteArray) : NerdFontArchiveProvider {
        var calls = 0

        override suspend fun download(entry: NerdFontCatalogEntry, destination: File) {
            calls++
            destination.writeBytes(bytes)
        }
    }
}
