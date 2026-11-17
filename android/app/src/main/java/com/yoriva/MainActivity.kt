package com.yoriva
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yoriva.patcher.AppUpdate
import com.yoriva.patcher.PatcherViewModel
import com.yoriva.ui.screens.ToolsScreen
import com.yoriva.ui.screens.LibraryScreen
import com.yoriva.ui.screens.PatchScreen
import com.yoriva.ui.screens.PluginsScreen
import com.yoriva.ui.screens.SettingsScreen
import com.yoriva.ui.screens.PluginEditorScreen
import com.yoriva.ui.screens.SaveChangesDialog
import com.yoriva.ui.screens.TopBarBackButton
import com.yoriva.ui.screens.getGroupedItemShape
import com.yoriva.ui.theme.Gray30
import com.yoriva.ui.theme.Gray40
import com.yoriva.ui.theme.Gray60
import com.yoriva.ui.theme.YorivaTheme
import com.yoriva.ui.theme.ThemeController
import com.yoriva.ui.theme.ThemeScreen
import coil.compose.SubcomposeAsyncImage
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
            YorivaTheme(themeChoice, themeController) {
                val vm: PatcherViewModel = viewModel()
                LaunchedEffect(Unit) {
                    vm.refreshGameInstalled()
                    vm.autoInstallAddons()
                }
                Box(Modifier.fillMaxSize()) {
                    PatcherApp(vm)
                }
            }
        }
    }
}
private enum class Dest(val route: String) {
    Tabs("tabs"),
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
    val setupPrefs = remember {
        context.getSharedPreferences("nexora_setup", Context.MODE_PRIVATE)
    }
    var showSetup by remember {
        mutableStateOf(!setupPrefs.getBoolean("completed", false))
    }
    var storageGranted by remember { mutableStateOf(hasStorageAccess(context)) }
    var notificationsGranted by remember { mutableStateOf(hasNotificationsAccess(context)) }
    val notificationsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notificationsGranted = it }
    val legacyStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        storageGranted = result[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true ||
            result[Manifest.permission.READ_EXTERNAL_STORAGE] == true ||
            hasStorageAccess(context)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                storageGranted = hasStorageAccess(context)
                notificationsGranted = hasNotificationsAccess(context)
                vm.refreshGameInstalled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val requestStorageAccess: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appSettings = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
            try {
                context.startActivity(appSettings)
            } catch (_: Exception) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyStorageLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
            )
        }
    }
    val requestNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationsGranted = hasNotificationsAccess(context)
        }
    }
    LaunchedEffect(update) {
        val u = update
        if (u is AppUpdate.Downloaded) {
            vm.installIntentFor(u.file)?.let { context.startActivity(it) }
            vm.consumeDownloaded()
        }
    }
    val clientInstalled by vm.gameInstalled.collectAsState()
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
        vm.refreshGameInstalled()
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
            BottomNavItem(0, "Patch", Icons.Filled.RocketLaunch, Icons.Outlined.RocketLaunch),
            BottomNavItem(1, "Tools", Icons.Filled.Handyman, Icons.Outlined.Handyman),
        )
    }
    val isMainTab = currentRoute == Dest.Tabs.route
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    val tabScope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showPluginConfig by remember { mutableStateOf(false) }
    var showPluginEditor by remember { mutableStateOf(false) }
    var pluginFile by remember { mutableStateOf<com.yoriva.patcher.ConfigFileInfo?>(null) }
    var showPluginSaveDialog by remember { mutableStateOf(false) }
    val iniDirty by vm.iniDirty.collectAsState()
    var showLibraries by remember { mutableStateOf(false) }
    BackHandler(
        enabled = showPluginSaveDialog ||
            showPluginEditor ||
            showPluginConfig ||
            showTheme ||
            showLibraries ||
            showSettings ||
            (isMainTab && pagerState.currentPage != 0 && !showSetup),
    ) {
        when {
            showPluginSaveDialog -> showPluginSaveDialog = false
            showPluginEditor -> {
                if (iniDirty) showPluginSaveDialog = true else showPluginEditor = false
            }
            showPluginConfig -> showPluginConfig = false
            showTheme -> showTheme = false
            showLibraries -> showLibraries = false
            showSettings -> showSettings = false
            else -> tabScope.launch { pagerState.animateScrollToPage(0) }
        }
    }
    val settingsRotation by animateFloatAsState(
        targetValue = if (showSettings) 90f else 0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow),
        label = "settingsRotation",
    )
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            topBar = {
                TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.onBackground,
                            actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                        ),
                        navigationIcon = {
                            if (!isMainTab) {
                                TopBarBackButton(onClick = { nav.popBackStack() })
                            }
                        },
                        title = {
                            if (isMainTab) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 4.dp),
                                ) {
                                    Image(
                                        painter = painterResource(id = R.drawable.yoriva_logo),
                                        contentDescription = "Yoriva Logo",
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        "Yoriva",
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = (-0.3).sp,
                                        ),
                                        color = MaterialTheme.colorScheme.onBackground,
                                    )
                                }
                            } else {
                                Text(
                                    text = "Yoriva",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = (-0.3).sp,
                                    ),
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                            }
                        },
                        actions = {
                            if (isMainTab) {
                                IconButton(onClick = { showSettings = true }) {
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
            },
            bottomBar = {
                if (isMainTab) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.background,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        tonalElevation = 0.dp,
                    ) {
                        bottomNavItems.forEach { item ->
                            val selected = pagerState.currentPage == item.index
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    if (pagerState.currentPage != item.index) {
                                        tabScope.launch { pagerState.animateScrollToPage(item.index) }
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                NavHost(
                    navController = nav,
                    startDestination = Dest.Tabs.route,
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = {
                        slideInHorizontally(
                            initialOffsetX = { fullWidth -> (fullWidth * 0.25f).toInt() },
                            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                        ) + fadeIn(animationSpec = tween(durationMillis = 250))
                    },
                    exitTransition = {
                        slideOutHorizontally(
                            targetOffsetX = { fullWidth -> (-fullWidth * 0.15f).toInt() },
                            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                        ) + fadeOut(animationSpec = tween(durationMillis = 200))
                    },
                    popEnterTransition = {
                        slideInHorizontally(
                            initialOffsetX = { fullWidth -> (-fullWidth * 0.15f).toInt() },
                            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                        )
                    },
                    popExitTransition = {
                        slideOutHorizontally(
                            targetOffsetX = { fullWidth -> fullWidth },
                            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                        )
                    },
                ) {
                    composable(Dest.Tabs.route) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            beyondViewportPageCount = 1,
                        ) { page ->
                            when (page) {
                                0 -> PatchScreen(
                                    vm = vm,
                                    onOpenLibraries = { showLibraries = true },
                                )
                                else -> ToolsScreen(
                                    vm = vm,
                                    gameInstalled = clientInstalled,
                                    onGoToPatch = { tabScope.launch { pagerState.animateScrollToPage(0) } },
                                    onOpenPluginConfig = { showPluginConfig = true },
                                )
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = showSettings,
            enter = slideInHorizontally(
                initialOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
            ) + fadeIn(animationSpec = tween(durationMillis = 250)),
            exit = slideOutHorizontally(
                targetOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
            ) + fadeOut(animationSpec = tween(durationMillis = 250)),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(
                        vm = vm,
                        onBack = {
                            showTheme = false
                            showSettings = false
                        },
                        onOpenTheme = { showTheme = true },
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
                    )
                    AnimatedVisibility(
                        visible = showTheme,
                        enter = slideInHorizontally(
                            initialOffsetX = { fullWidth -> fullWidth },
                            animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                        ) + fadeIn(animationSpec = tween(durationMillis = 250)),
                        exit = slideOutHorizontally(
                            targetOffsetX = { fullWidth -> fullWidth },
                            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                        ) + fadeOut(animationSpec = tween(durationMillis = 250)),
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background,
                        ) {
                            ThemeScreen(
                                onBack = { showTheme = false },
                            )
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = showLibraries,
            enter = slideInHorizontally(
                initialOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
            ) + fadeIn(animationSpec = tween(durationMillis = 250)),
            exit = slideOutHorizontally(
                targetOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
            ) + fadeOut(animationSpec = tween(durationMillis = 250)),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = "Libraries",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                            },
                            navigationIcon = {
                                TopBarBackButton(onClick = { showLibraries = false })
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background,
                                titleContentColor = MaterialTheme.colorScheme.onBackground,
                            ),
                        )
                    },
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        LibraryScreen(vm)
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = showPluginConfig,
            enter = slideInHorizontally(
                initialOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
            ) + fadeIn(animationSpec = tween(durationMillis = 250)),
            exit = slideOutHorizontally(
                targetOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
            ) + fadeOut(animationSpec = tween(durationMillis = 250)),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = "Config Editor",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                            },
                            navigationIcon = {
                                TopBarBackButton(onClick = { showPluginConfig = false })
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background,
                                titleContentColor = MaterialTheme.colorScheme.onBackground,
                            ),
                        )
                    },
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        PluginsScreen(
                            vm = vm,
                            onSelect = {
                                pluginFile = it
                                showPluginEditor = true
                            },
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = showPluginEditor,
            enter = slideInHorizontally(
                initialOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
            ) + fadeIn(animationSpec = tween(durationMillis = 250)),
            exit = slideOutHorizontally(
                targetOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
            ) + fadeOut(animationSpec = tween(durationMillis = 250)),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = pluginFile?.name ?: "Config",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                            },
                            navigationIcon = {
                                TopBarBackButton(onClick = {
                                    if (iniDirty) showPluginSaveDialog = true else showPluginEditor = false
                                })
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background,
                                titleContentColor = MaterialTheme.colorScheme.onBackground,
                            ),
                        )
                    },
                ) { padding ->
                    val info = pluginFile
                    if (info != null) {
                        Column(Modifier.fillMaxSize().padding(padding)) {
                            PluginEditorScreen(vm = vm, info = info)
                        }
                    }
                }
            }
        }
        if (showPluginSaveDialog && pluginFile != null) {
            SaveChangesDialog(
                fileName = pluginFile!!.name,
                onSave = {
                    vm.savePluginIni(pluginFile!!.file)
                    showPluginEditor = false
                    showPluginSaveDialog = false
                },
                onDiscard = {
                    showPluginEditor = false
                    showPluginSaveDialog = false
                },
                onCancel = { showPluginSaveDialog = false },
            )
        }
        if (showSetup) {
            SetupScreen(
                storageGranted = storageGranted,
                notificationsGranted = notificationsGranted,
                onRequestStorage = requestStorageAccess,
                onRequestNotifications = requestNotifications,
                onContinue = {
                    setupPrefs.edit().putBoolean("completed", true).apply()
                    showSetup = false
                },
            )
        }
    }
    if (showAbout) {
        AboutDialog(version = appVersion(context), onDismiss = { showAbout = false })
    }
    val showUpdateDialog = update is AppUpdate.Available ||
        update is AppUpdate.Downloading ||
        update is AppUpdate.UpToDate ||
        update is AppUpdate.Failed
    if (showUpdateDialog) {
        UpdateDialog(vm, update)
    }
}
private fun hasStorageAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }
private fun hasNotificationsAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
@Composable
private fun SetupScreen(
    storageGranted: Boolean,
    notificationsGranted: Boolean,
    onRequestStorage: () -> Unit,
    onRequestNotifications: () -> Unit,
    onContinue: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(id = R.drawable.yoriva_logo),
                contentDescription = null,
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Welcome to Yoriva",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "A quick setup before you start patching.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                PermissionRow(
                    icon = Icons.Filled.Folder,
                    title = "Storage access",
                    subtitle = if (storageGranted) "Granted" else "Required to patch and save files",
                    granted = storageGranted,
                    index = 0,
                    onRequest = onRequestStorage,
                )
                PermissionRow(
                    icon = Icons.Filled.Notifications,
                    title = "Notifications",
                    subtitle = if (notificationsGranted) "Granted" else "Optional · build and update alerts",
                    granted = notificationsGranted,
                    index = 1,
                    onRequest = onRequestNotifications,
                )
            }
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onContinue,
                enabled = storageGranted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Continue")
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (storageGranted) {
                    "You can change these later in Settings."
                } else {
                    "Storage access is required to continue."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
    index: Int,
    onRequest: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = getGroupedItemShape(index, 2),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (granted) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (granted) Icons.Filled.CheckCircle else icon,
                    contentDescription = null,
                    tint = if (granted) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
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
            if (!granted) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onRequest) {
                    Text("Allow")
                }
            }
        }
    }
}
private data class BottomNavItem(
    val index: Int,
    val title: String,
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    val unselectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
)
@Composable
private fun AboutDialog(version: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp),
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.secondaryContainer,
                                ),
                            ),
                        )
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            painter = painterResource(id = R.drawable.yoriva_logo),
                            contentDescription = null,
                            modifier = Modifier.size(96.dp),
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Yoriva",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Version $version",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "AMXX bundle injection for CS 1.6 on Android. Includes a Pawn compiler, addons manager and patch engine.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "Developers",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    DeveloperRow(name = "berkchy", role = "Founder", github = "berkchy")
                    Spacer(modifier = Modifier.height(8.dp))
                    DeveloperRow(name = "nenquen", role = "UI Developer", github = "nenquen")
                    Spacer(modifier = Modifier.height(20.dp))
                    FilledTonalButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}
