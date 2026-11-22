package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.bridge.TunnelState
import com.example.ui.BoosterViewModel
import com.example.ui.components.TacticalGridBackdrop
import com.example.ui.screens.ConfigVaultScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.theme.CodmStealthTheme
import kotlinx.serialization.Serializable

@Serializable
data object DashboardRoute

@Serializable
data object VaultRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        setContent {
            CodmStealthTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    CodmStealthRootApp()
                }
            }
        }
    }
}

@Composable
private fun CodmStealthRootApp(
    viewModel: BoosterViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshSystemStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.statusMessage) {
        val msg = uiState.statusMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    val activeAccent = when {
        uiState.tunnelStats.state == TunnelState.ERROR -> MaterialTheme.colorScheme.error
        uiState.isTunnelConnecting -> MaterialTheme.colorScheme.secondary
        uiState.isTunnelActive -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primary
    }

    val isDashboard = backStackEntry?.destination?.hasRoute<DashboardRoute>() == true
    val isVault = backStackEntry?.destination?.hasRoute<VaultRoute>() == true

    val navigateToDashboard = {
        navController.navigate(DashboardRoute) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val navigateToVault = {
        navController.navigate(VaultRoute) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TacticalGridBackdrop(
            accentColor = activeAccent,
            isActive = uiState.isTunnelActive
        )

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useNavigationRail = maxWidth >= 700.dp

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                bottomBar = {
                    if (!useNavigationRail) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                        ) {
                            NavigationBarItem(
                                selected = isDashboard,
                                onClick = navigateToDashboard,
                                icon = {
                                    Icon(
                                        imageVector = if (isDashboard) Icons.Filled.Radar
                                        else Icons.Outlined.Radar,
                                        contentDescription = "داشبورد فرماندهی"
                                    )
                                },
                                label = { Text("فرماندهی") },
                                modifier = Modifier.testTag("nav_dashboard_tab")
                            )
                            NavigationBarItem(
                                selected = isVault,
                                onClick = navigateToVault,
                                icon = {
                                    Icon(
                                        imageVector = if (isVault) Icons.Filled.Tune
                                        else Icons.Outlined.Tune,
                                        contentDescription = "کانفیگ و DoH"
                                    )
                                },
                                label = { Text("کانفیگ و DoH") },
                                modifier = Modifier.testTag("nav_vault_tab")
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (useNavigationRail) {
                        NavigationRail(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                        ) {
                            NavigationRailItem(
                                selected = isDashboard,
                                onClick = navigateToDashboard,
                                icon = {
                                    Icon(
                                        imageVector = if (isDashboard) Icons.Filled.Radar
                                        else Icons.Outlined.Radar,
                                        contentDescription = "داشبورد فرماندهی"
                                    )
                                },
                                label = { Text("فرماندهی") },
                                modifier = Modifier.testTag("rail_dashboard_tab")
                            )
                            NavigationRailItem(
                                selected = isVault,
                                onClick = navigateToVault,
                                icon = {
                                    Icon(
                                        imageVector = if (isVault) Icons.Filled.Tune
                                        else Icons.Outlined.Tune,
                                        contentDescription = "کانفیگ و DoH"
                                    )
                                },
                                label = { Text("کانفیگ و DoH") },
                                modifier = Modifier.testTag("rail_vault_tab")
                            )
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = DashboardRoute,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        composable<DashboardRoute> {
                            DashboardScreen(
                                uiState = uiState,
                                onPrepareVpnIntent = { viewModel.prepareVpnIntent() },
                                onSetRoutingMode = viewModel::setRoutingMode,
                                onToggleTunnel = viewModel::startOrStopActiveTunnel,
                                onToggleSplitTunnel = viewModel::toggleCodmSplitTunnel,
                                onToggleDndAuto = viewModel::toggleDndAuto,
                                onOpenDndSettings = {
                                    runCatching {
                                        context.startActivity(viewModel.createDndSettingsIntent())
                                    }
                                },
                                onToggleShizukuPro = viewModel::toggleShizukuPro,
                                onRequestShizukuPermission = viewModel::requestShizukuPermission,
                                onSelectTargetFps = viewModel::setTargetFps,
                                onEngageBoost = viewModel::engageBoostAndLaunchCodm,
                                onDisengageBoost = viewModel::disengageBoost,
                                onOpenVaultTab = navigateToVault
                            )
                        }
                        composable<VaultRoute> {
                            ConfigVaultScreen(
                                uiState = uiState,
                                onImportConfUri = viewModel::importConfFromUri,
                                onSelectProfile = viewModel::selectProfile,
                                onEditProfile = viewModel::loadProfileIntoEditor,
                                onDeleteProfile = viewModel::deleteProfile,
                                onNewProfile = viewModel::prepareNewEditor,
                                onEditorNameChange = viewModel::updateEditorName,
                                onEditorRawChange = viewModel::updateEditorRawConfig,
                                onSaveProfile = viewModel::saveEditorProfile,
                                onOptimizeCodm = viewModel::optimizeEditorOrActiveForCodm,
                                onSelectDohUrl = viewModel::selectDohUrl,
                                onAddCustomDoh = viewModel::addCustomDoh,
                                onRemoveCustomDoh = viewModel::removeCustomDoh,
                                onRunBenchmarks = viewModel::runConcurrentBenchmarks
                            )
                        }
                    }
                }
            }
        }
    }
}
