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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Security
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
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Palette
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
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
import androidx.lifecycle.repeatOnLifecycle
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
import kotlinx.coroutines.delay
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
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                vm.checkAppUpdate(manual = false)
                delay(3 * 60_000L)
            }
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                storageGranted = hasStorageAccess(context)
                notificationsGranted = hasNotificationsAccess(context)
                vm.refreshGameInstalled()
                vm.onResumeAppUpdate()
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
            vm.installAppUpdate(u.file)
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
    val configuration = LocalConfiguration.current
    val isTablet = configuration.screenWidthDp >= 600

    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (isTablet && isMainTab) {
                NavigationRail(
                    modifier = Modifier.fillMaxHeight(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    header = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(top = 16.dp, bottom = 20.dp),
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.yoriva_logo),
                                contentDescription = "Yoriva Logo",
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Yoriva",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.2).sp,
                                ),
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    },
                ) {
                    bottomNavItems.forEach { item ->
                        val selected = pagerState.currentPage == item.index
                        NavigationRailItem(
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
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onBackground,
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            ),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showLibraries = true }) {
                        Icon(
                            imageVector = Icons.Filled.Extension,
                            contentDescription = "Libraries",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    IconButton(onClick = { showTheme = true }) {
                        Icon(
                            imageVector = Icons.Filled.Palette,
                            contentDescription = "Theme",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.graphicsLayer { rotationZ = settingsRotation },
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f),
            ) {
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
                                    if (!isTablet) {
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
                                            text = if (pagerState.currentPage == 0) "Patcher" else "AMXX Tools",
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
                                if (isMainTab && !isTablet) {
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
                        if (!isTablet && isMainTab) {
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
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .widthIn(max = if (isTablet) 880.dp else Dp.Unspecified),
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
                                if (!isTablet) {
                                    showPluginEditor = true
                                }
                            },
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = showPluginEditor && !isTablet,
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
    val currentUpdate = update
    val isMandatoryUpdate = (currentUpdate as? AppUpdate.Checking)?.mandatory == true ||
        currentUpdate is AppUpdate.Available ||
        currentUpdate is AppUpdate.Downloading ||
        currentUpdate is AppUpdate.Downloaded ||
        currentUpdate is AppUpdate.Installing ||
        (currentUpdate as? AppUpdate.Failed)?.mandatory == true
    if (isMandatoryUpdate) {
        FullScreenUpdateScreen(vm = vm, state = currentUpdate)
    } else if (
        (currentUpdate as? AppUpdate.Checking)?.manual == true ||
        currentUpdate is AppUpdate.UpToDate ||
        (currentUpdate is AppUpdate.Failed && !currentUpdate.mandatory)
    ) {
        UpdateStatusDialog(vm = vm, state = currentUpdate)
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
private fun FullScreenUpdateScreen(vm: PatcherViewModel, state: AppUpdate) {
    BackHandler(enabled = true) {}
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        text = "MANDATORY UPDATE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                Spacer(modifier = Modifier.height(18.dp))
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(38.dp),
                    )
                }
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = when (state) {
                        is AppUpdate.Checking -> "Checking Required Update…"
                        is AppUpdate.Available -> "Update Required"
                        is AppUpdate.Downloading -> "Downloading Update…"
                        is AppUpdate.Downloaded -> "Preparing Installer…"
                        is AppUpdate.Installing -> if (state.needsInstallPermission) {
                            "Permission Required"
                        } else {
                            "Install Update"
                        }
                        is AppUpdate.Failed -> "Update Failed"
                        else -> "Update Required"
                    },
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(6.dp))
                val subtitleText = when (state) {
                    is AppUpdate.Available -> state.notes.ifBlank { state.remoteVersion }
                    is AppUpdate.Downloading -> state.tag
                    is AppUpdate.Downloaded -> state.tag
                    is AppUpdate.Installing -> state.tag
                    else -> "You must update Yoriva to continue using the app."
                }
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(24.dp))

                when (state) {
                    is AppUpdate.Checking -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Fetching the latest release details from GitHub…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }

                    is AppUpdate.Available -> {
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
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable(enabled = state.authorLogin.isNotBlank()) {
                                        if (state.authorLogin.isNotBlank()) {
                                            uriHandler.openUri("https://github.com/${state.authorLogin}")
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
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        if (state.commitMessage.isNotBlank() || state.releaseNotes.isNotBlank()) {
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
                                ) {
                                    if (state.commitMessage.isNotBlank()) {
                                        Text(
                                            text = "What's new",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = state.commitMessage.trim().take(1200),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        if (state.commitSha.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = "Commit ${state.commitSha.take(7)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    if (state.releaseNotes.isNotBlank()) {
                                        if (state.commitMessage.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(12.dp))
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                            Spacer(modifier = Modifier.height(12.dp))
                                        }
                                        Text(
                                            text = "Release notes",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = state.releaseNotes.trim().take(1600),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        Text(
                            text = if (state.size > 0) {
                                "Download size: ${formatBytes(state.size)} · Updates are required before continuing."
                            } else {
                                "Updates are required before continuing."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }

                    is AppUpdate.Downloading -> {
                        val frac = if (state.total > 0) {
                            (state.downloaded.toDouble() / state.total).toFloat().coerceIn(0f, 1f)
                        } else 0f
                        val pct = (frac * 100f).toInt().coerceIn(0, 100)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = if (state.total > 0) {
                                            "${formatBytes(state.downloaded)} / ${formatBytes(state.total)}"
                                        } else {
                                            formatBytes(state.downloaded)
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = if (state.total > 0) "$pct%" else formatSpeed(state.bytesPerSec),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                if (state.total > 0) {
                                    LinearProgressIndicator(
                                        progress = { frac },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(10.dp)
                                            .clip(CircleShape),
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(10.dp)
                                            .clip(CircleShape),
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "${formatSpeed(state.bytesPerSec)} · Installer opens automatically when complete.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    is AppUpdate.Downloaded,
                    is AppUpdate.Installing,
                    -> {
                        val installState = state as? AppUpdate.Installing
                        val errorMsg = installState?.errorMessage
                        if (!errorMsg.isNullOrBlank()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                ),
                            ) {
                                Text(
                                    text = errorMsg,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Security,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp),
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = if (installState?.needsInstallPermission == true) {
                                        "Allow Yoriva to install app updates in Android settings, then return here to finish installing."
                                    } else {
                                        "Confirm the Android installation prompt to update Yoriva. The app will reopen automatically afterward."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }

                    is AppUpdate.Failed -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(18.dp),
                            ) {
                                Text(
                                    text = "Could not complete mandatory update",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }

                    else -> {}
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (state) {
                    is AppUpdate.Available -> {
                        Button(
                            onClick = { vm.downloadAppUpdate() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text(
                                text = "Update Now",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    is AppUpdate.Downloading -> {
                        FilledTonalButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Text("Downloading Update…")
                        }
                    }

                    is AppUpdate.Installing -> {
                        if (state.needsInstallPermission) {
                            Button(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                            Uri.parse("package:${context.packageName}"),
                                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        runCatching { context.startActivity(intent) }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp),
                                shape = RoundedCornerShape(18.dp),
                            ) {
                                Text(
                                    text = "Allow Install Permission",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            FilledTonalButton(
                                onClick = { vm.retryInstallUpdate() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Text("Install Update")
                            }
                        } else {
                            Button(
                                onClick = { vm.retryInstallUpdate() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp),
                                shape = RoundedCornerShape(18.dp),
                            ) {
                                Text(
                                    text = "Install Update",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(onClick = { vm.redownloadAppUpdate() }) {
                                Text("Re-download APK")
                            }
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (state.canRetryDownload) "Retry Download" else "Retry Check",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (state.canRetryDownload) {
                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(onClick = { vm.checkAppUpdate(manual = true) }) {
                                Text("Re-check Release")
                            }
                        }
                    }

                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun UpdateStatusDialog(vm: PatcherViewModel, state: AppUpdate) {
    AlertDialog(
        onDismissRequest = {
            if (state !is AppUpdate.Checking) vm.dismissUpdate()
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
            Text(
                when (state) {
                    is AppUpdate.Checking -> "Checking for updates…"
                    is AppUpdate.UpToDate -> "Up to date"
                    is AppUpdate.Failed -> "Update check failed"
                    else -> "Update"
                },
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
        },
        text = {
            when (state) {
                is AppUpdate.Checking -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Contacting GitHub releases…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is AppUpdate.UpToDate -> {
                    Text(
                        "You have the latest version (${state.tag}).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is AppUpdate.Failed -> {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {}
            }
        },
        confirmButton = {
            when (state) {
                is AppUpdate.Failed -> {
                    TextButton(onClick = { vm.checkAppUpdate(manual = true) }) {
                        Text("Retry")
                    }
                }
                is AppUpdate.UpToDate -> {
                    TextButton(onClick = { vm.dismissUpdate() }) {
                        Text("OK")
                    }
                }
                else -> {}
            }
        },
        dismissButton = {
            if (state is AppUpdate.Failed) {
                TextButton(onClick = { vm.dismissUpdate() }) {
                    Text("Close")
                }
            }
        },
    )
}
