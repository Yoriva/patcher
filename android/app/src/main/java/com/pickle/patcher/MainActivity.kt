package com.pickle.patcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pickle.patcher.data.StoreApi
import com.pickle.patcher.patcher.PatcherViewModel
import com.pickle.patcher.ui.screens.AddonsScreen
import com.pickle.patcher.ui.screens.AdminScreen
import com.pickle.patcher.ui.screens.CompilerScreen
import com.pickle.patcher.ui.screens.PatchScreen
import com.pickle.patcher.ui.screens.SettingsScreen
import com.pickle.patcher.ui.screens.StoreManageScreen
import com.pickle.patcher.ui.screens.StoreProfileScreen
import com.pickle.patcher.ui.screens.StoreScreen
import com.pickle.patcher.ui.screens.TopBarBackButton
import com.pickle.patcher.ui.screens.toDisplayVersion
import com.pickle.patcher.ui.theme.Gray30
import com.pickle.patcher.ui.theme.Gray40
import com.pickle.patcher.ui.theme.Gray60
import com.pickle.patcher.ui.theme.NexoraTheme
import com.pickle.patcher.ui.theme.ThemeController
import com.pickle.patcher.ui.theme.ThemeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.install(applicationContext)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeController: ThemeController = viewModel()
            val themeChoice by themeController.choice.collectAsState()
            NexoraTheme(themeChoice, themeController) {
                val vm: PatcherViewModel = viewModel()
                LaunchedEffect(Unit) {
                    vm.autoInstallAddons()
                    vm.scanAddonsStatus()
                }
                Box(Modifier.fillMaxSize()) {
                    PatcherApp(vm)
                }
            }
        }
    }
}

private enum class Dest(val route: String) {
    Patch("patch"),
    Compiler("compiler"),
    Addons("addons"),
    Store("store"),
    StoreLogin("store/login"),
    StoreRegister("store/register"),
    StoreVerifyEmail("store/verify-email"),
    StoreUpload("store/upload"),
    StoreProfile("store/profile/{username}"),
    StoreManage("store/manage"),
    StoreAdmin("store/admin"),
    Settings("settings"),
    Theme("settings/theme"),
    Plugins("plugins"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatcherApp(vm: PatcherViewModel) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val update by vm.appUpdate.collectAsState()
    var showAbout by remember { mutableStateOf(false) }

    LaunchedEffect(update) {
        val u = update
        if (u is PatcherViewModel.AppUpdate.Downloaded) {
            vm.installIntentFor(u.file)?.let { context.startActivity(it) }
            vm.consumeDownloaded()
        }
    }

    val pendingInstall by vm.pendingInstall.collectAsState()
    LaunchedEffect(pendingInstall) {
        pendingInstall?.let {
            context.startActivity(it)
            vm.consumePendingInstall()
        }
    }

    val uninstallLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        vm.onUninstallFinished()
    }
    val pendingUninstall by vm.pendingUninstall.collectAsState()
    LaunchedEffect(pendingUninstall) {
        pendingUninstall?.let {
            uninstallLauncher.launch(it)
            vm.consumePendingUninstall()
        }
    }

    val bottomNavItems = remember {
        listOf(
            BottomNavItem(Dest.Patch.route, "Patch", Icons.Filled.RocketLaunch, Icons.Outlined.RocketLaunch),
            BottomNavItem(Dest.Compiler.route, "Compiler", Icons.Filled.Code, Icons.Outlined.Code),
            BottomNavItem(Dest.Addons.route, "Addons", Icons.Filled.Extension, Icons.Outlined.Extension),
            BottomNavItem(Dest.Store.route, "Store", Icons.Filled.Storefront, Icons.Outlined.Storefront),
        )
    }

    val mainTabRoutes = remember {
        listOf(
            Dest.Patch.route,
            Dest.Compiler.route,
            Dest.Addons.route,
            Dest.Store.route,
        )
    }

