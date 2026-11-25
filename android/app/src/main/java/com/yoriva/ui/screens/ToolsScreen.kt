package com.yoriva.ui.screens
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yoriva.patcher.AddonsState
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
    val scripts by vm.scripts.collectAsState()
    val compile by vm.compile.collectAsState()
    val scriptRoot by vm.scriptRoot.collectAsState()
    val cstrikeReady by vm.cstrikeReady.collectAsState()
    val addonsInstalled by vm.addonsInstalled.collectAsState()
    val addonsState by vm.addons.collectAsState()
    val engineInstalled by vm.engineInstalled.collectAsState()
    val clientInstalled by vm.clientInstalled.collectAsState()
    val toolsReady = (cstrikeReady && addonsInstalled) || scriptRoot != null

    LaunchedEffect(Unit) {
        vm.refreshGameInstalled()
    }

    if (!toolsReady) {
        ToolsSetupGuide(
            cstrikeReady = cstrikeReady,
            addonsInstalled = addonsInstalled,
            addonsState = addonsState,
            engineInstalled = engineInstalled,
            clientInstalled = clientInstalled,
            onInstallAddons = { vm.installAddons() },
            onRefresh = {
                vm.refreshGameInstalled()
                vm.refreshScripts()
            },
            onGoToPatch = onGoToPatch,
        )
        return
    }

    var selected by remember { mutableStateOf(setOf<String>()) }
    var stampError by remember { mutableStateOf<Pair<String, String>?>(null) }
    val selectedSources = scripts.filter { it.path in selected }
    val failures = (compile as? CompileState.Done)?.failures ?: emptyMap()
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            GroupedItem(
                title = "Config Editor",
                subtitle = "Edit AMX Mod X and YapB config files",
                index = 0,
                total = 2,
                trailing = {
                    SmallButton(
                        text = "Edit",
                        icon = Icons.Filled.Edit,
                        onClick = onOpenPluginConfig,
                    )
                },
            )
            BuildSection(
                compile = compile,
                scriptRoot = scriptRoot,
                scriptCount = scripts.size,
                selectedSources = selectedSources,
                index = 1,
                total = 2,
                onCompile = { vm.compileAll(selectedSources) },
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 4.dp, bottom = 8.dp),
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
                onClick = { pickPlugin() },
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
                    onClick = {
                        selected = if (selected.size != scripts.size) {
                            scripts.map { it.path }.toSet()
                        } else {
                            emptySet()
                        }
                    },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when {
                scriptRoot == null -> item(key = "retry_folder") {
                    RetryCard(
                        text = "Game folder not detected yet. Install addons first, then tap Retry.",
                        onRetry = { vm.refreshScripts() },
                    )
                }
                scripts.isEmpty() -> item(key = "retry_empty") {
                    RetryCard(
                        text = "No .sma plugin sources found in the detected folder.",
                        onRetry = { vm.refreshScripts() },
                    )
                }
                else -> itemsIndexed(scripts, key = { _, s -> s.path }) { index, source ->
                    ScriptRow(
                        source = source,
                        selected = source.path in selected,
                        error = failures[source.path],
                        index = index,
                        total = scripts.size,
                        onClick = {
                            selected = if (source.path in selected) selected - source.path else selected + source.path
                        },
                        onErrorClick = { message -> stampError = source.path to message },
                    )
                    if (index < scripts.lastIndex) {
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
            item(key = "bottom_pad") { Spacer(Modifier.height(12.dp)) }
        }
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
    val shape = getGroupedItemShape(index, total)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
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
    index: Int = 0,
    total: Int = 1,
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
            else -> "Select one or more plugins below."
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
        index = index,
        total = total,
        error = done != null && failures.isNotEmpty(),
        animateSize = false,
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
            AnimatedVisibility(
                visible = showLog,
                enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(220)) + fadeOut(tween(140)),
            ) {
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
private fun LogBox(
    text: String,
    error: Boolean = false,
    maxHeight: androidx.compose.ui.unit.Dp = 220.dp,
) {
    val scrollState = rememberScrollState()
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp, max = maxHeight),
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
private fun ToolsSetupGuide(
    cstrikeReady: Boolean,
    addonsInstalled: Boolean,
    addonsState: AddonsState,
    engineInstalled: Boolean,
    clientInstalled: Boolean,
    onInstallAddons: () -> Unit,
    onRefresh: () -> Unit,
    onGoToPatch: () -> Unit,
) {
    val downloading = addonsState as? AddonsState.Downloading
    val addonError = (addonsState as? AddonsState.Error)?.message
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = if (!cstrikeReady) "Set up game folder" else "Install addons to use Tools",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = if (!cstrikeReady) {
                "Tools edit AMX Mod X / YapB configs and compile .sma scripts directly inside your cstrike folder."
            } else {
                "Your cstrike folder is ready. Download AMX Mod X & YapB addons to unlock the Config Editor and Plugin Compiler."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!addonError.isNullOrBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(
                    text = addonError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ToolsCheckRow(
                icon = Icons.Filled.Folder,
                title = "Game folder (cstrike)",
                subtitle = if (cstrikeReady) "Detected on storage" else "Put cstrike in storage (.xash or xash)",
                done = cstrikeReady,
                index = 0,
                total = 3,
            )
            ToolsCheckRow(
                icon = Icons.Filled.Download,
                title = "AMX Mod X & YapB addons",
                subtitle = when {
                    addonsInstalled -> "Installed"
                    downloading != null -> downloading.step
                    cstrikeReady -> "Required for Config Editor & Compiler"
                    else -> "Waiting for cstrike folder"
                },
                done = addonsInstalled,
                progress = downloading?.percent,
                index = 1,
                total = 3,
                trailing = if (cstrikeReady && !addonsInstalled && downloading == null) {
                    {
                        FilledTonalButton(
                            onClick = onInstallAddons,
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        ) {
                            Text("Install")
                        }
                    }
                } else null,
            )
            ToolsCheckRow(
                icon = Icons.Filled.RocketLaunch,
                title = "Engine & patched client",
                subtitle = when {
                    engineInstalled && clientInstalled -> "Ready to launch"
                    !engineInstalled && !clientInstalled -> "Optional for editing · install from Patch tab"
                    !engineInstalled -> "Xash3D engine missing · optional for Tools"
                    else -> "Patched client missing · optional for Tools"
                },
                done = engineInstalled && clientInstalled,
                index = 2,
                total = 3,
            )
        }

        Spacer(Modifier.height(4.dp))

        if (cstrikeReady && !addonsInstalled) {
            Button(
                onClick = onInstallAddons,
                enabled = downloading == null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = CircleShape,
            ) {
                Text(
                    text = if (downloading != null) "Installing addons…" else "Install Addons",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            OutlinedButton(
                onClick = onGoToPatch,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = CircleShape,
            ) {
                Text("Go to Patch")
            }
        } else {
            Button(
                onClick = onGoToPatch,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = CircleShape,
            ) {
                Text(
                    text = "Go to Patch",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = CircleShape,
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Check Folder Again")
            }
        }
    }
}

@Composable
private fun ToolsCheckRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    done: Boolean,
    index: Int,
    total: Int,
    progress: Float? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = getGroupedItemShape(index, total),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (done) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (done) Icons.Filled.CheckCircle else icon,
                        contentDescription = null,
                        tint = if (done) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (trailing != null) {
                    Spacer(Modifier.width(10.dp))
                    trailing()
                }
            }
            if (progress != null) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                )
            }
        }
    }
}
