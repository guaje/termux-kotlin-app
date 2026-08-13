package com.termux.app.styling

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

internal val NERD_FONT_DOWNLOAD_HOSTS = setOf("github.com", "release-assets.githubusercontent.com")

internal fun requireAllowedNerdFontDownloadUrl(url: URL) {
    require(url.protocol.equals("https", true)) { "Only HTTPS download redirects are allowed" }
    require(url.host.lowercase() in NERD_FONT_DOWNLOAD_HOSTS) { "Download redirect host is not allowed" }
}

internal fun interface NerdFontConnectionFactory {
    fun open(url: URL): HttpURLConnection
}

/** Network boundary for Nerd Font archives; replace with a fake in JVM/Robolectric tests. */
interface NerdFontArchiveProvider {
    /** Writes exactly one archive to [destination], or throws an actionable exception. */
    suspend fun download(entry: NerdFontCatalogEntry, destination: File)
}

/** HTTPS-only GitHub release downloader with a small, explicit redirect allow-list. */
@Singleton
class HttpNerdFontArchiveProvider internal constructor(
    private val connectionFactory: NerdFontConnectionFactory
) : NerdFontArchiveProvider {
    @Inject
    constructor() : this(NerdFontConnectionFactory { url -> url.openConnection() as HttpURLConnection })

    override suspend fun download(entry: NerdFontCatalogEntry, destination: File) = withContext(Dispatchers.IO) {
        var url = URL(entry.archiveUrl)
        repeat(MAX_REDIRECTS + 1) { redirect ->
            requireAllowedNerdFontDownloadUrl(url)
            val connection = connectionFactory.open(url).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    if (redirect == MAX_REDIRECTS) throw IllegalStateException("Too many download redirects")
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalStateException("Download redirect has no location")
                    url = URL(url, location)
                    return@repeat
                }
                if (status !in 200..299) throw IllegalStateException("Download server returned HTTP $status")
                connection.inputStream.use { input -> copyExact(input, destination, entry.archiveBytes) }
                return@withContext
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("Too many download redirects")
    }

    private suspend fun copyExact(input: InputStream, destination: File, expectedBytes: Long) {
        var copied = 0L
        FileOutputStream(destination).use { output ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                copied += count
                if (copied > expectedBytes) throw IllegalStateException("Archive exceeds its expected size")
                output.write(buffer, 0, count)
            }
        }
        if (copied != expectedBytes) throw IllegalStateException("Archive size did not match the pinned release size")
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_REDIRECTS = 5
        const val DOWNLOAD_BUFFER_SIZE = 8192
        val USER_AGENT = "Termux-Kotlin-Styling/${NerdFontCatalog.VERSION}"
    }
}

/** A validated extracted font. Call [close] after FontManager has atomically installed it. */
class DownloadedNerdFont internal constructor(
    internal val file: File,
    internal val extension: String
) : AutoCloseable {
    override fun close() {
        file.delete()
    }
}

/** Injectable download boundary used by StylingManager and JVM tests. */
interface NerdFontDownloadClient {
    suspend fun download(entry: NerdFontCatalogEntry): Result<DownloadedNerdFont>
}

/**
 * Downloads and extracts one deterministic regular face. Archives are never materialized outside
 * private cache and ZIP names are never used as output paths.
 */
