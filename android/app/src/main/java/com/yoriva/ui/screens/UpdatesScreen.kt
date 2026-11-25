package com.yoriva.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yoriva.data.ReleaseRepository
import com.yoriva.patcher.AppUpdate
import com.yoriva.patcher.PatcherViewModel
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen(
    vm: PatcherViewModel,
    mandatory: Boolean,
    onBack: () -> Unit,
) {
    val state by vm.appUpdate.collectAsState()
    val availableRelease by vm.availableRelease.collectAsState()
    val lastCheckedAt by vm.lastAppUpdateCheckAtMillis.collectAsState()
    val recentCommits by vm.recentCommits.collectAsState()
    val commitsLoading by vm.commitsLoading.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(Unit) {
        vm.loadRecentCommits()
    }

    if (mandatory) {
        BackHandler(enabled = true) {}
    } else {
        BackHandler(onBack = onBack)
    }

    val available = state as? AppUpdate.Available
    val releaseInfo = available ?: availableRelease
    val downloading = state as? AppUpdate.Downloading
    val installingState = state as? AppUpdate.Installing
    val failed = state as? AppUpdate.Failed
    val isChecking = state is AppUpdate.Checking
    val isInstalling = state is AppUpdate.Downloading ||
        state is AppUpdate.Downloaded ||
        state is AppUpdate.Installing

    val title = when {
        state is AppUpdate.Failed -> if (mandatory) "Update required" else "Could not check for updates"
        isInstalling -> "Installing update"
        releaseInfo != null -> if (mandatory) "Update required" else "Update available"
        isChecking -> "Checking for updates"
        else -> "No updates available"
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            contentWindowInsets = WindowInsets(0),
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        if (!mandatory) {
                            TopBarBackButton(onClick = onBack)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
        ) { paddingValues ->
            val canPullToRefresh = !mandatory && !isInstalling
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                PullToRefreshBox(
                    isRefreshing = canPullToRefresh && isChecking,
                    onRefresh = {
                        if (canPullToRefresh) {
                            vm.checkAppUpdate(manual = true)
                            vm.loadRecentCommits(force = true)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    indicator = {},
                ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    val versionActivity = when {
                        downloading != null -> {
                            val frac = if (downloading.total > 0L) {
                                (downloading.downloaded.toDouble() / downloading.total)
                                    .toFloat()
                                    .coerceIn(0f, 1f)
                            } else null
                            VersionBoxActivity.Downloading(frac)
                        }
                        isInstalling -> VersionBoxActivity.Installing
                        isChecking -> VersionBoxActivity.Checking
                        else -> VersionBoxActivity.Idle
                    }

                    val errorText = failed?.message ?: installingState?.errorMessage
                    if (!errorText.isNullOrBlank()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                        ) {
                            Text(
                                text = errorText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }

                    if (releaseInfo == null) {
                        val lastCheckLabel = if (lastCheckedAt > 0L) {
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lastCheckedAt))
                        } else {
                            "Never"
                        }
                        val localSizeText = vm.installedAppSizeBytes
                            .takeIf { it > 0L }
                            ?.let { formatUpdateBytes(it) }
                            ?: "Unknown"
                        UpdateInfoBento(
                            version = vm.installedAppVersion,
                            versionLabel = "Version",
                            activity = versionActivity,
                            details = listOf(
                                "Last check" to lastCheckLabel,
                                "Size" to localSizeText,
                            ),
                        )
                    } else {
                        val displayVersion = releaseInfo.remoteVersion
                        val sizeText = releaseInfo.size
                            .takeIf { it > 0L }
                            ?.let { formatUpdateBytes(it) }
                            ?: downloading?.total?.takeIf { it > 0L }?.let { formatUpdateBytes(it) }
                            ?: vm.installedAppSizeBytes.takeIf { it > 0L }?.let { formatUpdateBytes(it) }
                            ?: "Auto"
                        val dateText = releaseInfo.updatedAtMillis
                            .takeIf { it > 0L }
                            ?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it)) }
                            ?: vm.installedAppVersion
                        val rightLabel = if (releaseInfo.updatedAtMillis > 0L) {
                            "Date"
                        } else {
                            "Installed"
                        }
                        UpdateInfoBento(
                            version = displayVersion,
                            versionLabel = "Version",
                            activity = versionActivity,
                            details = listOf(
                                "Size" to sizeText,
                                rightLabel to dateText,
                            ),
                        )
                    }

                    if (downloading != null) {
                        val progressText = if (downloading.total > 0L) {
                            "${formatUpdateBytes(downloading.downloaded)} / ${formatUpdateBytes(downloading.total)} · ${formatUpdateSpeed(downloading.bytesPerSec)}"
                        } else {
                            "${formatUpdateBytes(downloading.downloaded)} · ${formatUpdateSpeed(downloading.bytesPerSec)}"
                        }
                        Text(
                            text = progressText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else if (isInstalling) {
                        Text(
                            text = if (installingState?.needsInstallPermission == true) {
                                "Allow Yoriva to install app updates in Android settings, then return here."
                            } else {
                                "Confirm the Android installation prompt. Yoriva will reopen afterward."
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (releaseInfo != null) {
                        Text(
                            text = "Changelog",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        val profile = releaseInfo.authorLogin.ifBlank {
                            releaseInfo.authorName.ifBlank { "Yoriva" }
                        }
                        if (releaseInfo.authorLogin.isNotBlank() ||
                            releaseInfo.authorName.isNotBlank() ||
                            releaseInfo.updatedAtMillis > 0L
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable(enabled = releaseInfo.authorLogin.isNotBlank()) {
                                        if (releaseInfo.authorLogin.isNotBlank()) {
                                            uriHandler.openUri("https://github.com/${releaseInfo.authorLogin}")
                                        }
                                    }
                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                GithubAvatar(username = profile)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = releaseInfo.authorName.ifBlank {
                                            releaseInfo.authorLogin.ifBlank { "Yoriva" }
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    val meta = listOfNotNull(
                                        releaseInfo.authorLogin.takeIf { it.isNotBlank() }?.let { "@$it" },
                                        formatUpdateTimestamp(releaseInfo.updatedAtMillis)
                                            .takeIf { it.isNotBlank() }
                                            ?.let { "Updated $it" },
                                    ).joinToString(" · ")
                                    if (meta.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(1.dp))
                                        Text(
                                            text = meta,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (releaseInfo.authorLogin.isNotBlank()) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }

                        if (releaseInfo.commitMessage.isNotBlank() ||
                            releaseInfo.releaseNotes.isNotBlank() ||
                            releaseInfo.notes.isNotBlank()
                        ) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                ),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    if (releaseInfo.commitMessage.isNotBlank()) {
                                        Text(
                                            text = releaseInfo.commitMessage.trim().take(1200),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        if (releaseInfo.commitSha.isNotBlank()) {
                                            Text(
                                                text = "Commit ${releaseInfo.commitSha.take(7)}",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                ),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    if (releaseInfo.releaseNotes.isNotBlank()) {
                                        if (releaseInfo.commitMessage.isNotBlank()) {
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                        }
                                        Text(
                                            text = releaseInfo.releaseNotes.trim().take(1600),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    } else if (releaseInfo.notes.isNotBlank() && releaseInfo.commitMessage.isBlank()) {
                                        Text(
                                            text = releaseInfo.notes.trim(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (recentCommits.isNotEmpty() || commitsLoading) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Recent commits",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (commitsLoading && recentCommits.isNotEmpty()) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }

                        if (recentCommits.isEmpty() && commitsLoading) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                ),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Text(
                                        text = "Loading recent commits…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                recentCommits.forEachIndexed { index, commit ->
                                    CommitHistoryRow(
                                        commit = commit,
                                        index = index,
                                        total = recentCommits.size,
                                        isLatestBadge = index == 0,
                                        onClick = {
                                            uriHandler.openUri(
                                                "https://github.com/${PatcherViewModel.APP_RELEASE_REPO}/commit/${commit.sha}",
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(84.dp + navigationBarBottomPadding()))
                }
            }
                UpdatesBottomBar(
                    vm = vm,
                    state = state,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    onRequestInstallPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun UpdatesBottomBar(
    vm: PatcherViewModel,
    state: AppUpdate,
    modifier: Modifier = Modifier,
    onRequestInstallPermission: () -> Unit,
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.34f to Color.Transparent,
                    0.42f to surfaceColor,
                    1.0f to surfaceColor,
                ),
            )
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val buttonModifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
        val opaqueButtonColors = ButtonDefaults.buttonColors(
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (state) {
            is AppUpdate.Downloading -> {
                val pct = if (state.total > 0L) {
                    " (${((state.downloaded * 100L) / state.total).toInt().coerceIn(0, 100)}%)"
                } else ""
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = buttonModifier,
                    shape = CircleShape,
                    colors = opaqueButtonColors,
                ) {
                    Text("Downloading$pct")
                }
            }

            is AppUpdate.Downloaded -> {
                Button(
                    onClick = { vm.installAppUpdate(state.file) },
                    enabled = false,
                    modifier = buttonModifier,
                    shape = CircleShape,
                    colors = opaqueButtonColors,
                ) {
                    Text("Preparing installer…")
                }
            }

            is AppUpdate.Installing -> {
                if (state.needsInstallPermission) {
                    Button(
                        onClick = onRequestInstallPermission,
                        modifier = buttonModifier,
                        shape = CircleShape,
                    ) {
                        Text("Allow install permission")
                    }
                } else {
                    Button(
                        onClick = { vm.retryInstallUpdate() },
                        modifier = buttonModifier,
                        shape = CircleShape,
                    ) {
                        Text("Install")
                    }
                }
            }

            is AppUpdate.Available -> {
                Button(
                    onClick = { vm.downloadAppUpdate() },
                    modifier = buttonModifier,
                    shape = CircleShape,
                ) {
                    Text("Download")
                }
            }

            is AppUpdate.Failed -> {
                Button(
                    onClick = {
                        if (state.canRetryDownload) {
                            vm.downloadAppUpdate()
                        } else {
                            vm.checkAppUpdate(manual = true)
                        }
                    },
                    modifier = buttonModifier,
                    shape = CircleShape,
                ) {
                    Text("Retry")
                }
            }

            else -> {
                Button(
                    enabled = state !is AppUpdate.Checking,
                    onClick = { vm.checkAppUpdate(manual = true) },
                    modifier = buttonModifier,
                    shape = CircleShape,
                    colors = opaqueButtonColors,
                ) {
                    Text("Check for updates")
                }
            }
        }
    }
}

private sealed interface VersionBoxActivity {
    data object Idle : VersionBoxActivity
    data object Checking : VersionBoxActivity
    data class Downloading(val progress: Float?) : VersionBoxActivity
    data object Installing : VersionBoxActivity
}

@Composable
private fun UpdateInfoBento(
    version: String,
    details: List<Pair<String, String>>,
    versionLabel: String = "Version",
    activity: VersionBoxActivity = VersionBoxActivity.Idle,
) {
    val outer = 24.dp
    val inner = 4.dp
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        UpdateInfoTile(
            label = versionLabel,
            value = version,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(
                topStart = outer,
                topEnd = outer,
                bottomStart = inner,
                bottomEnd = inner,
            ),
            activity = activity,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            details.forEachIndexed { index, (label, value) ->
                UpdateInfoTile(
                    label = label,
                    value = value,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(
                        topStart = inner,
                        topEnd = inner,
                        bottomStart = if (index == 0) outer else inner,
                        bottomEnd = if (index == details.lastIndex) outer else inner,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun UpdateInfoTile(
    label: String,
    value: String,
    containerColor: Color,
    contentColor: Color,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    activity: VersionBoxActivity = VersionBoxActivity.Idle,
) {
    val isBusy = activity != VersionBoxActivity.Idle
    val isIndeterminate = activity is VersionBoxActivity.Checking ||
        activity is VersionBoxActivity.Installing ||
        (activity is VersionBoxActivity.Downloading && activity.progress == null)
    val determinateTarget = (activity as? VersionBoxActivity.Downloading)?.progress ?: 0f

    val fillVisibilityAlpha by animateFloatAsState(
        targetValue = if (isBusy) 1f else 0f,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "versionFillAlpha",
    )
    val animatedProgress by animateFloatAsState(
        targetValue = determinateTarget,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "versionDeterminateProgress",
    )

    val infiniteTransition = rememberInfiniteTransition(label = "versionFillWave")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1350, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wavePhase",
    )

    val accentFill = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .drawBehind {
                if (fillVisibilityAlpha <= 0.01f) return@drawBehind
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) return@drawBehind

                if (isIndeterminate) {
                    val fillEndPhase = 0.72f
                    val rawFill = (wavePhase / fillEndPhase).coerceIn(0f, 1f)
                    val easedFill = FastOutSlowInEasing.transform(rawFill)
                    val fadeOut = if (wavePhase < fillEndPhase) {
                        1f
                    } else {
                        1f - ((wavePhase - fillEndPhase) / (1f - fillEndPhase)).coerceIn(0f, 1f)
                    }
                    val fillW = (w * easedFill).coerceIn(0f, w)
                    if (fillW > 0f) {
                        drawRect(
                            color = accentFill.copy(alpha = 0.22f * fillVisibilityAlpha * fadeOut),
                            size = Size(fillW, h),
                        )
                    }
                } else {
                    val fillW = (w * animatedProgress).coerceIn(0f, w)
                    if (fillW > 0f) {
                        drawRect(
                            color = accentFill.copy(alpha = 0.24f * fillVisibilityAlpha),
                            size = Size(fillW, h),
                        )
                    }
                }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
            )
            AnimatedVisibility(
                visible = isBusy,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(220)),
            ) {
                val statusLabel = when (activity) {
                    is VersionBoxActivity.Checking -> "Checking…"
                    is VersionBoxActivity.Installing -> "Installing…"
                    is VersionBoxActivity.Downloading -> activity.progress?.let {
                        "${(it * 100f).toInt().coerceIn(0, 100)}%"
                    } ?: "Downloading…"
                    VersionBoxActivity.Idle -> ""
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (activity is VersionBoxActivity.Checking || activity is VersionBoxActivity.Installing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = contentColor,
                        )
                    }
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor.copy(alpha = 0.85f),
                    )
                }
            }
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            ),
            color = contentColor,
        )
    }
}

@Composable
private fun CommitHistoryRow(
    commit: ReleaseRepository.CommitDetails,
    index: Int,
    total: Int,
    isLatestBadge: Boolean,
    onClick: () -> Unit,
) {
    val lines = commit.message.trim().lines()
    val subject = lines.firstOrNull().orEmpty().ifBlank { commit.sha.take(7) }
    val body = lines.drop(1).joinToString("\n").trim()
    val authorDisplay = commit.authorName.ifBlank { commit.authorLogin.ifBlank { "Yoriva" } }
    val timeLabel = formatUpdateTimestamp(commit.authoredAtMillis)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(getGroupedItemShape(index, total))
            .clickable(onClick = onClick),
        shape = getGroupedItemShape(index, total),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (isLatestBadge) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                ) {
                    Text(
                        text = commit.sha.take(7),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = if (isLatestBadge) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                val meta = buildString {
                    append(authorDisplay)
                    if (timeLabel.isNotBlank()) append(" · $timeLabel")
                }
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = subject,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (body.isNotBlank()) {
                Text(
                    text = body.take(400),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatUpdateBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes.toDouble() / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

private fun formatUpdateSpeed(bps: Long): String = "${formatUpdateBytes(bps)}/s"

private fun formatUpdateTimestamp(millis: Long): String {
    if (millis <= 0L) return ""
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
}
