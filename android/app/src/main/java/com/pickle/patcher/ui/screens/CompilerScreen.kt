package com.pickle.patcher.ui.screens
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pickle.patcher.patcher.CompileState
import com.pickle.patcher.patcher.PatcherViewModel
import com.pickle.patcher.patcher.SmaSource
@Composable
fun CompilerScreen(
    vm: PatcherViewModel,
    gameClientInstalled: Boolean = true,
    onGoToPatch: () -> Unit = {},
) {
    if (!gameClientInstalled) {
        LockedScreen(
            title = "Patch it first",
            description = "Compiler becomes available after the game is patched and installed. Download the source APK on the Patch screen, build the patch, install the patched client — then come back here to build your plugins.",
            ctaLabel = "Open Patch screen",
            onCta = onGoToPatch,
        )
        return
    }
    val scripts by vm.scripts.collectAsState()
    val compile by vm.compile.collectAsState()
    val scriptRoot by vm.scriptRoot.collectAsState()
    var selected by remember { mutableStateOf(setOf<String>()) }
    var stampError by remember { mutableStateOf<Pair<String, String>?>(null) }
    val selectedSources = scripts.filter { it.path in selected }
    val failures = (compile as? CompileState.Done)?.failures ?: emptyMap()
    val context = LocalContext.current
    DisposableEffect(Unit) {
        vm.startScriptWatcher()
        onDispose { vm.stopScriptWatcher() }
    }
    LaunchedEffect(scriptRoot) {
        if (scriptRoot == null) vm.refreshScripts()
    }
    LaunchedEffect(scripts) {
        val available = scripts.mapTo(HashSet()) { it.path }
        selected = selected.intersect(available)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        GroupedItem(
            title = if (scriptRoot != null) "Plugins folder found" else "Plugins folder",
            subtitle = scriptRoot ?: "Searching for the game folder…",
            index = 0,
            total = 1,
        )
        PluginsSection(
            scriptRoot = scriptRoot,
            scripts = scripts,
            selected = selected,
            failures = failures,
            onToggle = { path ->
                selected = if (path in selected) selected - path else selected + path
            },
            onSelectAll = {
                selected = if (selected.size != scripts.size) {
                    scripts.map { it.path }.toSet()
                } else {
                    emptySet()
                }
            },
            onErrorClick = { name, message -> stampError = name to message },
            onRetry = { vm.refreshScripts() },
        )
        BuildSection(
            compile = compile,
            scriptRoot = scriptRoot,
            scriptCount = scripts.size,
            selectedSources = selectedSources,
            onCompile = { vm.compileAll(selectedSources) },
        )
        Spacer(Modifier.height(16.dp))
    }
    stampError?.let { (name, message) ->
        ErrorDialog(
            name = name.substringAfterLast('/'),
            message = message,
            onDismiss = { stampError = null },
        )
    }
}
@Composable
private fun PluginsSection(
    scriptRoot: String?,
    scripts: List<SmaSource>,
    selected: Set<String>,
    failures: Map<String, String>,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onErrorClick: (String, String) -> Unit,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Plugins",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (scripts.isNotEmpty()) {
                TextButton(
                    onClick = onSelectAll,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(
                        text = if (selected.size == scripts.size) "Clear" else "Select all",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        when {
            scriptRoot == null -> RetryCard(
                text = "Game folder not detected yet. Install addons first, then tap Retry.",
                onRetry = onRetry,
            )
            scripts.isEmpty() -> RetryCard(
                text = "No .sma plugin sources found in the detected folder.",
                onRetry = onRetry,
            )
            else -> Column {
                scripts.forEachIndexed { index, source ->
                    ScriptRow(
                        source = source,
                        selected = source.path in selected,
                        error = failures[source.path],
                        index = index,
                        total = scripts.size,
                        onClick = { onToggle(source.path) },
                        onErrorClick = { message -> onErrorClick(source.path, message) },
                    )
                }
            }
        }
    }
}
@Composable
private fun ScriptRow(
    source: SmaSource,
    selected: Boolean,
    error: String?,
    index: Int,
    total: Int,
    onClick: () -> Unit,
    onErrorClick: (String) -> Unit,
) {
    val container = if (error != null) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val titleColor = if (error != null) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clickable(onClick = onClick),
        shape = getGroupedItemShape(index, total),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (error != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Compile error",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when {
                error != null -> IconButton(
                    onClick = { onErrorClick(error) },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ErrorOutline,
                        contentDescription = "Compile error",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
                else -> Icon(
                    imageVector = if (selected) {
                        Icons.Filled.CheckBox
                    } else {
                        Icons.Outlined.CheckBoxOutlineBlank
                    },
                    contentDescription = if (selected) "Selected" else "Not selected",
                    tint = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    },
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
@Composable
private fun BuildSection(
    compile: CompileState,
    scriptRoot: String?,
    scriptCount: Int,
    selectedSources: List<SmaSource>,
    onCompile: () -> Unit,
) {
    val compiling = compile as? CompileState.Compiling
    val done = compile as? CompileState.Done
    val failures = done?.failures ?: emptyMap()
    val title = when {
        compiling != null -> "Compiling…"
        done != null && failures.isNotEmpty() -> "Build Finished with Errors"
        done != null -> "Build Succeeded"
        else -> "Build Plugins"
    }
    val subtitle = when {
        compiling != null -> compiling.source
        done != null && failures.isNotEmpty() -> "${done.ok} compiled · ${failures.size} failed"
        done != null -> "${done.ok} plugin${if (done.ok == 1) "" else "s"} compiled"
        selectedSources.isEmpty() -> when {
            scriptRoot == null -> "Waiting for the game folder to be detected…"
            scriptCount == 0 -> "No .sma plugin sources found in the detected folder."
            else -> "Select one or more plugins above."
        }
        selectedSources.size == 1 -> selectedSources.first().name
        else -> "${selectedSources.size} plugins selected"
    }
    var showLog by remember { mutableStateOf(false) }
    val logText = when (compile) {
        is CompileState.Done -> compile.log
        is CompileState.Failed -> compile.message
        else -> null
    }
    GroupedItem(
        title = title,
        subtitle = subtitle,
        index = 0,
        total = 1,
        error = done != null && failures.isNotEmpty(),
        trailing = {
            if (compiling != null) {
                SmallProgressButton("Building")
            } else {
                SmallButton(
                    text = "Compile",
                    icon = Icons.Filled.PlayArrow,
                    filled = true,
                    enabled = selectedSources.isNotEmpty(),
                    onClick = onCompile,
                )
            }
        },
    ) {
        if (compiling != null) {
            ProgressFooter(progress = null, left = compiling.source, right = "")
        }
        if (logText != null) {
            TextButton(
                onClick = { showLog = !showLog },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(if (showLog) "Hide Build Log" else "View Build Log")
            }
            AnimatedVisibility(visible = showLog) {
                Box(
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                ) {
                    LogBox(text = logText, error = failures.isNotEmpty())
                }
            }
        }
    }
}
@Composable
private fun RetryCard(text: String, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = getGroupedItemShape(0, 1),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}
@Composable
private fun EmptyCard(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = getGroupedItemShape(0, 1),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
@Composable
private fun ErrorDialog(name: String, message: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scroll = rememberScrollState()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text(
                text = name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(scroll),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    val clip = context.getSystemService(ClipboardManager::class.java)
                    clip?.setPrimaryClip(ClipData.newPlainText(name, message))
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Copy")
            }
        },
    )
}
@Composable
private fun LogBox(text: String, error: Boolean = false) {
    val scrollState = rememberScrollState()
    LaunchedEffect(text) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp, max = 240.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            ),
            color = if (error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(12.dp),
        )
    }
}