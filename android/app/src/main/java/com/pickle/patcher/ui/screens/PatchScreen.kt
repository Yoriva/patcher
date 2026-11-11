package com.pickle.patcher.ui.screens
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InstallDesktop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pickle.patcher.patcher.BundleState
import com.pickle.patcher.patcher.PatchUiState
import com.pickle.patcher.patcher.PatcherViewModel
import com.pickle.patcher.patcher.SourceDownloadState
import java.text.DecimalFormat
internal val mbFmt = DecimalFormat("0.0")
internal fun Long.mb(): String = if (this < 1048576L) {
    "%.0f KB".format(this / 1024.0)
} else {
    "${mbFmt.format(this / 1048576.0)} MB"
}
fun String.toDisplayVersion(): String =
    if (firstOrNull()?.isDigit() == true) "v$this" else this
internal fun displayAbi(abi: String): String = when (abi) {
    "armeabi-v7a" -> "Arm32 (32-bit)"
    else -> "Arm64 (64-bit)"
}
@Composable
fun PatchScreen(vm: PatcherViewModel, onOpenLibraries: () -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(Unit) {
        vm.runAutoDetectAbi()
        vm.refreshSourceApkStatus()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ArchitectureSection(vm)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ClientRow(vm, index = 0, total = 3)
            LibrariesRow(vm, index = 1, total = 3, onOpenLibraries = onOpenLibraries)
            BuildRow(vm, index = 2, total = 3)
        }
        Spacer(Modifier.height(16.dp))
    }
}
@Composable
private fun ArchitectureSection(vm: PatcherViewModel) {
    val abi by vm.abi.collectAsState()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayAbi(abi),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Matched to this device",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                )
            }
        }
    }
}
@Composable
private fun ClientRow(vm: PatcherViewModel, index: Int, total: Int) {
    val source by vm.source.collectAsState()
    val download by vm.sourceDownload.collectAsState()
    val src = source
    val downloading = download as? SourceDownloadState.Downloading
    val isFetching = download is SourceDownloadState.Fetching
    val isOutdated = download is SourceDownloadState.Outdated
    val busy = downloading != null || isFetching
    val errorNote = when (val s = download) {
        is SourceDownloadState.Corrupt -> "${s.reason}. Re-download required."
        is SourceDownloadState.Failed -> s.message
        else -> null
    }
    val actionKey = when {
        busy -> "busy"
        src == null -> "download"
        isOutdated -> "update"
        else -> "redownload"
    }
    GroupedItem(
        title = "Game Client APK",
        subtitle = when {
            src == null && isFetching -> "Checking the latest release…"
            src == null -> "Not downloaded yet"
            isOutdated -> "A newer client build is available"
            else -> "Downloaded · ${src.sizeBytes.mb()}"
        },
        index = index,
        total = total,
        error = errorNote != null,
        trailing = {
            AnimatedContent(
                targetState = actionKey,
                transitionSpec = {
                    (fadeIn(tween(180)) + scaleIn(initialScale = 0.92f)) togetherWith
                        (fadeOut(tween(140)) + scaleOut(targetScale = 0.92f))
                },
                label = "clientAction",
            ) { key ->
                when (key) {
                    "busy" -> SmallProgressButton("Downloading")
                    "download" -> SmallButton("Download", Icons.Filled.Download) { vm.downloadSourceApk() }
                    "update" -> SmallButton("Update", Icons.Filled.SystemUpdateAlt) { vm.downloadSourceApk() }
                    else -> SmallButton("Re-download", Icons.Filled.Refresh) { vm.downloadSourceApk() }
                }
            }
        },
    ) {
        if (downloading != null) {
            val progress = if (downloading.total > 0) {
                downloading.downloaded.toFloat() / downloading.total.toFloat()
            } else {
                null
            }
            ProgressFooter(
                progress = progress,
                left = if (downloading.total > 0) {
                    "%.1f / %.1f MB".format(downloading.downloaded / 1048576.0, downloading.total / 1048576.0)
                } else {
                    "%.1f MB".format(downloading.downloaded / 1048576.0)
                },
                right = progress?.let { "${(it * 100).toInt()}%" }.orEmpty(),
            )
        }
        if (errorNote != null) {
            Text(
                text = errorNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
            )
        }
    }
}
@Composable
private fun LibrariesRow(
    vm: PatcherViewModel,
    index: Int,
    total: Int,
    onOpenLibraries: () -> Unit,
) {
    val bundleState by vm.bundle.collectAsState()
    val abiStatus by vm.abiStatus.collectAsState()
    val currentAbi by vm.abi.collectAsState()
    val target = abiStatus.firstOrNull { it.selected }
    val isDownloading = bundleState is BundleState.Downloading
    val isReady = target?.installed == true ||
        bundleState is BundleState.Ready ||
        bundleState is BundleState.Loaded
    val bundleError = bundleState as? BundleState.DownloadError
    val actionKey = when {
        isDownloading -> "busy"
        isReady -> "refresh"
        else -> "download"
    }
    GroupedItem(
        title = "Mod Libraries",
        subtitle = when (val bs = bundleState) {
            is BundleState.Downloading -> "Downloading libraries…"
            is BundleState.Loaded -> "${target?.libCount ?: 0} libraries ready"
            is BundleState.Ready -> "${bs.entries} entries · ${bs.version.toDisplayVersion()}"
            is BundleState.DownloadError -> bs.message
            is BundleState.None -> if (target?.installed == true) {
                "${target.libCount} libraries ready"
            } else {
                "Required for ${displayAbi(currentAbi)}"
            }
        },
        index = index,
        total = total,
        error = bundleError != null,
        trailing = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = onOpenLibraries,
                    modifier = Modifier.height(38.dp),
                    shape = RoundedCornerShape(percent = 50),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = "View libraries",
                        modifier = Modifier.size(18.dp),
                    )
                }
                AnimatedContent(
                    targetState = actionKey,
                    transitionSpec = {
                        (fadeIn(tween(180)) + scaleIn(initialScale = 0.92f)) togetherWith
                            (fadeOut(tween(140)) + scaleOut(targetScale = 0.92f))
                    },
                    label = "librariesAction",
                ) { key ->
                    when (key) {
                        "busy" -> SmallProgressButton("Downloading")
                        "refresh" -> SmallButton("Refresh", Icons.Filled.Refresh) { vm.fetchAndDownloadBundle() }
                        else -> SmallButton("Download", Icons.Filled.Download) { vm.fetchAndDownloadBundle() }
                    }
                }
            }
        },
    ) {
        if (bundleState is BundleState.Downloading) {
            val bs = bundleState as BundleState.Downloading
            ProgressFooter(
                progress = bs.percent,
                left = if (bs.fileTotal > 0) "File ${bs.fileIndex + 1} of ${bs.fileTotal}" else "Downloading…",
                right = "${(bs.percent * 100).toInt()}%",
            )
        }
    }
}
@Composable
private fun BuildRow(vm: PatcherViewModel, index: Int, total: Int) {
    val patchState by vm.patch.collectAsState()
    val source by vm.source.collectAsState()
    val bundleState by vm.bundle.collectAsState()
    val abiStatus by vm.abiStatus.collectAsState()
    val patchLog by vm.patchLog.collectAsState()
    val target = abiStatus.firstOrNull { it.selected }
    val hasClient = source != null
    val hasBundle = target?.installed == true ||
        bundleState is BundleState.Ready ||
        bundleState is BundleState.Loaded
    val canPatch = hasClient && hasBundle
    var showLog by remember { mutableStateOf(false) }
    val running = patchState as? PatchUiState.Running
    val actionKey = when (patchState) {
        PatchUiState.Idle -> "idle"
        is PatchUiState.Running -> "running"
        is PatchUiState.Done -> "done"
        is PatchUiState.Failed -> "failed"
    }
    GroupedItem(
        title = when (patchState) {
            is PatchUiState.Done -> "Patch Succeeded"
            is PatchUiState.Failed -> "Patch Failed"
            is PatchUiState.Running -> "Patching APK…"
            PatchUiState.Idle -> "Build Patched APK"
        },
        index = index,
        total = total,
        trailing = {
            AnimatedContent(
                targetState = actionKey,
                transitionSpec = {
                    (fadeIn(tween(180)) + scaleIn(initialScale = 0.92f)) togetherWith
                        (fadeOut(tween(140)) + scaleOut(targetScale = 0.92f))
                },
                label = "buildAction",
            ) { key ->
                when (key) {
                    "idle" -> SmallButton(
                        text = "Start Patch",
                        icon = Icons.Filled.RocketLaunch,
                        filled = true,
                        enabled = canPatch,
                        onClick = { vm.startPatch() },
                    )
                    "running" -> SmallProgressButton("Building")
                    "done" -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SmallButton("Install", Icons.Filled.InstallDesktop, filled = true) { vm.requestInstall() }
                        SmallButton("Reset", Icons.Filled.Refresh) { vm.reset() }
                    }
                    else -> SmallButton(
                        text = "Reset",
                        icon = Icons.Filled.Refresh,
                        filled = true,
                        onClick = { vm.reset() },
                    )
                }
            }
        },
    ) {
        val failedState = patchState as? PatchUiState.Failed
        if (failedState != null) {
            Text(
                text = failedState.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
            )
        }
        if (running != null) {
            ProgressFooter(
                progress = running.progress,
                left = running.step.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
                right = "${(running.progress * 100).toInt()}%",
            )
        }
        if (patchLog.isNotEmpty()) {
            TextButton(
                onClick = { showLog = !showLog },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 2.dp),
            ) {
                Text(if (showLog) "Hide Build Log" else "View Build Log")
            }
            AnimatedVisibility(visible = showLog) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    val logText = patchLog.joinToString("\n")
                    val scrollState = rememberScrollState()
                    Text(
                        text = logText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(14.dp)
                            .heightIn(max = 200.dp)
                            .verticalScroll(scrollState),
                    )
                }
            }
        }
    }
}
@Composable
internal fun GroupedItem(
    title: String,
    subtitle: String? = null,
    index: Int,
    total: Int,
    error: Boolean = false,
    trailing: @Composable () -> Unit,
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
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
                    .padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    subtitle?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                trailing()
            }
            footer()
        }
    }
}
@Composable
internal fun SmallButton(
    text: String,
    icon: ImageVector,
    filled: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "smallButtonScale",
    )
    val modifier = Modifier
        .height(38.dp)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    val shape = RoundedCornerShape(percent = 50)
    val padding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
    val content: @Composable RowScope.() -> Unit = {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
    if (filled) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            shape = shape,
            contentPadding = padding,
            interactionSource = interactionSource,
            content = content,
        )
    } else {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier,
            shape = shape,
            contentPadding = padding,
            interactionSource = interactionSource,
            content = content,
        )
    }
}
@Composable
internal fun SmallProgressButton(text: String) {
    Button(
        onClick = {},
        enabled = false,
        modifier = Modifier.height(38.dp),
        shape = RoundedCornerShape(percent = 50),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onPrimary,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}
@Composable
internal fun ProgressFooter(progress: Float?, left: String, right: String) {
    Column(
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
    ) {
        val indicatorModifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = indicatorModifier,
            )
        } else {
            LinearProgressIndicator(modifier = indicatorModifier)
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = left,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = right,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}