package com.yoriva.ui.screens
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yoriva.patcher.ConfigFileInfo
import com.yoriva.patcher.PatcherViewModel
private val EditorFont = FontFamily.Monospace
private const val EDITOR_FONT_SIZE = 14
private val STRING_REGEX = Regex("\"[^\"]*\"")
private val NUMBER_REGEX = Regex("(?<![\\w.])\\d+(\\.\\d+)?(?![\\w.])")
private fun findUnquoted(line: String, marker: String): Int {
    var inQuotes = false
    var i = 0
    while (i <= line.length - marker.length) {
        val c = line[i]
        if (c == '"') inQuotes = !inQuotes
        if (!inQuotes && line.regionMatches(i, marker, 0, marker.length)) return i
        i++
    }
    return -1
}
private fun highlightIni(text: String, colors: IniColors): AnnotatedString = buildAnnotatedString {
    var pos = 0
    while (pos <= text.length) {
        val lineEnd = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
        val line = text.substring(pos, lineEnd)
        var first = 0
        while (first < line.length && line[first] == ' ') first++
        fun style(from: Int, to: Int, span: SpanStyle) {
            if (to > from) addStyle(span, pos + from, pos + to)
        }
        if (first < line.length) {
            when {
                line[first] == ';' || line.startsWith("//", first) -> {
                    style(first, line.length, SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic))
                }
                line[first] == '[' -> {
                    style(first, line.length, SpanStyle(color = colors.section, fontWeight = FontWeight.Bold))
                }
                else -> {
                    val commentIdx = findUnquoted(line, "//")
                    val bodyEnd = if (commentIdx > first) commentIdx else line.length
                    val eq = line.indexOf('=')
                    if (eq > first && eq < bodyEnd) {
                        val semi = line.indexOf(';', eq + 1).let { if (it > 0) it else line.length }
                        val valueEnd = minOf(semi, bodyEnd)
                        style(first, eq, SpanStyle(color = colors.text))
                        style(eq, eq + 1, SpanStyle(color = colors.operator))
                        var valueStart = eq + 1
                        while (valueStart < valueEnd && line[valueStart] == ' ') valueStart++
                        val value = line.substring(valueStart, valueEnd)
                        style(
                            valueStart,
                            valueEnd,
                            SpanStyle(color = if (value.endsWith(".amxx")) colors.string else colors.value),
                        )
                        if (semi < line.length) {
                            style(semi, line.length, SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic))
                        }
                    } else {
                        STRING_REGEX.findAll(line).forEach { m ->
                            style(m.range.first, m.range.last + 1, SpanStyle(color = colors.string))
                        }
                        NUMBER_REGEX.findAll(line.substring(0, bodyEnd)).forEach { m ->
                            style(m.range.first, m.range.last + 1, SpanStyle(color = colors.value))
                        }
                    }
                    if (commentIdx > first) {
                        style(commentIdx, line.length, SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic))
                    }
                }
            }
        }
        append(line)
        if (lineEnd < text.length) {
            append('\n')
            pos = lineEnd + 1
        } else {
            pos = text.length + 1
        }
    }
}
private data class IniColors(
    val comment: Color,
    val section: Color,
    val text: Color,
    val operator: Color,
    val string: Color,
    val value: Color,
)
private class IniHighlight(private val colors: IniColors) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(highlightIni(text.text, colors), OffsetMapping.Identity)
}
private fun autoIndentOnNewLine(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    val a = old.text
    val b = new.text
    if (b.length <= a.length) return new
    var start = 0
    while (start < minOf(a.length, b.length) && a[start] == b[start]) start++
    var aEnd = a.length
    var bEnd = b.length
    while (aEnd > start && bEnd > start && a[aEnd - 1] == b[bEnd - 1]) { aEnd--; bEnd-- }
    val inserted = b.substring(start, bEnd)
    if (inserted != "\n") return new
    val lineStart = a.lastIndexOf('\n', start - 1) + 1
    var indent = 0
    while (lineStart + indent < a.length) {
        val c = a[lineStart + indent]
        if (c == ' ' || c == '\t') indent++ else break
    }
    if (indent == 0) return new
    val prefix = a.substring(lineStart, lineStart + indent)
    val finalText = b.substring(0, start + 1) + prefix + b.substring(start + 1)
    val cursor = start + 1 + prefix.length
    return TextFieldValue(finalText, TextRange(cursor, cursor))
}
private val GROUP_ORDER = listOf("AMX Mod X", "YapB")
@Composable
fun PluginsScreen(
    vm: PatcherViewModel,
    onSelect: (ConfigFileInfo) -> Unit,
) {
    val files by vm.pluginInis.collectAsState()
    val configuration = LocalConfiguration.current
    val isTablet = configuration.screenWidthDp >= 600

    LaunchedEffect(Unit) { vm.loadPluginInis() }

    if (isTablet) {
        var activeFile by remember { mutableStateOf<ConfigFileInfo?>(null) }
        LaunchedEffect(files) {
            if (activeFile == null && files.isNotEmpty()) {
                activeFile = files.firstOrNull { it.name.equals("plugins.ini", ignoreCase = true) } ?: files.first()
            }
        }
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .width(320.dp)
                    .fillMaxHeight(),
            ) {
                ConfigList(
                    files = files,
                    selectedFile = activeFile,
                    onSelect = { activeFile = it },
                )
            }
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                val current = activeFile
                if (current != null) {
                    PluginEditorScreen(vm = vm, info = current)
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Select a config file to edit",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    } else {
        ConfigList(files = files, selectedFile = null, onSelect = onSelect)
    }
}
@Composable
private fun ConfigList(
    files: List<ConfigFileInfo>,
    selectedFile: ConfigFileInfo? = null,
    onSelect: (ConfigFileInfo) -> Unit,
) {
    val grouped = files.groupBy { it.group }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        GROUP_ORDER.forEach { group ->
            val items = grouped[group].orEmpty()
            if (items.isEmpty()) return@forEach
            item {
                Text(
                    text = group,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 6.dp),
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items.forEachIndexed { index, info ->
                        val shape = getGroupedItemShape(index, items.size)
                        val isSelected = selectedFile?.file?.absolutePath == info.file.absolutePath
                        Card(
                            shape = shape,
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                                                 else MaterialTheme.colorScheme.surfaceContainer,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .clickable { onSelect(info) },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 8.dp, top = 13.dp, bottom = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = info.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                    color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                                            else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    Icons.Filled.ChevronRight,
                                    contentDescription = null,
                                    tint = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        if (files.isEmpty()) {
            item {
                Text(
                    "No config files found. Install the game and addons first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 32.dp, start = 24.dp, end = 24.dp),
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
private data class ParsedPluginItem(
    val lineIndex: Int,
    val isPlugin: Boolean,
    val isEnabled: Boolean,
    val name: String,
    val comment: String,
    val isHeader: Boolean,
    val headerTitle: String,
)

private fun parseIniLines(text: String): List<ParsedPluginItem> {
    val items = ArrayList<ParsedPluginItem>()
    val lines = text.lines()
    for ((idx, line) in lines.withIndex()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            items.add(
                ParsedPluginItem(
                    lineIndex = idx,
                    isPlugin = false,
                    isEnabled = false,
                    name = "",
                    comment = "",
                    isHeader = true,
                    headerTitle = trimmed.substring(1, trimmed.length - 1).trim(),
                )
            )
            continue
        }
        val isCommented = trimmed.startsWith(";") || trimmed.startsWith("//")
        val content = if (isCommented) {
            if (trimmed.startsWith(";")) trimmed.substring(1).trim()
            else trimmed.substring(2).trim()
        } else {
            trimmed
        }
        val amxxMatch = Regex("([a-zA-Z0-9_.-]+\\.amxx)", RegexOption.IGNORE_CASE).find(content)
        if (amxxMatch != null) {
            val name = amxxMatch.value
            val desc = content.substring(amxxMatch.range.last + 1).trimStart(' ', ';', '-', ':').trim()
            items.add(
                ParsedPluginItem(
                    lineIndex = idx,
                    isPlugin = true,
                    isEnabled = !isCommented,
                    name = name,
                    comment = desc,
                    isHeader = false,
                    headerTitle = "",
                )
            )
        } else if (trimmed.startsWith(";") && trimmed.length > 3 && !trimmed.contains("=")) {
            val headerClean = trimmed.trimStart(';', ' ', '-').trimEnd(';', ' ', '-')
            if (headerClean.isNotBlank()) {
                items.add(
                    ParsedPluginItem(
                        lineIndex = idx,
                        isPlugin = false,
                        isEnabled = false,
                        name = "",
                        comment = "",
                        isHeader = true,
                        headerTitle = headerClean,
                    )
                )
            }
        }
    }
    return items
}

@Composable
fun PluginEditorScreen(
    vm: PatcherViewModel,
    info: ConfigFileInfo,
) {
    val iniText by vm.iniText.collectAsState()
    val dirty by vm.iniDirty.collectAsState()
    val file = info.file
    val isPluginsIni = info.name.contains("plugins", ignoreCase = true) || iniText.contains(".amxx", ignoreCase = true)
    var selectedTab by remember(info.file) { mutableStateOf(if (isPluginsIni) 0 else 1) }
    var tfv by remember { mutableStateOf(TextFieldValue("")) }
    var showRevertConfirm by remember { mutableStateOf(false) }
    var showAddPluginDialog by remember { mutableStateOf(false) }
    var newPluginName by remember { mutableStateOf("") }
    var newPluginDesc by remember { mutableStateOf("") }
    val undoStack = remember { mutableStateListOf<TextFieldValue>() }
    val redoStack = remember { mutableStateListOf<TextFieldValue>() }
    val vScroll = rememberScrollState()
    val iniColors = IniColors(
        comment = MaterialTheme.colorScheme.onSurfaceVariant,
        section = MaterialTheme.colorScheme.primary,
        text = MaterialTheme.colorScheme.onSurface,
        operator = MaterialTheme.colorScheme.outline,
        string = MaterialTheme.colorScheme.tertiary,
        value = MaterialTheme.colorScheme.secondary,
    )
    LaunchedEffect(file) {
        vm.openPluginIni(file)
        undoStack.clear()
        redoStack.clear()
        tfv = TextFieldValue("")
    }
    LaunchedEffect(iniText) {
        if (iniText != tfv.text) {
            val clamped = TextRange(
                tfv.selection.start.coerceIn(0, iniText.length),
                tfv.selection.end.coerceIn(0, iniText.length),
            )
            tfv = TextFieldValue(iniText, selection = clamped)
        }
    }
    val editorStyle = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = EditorFont,
        fontSize = EDITOR_FONT_SIZE.sp,
        lineHeight = (EDITOR_FONT_SIZE + 6).sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
    val highlight = remember(iniColors) { IniHighlight(iniColors) }

    fun togglePluginAt(lineIndex: Int, enabled: Boolean) {
        val lines = iniText.lines().toMutableList()
        if (lineIndex in lines.indices) {
            val current = lines[lineIndex]
            val trimmed = current.trim()
            val isCommented = trimmed.startsWith(";") || trimmed.startsWith("//")
            if (enabled && isCommented) {
                lines[lineIndex] = if (trimmed.startsWith(";")) trimmed.substring(1).trimStart()
                                   else trimmed.substring(2).trimStart()
            } else if (!enabled && !isCommented) {
                lines[lineIndex] = "; $trimmed"
            }
            val updated = lines.joinToString("\n")
            undoStack.add(tfv)
            if (undoStack.size > 100) undoStack.removeAt(0)
            redoStack.clear()
            vm.editIniText(updated)
        }
    }

    fun toggleAllPlugins(enabled: Boolean) {
        val lines = iniText.lines().toMutableList()
        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            val content = if (trimmed.startsWith(";")) trimmed.substring(1).trim()
                           else if (trimmed.startsWith("//")) trimmed.substring(2).trim()
                           else trimmed
            if (content.contains(".amxx", ignoreCase = true)) {
                val isCommented = trimmed.startsWith(";") || trimmed.startsWith("//")
                if (enabled && isCommented) {
                    lines[i] = if (trimmed.startsWith(";")) trimmed.substring(1).trimStart()
                               else trimmed.substring(2).trimStart()
                } else if (!enabled && !isCommented) {
                    lines[i] = "; $trimmed"
                }
            }
        }
        val updated = lines.joinToString("\n")
        undoStack.add(tfv)
        if (undoStack.size > 100) undoStack.removeAt(0)
        redoStack.clear()
        vm.editIniText(updated)
    }

    fun deletePluginAt(lineIndex: Int) {
        val lines = iniText.lines().toMutableList()
        if (lineIndex in lines.indices) {
            lines.removeAt(lineIndex)
            val updated = lines.joinToString("\n")
            undoStack.add(tfv)
            if (undoStack.size > 100) undoStack.removeAt(0)
            redoStack.clear()
            vm.editIniText(updated)
        }
    }

    fun addNewPlugin(name: String, desc: String) {
        val cleanName = if (name.endsWith(".amxx", ignoreCase = true)) name else "$name.amxx"
        val newLine = if (desc.isNotBlank()) "$cleanName ; $desc" else cleanName
        val updated = if (iniText.isBlank()) newLine else "$iniText\n$newLine"
        undoStack.add(tfv)
        if (undoStack.size > 100) undoStack.removeAt(0)
        redoStack.clear()
        vm.editIniText(updated)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isPluginsIni) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium),
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Visual Toggles", fontWeight = FontWeight.Bold) },
                    icon = { Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Raw Config", fontWeight = FontWeight.Bold) },
                    icon = { Icon(Icons.Filled.Code, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }

        if (selectedTab == 0 && isPluginsIni) {
            val parsed = remember(iniText) { parseIniLines(iniText) }
            val pluginCount = parsed.count { it.isPlugin }
            val activeCount = parsed.count { it.isPlugin && it.isEnabled }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "$activeCount / $pluginCount Active Plugins",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { toggleAllPlugins(true) }) {
                        Text("Enable All", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = { toggleAllPlugins(false) }) {
                        Text("Disable All", style = MaterialTheme.typography.labelMedium)
                    }
                    FilledTonalButton(
                        onClick = {
                            newPluginName = ""
                            newPluginDesc = ""
                            showAddPluginDialog = true
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(parsed) { item ->
                        if (item.isHeader) {
                            Text(
                                text = item.headerTitle,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 2.dp),
                            )
                        } else if (item.isPlugin) {
                            Card(
                                shape = MaterialTheme.shapes.medium,
                                colors = CardDefaults.cardColors(
                                    containerColor = if (item.isEnabled) MaterialTheme.colorScheme.surfaceContainer
                                    else MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.6f),
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.Extension,
                                        contentDescription = null,
                                        tint = if (item.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.name,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = EditorFont),
                                            fontWeight = if (item.isEnabled) FontWeight.Bold else FontWeight.Normal,
                                            color = if (item.isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (item.comment.isNotBlank()) {
                                            Text(
                                                text = item.comment,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { deletePluginAt(item.lineIndex) },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "Delete",
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    Switch(
                                        checked = item.isEnabled,
                                        onCheckedChange = { togglePluginAt(item.lineIndex, it) },
                                        modifier = Modifier.size(38.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(vScroll)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    BasicTextField(
                        value = tfv,
                        onValueChange = { newValue ->
                            val indented = autoIndentOnNewLine(tfv, newValue)
                            if (indented.text != tfv.text) {
                                undoStack.add(tfv)
                                if (undoStack.size > 100) undoStack.removeAt(0)
                                redoStack.clear()
                                vm.editIniText(indented.text)
                            }
                            tfv = indented
                        },
                        textStyle = editorStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        visualTransformation = highlight,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(
                onClick = {
                    if (undoStack.isNotEmpty()) {
                        redoStack.add(tfv)
                        tfv = undoStack.removeAt(undoStack.lastIndex)
                        vm.editIniText(tfv.text)
                    }
                },
                enabled = undoStack.isNotEmpty(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
            }
            IconButton(
                onClick = {
                    if (redoStack.isNotEmpty()) {
                        undoStack.add(tfv)
                        tfv = redoStack.removeAt(redoStack.lastIndex)
                        vm.editIniText(tfv.text)
                    }
                },
                enabled = redoStack.isNotEmpty(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
            }
            Spacer(Modifier.weight(1f))
            if (dirty) {
                TextButton(onClick = { showRevertConfirm = true }) {
                    Text("Revert")
                }
            }
            FilledTonalButton(
                onClick = { vm.savePluginIni(file) },
                enabled = dirty,
                modifier = Modifier.height(40.dp),
                contentPadding = PaddingValues(horizontal = 18.dp),
            ) {
                Icon(Icons.Filled.Save, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save")
            }
        }

        if (showAddPluginDialog) {
            AlertDialog(
                onDismissRequest = { showAddPluginDialog = false },
                title = { Text("Add AMXX Plugin") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newPluginName,
                            onValueChange = { newPluginName = it },
                            label = { Text("Plugin Name (e.g. zombi_plague.amxx)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = newPluginDesc,
                            onValueChange = { newPluginDesc = it },
                            label = { Text("Description (Optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    FilledTonalButton(
                        onClick = {
                            if (newPluginName.isNotBlank()) {
                                addNewPlugin(newPluginName.trim(), newPluginDesc.trim())
                                showAddPluginDialog = false
                            }
                        },
                        enabled = newPluginName.isNotBlank(),
                    ) {
                        Text("Add")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddPluginDialog = false }) { Text("Cancel") }
                },
            )
        }

        if (showRevertConfirm) {
            AlertDialog(
                onDismissRequest = { showRevertConfirm = false },
                title = { Text("Discard changes") },
                text = { Text("Unsaved changes to ${file.name} will be lost.") },
                confirmButton = {
                    TextButton(onClick = {
                        vm.revertPluginIni(file)
                        showRevertConfirm = false
                    }) {
                        Text("Discard", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRevertConfirm = false }) { Text("Cancel") }
                },
            )
        }
    }
}
