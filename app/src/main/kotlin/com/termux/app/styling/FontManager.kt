package com.termux.app.styling

import android.content.Context
import android.graphics.Typeface
import android.util.AtomicFile
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Manages the canonical Termux terminal font and fonts selectable by Styling. */
@Singleton
class FontManager @Inject constructor(
    private val context: Context
) {
    private var canonicalFontFile: File = TermuxConstants.TERMUX_FONT_FILE
    private var fontsDirectory: File = File(TermuxConstants.TERMUX_DATA_HOME_DIR, "fonts")

    internal constructor(context: Context, canonicalFontFile: File, fontsDirectory: File) : this(context) {
        this.canonicalFontFile = canonicalFontFile
        this.fontsDirectory = fontsDirectory
    }

    data class FontInfo(
        val name: String,
        val displayName: String,
        val isBuiltIn: Boolean,
        val isMonospace: Boolean = true,
        /** Asset path for bundled fonts, absolute path for user fonts, and null for Default. */
        val path: String? = null
    )

    sealed class ApplyResult {
        data class Success(val name: String, val typeface: Typeface) : ApplyResult()
        data class Error(val name: String, val message: String) : ApplyResult()
    }

    private val bundledFonts = listOf(
        FontInfo("default", "Default (system monospace)", isBuiltIn = true),
        FontInfo("fira_code", "Fira Code", isBuiltIn = true, path = "fonts/FiraCode-Regular.ttf"),
        FontInfo("jetbrains_mono", "JetBrains Mono", isBuiltIn = true, path = "fonts/JetBrainsMono-Regular.ttf")
    )

    private var currentFont: Typeface = Typeface.MONOSPACE
    private var currentFontName: String = "default"

    /** Returns Default and only bundled assets which are actually packaged and readable. */
    fun getAvailableFonts(): List<FontInfo> = buildList {
        bundledFonts.forEach { font ->
            if (font.path == null || canOpenAsset(font.path)) add(font)
        }
        validUserFontFiles().forEach { file ->
            add(
                FontInfo(
                    name = file.nameWithoutExtension,
                    displayName = file.nameWithoutExtension.replace('_', ' '),
                    isBuiltIn = false,
                    path = file.absolutePath
                )
            )
        }
        if (canonicalFontFile.isFile) {
            add(
                FontInfo(
                    name = "custom",
                    displayName = "Custom (font.ttf)",
                    isBuiltIn = false,
                    path = canonicalFontFile.absolutePath
                )
            )
        }
    }

    /**
     * Applies a selected font. No current state is changed until the source and resulting canonical
     * font both validate successfully.
     */
    fun applyFont(name: String): ApplyResult = rememberSuccessful(
        when (name) {
            "default" -> applyDefault()
            "custom" -> loadCanonicalFont()
            else -> applyBundledOrUserFont(name)
        }
    )

    /**
     * Restores a selection saved by an older broken Styling build. A valid canonical font.ttf
     * that differs from the saved source is treated as a manually installed custom font and is
     * never overwritten during migration.
     */
    fun restoreSavedFont(name: String): ApplyResult {
        val canonical = loadCanonicalFont()
        if (canonical is ApplyResult.Success) {
            val restored = when {
                name == "custom" -> canonical
                canonicalMatchesSelection(name) -> ApplyResult.Success(name, canonical.typeface)
                else -> ApplyResult.Success("custom", canonical.typeface)
            }
            return rememberSuccessful(restored)
        }
        return applyFont(name)
    }

    fun getCurrentFont(): Typeface = currentFont

    fun getCurrentFontName(): String = currentFontName

    fun hasCustomFont(): Boolean = canonicalFontFile.isFile

    fun ensureFontsDirectory(): File {
        if (!fontsDirectory.exists()) fontsDirectory.mkdirs()
        return fontsDirectory
    }

    fun installFont(sourceFile: File, name: String): Boolean {
        var stagedFont: File? = null
        return try {
            require(isSafeUserFontName(name)) { "Invalid font name" }
            val extension = sourceFile.extension.lowercase().takeIf { it == "ttf" || it == "otf" } ?: "ttf"
            val directory = ensureFontsDirectory()
            stagedFont = File.createTempFile(".termux-styling-install-", ".tmp", directory)
            FileInputStream(sourceFile).use { input ->
                stagedFont.outputStream().use { output -> input.copyTo(output) }
            }
            validateFile(stagedFont)

            val destination = File(directory, "$name.$extension")
            val atomicFile = AtomicFile(destination)
            var output: FileOutputStream? = null
            try {
                output = atomicFile.startWrite()
                FileInputStream(stagedFont).use { input -> input.copyTo(output) }
                atomicFile.finishWrite(output)
            } catch (e: Exception) {
                output?.let { atomicFile.failWrite(it) }
                throw e
            }
            Logger.logInfo(LOG_TAG, "Installed font: ${destination.absolutePath}")
            true
        } catch (e: Exception) {
            Logger.logError(LOG_TAG, "Failed to install font: ${e.message}")
            false
        } finally {
            stagedFont?.delete()
        }
    }

    fun removeFont(name: String): Boolean {
        if (!isSafeUserFontName(name)) return false
        val ttfFile = File(fontsDirectory, "$name.ttf")
        val otfFile = File(fontsDirectory, "$name.otf")
        var removed = false
        if (ttfFile.exists()) removed = ttfFile.delete()
        if (otfFile.exists()) removed = otfFile.delete() || removed
        return removed
    }

    private fun applyDefault(): ApplyResult = try {
        val atomicFile = AtomicFile(canonicalFontFile)
        atomicFile.delete()
        if (
            canonicalFontFile.exists() ||
            File(canonicalFontFile.path + ".new").exists() ||
            File(canonicalFontFile.path + ".bak").exists()
        ) {
            throw IllegalStateException("Canonical font file could not be deleted")
        }
        ApplyResult.Success("default", Typeface.MONOSPACE)
    } catch (e: Exception) {
        error("default", "Unable to reset the terminal font", e)
    }

    private fun loadCanonicalFont(): ApplyResult = try {
        ApplyResult.Success("custom", validateFile(canonicalFontFile))
    } catch (e: Exception) {
        error("custom", "Custom font.ttf is missing or invalid", e)
    }

    private fun applyBundledOrUserFont(name: String): ApplyResult {
        val bundled = bundledFonts.firstOrNull { it.name == name && it.path != null }
        val userFont = if (bundled == null) {
            validUserFontFiles().firstOrNull { it.nameWithoutExtension == name }
                ?: return ApplyResult.Error(name, "Font '$name' is not available")
        } else {
            null
        }

        canonicalFontFile.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) {
                return ApplyResult.Error(name, "Unable to create the terminal font directory")
            }
        }
        val stagedFont = File.createTempFile(".termux-styling-font-", ".tmp", canonicalFontFile.parentFile)
        return try {
            if (bundled != null) {
                context.assets.open(bundled.path!!).use { input ->
                    stagedFont.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                FileInputStream(userFont!!).use { input ->
                    stagedFont.outputStream().use { output -> input.copyTo(output) }
                }
            }

            // Validate the exact staged bytes that will be committed. This avoids a user font
            // changing between validation and copy from replacing a working canonical font.
            validateFile(stagedFont)
            copyFileAtomically(stagedFont)
            ApplyResult.Success(name, validateFile(canonicalFontFile))
        } catch (e: Exception) {
            error(name, "Unable to apply font '$name'", e)
        } finally {
            stagedFont.delete()
        }
    }

    private fun copyFileAtomically(source: File) {
        FileInputStream(source).use { input -> writeAtomically(input) }
    }

    private fun writeAtomically(input: java.io.InputStream) {
        canonicalFontFile.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) {
                throw IllegalStateException("Unable to create font directory")
            }
        }
        val atomicFile = AtomicFile(canonicalFontFile)
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            input.copyTo(output)
            atomicFile.finishWrite(output)
        } catch (e: Exception) {
            output?.let { atomicFile.failWrite(it) }
            throw e
        }
    }

    private fun validateFile(file: File): Typeface {
        require(file.isFile && file.length() >= 12) { "Font file does not exist or is too small" }
        require(hasSupportedSfntHeader(file)) { "Font file has an invalid TrueType/OpenType header" }
        return Typeface.createFromFile(file)
    }

    private fun hasSupportedSfntHeader(file: File): Boolean = RandomAccessFile(file, "r").use { input ->
        when (input.readInt()) {
            SFNT_VERSION_TRUE_TYPE,
            SFNT_VERSION_OTTO,
            SFNT_VERSION_TRUE,
            SFNT_VERSION_TYP1 -> true
            else -> false
        }
    }

    private fun canonicalMatchesSelection(name: String): Boolean {
        val bundled = bundledFonts.firstOrNull { it.name == name && it.path != null }
        return try {
            val expectedDigest = if (bundled != null) {
                context.assets.open(bundled.path!!).use(::sha256)
            } else {
                val userFont = validUserFontFiles().firstOrNull { it.nameWithoutExtension == name }
                    ?: return false
                FileInputStream(userFont).use(::sha256)
            }
            FileInputStream(canonicalFontFile).use(::sha256).contentEquals(expectedDigest)
        } catch (_: Exception) {
            false
        }
    }

    private fun sha256(input: java.io.InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest()
    }

    private fun rememberSuccessful(result: ApplyResult): ApplyResult {
        if (result is ApplyResult.Success) {
            currentFont = result.typeface
            currentFontName = result.name
        }
        return result
    }

    private fun canOpenAsset(path: String): Boolean = try {
        context.assets.open(path).use { }
        true
    } catch (_: Exception) {
        false
    }

    private fun isSafeUserFontName(name: String): Boolean =
        SAFE_USER_FONT_NAME.matches(name) && name.lowercase() !in reservedFontNames

    private fun validUserFontFiles(): List<File> = fontsDirectory.listFiles()
        ?.filter {
            it.isFile &&
                (it.extension.equals("ttf", true) || it.extension.equals("otf", true)) &&
                it.nameWithoutExtension.lowercase() !in reservedFontNames
        }
        ?.sortedBy { it.name.lowercase() }
        .orEmpty()

    private fun error(name: String, message: String, exception: Exception): ApplyResult.Error {
        Logger.logWarn(LOG_TAG, "$message: ${exception.message}")
        return ApplyResult.Error(name, message)
    }

    private companion object {
        const val LOG_TAG = "FontManager"
        const val SFNT_VERSION_TRUE_TYPE = 0x00010000
        const val SFNT_VERSION_OTTO = 0x4F54544F
        const val SFNT_VERSION_TRUE = 0x74727565
        const val SFNT_VERSION_TYP1 = 0x74797031
        val reservedFontNames = setOf("default", "custom", "fira_code", "jetbrains_mono")
        val SAFE_USER_FONT_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}
