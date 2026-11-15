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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
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
    val abi by vm.abi.collectAsState()
    val patchable = abi in vm.patchableAbis
    val showStatus = patchable && (!engineInstalled || !cstrikeReady)

    LaunchedEffect(Unit) {
        vm.runAutoDetectAbi()
        vm.refreshSourceApkStatus()
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
            "https://github.com/Yoriva/patcher/releases",
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
            Spacer(Modifier.width(16.dp))
            Button(
                onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                },
            ) {
                Text("Install")
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
    val errorNote = outdated?.let { "Client APK: ${it.reason} — download to update it." }
        ?: when (val s = download) {
            is SourceDownloadState.Corrupt -> "${s.reason}. Re-download required."
            is SourceDownloadState.Failed -> s.message
            else -> (bundleState as? BundleState.DownloadError)?.message
        }
    val actionKey = when {
        !patchable -> "disabled"
        busy -> "busy"
        allReady && outdated == null -> "ready"
        else -> "download"
    }
    GroupedItem(
        title = "Assets",
        subtitle = when {
            !patchable -> "Not supported on ${displayAbi(abi)} — ARM only"
            isFetching -> "Checking the latest release…"
            downloading != null -> "Downloading the client APK…"
            downloadingBundle != null -> "Downloading mod libraries…"
            outdated != null -> "A newer client build is available on GitHub"
            allReady -> "Client APK and mod libraries"
            !hasClient && bundleReady -> "The client APK is still required"
            hasClient && !bundleReady -> "Mod libraries are still required"
            else -> "Client APK and mod libraries"
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
                        "busy" -> SmallProgressButton("Downloading")
                        "disabled" -> SmallButton("Not supported", Icons.Filled.Warning, enabled = false) { }
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
                left = if (downloadingBundle.fileTotal > 0) "File ${downloadingBundle.fileIndex + 1} of ${downloadingBundle.fileTotal}" else "Downloading…",
                right = "${(downloadingBundle.percent * 100).toInt()}%",
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
            is PatchUiState.Done -> if (clientIsInstalled) "Already Patched" else "Patch Succeeded"
            is PatchUiState.Failed -> "Patch Failed"
            is PatchUiState.Running -> "Patching APK…"
            PatchUiState.Idle -> if (showPatched) "Already Patched" else "Build Patched APK"
        },
        subtitle = when (patchState) {
            is PatchUiState.Done, PatchUiState.Idle -> if (showPatched) {
                "The patched client is installed on this device"
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
                            SmallButton("Repatch", Icons.Filled.RocketLaunch, filled = true) {
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
internal fun GroupedItem(
    title: String,
    subtitle: String? = null,
    index: Int,
    total: Int,
    error: Boolean = false,
    trailing: @Composable () -> Unit = {},
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