@Composable
private fun DeveloperRow(name: String, role: String, github: String) {
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { uriHandler.openUri("https://github.com/$github") }
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GithubAvatar(username = github)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = role,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp),
        )
    }
}
@Composable
private fun GithubAvatar(username: String) {
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
private fun formatUpdateDate(millis: Long): String {
    if (millis <= 0L) return ""
    return java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.MEDIUM,
        java.text.DateFormat.SHORT,
    ).format(java.util.Date(millis))
}
@Composable
private fun UpdateDialog(vm: PatcherViewModel, state: AppUpdate) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = {
            if (state !is AppUpdate.Downloading) vm.dismissUpdate()
        },
        icon = {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(28.dp),
                )
            }
        },
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    when (state) {
                        is AppUpdate.Available -> "New update available"
                        is AppUpdate.Downloading -> "Downloading ${state.tag}…"
                        is AppUpdate.UpToDate -> "Up to date"
                        is AppUpdate.Failed -> "Update failed"
                        else -> "Update"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                if (state is AppUpdate.Available) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        state.tag,
                        style = MaterialTheme.typography.labelLarge,
                        color = Gray40,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (state is AppUpdate.Available) {
                    val profile = state.authorLogin.ifBlank {
                        state.authorName.ifBlank { "Yoriva" }
                    }
                    if (state.authorLogin.isNotBlank() ||
                        state.authorName.isNotBlank() ||
                        state.updatedAtMillis > 0L
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .clickable(enabled = state.authorLogin.isNotBlank()) {
                                    if (state.authorLogin.isNotBlank()) {
                                        uriHandler.openUri("https://github.com/${state.authorLogin}")
                                    }
                                }
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GithubAvatar(username = profile)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = state.authorName.ifBlank {
                                        state.authorLogin.ifBlank { "Yoriva" }
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                val meta = listOfNotNull(
                                    state.authorLogin.takeIf { it.isNotBlank() }?.let { "@$it" },
                                    formatUpdateDate(state.updatedAtMillis)
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
                            if (state.authorLogin.isNotBlank()) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    if (state.commitMessage.isNotBlank()) {
                        Text(
                            "Latest commit",
                            style = MaterialTheme.typography.titleSmall,
                            color = Gray30,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            state.commitMessage.trim().take(1200),
                            style = MaterialTheme.typography.bodySmall,
                            color = Gray40,
                        )
                        if (state.commitSha.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                state.commitSha.take(7),
                                style = MaterialTheme.typography.labelSmall,
                                color = Gray40,
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    if (state.releaseNotes.isNotBlank()) {
                        HorizontalDivider(color = Gray60)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Release notes",
                            style = MaterialTheme.typography.titleSmall,
                            color = Gray30,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            state.releaseNotes.trim().take(1600),
                            style = MaterialTheme.typography.bodySmall,
                            color = Gray40,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    if (state.notes.isNotBlank()) {
                        Text(
                            state.notes.trim(),
                            style = MaterialTheme.typography.bodySmall,
                            color = Gray40,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text(
                        if (state.size > 0) "Size: ${formatBytes(state.size)}"
                        else "Size: varies with build",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                    )
                }
                if (state is AppUpdate.Downloading) {
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
                if (state is AppUpdate.UpToDate) {
                    Text(
                        "You have the latest version (${state.tag}).",
                        style = MaterialTheme.typography.bodySmall,
                        color = Gray40,
                    )
                }
                if (state is AppUpdate.Failed) {
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
                is AppUpdate.Available -> {
                    TextButton(onClick = { vm.downloadAppUpdate() }) {
                        Text("Download")
                    }
                }
                is AppUpdate.Failed -> {
                    TextButton(onClick = { vm.checkAppUpdate() }) {
                        Text("Retry")
                    }
                }
                else -> {}
            }
        },
        dismissButton = {
            if (state !is AppUpdate.Downloading) {
                TextButton(onClick = { vm.dismissUpdate() }) {
                    Text("Later")
                }
            }
        },
    )
}
