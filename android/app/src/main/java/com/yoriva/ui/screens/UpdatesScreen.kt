package com.yoriva.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
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
    val lastCheckedAt by vm.lastAppUpdateCheckAtMillis.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    if (mandatory) {
        BackHandler(enabled = true) {}
    } else {
        BackHandler(onBack = onBack)
    }

    val available = state as? AppUpdate.Available
    val downloading = state as? AppUpdate.Downloading
    val installingState = state as? AppUpdate.Installing
    val failed = state as? AppUpdate.Failed
    val isChecking = state is AppUpdate.Checking
    val isInstalling = state is AppUpdate.Downloading ||
        state is AppUpdate.Downloaded ||
        state is AppUpdate.Installing

    val title = when (state) {
        is AppUpdate.Failed -> if (mandatory) "Update required" else "Could not check for updates"
        is AppUpdate.Downloading -> "Downloading update"
        is AppUpdate.Downloaded -> "Preparing update"
        is AppUpdate.Installing -> if (installingState?.needsInstallPermission == true) {
            "Permission required"
        } else {
            "Installing update"
        }
        is AppUpdate.Available -> if (mandatory) "Update required" else "Update available"
        is AppUpdate.Checking -> "Checking for updates"
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
            bottomBar = {
                UpdatesBottomBar(
                    vm = vm,
                    state = state,
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
            },
        ) { paddingValues ->
            val canPullToRefresh = !mandatory && !isInstalling
            PullToRefreshBox(
                isRefreshing = canPullToRefresh && isChecking,
                onRefresh = {
                    if (canPullToRefresh) {
                        vm.checkAppUpdate(manual = true)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
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

                    if (isInstalling || isChecking) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (downloading != null && downloading.total > 0L) {
                                val frac = (downloading.downloaded.toDouble() / downloading.total)
                                    .toFloat()
                                    .coerceIn(0f, 1f)
                                LinearProgressIndicator(
                                    progress = { frac },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(CircleShape),
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(CircleShape),
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
                                )
                            }
                        }
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

                    if (available == null && downloading == null && state !is AppUpdate.Downloaded && installingState == null) {
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
                            details = listOf(
                                "Last check" to lastCheckLabel,
                                "Size" to localSizeText,
                            ),
                        )
                    } else {
                        val displayVersion = available?.remoteVersion
                            ?: downloading?.tag
                            ?: (state as? AppUpdate.Downloaded)?.tag
                            ?: installingState?.tag
                            ?: vm.installedAppVersion
                        val sizeText = available?.size
                            ?.takeIf { it > 0L }
                            ?.let { formatUpdateBytes(it) }
                            ?: downloading?.total?.takeIf { it > 0L }?.let { formatUpdateBytes(it) }
                            ?: vm.installedAppSizeBytes.takeIf { it > 0L }?.let { formatUpdateBytes(it) }
                            ?: "Auto"
                        val dateText = available?.updatedAtMillis
                            ?.takeIf { it > 0L }
                            ?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it)) }
                            ?: vm.installedAppVersion
                        val rightLabel = if (available?.updatedAtMillis?.let { it > 0L } == true) {
                            "Date"
                        } else {
                            "Installed"
                        }
                        UpdateInfoBento(
                            version = displayVersion,
                            versionLabel = "Version",
                            details = listOf(
                                "Size" to sizeText,
                                rightLabel to dateText,
                            ),
                        )
                    }

                    if (available != null) {
                        Text(
                            text = "Changelog",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        val profile = available.authorLogin.ifBlank {
                            available.authorName.ifBlank { "Yoriva" }
                        }
                        if (available.authorLogin.isNotBlank() ||
                            available.authorName.isNotBlank() ||
                            available.updatedAtMillis > 0L
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable(enabled = available.authorLogin.isNotBlank()) {
                                        if (available.authorLogin.isNotBlank()) {
                                            uriHandler.openUri("https://github.com/${available.authorLogin}")
                                        }
                                    }
                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                UpdateAuthorAvatar(username = profile)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = available.authorName.ifBlank {
                                            available.authorLogin.ifBlank { "Yoriva" }
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    val meta = listOfNotNull(
                                        available.authorLogin.takeIf { it.isNotBlank() }?.let { "@$it" },
                                        formatUpdateTimestamp(available.updatedAtMillis)
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
                                if (available.authorLogin.isNotBlank()) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }

                        if (available.commitMessage.isNotBlank() ||
                            available.releaseNotes.isNotBlank() ||
                            available.notes.isNotBlank()
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
                                    if (available.commitMessage.isNotBlank()) {
                                        Text(
                                            text = available.commitMessage.trim().take(1200),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        if (available.commitSha.isNotBlank()) {
                                            Text(
                                                text = "Commit ${available.commitSha.take(7)}",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                ),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    if (available.releaseNotes.isNotBlank()) {
                                        if (available.commitMessage.isNotBlank()) {
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                        }
                                        Text(
                                            text = available.releaseNotes.trim().take(1600),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    } else if (available.notes.isNotBlank() && available.commitMessage.isBlank()) {
                                        Text(
                                            text = available.notes.trim(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun UpdatesBottomBar(
    vm: PatcherViewModel,
    state: AppUpdate,
    onRequestInstallPermission: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val buttonModifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
        when (state) {
            is AppUpdate.Downloading -> {
                val pct = if (state.total > 0L) {
                    " (${((state.downloaded * 100L) / state.total).toInt().coerceIn(0, 100)}%)"
                } else ""
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = buttonModifier,
                    shape = CircleShape,
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
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { vm.retryInstallUpdate() },
                        modifier = buttonModifier,
                        shape = CircleShape,
                    ) {
                        Text("Install")
                    }
                } else {
                    Button(
                        onClick = { vm.retryInstallUpdate() },
                        modifier = buttonModifier,
                        shape = CircleShape,
                    ) {
                        Text("Install")
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(onClick = { vm.redownloadAppUpdate() }) {
                        Text("Re-download APK")
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
                ) {
                    Text("Check for updates")
                }
            }
        }
    }
}

@Composable
private fun UpdateInfoBento(
    version: String,
    details: List<Pair<String, String>>,
    versionLabel: String = "Version",
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
) {
    Column(
        modifier = modifier
            .background(containerColor, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
        )
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
private fun UpdateAuthorAvatar(username: String) {
    SubcomposeAsyncImage(
        model = "https://github.com/$username.png?size=200",
        contentDescription = "$username GitHub avatar",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        loading = {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
        error = {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = username.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        },
    )
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
