package com.pickle.patcher.ui.screens
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pickle.patcher.patcher.PatcherViewModel
import java.io.File
private val EditorFont = FontFamily.Monospace
private val EditorSize = 13.sp
private val EditorLineHeight = 19.sp
private val EditorLines = 18
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
                line[first] == ';' -> {
                    style(first, line.length, SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic))
                    var nameStart = first + 1
                    if (nameStart < line.length && line[nameStart] == ' ') nameStart++
                    var nameEnd = nameStart
                    while (nameEnd < line.length && line[nameEnd] != ' ' && line[nameEnd] != ';') nameEnd++
                    if (nameEnd > nameStart && line.getOrNull(nameEnd) != ';') {
                        style(first + 1, nameStart, SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic))
                        style(nameStart, nameEnd, SpanStyle(color = colors.pluginName))
                    }
                }
                line[first] == '[' -> {
                    style(first, line.length, SpanStyle(color = colors.section, fontWeight = FontWeight.Bold))
                }
                else -> {
                    val eq = line.indexOf('=')
                    if (eq > 0) {
                        val semi = line.indexOf(';', eq + 1)
                        val valueEnd = if (semi > eq) semi else line.length
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
                        if (semi > eq) {
                            style(semi, line.length, SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic))
                        }
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
    val pluginName: Color,
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
@Composable
fun PluginsScreen(vm: PatcherViewModel) {
    val inis by vm.pluginInis.collectAsState()
    val text by vm.iniText.collectAsState()
    val dirty by vm.iniDirty.collectAsState()
    var selected by remember { mutableStateOf<File?>(null) }
    var showRevertConfirm by remember { mutableStateOf(false) }
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    LaunchedEffect(Unit) { vm.loadPluginInis() }
    LaunchedEffect(inis) {
        if (selected == null || inis.none { it.file == selected }) {
            selected = inis.firstOrNull()?.file
        }
    }
    LaunchedEffect(selected) { selected?.let(vm::openPluginIni) }
    val file = selected
    val iniColors = IniColors(
        comment = MaterialTheme.colorScheme.onSurfaceVariant,
        pluginName = MaterialTheme.colorScheme.tertiary,
        section = MaterialTheme.colorScheme.primary,
        text = MaterialTheme.colorScheme.onSurface,
        operator = MaterialTheme.colorScheme.outline,
        string = MaterialTheme.colorScheme.tertiary,
        value = MaterialTheme.colorScheme.secondary,
    )
    val lineCount = remember(text) { text.count { it == '\n' } + 1 }
    val editorStyle = MaterialTheme.typography.bodySmall.copy(
        fontFamily = EditorFont,
        fontSize = EditorSize,
        lineHeight = EditorLineHeight,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
    val gutterText = remember(text) { (1..lineCount).joinToString("\n") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (inis.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(inis) { ini ->
                    val active = ini.file == file
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (active) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        modifier = Modifier.clickable { selected = ini.file },
                    ) {
                        Text(
                            ini.name,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (active) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
        if (file == null) {
            Text(
                "No plugin list found. Install addons first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp),
            )
            return@Column
        }
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(vScroll)
                    .horizontalScroll(hScroll),
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                    Text(
                        text = gutterText,
                        style = editorStyle.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        softWrap = false,
                        textAlign = TextAlign.End,
                    )
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        value = text,
                        onValueChange = vm::editIniText,
                        textStyle = editorStyle.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        visualTransformation = IniHighlight(iniColors),
                        modifier = Modifier.widthIn(min = 240.dp),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (dirty) "unsaved changes" else "saved",
                style = MaterialTheme.typography.bodySmall,
                color = if (dirty) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (dirty) {
                TextButton(onClick = { showRevertConfirm = true }) {
                    Text("Revert")
                }
                Spacer(Modifier.width(8.dp))
            }
            FilledTonalButton(
                onClick = { vm.savePluginIni(file) },
                enabled = dirty,
                modifier = Modifier.height(38.dp),
                contentPadding = PaddingValues(horizontal = 18.dp),
            ) {
                Icon(Icons.Filled.Save, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save")
            }
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
