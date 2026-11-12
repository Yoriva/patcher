package com.pickle.patcher.ui.screens
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
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.pickle.patcher.patcher.LibInfo
import com.pickle.patcher.patcher.PatcherViewModel
import com.pickle.patcher.patcher.PatcherViewModel.CustomLib
@Composable
fun LibraryScreen(vm: PatcherViewModel) {
    val libs by vm.libs.collectAsState()
    val customLibs by vm.customLibs.collectAsState()
    val checking by vm.libsChecking.collectAsState()
    val problem by vm.customLibProblem.collectAsState()
    val abi by vm.abi.collectAsState()
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
    LaunchedEffect(Unit) { vm.scanLibs() }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            LibrarySummary(
                installed = libs,
                customCount = customLibs.size,
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                LibrarySectionHeader(
                    title = "Installed Libraries",
                    action = { CheckUpdatesButton(checking = checking) { vm.refreshLibStatus() } },
                )
                if (libs.isEmpty()) {
                    EmptyLibraryCard(
                        title = "No libraries yet",
                        subtitle = "Download the bundle from the Patch screen to see the files it will inject.",
                    )
                } else {
                    libs.forEachIndexed { index, lib ->
                        LibraryEntry(
                            lib = lib,
                            index = index,
                            total = libs.size,
                            onUpdate = { vm.refreshSingleLib(lib.name) },
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
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Extension,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    text = "${installed.size} installed · $customCount custom",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    onUpdate: () -> Unit,
) {
    val hasUpdate = !lib.upToDate && lib.releaseSize > 0
    val subtitle = when {
        lib.downloading && lib.downloadProgress >= 0 ->
            "Downloading… ${(lib.downloadProgress * 100).toInt()}%"
        lib.downloading -> "Downloading…"
        hasUpdate && lib.localSize == 0L ->
            "Not installed · ${lib.releaseSize.mb()}"
        hasUpdate ->
            "Update available · ${lib.releaseSize.mb()}"
        lib.localSize > 0 -> "${lib.localSize.mb()} · Up to date"
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasUpdate) {
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
                    lib.downloading -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    hasUpdate -> UpdateLibraryButton(
                        label = if (lib.localSize == 0L) "Download" else "Update",
                        onClick = onUpdate,
                    )
                    else -> Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Up to date",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
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
    onRemove: () -> Unit,
) {
    val subtitle = if (stale) {
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
private fun UpdateLibraryButton(label: String, onClick: () -> Unit) {
    SmallPillButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        height = 34.dp,
    ) {
        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
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