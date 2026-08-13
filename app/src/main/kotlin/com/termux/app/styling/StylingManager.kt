package com.termux.app.styling

import android.content.Context
import android.graphics.Typeface
import android.util.AtomicFile
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.termux.app.TermuxActivity
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

private val Context.stylingDataStore: DataStore<Preferences> by preferencesDataStore(name = "termux_styling")

/** Central manager for terminal colors, fonts, and Styling's persisted state. */
@Singleton
class StylingManager @Inject constructor(
    private val context: Context,
    private val fontManager: FontManager
) {
    internal constructor(
        context: Context,
        fontManager: FontManager,
        dataStore: DataStore<Preferences>
    ) : this(context, fontManager) {
        this.dataStore = dataStore
    }
    companion object {
        private const val LOG_TAG = "StylingManager"
        private val COLORS_FILE = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE
        private val CUSTOM_SCHEMES_DIR = File(TermuxConstants.TERMUX_DATA_HOME_DIR, "colors")

        private val KEY_CURRENT_SCHEME = stringPreferencesKey("current_color_scheme")
        private val KEY_CURRENT_FONT = stringPreferencesKey("current_font")
        private val KEY_FONT_SIZE = intPreferencesKey("font_size")
        private val KEY_BOLD_TEXT = booleanPreferencesKey("bold_text")
        private val KEY_CURSOR_BLINK = booleanPreferencesKey("cursor_blink")
        private val KEY_CURSOR_STYLE = stringPreferencesKey("cursor_style")
        private val KEY_BELL_ENABLED = booleanPreferencesKey("bell_enabled")
        private val KEY_VIBRATE_ON_BELL = booleanPreferencesKey("vibrate_on_bell")

        const val DEFAULT_FONT_SIZE = 14
        const val MIN_FONT_SIZE = 6
        const val MAX_FONT_SIZE = 42
    }

    private var dataStore = context.stylingDataStore
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private var currentScheme: ColorScheme = BuiltInColorSchemes.Default
    private var currentFont: Typeface = Typeface.MONOSPACE
    private val styleChangeListeners = mutableListOf<StyleChangeListener>()

    interface StyleChangeListener {
        fun onColorSchemeChanged(scheme: ColorScheme)
        fun onFontChanged(font: Typeface, name: String)
        fun onFontSizeChanged(size: Int)
        fun onSettingsChanged()
    }

    val currentSchemeName: Flow<String>
        get() = dataStore.data.map { it[KEY_CURRENT_SCHEME] ?: "Default" }
    val currentFontName: Flow<String>
        get() = dataStore.data.map { it[KEY_CURRENT_FONT] ?: "default" }
    val fontSize: Flow<Int>
        get() = dataStore.data.map { it[KEY_FONT_SIZE] ?: DEFAULT_FONT_SIZE }
    val boldText: Flow<Boolean>
        get() = dataStore.data.map { it[KEY_BOLD_TEXT] ?: false }
    val cursorBlink: Flow<Boolean>
        get() = dataStore.data.map { it[KEY_CURSOR_BLINK] ?: true }
    val cursorStyle: Flow<String>
        get() = dataStore.data.map { it[KEY_CURSOR_STYLE] ?: "block" }
    val bellEnabled: Flow<Boolean>
        get() = dataStore.data.map { it[KEY_BELL_ENABLED] ?: true }
    val vibrateOnBell: Flow<Boolean>
        get() = dataStore.data.map { it[KEY_VIBRATE_ON_BELL] ?: true }

    /**
     * Reconciles Styling's saved selection with Termux's canonical font file.
     * A missing Styling preference never overwrites a manually installed font.ttf.
     */
    suspend fun initialize() {
        val preferences = dataStore.data.first()
        val schemeName = preferences[KEY_CURRENT_SCHEME] ?: "Default"
        withContext(Dispatchers.IO) {
            currentScheme = getColorScheme(schemeName) ?: BuiltInColorSchemes.Default

            val savedFontName = preferences[KEY_CURRENT_FONT]
            if (savedFontName == null) {
                // A manual font.ttf predating integrated Styling remains authoritative until the
                // user explicitly selects another font.
                when (val customResult = fontManager.applyFont("custom")) {
                    is FontManager.ApplyResult.Success -> {
                        currentFont = customResult.typeface
                        dataStore.edit { it[KEY_CURRENT_FONT] = "custom" }
                    }
                    is FontManager.ApplyResult.Error -> resetInvalidCanonicalFont(customResult.message)
                }
            } else {
                when (val result = fontManager.restoreSavedFont(savedFontName)) {
                    is FontManager.ApplyResult.Success -> {
                        currentFont = result.typeface
                        if (result.name != savedFontName) {
                            dataStore.edit { it[KEY_CURRENT_FONT] = result.name }
                        }
                    }
                    is FontManager.ApplyResult.Error -> {
                        // A broken/removed saved selection must not destroy a valid manual font.ttf.
                        when (val customResult = fontManager.applyFont("custom")) {
                            is FontManager.ApplyResult.Success -> {
                                currentFont = customResult.typeface
                                dataStore.edit { it[KEY_CURRENT_FONT] = "custom" }
                            }
                            is FontManager.ApplyResult.Error -> resetInvalidCanonicalFont(customResult.message)
                        }
                        Logger.logWarn(LOG_TAG, "Saved font '$savedFontName' could not be applied: ${result.message}")
                    }
                }
            }
        }

        syncFontSizeMirror()
        Logger.logInfo(LOG_TAG, "Styling initialized: scheme=$schemeName, font=${fontManager.getCurrentFontName()}")
    }

    fun getCurrentScheme(): ColorScheme = currentScheme

    fun getCurrentFont(): Typeface = currentFont

    fun getAvailableSchemes(): List<ColorScheme> = BuiltInColorSchemes.getAll() + loadCustomSchemes()

    private fun loadCustomSchemes(): List<ColorScheme> = CUSTOM_SCHEMES_DIR.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".json") }
        ?.mapNotNull { file ->
            try {
                json.decodeFromString<ColorScheme>(file.readText())
            } catch (e: Exception) {
                Logger.logWarn(LOG_TAG, "Failed to load color scheme: ${file.name}")
                null
            }
        }
        .orEmpty()

    fun getColorScheme(name: String): ColorScheme? =
        BuiltInColorSchemes.getByName(name) ?: loadCustomSchemes().find { it.name.equals(name, ignoreCase = true) }

    suspend fun setColorScheme(scheme: ColorScheme): Boolean {
        if (!withContext(Dispatchers.IO) { writeColorsFile(scheme) }) return false
        currentScheme = scheme
        dataStore.edit { it[KEY_CURRENT_SCHEME] = scheme.name }
        styleChangeListeners.forEach { it.onColorSchemeChanged(scheme) }
        TermuxActivity.updateTermuxActivityStyling(context, false)
        Logger.logInfo(LOG_TAG, "Color scheme changed to: ${scheme.name}")
        return true
    }

    suspend fun setColorScheme(name: String): Boolean {
        val scheme = getColorScheme(name) ?: return false
        return setColorScheme(scheme)
    }

    /** Applies the font before changing in-memory or persisted selected-font state. */
    suspend fun setFont(name: String): FontManager.ApplyResult {
        val result = withContext(Dispatchers.IO) { fontManager.applyFont(name) }
        if (result is FontManager.ApplyResult.Success) {
            currentFont = result.typeface
            dataStore.edit { it[KEY_CURRENT_FONT] = name }
            styleChangeListeners.forEach { it.onFontChanged(currentFont, name) }
            TermuxActivity.updateTermuxActivityStyling(context, false)
            Logger.logInfo(LOG_TAG, "Font changed to: $name")
        }
        return result
    }

    /** Stores Styling's selected size in sp and Termux's canonical size in rounded px. */
    suspend fun setFontSize(size: Int) {
        val sp = size.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        val px = (sp * context.resources.displayMetrics.scaledDensity).roundToInt()
        TermuxAppSharedPreferences.build(context)?.setFontSize(px)
        dataStore.edit { it[KEY_FONT_SIZE] = sp }
        styleChangeListeners.forEach { it.onFontSizeChanged(sp) }
        TermuxActivity.updateTermuxActivityStyling(context, false)
    }

    suspend fun setBoldText(enabled: Boolean) {
        dataStore.edit { it[KEY_BOLD_TEXT] = enabled }
        styleChangeListeners.forEach { it.onSettingsChanged() }
    }

    suspend fun setCursorBlink(enabled: Boolean) {
        dataStore.edit { it[KEY_CURSOR_BLINK] = enabled }
        styleChangeListeners.forEach { it.onSettingsChanged() }
    }

    suspend fun setCursorStyle(style: String) {
        dataStore.edit { it[KEY_CURSOR_STYLE] = style }
        styleChangeListeners.forEach { it.onSettingsChanged() }
    }

    suspend fun setBellEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_BELL_ENABLED] = enabled }
        styleChangeListeners.forEach { it.onSettingsChanged() }
    }

    suspend fun setVibrateOnBell(enabled: Boolean) {
        dataStore.edit { it[KEY_VIBRATE_ON_BELL] = enabled }
        styleChangeListeners.forEach { it.onSettingsChanged() }
    }

    private fun writeColorsFile(scheme: ColorScheme): Boolean = try {
        COLORS_FILE.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) {
                throw IllegalStateException("Unable to create color scheme directory")
            }
        }
        val content = buildString {
            appendLine("# Termux color scheme: ${scheme.name}")
            appendLine("# Generated by Termux Styling")
            appendLine()
            appendLine("foreground=${ColorScheme.toHex(scheme.foreground)}")
            appendLine("background=${ColorScheme.toHex(scheme.background)}")
            appendLine("cursor=${ColorScheme.toHex(scheme.cursor)}")
            appendLine()
            for (i in 0..15) appendLine("color$i=${ColorScheme.toHex(scheme.getColor(i))}")
        }
        val atomicFile = AtomicFile(COLORS_FILE)
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(content.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (e: Exception) {
            output?.let { atomicFile.failWrite(it) }
            throw e
        }
        true
    } catch (e: Exception) {
        Logger.logError(LOG_TAG, "Failed to write colors.properties: ${e.message}")
        false
    }

    fun saveCustomScheme(scheme: ColorScheme): Boolean = try {
        CUSTOM_SCHEMES_DIR.mkdirs()
        File(CUSTOM_SCHEMES_DIR, "${scheme.name.lowercase().replace(" ", "_")}.json").writeText(json.encodeToString(scheme))
        true
    } catch (e: Exception) {
        Logger.logError(LOG_TAG, "Failed to save custom scheme: ${e.message}")
        false
    }

    fun deleteCustomScheme(name: String): Boolean =
        File(CUSTOM_SCHEMES_DIR, "${name.lowercase().replace(" ", "_")}.json").delete()

    /** Uses legacy canonical px so pinch zoom changes are visible when Styling is reopened. */
    suspend fun getCurrentSettings(): StylingSettings {
        val preferences = dataStore.data.first()
        val actualFontSize = currentFontSizeSp()
        if (preferences[KEY_FONT_SIZE] != actualFontSize) {
            dataStore.edit { it[KEY_FONT_SIZE] = actualFontSize }
        }
        return StylingSettings(
            schemeName = preferences[KEY_CURRENT_SCHEME] ?: "Default",
            fontName = preferences[KEY_CURRENT_FONT] ?: "default",
            fontSize = actualFontSize,
            boldText = preferences[KEY_BOLD_TEXT] ?: false,
            cursorBlink = preferences[KEY_CURSOR_BLINK] ?: true,
            cursorStyle = preferences[KEY_CURSOR_STYLE] ?: "block",
            bellEnabled = preferences[KEY_BELL_ENABLED] ?: true,
            vibrateOnBell = preferences[KEY_VIBRATE_ON_BELL] ?: true
        )
    }

    private suspend fun resetInvalidCanonicalFont(reason: String) {
        when (val defaultResult = fontManager.applyFont("default")) {
            is FontManager.ApplyResult.Success -> {
                currentFont = defaultResult.typeface
                dataStore.edit { it[KEY_CURRENT_FONT] = "default" }
            }
            is FontManager.ApplyResult.Error -> {
                currentFont = Typeface.MONOSPACE
                Logger.logError(LOG_TAG, "Unable to reset invalid canonical font: ${defaultResult.message}")
            }
        }
        Logger.logWarn(LOG_TAG, "Canonical custom font was unavailable or invalid: $reason")
    }

    private suspend fun syncFontSizeMirror() {
        val sp = currentFontSizeSp()
        dataStore.edit { it[KEY_FONT_SIZE] = sp }
    }

    private fun currentFontSizeSp(): Int {
        val px = TermuxAppSharedPreferences.build(context)?.getFontSize() ?: return DEFAULT_FONT_SIZE
        return (px / context.resources.displayMetrics.scaledDensity).roundToInt().coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
    }

    fun addStyleChangeListener(listener: StyleChangeListener) {
        styleChangeListeners.add(listener)
    }

    fun removeStyleChangeListener(listener: StyleChangeListener) {
        styleChangeListeners.remove(listener)
    }

    fun getAvailableFonts(): List<FontManager.FontInfo> = fontManager.getAvailableFonts()
}

data class StylingSettings(
    val schemeName: String,
    val fontName: String,
    val fontSize: Int,
    val boldText: Boolean,
    val cursorBlink: Boolean,
    val cursorStyle: String,
    val bellEnabled: Boolean,
    val vibrateOnBell: Boolean
)