@Singleton
class NerdFontDownloader @Inject constructor(
    private val context: Context,
    private val archiveProvider: NerdFontArchiveProvider
) : NerdFontDownloadClient {
    private val downloadMutex = Mutex()

    override suspend fun download(entry: NerdFontCatalogEntry): Result<DownloadedNerdFont> =
        withContext(Dispatchers.IO) {
            if (!entry.primaryFontSupported) return@withContext Result.failure(
                IllegalArgumentException("${entry.family} is symbols-only and cannot be the terminal primary font")
            )
            if (entry.bundled) return@withContext Result.failure(
                IllegalArgumentException("${entry.family} is already bundled")
            )
            downloadMutex.withLock {
                var archive: File? = null
                var extracted: File? = null
                try {
                    val cache = File(context.cacheDir, "nerd-fonts").apply {
                        if (!isDirectory && !mkdirs()) {
                            throw IllegalStateException("Unable to create private font cache")
                        }
                    }
                    archive = File.createTempFile(".nerd-${entry.id}-", ".zip", cache)
                    archiveProvider.download(entry, archive)
                    verifyArchive(archive, entry)
                    extracted = File.createTempFile(".nerd-${entry.id}-", ".font", cache)
                    val extension = extractRegularMonoFace(archive, extracted)
                    Result.success(DownloadedNerdFont(extracted, extension).also { extracted = null })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(IllegalStateException("Unable to download ${entry.family}: ${e.message}", e))
                } finally {
                    archive?.delete()
                    extracted?.delete()
                }
            }
        }

    private suspend fun verifyArchive(archive: File, entry: NerdFontCatalogEntry) {
        if (archive.length() != entry.archiveBytes) throw IllegalStateException("Archive size did not match the pinned release size")
        val actual = FileInputStream(archive).use(::sha256)
        if (!actual.equals(entry.archiveSha256, true)) throw IllegalStateException("Archive SHA-256 did not match the pinned release")
    }

    private suspend fun extractRegularMonoFace(archive: File, output: File): String {
        ZipFile(archive).use { zip ->
            val entries = mutableListOf<java.util.zip.ZipEntry>()
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                if (entries.size == MAX_ZIP_ENTRIES) throw IllegalStateException("Archive contains too many entries")
                val entry = enumeration.nextElement()
                if (isUnsafeZipPath(entry.name)) throw IllegalStateException("Archive contains an unsafe entry path")
                entries += entry
            }
            val candidates = entries.asSequence()
                .filterNot { it.isDirectory }
                .mapNotNull { entry -> candidatePriority(entry.name)?.let { priority -> entry to priority } }
                .sortedWith(compareBy<Pair<java.util.zip.ZipEntry, Int>> { it.second }.thenBy { it.first.name.lowercase() })
                .toList()
            val candidate = candidates.firstOrNull()?.first
                ?: throw IllegalStateException("Archive has no regular Nerd Font TTF or OTF face")
            if (candidate.size > MAX_EXTRACTED_FONT_BYTES) throw IllegalStateException("Font in archive is too large")
            zip.getInputStream(candidate).use { input -> copyFont(input, output) }
            return if (candidate.name.endsWith(".otf", ignoreCase = true)) "otf" else "ttf"
        }
    }

    private suspend fun copyFont(input: InputStream, output: File) {
        var copied = 0L
        BufferedInputStream(input).use { source ->
            FileOutputStream(output).use { sink ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    copied += count
                    if (copied > MAX_EXTRACTED_FONT_BYTES) throw IllegalStateException("Extracted font is too large")
                    sink.write(buffer, 0, count)
                }
            }
        }
        if (copied < 12) throw IllegalStateException("Extracted font is too small")
    }

    /** Prefer the terminal-safe Mono face, falling back only for proportional source families. */
    private fun candidatePriority(name: String): Int? {
        val lower = name.lowercase()
        return when {
            lower.endsWith("nerdfontmono-regular.ttf") -> 0
            lower.endsWith("nerdfontmono-regular.otf") -> 1
            lower.endsWith("nerdfont-regular.ttf") -> 2
            lower.endsWith("nerdfont-regular.otf") -> 3
            else -> null
        }
    }

    private fun isUnsafeZipPath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return normalized.startsWith('/') || normalized.startsWith("\\") ||
            Regex("^[a-zA-Z]:").containsMatchIn(normalized) ||
            normalized.split('/').any { it == "." || it == ".." }
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val BUFFER_SIZE = 8192
        const val MAX_ZIP_ENTRIES = 4096
        const val MAX_EXTRACTED_FONT_BYTES = 32L * 1024L * 1024L
    }
}
