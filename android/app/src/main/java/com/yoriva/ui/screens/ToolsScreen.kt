package com.yoriva.ui.screens
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.yoriva.patcher.HubPlugin
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
import com.yoriva.patcher.CompileState
import com.yoriva.patcher.PatcherViewModel
import com.yoriva.patcher.SmaSource
@Composable
fun ToolsScreen(
    vm: PatcherViewModel,
    gameInstalled: Boolean = true,
    onGoToPatch: () -> Unit = {},
    onOpenPluginConfig: () -> Unit = {},
) {
    if (!gameInstalled) {
        LockedScreen(
            title = "Patch it first",
            description = "Patch and install the game to use the tools.",
            ctaLabel = "Open Patch screen",
            onCta = onGoToPatch,
        )
        return
    }
    val scripts by vm.scripts.collectAsState()
    val compile by vm.compile.collectAsState()
    val scriptRoot by vm.scriptRoot.collectAsState()
    val fastDlState by vm.fastDlState.collectAsState()
    val hubInstalledMap by vm.hubInstalledMap.collectAsState()
    val hubInstalling by vm.hubInstalling.collectAsState()
    var showHubDialog by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var stampError by remember { mutableStateOf<Pair<String, String>?>(null) }
    val selectedSources = scripts.filter { it.path in selected }
    val failures = (compile as? CompileState.Done)?.failures ?: emptyMap()
    val context = LocalContext.current
    val pluginPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.addPluginSource(uri) }
    fun pickPlugin() {
        pluginPicker.launch(arrayOf("*/*"))
    }
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
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                GroupedItem(
                    title = "Config Editor",
                    subtitle = "Edit AMX Mod X and YapB config files",
                    index = 0,
                    total = 3,
                    trailing = {
                        SmallButton(
                            text = "Edit",
                            icon = Icons.Filled.Edit,
                            onClick = onOpenPluginConfig,
                        )
                    },
                )
                GroupedItem(
                    title = "Plugin Hub",
                    subtitle = "1-Click install curated AMXX mods (Zombie, CSDM, RS...)",
                    index = 1,
                    total = 3,
                    trailing = {
                        SmallButton(
                            text = "Browse",
                            icon = Icons.Filled.Storefront,
                            onClick = {
                                vm.refreshHub()
                                showHubDialog = true
                            },
                        )
                    },
                )
                GroupedItem(
                    title = "FastDL Server",
                    subtitle = if (fastDlState.isRunning) "${fastDlState.downloadUrl} (${fastDlState.totalDownloads} files sent)"
                               else "Local HTTP fast-download for LAN/hotspot friends",
                    index = 2,
                    total = 3,
                    trailing = {
                        Switch(
                            checked = fastDlState.isRunning,
                            onCheckedChange = { vm.toggleFastDlServer() },
                            modifier = Modifier.size(38.dp),
                        )
                    },
                )
            }
        }
        if (fastDlState.isRunning) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Wifi,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "FastDL Active: ${fastDlState.downloadUrl}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        Text(
                            text = "Share this with players on your hotspot so maps & sounds download in seconds.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = fastDlState.cvarCommand,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            FilledTonalButton(
                                onClick = {
                                    val clip = context.getSystemService(ClipboardManager::class.java)
                                    clip?.setPrimaryClip(ClipData.newPlainText("sv_downloadurl", fastDlState.cvarCommand))
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp),
                            ) {
                                Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Copy Cvar", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(10.dp)) }
        item {
            GroupedItem(
                title = if (scriptRoot != null) "Plugins folder found" else "Plugins folder",
                subtitle = scriptRoot ?: "Searching for the game folder…",
                index = 0,
                total = 1,
            )
        }
        item {
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
            onAddPlugin = { pickPlugin() },
        )
        }
        item {
            BuildSection(
                compile = compile,
                scriptRoot = scriptRoot,
                scriptCount = scripts.size,
                selectedSources = selectedSources,
                onCompile = { vm.compileAll(selectedSources) },
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
    stampError?.let { (name, message) ->
        ErrorDialog(
            name = name.substringAfterLast('/'),
            message = message,
            onDismiss = { stampError = null },
        )
    }
    if (showHubDialog) {
        PluginHubDialog(
            catalog = vm.hubCatalog,
            installedMap = hubInstalledMap,
            installingId = hubInstalling,
            onInstall = { plugin ->
                vm.installHubPlugin(plugin) { ok, msg -> }
            },
            onUninstall = { plugin ->
                vm.uninstallHubPlugin(plugin) { }
            },
            onDismiss = { showHubDialog = false },
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
    onAddPlugin: () -> Unit,
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
            FilledTonalButton(
                onClick = onAddPlugin,
                modifier = Modifier.height(38.dp),
                shape = RoundedCornerShape(percent = 50),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Add",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (scripts.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                FilledTonalButton(
                    onClick = onSelectAll,
                    modifier = Modifier.height(38.dp),
                    shape = RoundedCornerShape(percent = 50),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
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
            else -> {
                val indexByPath = remember(scripts) {
                    scripts.withIndex().associate { (i, s) -> s.path to i }
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 176.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(scripts, key = { it.path }) { source ->
                        val index = indexByPath[source.path] ?: 0
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
            .padding(vertical = 0.dp)
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
        compiling != null -> null
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
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp, max = 240.dp),
    ) {
        val scope = rememberCoroutineScope()
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
            onTextLayout = {
                scope.launch { scrollState.scrollTo(scrollState.maxValue) }
            },
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(12.dp),
        )
    }
}

@Composable
private fun PluginHubDialog(
    catalog: List<HubPlugin>,
    installedMap: Map<String, Boolean>,
    installingId: String?,
    onInstall: (HubPlugin) -> Unit,
    onUninstall: (HubPlugin) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Storefront, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("AMXX Plugin Hub")
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(catalog) { plugin ->
                    val isInstalled = installedMap[plugin.id] == true
                    val isInstalling = installingId == plugin.id
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = plugin.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                        ) {
                                            Text(
                                                text = plugin.category,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                            )
                                        }
                                    }
                                    Text(
                                        text = "by ${plugin.author} · v${plugin.version}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (isInstalling) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                } else if (isInstalled) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = "Installed",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        IconButton(
                                            onClick = { onUninstall(plugin) },
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                Icons.Filled.Delete,
                                                contentDescription = "Uninstall",
                                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                } else {
                                    FilledTonalButton(
                                        onClick = { onInstall(plugin) },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp),
                                    ) {
                                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Install", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            Text(
                                text = plugin.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (plugin.requiredModules.isNotEmpty()) {
                                Text(
                                    text = "Modules: ${plugin.requiredModules.joinToString(", ")}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
