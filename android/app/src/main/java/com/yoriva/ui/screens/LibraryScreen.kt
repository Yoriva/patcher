package com.yoriva.ui.screens
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.yoriva.patcher.BundleState
import com.yoriva.patcher.LibInfo
import com.yoriva.patcher.LibSource
import com.yoriva.patcher.PatcherViewModel
import com.yoriva.patcher.PatcherViewModel.CustomLib
@Composable
fun LibraryScreen(vm: PatcherViewModel) {
    val allLibs by vm.libs.collectAsState()
    val libs = allLibs.filter { it.source == LibSource.CORE }
    val plugins = allLibs.filter { it.source == LibSource.PLUGIN }
    val customLibs by vm.customLibs.collectAsState()
    val libsCheckingError by vm.libsError.collectAsState()
    val checking by vm.libsChecking.collectAsState()
    val pluginsDownloading by vm.pluginsDownloading.collectAsState()
    val coreOutdated by vm.coreOutdatedCount.collectAsState()
    val bundleState by vm.bundle.collectAsState()
    val problem by vm.customLibProblem.collectAsState()
    val abi by vm.abi.collectAsState()
    val excluded by vm.libExcluded.collectAsState()
    val downloadAllRunning by vm.downloadAllRunning.collectAsState()
    val downloadingBundle = bundleState as? BundleState.Downloading
    val syncingLibs = downloadingBundle != null || downloadAllRunning
    val missingCoreCount = libs.count { it.localSize == 0L && it.releaseSize > 0L }
    val anyPluginActionable = plugins.any { !it.upToDate && it.releaseSize > 0 }
    val addLibs = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) vm.addCustomLibs(uris) }
    var staleNames by remember { mutableStateOf(emptySet<String>()) }
    var pendingRemove by remember { mutableStateOf<CustomLib?>(null) }
    LaunchedEffect(customLibs, abi) {
        staleNames = if (customLibs.isEmpty()) {
            emptySet()
        } else {
            withContext(Dispatchers.IO) {
                runCatching { vm.staleCustomLibs().map { it.name }.toSet() }.getOrDefault(emptySet())
            }
        }
    }
    LaunchedEffect(Unit) { vm.scanLibs(checkRemote = true) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 12.dp,
            bottom = 12.dp + navigationBarBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            LibrarySummary(
                installed = allLibs.filter { it.localSize > 0L },
                customCount = customLibs.size,
                coreOutdated = coreOutdated,
                missingCore = missingCoreCount,
                excluded = excluded,
                downloadingBundle = downloadingBundle,
                syncing = syncingLibs,
                onSync = { vm.fetchAndDownloadBundle() },
                onStop = {
                    vm.cancelBundleDownload()
                    if (downloadAllRunning) vm.cancelDownloadAll()
                },
            )
        }
        if (!libsCheckingError.isNullOrBlank()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = libsCheckingError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.dismissLibsError() }) {
                            Text("Dismiss", color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                        TextButton(onClick = { vm.refreshLibStatus(force = true) }) {
                            Text("Retry", color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                LibrarySectionHeader(
                    title = "Core Libraries",
                    action = { CheckUpdatesButton(checking = checking) { vm.refreshLibStatus(force = true) } },
                )
                if (libs.isEmpty()) {
                    EmptyLibraryCard(
                        title = "No libraries yet",
                        subtitle = "Download assets from the Patch screen to see the core libraries it will inject.",
                    )
                } else {
                    libs.forEachIndexed { index, lib ->
                        LibraryEntry(
                            lib = lib,
                            index = index,
                            total = libs.size,
                            excluded = lib.name in excluded,
                            onToggle = { include -> vm.setLibExcluded(lib.name, !include) },
                            onUpdate = { vm.refreshSingleLib(lib.name) },
                            onCancel = { vm.cancelSingleLib(lib.name) },
                        )
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                LibrarySectionHeader(
                    title = "Yoriva Plugins",
                    action = {
                        if (anyPluginActionable || pluginsDownloading) {
                            DownloadPluginsButton(
                                downloading = pluginsDownloading,
                                onDownload = { vm.downloadOptionalPlugins() },
                                onCancel = { vm.cancelPluginsDownload() },
                            )
                        } else {
                            CheckUpdatesButton(checking = checking) { vm.refreshLibStatus(force = true) }
                        }
                    },
                )
                if (plugins.isEmpty()) {
                    EmptyLibraryCard(
                        title = "No optional plugins loaded",
                        subtitle = "Check for updates to browse optional plugins from Yoriva Plugins.",
                    )
                } else {
                    plugins.forEachIndexed { index, lib ->
                        LibraryEntry(
                            lib = lib,
                            index = index,
                            total = plugins.size,
                            excluded = lib.name in excluded,
                            onToggle = { include -> vm.setLibExcluded(lib.name, !include) },
                            onUpdate = { vm.refreshSingleLib(lib.name) },
                            onCancel = { vm.cancelSingleLib(lib.name) },
                        )
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                LibrarySectionHeader(
                    title = "Custom Libraries",
                    action = { AddLibrariesButton { addLibs.launch(arrayOf("*/*")) } },
                )
                if (customLibs.isEmpty()) {
                    EmptyLibraryCard(
                        title = "No custom libraries",
                    )
                } else {
                    customLibs.forEachIndexed { index, lib ->
                        CustomLibraryEntry(
                            lib = lib,
                            index = index,
                            total = customLibs.size,
                            abi = abi,
                            stale = staleNames.contains(lib.name),
                            excluded = lib.name in excluded,
                            onToggle = { include -> vm.setLibExcluded(lib.name, !include) },
                            onRemove = { pendingRemove = lib },
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
    pendingRemove?.let { lib ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            icon = { Icon(Icons.Rounded.Close, contentDescription = null) },
            title = { Text("Remove library") },
            text = { Text("${lib.name} will be deleted from storage. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeCustomLib(lib.name)
                    pendingRemove = null
                }) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) {
                    Text("Cancel")
                }
            },
        )
    }
    problem?.let { message ->
        AlertDialog(
            onDismissRequest = { vm.dismissCustomLibProblem() },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text("Couldn't add library") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { vm.dismissCustomLibProblem() }) {
                    Text("OK")
                }
            },
        )
    }
}
@Composable
private fun LibrarySummary(
    installed: List<LibInfo>,
    customCount: Int,
    coreOutdated: Int,
    missingCore: Int,
    excluded: Set<String>,
    downloadingBundle: BundleState.Downloading?,
    syncing: Boolean,
    onSync: () -> Unit,
    onStop: () -> Unit,
) {
    val included = installed.count { it.name !in excluded }
    val updatesCount = coreOutdated + missingCore
    val subtitleText = when {
        downloadingBundle != null && downloadingBundle.fileTotal > 0 ->
            "${downloadingBundle.currentFile.ifBlank { "Syncing" }} (${downloadingBundle.fileIndex + 1}/${downloadingBundle.fileTotal})"
        syncing -> "Checking and syncing libraries…"
        updatesCount > 0 -> "$updatesCount updates available · $included/${installed.size} included"
        customCount > 0 -> "$included/${installed.size} included · $customCount custom"
        else -> "$included/${installed.size} included · Up to date"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Extension,
                    contentDescription = null,
                    tint = if (updatesCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Libraries",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitleText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (updatesCount > 0 && !syncing) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(10.dp))
                if (syncing) {
                    SmallPillButton(
                        onClick = onStop,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Stop",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                } else {
                    val hasUpdates = updatesCount > 0
                    SmallPillButton(
                        onClick = onSync,
                        containerColor = if (hasUpdates) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        contentColor = if (hasUpdates) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                    ) {
                        Icon(
                            imageVector = if (hasUpdates) Icons.Filled.Download else Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (hasUpdates) "Update ($updatesCount)" else "Sync",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }
            if (downloadingBundle != null) {
                ProgressFooter(
                    progress = downloadingBundle.percent.coerceIn(0f, 1f),
                    left = if (downloadingBundle.fileTotal > 0) {
                        "File ${downloadingBundle.fileIndex + 1} of ${downloadingBundle.fileTotal}"
                    } else {
                        "Preparing…"
                    },
                    right = "${(downloadingBundle.percent * 100).toInt().coerceIn(0, 100)}%",
                )
            }
        }
    }
}
@Composable
private fun LibrarySectionHeader(
    title: String,
    action: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            action()
        }
    }
}
@Composable
private fun CheckUpdatesButton(checking: Boolean, onClick: () -> Unit) {
    SmallPillButton(
        enabled = !checking,
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        if (checking) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
            Text("Checking", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
        } else {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Check updates", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}
@Composable
private fun AddLibrariesButton(onClick: () -> Unit) {
    SmallPillButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Add .so", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
    }
}
@Composable
private fun LibraryEntry(
    lib: LibInfo,
    index: Int,
    total: Int,
    excluded: Boolean,
    onToggle: (Boolean) -> Unit,
    onUpdate: () -> Unit,
    onCancel: () -> Unit,
) {
    val isOptionalAvailable = lib.source == LibSource.PLUGIN && lib.localSize == 0L && lib.releaseSize > 0
    val hasAction = !lib.upToDate && lib.releaseSize > 0
    val hasUpdate = hasAction && !isOptionalAvailable
    val canInclude = lib.upToDate && lib.localSize > 0L
    val subtitle = when {
        lib.downloading && lib.downloadProgress >= 0 ->
            "Downloading… ${(lib.downloadProgress * 100).toInt()}%"
        lib.downloading -> "Downloading…"
        isOptionalAvailable ->
            "Optional · ${lib.releaseSize.mb()}"
        hasAction && lib.localSize == 0L ->
            "Not installed · ${lib.releaseSize.mb()}"
        hasUpdate ->
            "Update · ${lib.localSize.mb()} → ${lib.releaseSize.mb()}"
        lib.localSize > 0 -> "${lib.localSize.mb()} · Installed"
        else -> "Not installed"
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                spring(
                    dampingRatio = 0.9f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
        shape = getGroupedItemShape(index, total),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = lib.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (excluded && canInclude) 0.45f else 1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (excluded && canInclude) "Not included in the patch" else subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (excluded && canInclude) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else if (hasUpdate) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(10.dp))
                when {
                    lib.downloading -> FilledTonalIconButton(
                        onClick = onCancel,
                        modifier = Modifier.size(34.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Cancel ${lib.name}",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    isOptionalAvailable -> UpdateLibraryButton(
                        contentDescription = "Download ${lib.name}",
                        optional = true,
                        onClick = onUpdate,
                    )
                    hasAction -> UpdateLibraryButton(
                        contentDescription = if (lib.localSize == 0L) "Download ${lib.name}" else "Update ${lib.name}",
                        optional = lib.localSize == 0L,
                        onClick = onUpdate,
                    )
                    else -> Unit
                }
                if (canInclude) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Switch(
                        checked = !excluded,
                        onCheckedChange = onToggle,
                    )
                }
            }
            if (lib.downloading) {
                if (lib.downloadProgress >= 0) {
                    LinearProgressIndicator(
                        progress = { lib.downloadProgress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 18.dp, end = 18.dp, bottom = 12.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 18.dp, end = 18.dp, bottom = 12.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
    }
}
@Composable
private fun CustomLibraryEntry(
    lib: CustomLib,
    index: Int,
    total: Int,
    abi: String,
    stale: Boolean,
    excluded: Boolean,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val subtitle = if (excluded) {
        "Not included in the patch"
    } else if (stale) {
        "Incompatible with ${displayAbi(abi)}"
    } else if (lib.shadows != null) {
        "${lib.machineName} · replaces ${lib.shadows}"
    } else {
        "${lib.machineName} · added to patcher"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = getGroupedItemShape(index, total),
        colors = CardDefaults.cardColors(
            containerColor = if (stale) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = lib.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (excluded) 0.45f else 1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (stale) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = !excluded,
                onCheckedChange = onToggle,
                modifier = Modifier.padding(end = 4.dp),
            )
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(34.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Remove ${lib.name}",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
@Composable
private fun DownloadPluginsButton(
    downloading: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    SmallPillButton(
        onClick = if (downloading) onCancel else onDownload,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        if (downloading) {
            Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Stop", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
        } else {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Download all", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}
@Composable
private fun UpdateLibraryButton(
    contentDescription: String,
    optional: Boolean = false,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(34.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (optional) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
            contentColor = if (optional) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
        ),
    ) {
        Icon(
            imageVector = if (optional) Icons.Filled.Download else Icons.Filled.Refresh,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
        )
    }
}
@Composable
private fun SmallPillButton(
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    enabled: Boolean = true,
    height: androidx.compose.ui.unit.Dp = 38.dp,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(height),
        shape = RoundedCornerShape(percent = 50),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        ),
    ) {
        content()
    }
}
@Composable
private fun EmptyLibraryCard(title: String, subtitle: String? = null) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = getGroupedItemShape(0, 1),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}