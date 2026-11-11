package com.pickle.patcher.ui.screens
import android.content.Intent
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pickle.patcher.patcher.AddonsState
import com.pickle.patcher.patcher.PatcherViewModel

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AddonsScreen(
    vm: PatcherViewModel,
    gameInstalled: Boolean = true,
    onGoToPatch: () -> Unit = {},
) {
    if (!gameInstalled) {
        LockedScreen(
            title = "Patch it first",
            description = "Addons install the mod files into your game directory. That step is available after you patch and install the client on the Patch screen.",
            ctaLabel = "Open Patch screen",
            onCta = onGoToPatch,
        )
        return
    }
    var selectedTab by remember { mutableStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.primary,
                divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)) },
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Packages", style = MaterialTheme.typography.titleSmall) },
                    icon = { Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("plugins.ini", style = MaterialTheme.typography.titleSmall) },
                    icon = { Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (selectedTab) {
            0 -> AddonsPackagesContent(vm)
            1 -> PluginsScreen(vm)
        }
    }
}

@Composable
private fun AddonsPackagesContent(vm: PatcherViewModel) {
    val context = LocalContext.current
    val addons by vm.addons.collectAsState()
    val installed by vm.addonsInstalled.collectAsState()
    val installPath by vm.installPath.collectAsState()
    val downloading = addons as? AddonsState.Downloading
    val error = addons as? AddonsState.Error
    val doneMessage = (addons as? AddonsState.Done)?.message
    val hasStorage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else true
    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            vm.fetchAndInstallAddons()
        }
    }
    fun install() {
        if (hasStorage) {
            vm.fetchAndInstallAddons()
        } else {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}"),
            )
            storageLauncher.launch(intent)
        }
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader(
                title = "Addons",
                caption = "Install the mod files into your game directory",
            )
        }
        item {
            GroupedItem(
                title = when {
                    downloading != null -> "Installing…"
                    error != null -> "Install failed"
                    installed -> "Addons ready"
                    else -> "Setup required"
                },
                subtitle = when {
                    downloading != null -> downloading.step
                    error != null -> error.message
                    installed -> doneMessage ?: "Installed into the game directory"
                    else -> "Download the addon bundle to install the mod"
                },
                index = 0,
                total = 1,
                error = error != null,
                trailing = {
                    if (downloading != null) {
                        SmallProgressButton("${(downloading.percent * 100).toInt()}%")
                    } else {
                        SmallButton(
                            text = if (installed) "Reinstall" else "Download",
                            icon = Icons.Filled.Download,
                            filled = true,
                            onClick = ::install,
                        )
                    }
                },
            ) {
                downloading?.let { state ->
                    ProgressFooter(progress = state.percent, left = state.step, right = "${(state.percent * 100).toInt()}%")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    installPath,
                    modifier = Modifier.padding(horizontal = 18.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!hasStorage) {
            item {
                Button(
                    onClick = ::install,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Grant File Access")
                }
            }
        }
    }
}
