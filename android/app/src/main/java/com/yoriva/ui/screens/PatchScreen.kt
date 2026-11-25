package com.yoriva.ui.screens
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yoriva.patcher.BundleState
import com.yoriva.patcher.PatchUiState
import com.yoriva.patcher.PatcherViewModel
import com.yoriva.patcher.SourceDownloadState
import java.text.DecimalFormat
internal val mbFmt = DecimalFormat("0.0", java.text.DecimalFormatSymbols(java.util.Locale.US))
internal fun Long.mb(): String = if (this < 1048576L) {
    "%.0f KB".format(java.util.Locale.US, this / 1024.0)
} else {
    "${mbFmt.format(this / 1048576.0)} MB"
}
internal fun displayAbi(abi: String): String = when (abi) {
    "armeabi-v7a" -> "Arm32 (32-bit)"
    "x86" -> "x86 (32-bit)"
    "x86_64" -> "x86_64 (64-bit)"
    else -> "Arm64 (64-bit)"
}
@Composable
fun PatchScreen(vm: PatcherViewModel, onOpenLibraries: () -> Unit) {
    val engineInstalled by vm.engineInstalled.collectAsState()
    val cstrikeReady by vm.cstrikeReady.collectAsState()
    val gameReady by vm.gameInstalled.collectAsState()
    val abi by vm.abi.collectAsState()
    val patchable = abi in vm.patchableAbis
    val showStatus = patchable && (!engineInstalled || !cstrikeReady)

    LaunchedEffect(Unit) {
        vm.runAutoDetectAbi()
        vm.checkAllAssetsStatus()
        vm.refreshGameInstalled()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                vm.refreshGameInstalled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { ArchitectureSection(vm) }
        if (showStatus) item { GameStatusCard(vm) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                DownloadRow(vm, index = 0, total = 2, onOpenLibraries = onOpenLibraries)
                BuildRow(vm, index = 1, total = 2)
            }
        }
        if (gameReady) {
            item(key = "launch_section") {
                Column(
                    modifier = Modifier.animateItem(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    CommandArgsRow(vm, index = 0, total = 2)
                    LaunchGameRow(vm, index = 1, total = 2)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}
@Composable
private fun ArchitectureSection(vm: PatcherViewModel) {
    val abi by vm.abi.collectAsState()
    val patchable = abi in vm.patchableAbis
    if (!patchable) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.large,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
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
                        text = "Not supported — this build only ships ARM libraries (arm64-v8a, armeabi-v7a). x86 devices can't run the patched client.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                    )
                }
            }
        }
        return
    }
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
private fun GameStatusCard(vm: PatcherViewModel) {
    val engineInstalled by vm.engineInstalled.collectAsState()
    val context = LocalContext.current
    val (title, subtitle, url) = when {
        !engineInstalled -> Triple(
            "Xash3D engine is not installed",
            "The patched client needs the engine to launch. Download and install it, then return here.",
            "https://github.com/FWGS/xash3d-fwgs/releases",
        )
        else -> Triple(
            "cstrike folder was not found",
            "All game files must be installed before patching. Put the cstrike folder in the game directory and return here.",
            null,
        )
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                )
            }
            if (!engineInstalled && url != null) {
                Spacer(Modifier.width(16.dp))
                Button(
                    onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onErrorContainer,
                        contentColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text("Install", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
@Composable
private fun DownloadRow(
    vm: PatcherViewModel,
    index: Int,
    total: Int,
    onOpenLibraries: () -> Unit,
) {
    val source by vm.source.collectAsState()
    val download by vm.sourceDownload.collectAsState()
    val bundleState by vm.bundle.collectAsState()
    val abiStatus by vm.abiStatus.collectAsState()
    val bulkRunning by vm.downloadAllRunning.collectAsState()
    val assetsSyncStage by vm.assetsSyncStage.collectAsState()
    val gameAssetsProgress by vm.gameAssetsProgress.collectAsState()
    val assetsSyncError by vm.assetsSyncError.collectAsState()
    val coreOutdated by vm.coreOutdatedCount.collectAsState()
    val gameAssetsOutdated by vm.gameAssetsOutdated.collectAsState()
    val abi by vm.abi.collectAsState()
    val patchable = abi in vm.patchableAbis
    val target = abiStatus.firstOrNull { it.selected }
    val downloading = download as? SourceDownloadState.Downloading
    val isFetching = download is SourceDownloadState.Fetching
    val downloadingBundle = bundleState as? BundleState.Downloading
    val hasClient = source != null
    val bundleReady = target?.installed == true ||
        bundleState is BundleState.Ready ||
        bundleState is BundleState.Loaded
    val allReady = hasClient && bundleReady
    val busy = bulkRunning || downloading != null || isFetching || downloadingBundle != null
    val outdated = download as? SourceDownloadState.Outdated
    val hasUpdates = outdated != null || coreOutdated > 0 || gameAssetsOutdated
    val errorNote = when (val s = download) {
        is SourceDownloadState.Corrupt -> "${s.reason}. Re-download required."
        is SourceDownloadState.Failed -> s.message
        else -> (bundleState as? BundleState.DownloadError)?.message ?: assetsSyncError
    }
    val actionKey = when {
        !patchable -> "disabled"
        busy -> "busy"
        hasUpdates -> "update"
        allReady -> "ready"
        else -> "download"
    }
    GroupedItem(
        title = "Assets",
        subtitle = when {
            !patchable -> "Not supported on ${displayAbi(abi)} — ARM only"
            downloading != null -> "Downloading client APK…"
            isFetching -> "Checking client release…"
            downloadingBundle != null -> "Downloading mod libraries…"
            busy -> assetsSyncStage ?: "Checking assets…"
            outdated != null -> "Client APK update available · ${outdated.reason}"
            coreOutdated > 0 -> "$coreOutdated core ${if (coreOutdated == 1) "library" else "libraries"} updated on GitHub"
            gameAssetsOutdated -> "Game content archive update available"
            allReady -> "Client APK and core libraries ready"
            !hasClient && bundleReady -> "Client APK still required"
            hasClient && !bundleReady -> "Core libraries still required"
            else -> "Client APK, core libraries, and game data"
        },
        index = index,
        total = total,
        error = errorNote != null,
        trailing = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedContent(
                    targetState = actionKey,
                    transitionSpec = {
                        (fadeIn(tween(180)) + scaleIn(initialScale = 0.92f)) togetherWith
                            (fadeOut(tween(140)) + scaleOut(targetScale = 0.92f))
                    },
                    label = "downloadAction",
                ) { key ->
                    when (key) {
                        "busy" -> SmallButton("Stop", Icons.Filled.Close) { vm.cancelDownloadAll() }
                        "disabled" -> SmallButton("Not supported", Icons.Filled.Warning, enabled = false) { }
                        "update" -> SmallButton("Update", Icons.Filled.Refresh) { vm.downloadAll() }
                        "ready" -> FilledTonalButton(
                            onClick = onOpenLibraries,
                            modifier = Modifier.height(38.dp),
                            shape = RoundedCornerShape(percent = 50),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.LibraryBooks,
                                contentDescription = "View libraries",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        else -> SmallButton("Download", Icons.Filled.Download) { vm.downloadAll() }
                    }
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
                    "%.1f / %.1f MB".format(java.util.Locale.US, downloading.downloaded / 1048576.0, downloading.total / 1048576.0)
                } else {
                    "%.1f MB".format(java.util.Locale.US, downloading.downloaded / 1048576.0)
                },
                right = progress?.let { "${(it * 100).toInt()}%" }.orEmpty(),
            )
        }
        if (downloadingBundle != null) {
            ProgressFooter(
                progress = downloadingBundle.percent,
                left = if (downloadingBundle.fileTotal > 0) {
                    "${downloadingBundle.currentFile.ifBlank { "Libraries" }} (${downloadingBundle.fileIndex + 1}/${downloadingBundle.fileTotal})"
                } else {
                    "Checking libraries…"
                },
                right = "${(downloadingBundle.percent * 100).toInt()}%",
            )
        }
        val ga = gameAssetsProgress
        if (busy && downloading == null && downloadingBundle == null && ga != null) {
            val (done, totalBytes) = ga
            val progress = if (totalBytes > 0L) (done.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else null
            ProgressFooter(
                progress = progress,
                left = if (totalBytes > 0L) {
                    "%.1f / %.1f MB".format(java.util.Locale.US, done / 1048576.0, totalBytes / 1048576.0)
                } else {
                    "%.1f MB".format(java.util.Locale.US, done / 1048576.0)
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
}@Composable
private fun BuildRow(vm: PatcherViewModel, index: Int, total: Int) {
    val patchState by vm.patch.collectAsState()
    val source by vm.source.collectAsState()
    val bundleState by vm.bundle.collectAsState()
    val abiStatus by vm.abiStatus.collectAsState()
    val clientInstalled by vm.gameInstalled.collectAsState()
    val engineInstalled by vm.engineInstalled.collectAsState()
    val cstrikeReady by vm.cstrikeReady.collectAsState()
    val abi by vm.abi.collectAsState()
    val patchable = abi in vm.patchableAbis
    val target = abiStatus.firstOrNull { it.selected }
    val hasClient = source != null
    val hasBundle = target?.installed == true ||
        bundleState is BundleState.Ready ||
        bundleState is BundleState.Loaded
    val canPatch = patchable && hasClient && hasBundle && engineInstalled && cstrikeReady
    val running = patchState as? PatchUiState.Running
    val clientIsInstalled = clientInstalled
    val showPatched = clientIsInstalled && (patchState is PatchUiState.Idle || patchState is PatchUiState.Done)
    val actionKey = when (patchState) {
        PatchUiState.Idle -> if (clientIsInstalled) "done" else "idle"
        is PatchUiState.Running -> "running"
        is PatchUiState.Done -> "done"
        is PatchUiState.Failed -> "failed"
    }
    GroupedItem(
        title = when (patchState) {
            is PatchUiState.Done -> if (clientIsInstalled) "CS 1.6 Ready" else "Patch Succeeded"
            is PatchUiState.Failed -> "Patch Failed"
            is PatchUiState.Running -> "Patching APK…"
            PatchUiState.Idle -> if (showPatched) "CS 1.6 Ready" else "Build Patched APK"
        },
        subtitle = when (patchState) {
            is PatchUiState.Done, PatchUiState.Idle -> if (showPatched) {
                "AMXX client is installed and ready to play"
            } else {
                null
            }
            else -> null
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
                        if (clientIsInstalled) {
                            SmallButton("Repatch", Icons.Filled.RocketLaunch, filled = false) {
                                vm.reset()
                                vm.startPatch()
                            }
                        } else {
                            SmallButton("Reset", Icons.Filled.Refresh) { vm.reset() }
                        }
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
        var lastFailed by remember { mutableStateOf(failedState) }
        LaunchedEffect(failedState) {
            if (failedState != null) lastFailed = failedState
        }
        val patchLog by vm.patchLog.collectAsState()
        var showLog by remember { mutableStateOf(false) }
        AnimatedVisibility(
            visible = failedState != null,
            enter = expandVertically(tween(240)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(240)) + fadeOut(tween(200)),
        ) {
            Column {
                Text(
                    text = lastFailed?.message.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 10.dp),
                )
                if (patchLog.isNotEmpty()) {
                    TextButton(
                        onClick = { showLog = !showLog },
                        modifier = Modifier.padding(start = 10.dp, bottom = 2.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (showLog) "Hide log" else "View log",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    AnimatedVisibility(
                        visible = showLog,
                        enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                        exit = shrinkVertically(tween(220)) + fadeOut(tween(180)),
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 18.dp, end = 18.dp, bottom = 12.dp)
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Text(
                                text = patchLog.takeLast(80).joinToString("\n"),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
        if (running != null) {
            ProgressFooter(
                progress = running.progress,
                left = running.step.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
                right = "${(running.progress * 100).toInt()}%",
            )
        }
    }
}
@Composable
private fun CommandArgsRow(vm: PatcherViewModel, index: Int, total: Int) {
    val launchArgs by vm.launchArgs.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(220),
        label = "argsArrowRotation",
    )

    Card(
        onClick = {
            if (expanded) {
                vm.setLaunchArgs(launchArgs.trim())
            }
            expanded = !expanded
        },
        modifier = Modifier.fillMaxWidth(),
        shape = getGroupedItemShape(index, total),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Command Arguments",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    AnimatedVisibility(
                        visible = !expanded,
                        enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                        exit = shrinkVertically(tween(220)) + fadeOut(tween(140)),
                    ) {
                        Column {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = launchArgs.trim().ifEmpty { "-dev 2 -log" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer { rotationZ = arrowRotation },
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(220)) + fadeOut(tween(140)),
            ) {
                OutlinedTextField(
                    value = launchArgs,
                    onValueChange = { vm.setLaunchArgs(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
                    singleLine = false,
                    maxLines = 3,
                    shape = RoundedCornerShape(16.dp),
                    placeholder = { Text("-dev 2 -log") },
                )
            }
        }
    }
}
@Composable
private fun LaunchGameRow(vm: PatcherViewModel, index: Int, total: Int) {
    val engineInstalled by vm.engineInstalled.collectAsState()
    val clientInstalled by vm.clientInstalled.collectAsState()
    val ready = engineInstalled && clientInstalled

    Card(
        onClick = { vm.launchGame() },
        enabled = ready,
        modifier = Modifier.fillMaxWidth(),
        shape = getGroupedItemShape(index, total),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Launch it!",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
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
    animateSize: Boolean = true,
    trailing: @Composable () -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    Card(
        modifier = if (animateSize) {
            Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = 0.9f,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                )
        } else {
            Modifier.fillMaxWidth()
        },
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
    FilledTonalButton(
        onClick = {},
        enabled = false,
        modifier = Modifier.height(38.dp),
        shape = RoundedCornerShape(percent = 50),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            disabledContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            disabledContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
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