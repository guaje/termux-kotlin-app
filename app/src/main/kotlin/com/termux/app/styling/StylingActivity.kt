package com.termux.app.styling

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.shared.logger.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Activity for terminal styling configuration.
 * 
 * Allows users to:
 * - Select color schemes
 * - Choose fonts
 * - Adjust font size
 * - Configure terminal appearance settings
 */
@AndroidEntryPoint
class StylingActivity : ComponentActivity() {
    
    companion object {
        private const val LOG_TAG = "StylingActivity"
    }
    
    @Inject
    lateinit var stylingManager: StylingManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Logger.logInfo(LOG_TAG, "StylingActivity opened")
        
        setContent {
            StylingScreen(
                stylingManager = stylingManager,
                onClose = { finish() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StylingScreen(
    stylingManager: StylingManager,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    
    // State
    var selectedTab by remember { mutableStateOf(0) }
    var currentScheme by remember { mutableStateOf(stylingManager.getCurrentScheme()) }
    var currentSettings by remember { mutableStateOf<StylingSettings?>(null) }
    var schemes by remember { mutableStateOf(emptyList<ColorScheme>()) }
    var fonts by remember { mutableStateOf(emptyList<FontManager.FontInfo>()) }
    var nerdQuery by remember { mutableStateOf("") }
    var nerdStatuses by remember { mutableStateOf(emptyMap<String, NerdFontStatus>()) }
    var downloadingNerdIds by remember { mutableStateOf(emptySet<String>()) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Reconcile legacy Termux state before reading it into the UI.
    LaunchedEffect(Unit) {
        stylingManager.initialize()
        schemes = stylingManager.getAvailableSchemes()
        fonts = stylingManager.getAvailableFonts()
        nerdStatuses = stylingManager.getNerdFontStatuses()
        currentScheme = stylingManager.getCurrentScheme()
        currentSettings = stylingManager.getCurrentSettings()
    }
    
    MaterialTheme(
        colorScheme = darkColorScheme()
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text("Terminal Styling") },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.ArrowBack, "Back")
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Tabs
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Colors") },
                        icon = { Icon(Icons.Default.Palette, null) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Fonts") },
                        icon = { Icon(Icons.Default.TextFields, null) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("Settings") },
                        icon = { Icon(Icons.Default.Settings, null) }
                    )
                }
                
                // Tab content
                when (selectedTab) {
                    0 -> ColorSchemesTab(
                        schemes = schemes,
                        currentScheme = currentScheme,
                        onSchemeSelected = { scheme ->
                            scope.launch {
                                if (stylingManager.setColorScheme(scheme)) {
                                    currentScheme = scheme
                                } else {
                                    snackbarHostState.showSnackbar("Unable to apply color scheme")
                                }
                            }
                        }
                    )
                    1 -> FontsTab(
                        fonts = fonts,
                        currentFont = currentSettings?.fontName ?: "default",
                        currentSize = currentSettings?.fontSize ?: 14,
                        onFontSelected = { fontName ->
                            scope.launch {
                                when (val result = stylingManager.setFont(fontName)) {
                                    is FontManager.ApplyResult.Success -> {
                                        currentSettings = stylingManager.getCurrentSettings()
                                    }
                                    is FontManager.ApplyResult.Error -> {
                                        snackbarHostState.showSnackbar(result.message)
                                    }
                                }
                            }
                        },
                        onSizeChanged = { size ->
                            scope.launch {
                                stylingManager.setFontSize(size)
                                currentSettings = stylingManager.getCurrentSettings()
                            }
                        },
                        nerdQuery = nerdQuery,
                        onNerdQueryChanged = { nerdQuery = it },
                        downloadingIds = downloadingNerdIds,
                        nerdStatus = { entry ->
                            nerdStatuses[entry.id] ?: if (entry.primaryFontSupported) {
                                NerdFontStatus.Downloadable
                            } else {
                                NerdFontStatus.Unsupported
                            }
                        },
                        onNerdFontAction = { entry ->
                            if (entry.id !in downloadingNerdIds) scope.launch {
                                val status = nerdStatuses[entry.id] ?: if (entry.primaryFontSupported) {
                                    NerdFontStatus.Downloadable
                                } else {
                                    NerdFontStatus.Unsupported
                                }
                                when (status) {
                                    NerdFontStatus.Downloadable -> {
                                        downloadingNerdIds = downloadingNerdIds + entry.id
                                        try {
                                            when (val result = stylingManager.downloadInstallAndApplyNerdFont(entry.id)) {
                                                is OptionalFontResult.Error -> snackbarHostState.showSnackbar(result.message)
                                                is OptionalFontResult.Success -> Unit
                                            }
                                        } finally {
                                            downloadingNerdIds = downloadingNerdIds - entry.id
                                        }
                                    }
                                    NerdFontStatus.Installed -> {
                                        when (val result = stylingManager.applyNerdFont(entry.id)) {
                                            is OptionalFontResult.Error -> snackbarHostState.showSnackbar(result.message)
                                            is OptionalFontResult.Success -> Unit
                                        }
                                    }
                                    else -> Unit
                                }
                                fonts = stylingManager.getAvailableFonts()
                                nerdStatuses = stylingManager.getNerdFontStatuses()
                                currentSettings = stylingManager.getCurrentSettings()
                            }
                        },
                        onRemoveNerdFont = { entry ->
                            scope.launch {
                                when (val result = stylingManager.removeNerdFont(entry.id)) {
                                    is OptionalFontResult.Error -> snackbarHostState.showSnackbar(result.message)
                                    is OptionalFontResult.Success -> Unit
                                }
                                fonts = stylingManager.getAvailableFonts()
                                nerdStatuses = stylingManager.getNerdFontStatuses()
                                currentSettings = stylingManager.getCurrentSettings()
                            }
                        }
                    )
                    2 -> SettingsTab(
                        settings = currentSettings,
                        onSettingsChanged = { settings ->
                            scope.launch {
                                stylingManager.setBoldText(settings.boldText)
                                stylingManager.setCursorBlink(settings.cursorBlink)
                                stylingManager.setCursorStyle(settings.cursorStyle)
                                stylingManager.setBellEnabled(settings.bellEnabled)
                                stylingManager.setVibrateOnBell(settings.vibrateOnBell)
                                currentSettings = stylingManager.getCurrentSettings()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ColorSchemesTab(
    schemes: List<ColorScheme>,
    currentScheme: ColorScheme,
    onSchemeSelected: (ColorScheme) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Preview
        item {
            ColorSchemePreview(scheme = currentScheme)
        }
        
        // Scheme list
        item {
            Text(
                "Color Schemes",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        
        items(schemes) { scheme ->
            ColorSchemeCard(
                scheme = scheme,
                isSelected = scheme.name == currentScheme.name,
                onClick = { onSchemeSelected(scheme) }
            )
        }
    }
}

@Composable
fun ColorSchemePreview(scheme: ColorScheme) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(scheme.background))
                .padding(16.dp)
        ) {
            Text(
                "Terminal Preview",
                color = Color(scheme.foreground),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "$ echo \"Hello, Termux!\"",
                color = Color(scheme.foreground),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp
            )
            Text(
                "Hello, Termux!",
                color = Color(scheme.color2), // Green
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp
            )
            Text(
                "$ ls -la",
                color = Color(scheme.foreground),
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp
            )
            Row {
                Text("drwxr-xr-x ", color = Color(scheme.color4), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                Text("home", color = Color(scheme.color6), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }
            Row {
                Text("-rw-r--r-- ", color = Color(scheme.foreground), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                Text("file.txt", color = Color(scheme.foreground), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }
            
            // Color palette preview
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (i in 0..7) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(16.dp)
                            .background(Color(scheme.getColor(i)))
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (i in 8..15) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(16.dp)
                            .background(Color(scheme.getColor(i)))
                    )
                }
            }
        }
    }
}

@Composable
fun ColorSchemeCard(
    scheme: ColorScheme,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(
                if (isSelected) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(12.dp)
                ) else Modifier
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Color preview squares
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(scheme.background))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                for (i in listOf(1, 2, 3, 4, 5, 6)) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(Color(scheme.getColor(i)))
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    scheme.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                if (scheme.description.isNotEmpty()) {
                    Text(
                        scheme.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            if (isSelected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun FontsTab(
    fonts: List<FontManager.FontInfo>,
    currentFont: String,
    currentSize: Int,
    onFontSelected: (String) -> Unit,
    onSizeChanged: (Int) -> Unit,
    nerdQuery: String,
    onNerdQueryChanged: (String) -> Unit,
    downloadingIds: Set<String>,
    nerdStatus: (NerdFontCatalogEntry) -> NerdFontStatus,
    onNerdFontAction: (NerdFontCatalogEntry) -> Unit,
    onRemoveNerdFont: (NerdFontCatalogEntry) -> Unit
) {
    val uriHandler = LocalUriHandler.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Font size slider
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Font Size",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "${currentSize}sp",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    var sliderSize by remember(currentSize) { mutableFloatStateOf(currentSize.toFloat()) }
                    Slider(
                        value = sliderSize,
                        onValueChange = { sliderSize = it },
                        onValueChangeFinished = { onSizeChanged(sliderSize.toInt()) },
                        valueRange = 6f..42f,
                        steps = 35,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("6sp", style = MaterialTheme.typography.bodySmall)
                        Text("42sp", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        
        // Font preview
        item {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                        .padding(16.dp)
                ) {
                    Text(
                        "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = currentSize.sp
                    )
                    Text(
                        "abcdefghijklmnopqrstuvwxyz",
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = currentSize.sp
                    )
                    Text(
                        "0123456789 !@#$%^&*()_+-=",
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = currentSize.sp
                    )
                    Text(
                        "{}[]|\\:\";<>?,./`~",
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = currentSize.sp
                    )
                }
            }
        }
        
        item {
            Text(
                "Available Fonts",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        
        items(fonts) { font ->
            FontCard(
                font = font,
                isSelected = font.name == currentFont,
                onClick = { onFontSelected(font.name) }
            )
        }

        item {
            Text("Nerd Fonts v3.5.0", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        }
        item {
            Column {
                Text(
                    "Optional downloads from the pinned Nerd Fonts v${NerdFontCatalog.VERSION} release. " +
                        "Archives can be large and remain subject to their upstream licenses.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row {
                    TextButton(onClick = { uriHandler.openUri(NerdFontCatalog.DOWNLOADS_URL) }) {
                        Text("Font downloads")
                    }
                    TextButton(onClick = { uriHandler.openUri(NerdFontCatalog.LICENSES_URL) }) {
                        Text("License details")
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = nerdQuery,
                onValueChange = onNerdQueryChanged,
                label = { Text("Search Nerd Fonts") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search Nerd Fonts") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        items(NerdFontCatalog.optionalEntries.filter { it.family.contains(nerdQuery, ignoreCase = true) }) { entry ->
            val status = if (entry.id in downloadingIds) NerdFontStatus.Downloading else nerdStatus(entry)
            NerdFontCard(
                entry = entry,
                status = status,
                onAction = { onNerdFontAction(entry) },
                onRemove = { onRemoveNerdFont(entry) }
            )
        }
    }
}

@Composable
fun NerdFontCard(
    entry: NerdFontCatalogEntry,
    status: NerdFontStatus,
    onAction: () -> Unit,
    onRemove: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.family, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                val detail = when (status) {
                    NerdFontStatus.Downloadable -> "Downloadable • ${formatArchiveSize(entry.archiveBytes)} archive"
                    NerdFontStatus.Downloading -> "Downloading…"
                    NerdFontStatus.Installed -> "Installed"
                    NerdFontStatus.Selected -> "Selected"
                    NerdFontStatus.Unsupported -> "Unsupported symbols-only font"
                }
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!entry.isMonospaced) {
                    Text(
                        "Proportional font: terminal text may misalign.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            when (status) {
                NerdFontStatus.Downloading -> CircularProgressIndicator(
                    modifier = Modifier
                        .size(24.dp)
                        .semantics { contentDescription = "Downloading ${entry.family}" },
                    strokeWidth = 2.dp
                )
                NerdFontStatus.Unsupported -> Icon(Icons.Default.Block, contentDescription = "Unsupported symbols-only font")
                NerdFontStatus.Selected -> IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove selected ${entry.family}")
                }
                NerdFontStatus.Installed -> Row {
                    IconButton(onClick = onAction, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.CheckCircle, contentDescription = "Apply installed ${entry.family}")
                    }
                    IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove ${entry.family}")
                    }
                }
                NerdFontStatus.Downloadable -> IconButton(onClick = onAction, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Download, contentDescription = "Download ${entry.family}")
                }
            }
        }
    }
}

private fun formatArchiveSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format("%.1f MiB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format("%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
fun FontCard(
    font: FontManager.FontInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(
                if (isSelected) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(12.dp)
                ) else Modifier
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    font.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    if (font.isBuiltIn) "Built-in" else "Custom",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (isSelected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun SettingsTab(
    settings: StylingSettings?,
    onSettingsChanged: (StylingSettings) -> Unit
) {
    if (settings == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }
    
    var localSettings by remember(settings) { mutableStateOf(settings) }
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "Text Rendering",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        
        item {
            SettingsSwitch(
                title = "Bold Text",
                description = "Use bold text for normal text weight",
                checked = localSettings.boldText,
                onCheckedChange = { 
                    localSettings = localSettings.copy(boldText = it)
                    onSettingsChanged(localSettings)
                }
            )
        }
        
        item {
            Text(
                "Cursor",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        
        item {
            SettingsSwitch(
                title = "Cursor Blink",
                description = "Animate cursor blinking",
                checked = localSettings.cursorBlink,
                onCheckedChange = { 
                    localSettings = localSettings.copy(cursorBlink = it)
                    onSettingsChanged(localSettings)
                }
            )
        }
        
        item {
            CursorStyleSelector(
                currentStyle = localSettings.cursorStyle,
                onStyleSelected = { style ->
                    localSettings = localSettings.copy(cursorStyle = style)
                    onSettingsChanged(localSettings)
                }
            )
        }
        
        item {
            Text(
                "Bell",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        
        item {
            SettingsSwitch(
                title = "Bell Enabled",
                description = "Play sound on terminal bell",
                checked = localSettings.bellEnabled,
                onCheckedChange = { 
                    localSettings = localSettings.copy(bellEnabled = it)
                    onSettingsChanged(localSettings)
                }
            )
        }
        
        item {
            SettingsSwitch(
                title = "Vibrate on Bell",
                description = "Vibrate device on terminal bell",
                checked = localSettings.vibrateOnBell,
                onCheckedChange = { 
                    localSettings = localSettings.copy(vibrateOnBell = it)
                    onSettingsChanged(localSettings)
                }
            )
        }
    }
}

@Composable
fun SettingsSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
fun CursorStyleSelector(
    currentStyle: String,
    onStyleSelected: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Cursor Style",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CursorStyleOption(
                    name = "Block",
                    style = "block",
                    isSelected = currentStyle == "block",
                    onClick = { onStyleSelected("block") },
                    modifier = Modifier.weight(1f)
                )
                CursorStyleOption(
                    name = "Underline",
                    style = "underline",
                    isSelected = currentStyle == "underline",
                    onClick = { onStyleSelected("underline") },
                    modifier = Modifier.weight(1f)
                )
                CursorStyleOption(
                    name = "Bar",
                    style = "bar",
                    isSelected = currentStyle == "bar",
                    onClick = { onStyleSelected("bar") },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun CursorStyleOption(
    name: String,
    style: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        modifier = modifier
            .clickable(onClick = onClick)
            .then(
                if (isSelected) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(8.dp)
                ) else Modifier
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Cursor preview
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(Color.Black)
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                when (style) {
                    "block" -> Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(Color.White)
                    )
                    "underline" -> Box(
                        modifier = Modifier
                            .width(16.dp)
                            .height(2.dp)
                            .align(Alignment.BottomCenter)
                            .background(Color.White)
                    )
                    "bar" -> Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(16.dp)
                            .align(Alignment.CenterStart)
                            .background(Color.White)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}