    val isMainTab = currentRoute in mainTabRoutes

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            if (currentRoute != Dest.StoreAdmin.route) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                    navigationIcon = {
                        AnimatedVisibility(
                            visible = !isMainTab,
                            enter = fadeIn(animationSpec = tween(220, delayMillis = 60)) + slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = tween(220, delayMillis = 60)),
                            exit = fadeOut(animationSpec = tween(140)),
                        ) {
                            TopBarBackButton(onClick = { nav.popBackStack() })
                        }
                    },
                    title = {
                        AnimatedContent(
                            targetState = isMainTab,
                            transitionSpec = {
                                fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(160))
                            },
                            label = "titleCrossfade",
                        ) { mainTab ->
                            if (mainTab) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 4.dp),
                                ) {
                                    Image(
                                        painter = painterResource(id = R.drawable.nexora_logo),
                                        contentDescription = "Nexora Logo",
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        "Nexora",
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = (-0.3).sp,
                                        ),
                                        color = MaterialTheme.colorScheme.onBackground,
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    val version by vm.bundleVersion.collectAsState()
                                    val isUpdate = update is PatcherViewModel.AppUpdate.Available
                                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                                    val dotAlpha by infiniteTransition.animateFloat(
                                        initialValue = 0.45f,
                                        targetValue = 1f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(1200, easing = FastOutSlowInEasing),
                                            repeatMode = RepeatMode.Reverse,
                                        ),
                                        label = "dotAlpha",
                                    )

                                    Surface(
                                        shape = RoundedCornerShape(percent = 50),
                                        color = if (isUpdate) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        },
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .graphicsLayer { alpha = dotAlpha }
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (isUpdate) MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.secondary,
                                                    ),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = if (isUpdate) "Update" else if (version.isNotBlank()) version.toDisplayVersion() else "Live",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                color = if (isUpdate) {
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                            )
                                        }
                                    }
                                }
                            } else {
                                val title = when (currentRoute) {
                                    Dest.Settings.route -> "Settings"
                                    Dest.Theme.route -> "Theme"
                                    Dest.StoreLogin.route -> "Sign In"
                                    Dest.StoreRegister.route -> "Register"
                                    Dest.StoreUpload.route -> "Upload Package"
                                    Dest.StoreManage.route -> "Manage Posts"
                                    else -> "Nexora"
                                }
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = (-0.3).sp,
                                    ),
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                            }
                        }
                    },
                    actions = {
                        var settingsRotationAngle by remember { mutableFloatStateOf(0f) }
                        val settingsRotation by animateFloatAsState(
                            targetValue = settingsRotationAngle,
                            animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow),
                            label = "settingsRotation",
                        )
                        AnimatedVisibility(
                            visible = isMainTab,
                            enter = fadeIn(animationSpec = tween(220, delayMillis = 60)),
                            exit = fadeOut(animationSpec = tween(150)),
                        ) {
                            IconButton(
                                onClick = {
                                    settingsRotationAngle += 180f
                                    scope.launch {
                                        kotlinx.coroutines.delay(120)
                                        nav.navigate(Dest.Settings.route) {
                                            launchSingleTop = true
                                        }
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Settings,
                                    contentDescription = "Settings",
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .graphicsLayer { rotationZ = settingsRotation },
                                )
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (isMainTab) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 0.dp,
                ) {
                    bottomNavItems.forEach { item ->
                        val selected = currentRoute == item.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (currentRoute != item.route) {
                                    nav.navigate(item.route) {
                                        popUpTo(nav.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.title,
                                )
                            },
                            label = {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    ),
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onBackground,
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            NavHost(
                navController = nav,
                startDestination = Dest.Patch.route,
                modifier = Modifier.fillMaxSize(),
                enterTransition = { fadeIn(animationSpec = tween(durationMillis = 200)) },
                exitTransition = { fadeOut(animationSpec = tween(durationMillis = 180)) },
                popEnterTransition = { fadeIn(animationSpec = tween(durationMillis = 200)) },
                popExitTransition = { fadeOut(animationSpec = tween(durationMillis = 180)) },
            ) {
                composable(Dest.Patch.route) { PatchScreen(vm) }
                composable(Dest.Compiler.route) {
                    CompilerScreen(
                        vm = vm,
                        onOpenSettings = {
                            nav.navigate(Dest.Settings.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
                composable(Dest.Addons.route) { AddonsScreen(vm) }
                composable(Dest.Store.route) {
                    val api = StoreApi("https://nexora-store-tawny.vercel.app", context)
                    StoreScreen(
                        api = api,
                        onBack = { nav.popBackStack() },
                        onUserClick = { username -> nav.navigate("store/profile/$username") },
                    )
                }
                composable(Dest.StoreLogin.route) {
                    val api = StoreApi("https://nexora-store-tawny.vercel.app", context)
                    com.pickle.patcher.ui.screens.StoreLoginScreen(
                        api = api,
                        onBack = { nav.popBackStack() },
                        onLoginSuccess = { nav.popBackStack() },
                        onRegister = { nav.navigate(Dest.StoreRegister.route) },
                    )
                }
                composable(Dest.StoreRegister.route) {
                    val api = StoreApi("https://nexora-store-tawny.vercel.app", context)
                    com.pickle.patcher.ui.screens.StoreRegisterScreen(
                        api = api,
                        onBack = { nav.popBackStack() },
                        onRegisterSuccess = { email -> nav.navigate("store/verify-email?email=$email") },
                    )
                }
                composable(
                    route = "store/verify-email?email={email}",
                    arguments = listOf(navArgument("email") { type = NavType.StringType; defaultValue = "" }),
                ) { backStack ->
                    val email = backStack.arguments?.getString("email") ?: ""
                    val api = StoreApi("https://nexora-store-tawny.vercel.app", context)
                    com.pickle.patcher.ui.screens.StoreVerifyEmailScreen(
                        api = api,
                        email = email,
                        onBack = { nav.popBackStack() },
                        onVerified = { nav.popBackStack() },
                    )
                }
                composable(Dest.StoreUpload.route) {
                    val api = StoreApi("https://nexora-store-tawny.vercel.app", context)
                    com.pickle.patcher.ui.screens.StoreUploadScreen(
                        api = api,
                        onBack = { nav.popBackStack() },
                        onUploadSuccess = { nav.popBackStack() },
                    )
                }
                composable(Dest.StoreManage.route) {
                    val api = StoreApi("https://nexora-store-tawny.vercel.app", context)
                    StoreManageScreen(api, onBack = { nav.popBackStack() })
                }
                composable(Dest.StoreAdmin.route) {
                    val api = StoreApi("https://nexora-store-tawny.vercel.app", context)
                    AdminScreen(api, onBack = { nav.popBackStack() })
                }
                composable(
                    route = Dest.StoreProfile.route,
                    arguments = listOf(navArgument("username") { type = NavType.StringType }),
                ) { backStack ->
                    val username = backStack.arguments?.getString("username") ?: ""
                    val api = StoreApi("https://nexora-store-tawny.vercel.app", context)
                    StoreProfileScreen(api, username = username, onBack = { nav.popBackStack() }, onEditProfile = { })
                }
                composable(Dest.Settings.route) {
                    SettingsScreen(
                        vm = vm,
                        onOpenTheme = {
                            nav.navigate(Dest.Theme.route) {
                                launchSingleTop = true
                            }
                        },
                        onOpenAbout = { showAbout = true },
                        onShareLogs = {
                            scope.launch(Dispatchers.IO) {
                                val text = vm.buildLogShareText()
                                withContext(Dispatchers.Main) {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_TEXT, text)
                                    }
                                    context.startActivity(android.content.Intent.createChooser(intent, "Share logs"))
                                }
                            }
                        },
                        onRedownloadBundle = { vm.fetchAndDownloadBundle() },
                    )
                }
                composable(Dest.Theme.route) {
                    ThemeScreen(onBack = { nav.popBackStack() })
                }
                composable(Dest.Plugins.route) { AddonsScreen(vm) }
            }
        }
    }

    if (showAbout) {
        AboutDialog(version = appVersion(context), onDismiss = { showAbout = false })
    }

    val showUpdateDialog = update is PatcherViewModel.AppUpdate.Available ||
        update is PatcherViewModel.AppUpdate.Downloading ||
        update is PatcherViewModel.AppUpdate.UpToDate ||
        update is PatcherViewModel.AppUpdate.Failed
    if (showUpdateDialog) {
        UpdateDialog(vm, update)
    }
}

private data class BottomNavItem(
    val route: String,
    val title: String,
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    val unselectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
)

@Composable
private fun AboutDialog(version: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nexora") },
        text = {
            Column {
                Text(
                    "Version: $version",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "AMXX bundle injection for CS 1.6 on Android: Pawn compiler, addons manager and patch engine.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "github.com/berkchy/nexora",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun appVersion(context: android.content.Context): String {
    val pm = context.packageManager
    return try {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
                .versionName ?: "unknown"
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        }
    } catch (_: Throwable) {
        "unknown"
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes.toDouble() / 1024.0
    if (kb < 1024.0) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

private fun formatSpeed(bps: Long): String = "${formatBytes(bps)}/s"

@Composable
private fun UpdateDialog(vm: PatcherViewModel, state: PatcherViewModel.AppUpdate) {
    AlertDialog(
        onDismissRequest = {
            if (state !is PatcherViewModel.AppUpdate.Downloading) vm.dismissUpdate()
        },
        title = {
            Text(
                when (state) {
                    is PatcherViewModel.AppUpdate.Available -> "New update available ${state.tag}"
                    is PatcherViewModel.AppUpdate.Downloading -> "Downloading ${state.tag}…"
                    is PatcherViewModel.AppUpdate.UpToDate -> "Up to date"
                    is PatcherViewModel.AppUpdate.Failed -> "Update failed"
                    else -> "Update"
                },
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (state is PatcherViewModel.AppUpdate.Available) {
                    if (state.commits.isNotEmpty() || state.notes.isNotBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            if (state.commits.isNotEmpty()) {
                                Text(
                                    "Changes:",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Gray30,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                state.commits.forEach { msg ->
                                    Text(
                                        "• $msg",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Gray40,
                                    )
                                }
                            }
                            if (state.notes.isNotBlank()) {
                                if (state.commits.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    HorizontalDivider(color = Gray60)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "Release Notes:",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Gray30,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                                Text(
                                    state.notes.trim().take(1200),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Gray40,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text(
                        if (state.size > 0) "Size: ${formatBytes(state.size)}"
                        else "Size: varies with build",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                    )
                }
                if (state is PatcherViewModel.AppUpdate.Downloading) {
                    val frac = if (state.total > 0) {
                        (state.downloaded.toDouble() / state.total).toFloat().coerceIn(0f, 1f)
                    } else 0f
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${formatBytes(state.downloaded)} / ${formatBytes(state.total)}  ·  ${formatSpeed(state.bytesPerSec)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Install starts automatically when the download finishes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                    )
                }
                if (state is PatcherViewModel.AppUpdate.UpToDate) {
                    Text(
                        "You have the latest version (${state.tag}).",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                    )
                }
                if (state is PatcherViewModel.AppUpdate.Failed) {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                    )
                }
            }
        },
        confirmButton = {
            when (state) {
                is PatcherViewModel.AppUpdate.Available -> {
                    TextButton(onClick = { vm.downloadAppUpdate() }) {
                        Text("Download")
                    }
                }
                is PatcherViewModel.AppUpdate.Failed -> {
                    TextButton(onClick = { vm.checkAppUpdate() }) {
                        Text("Retry")
                    }
                }
                else -> {}
            }
        },
        dismissButton = {
            if (state !is PatcherViewModel.AppUpdate.Downloading) {
                TextButton(onClick = { vm.dismissUpdate() }) {
                    Text("Later")
                }
            }
        },
    )
}
