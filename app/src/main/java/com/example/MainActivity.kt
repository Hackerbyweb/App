package com.example

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.model.TunnelState
import com.example.ui.VpnViewModel
import com.example.ui.screens.ChainsScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.InspectorScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.HopShieldTheme
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppRoute {
    @Serializable data object Tunnel : AppRoute
    @Serializable data object Chains : AppRoute
    @Serializable data object Inspector : AppRoute
    @Serializable data object Settings : AppRoute
}

private data class NavTabItem(
    val route: AppRoute,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: VpnViewModel = viewModel()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            HopShieldTheme(darkTheme = uiState.settings.stealthDarkTheme) {
                HopShieldApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun HopShieldApp(viewModel: VpnViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.feedbackMessage) {
        val msg = uiState.feedbackMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearFeedback()
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startTunnel()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        val prepareIntent = viewModel.prepareVpnIntent()
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            viewModel.startTunnel()
        }
    }

    val triggerVpnToggle: () -> Unit = {
        val currentState = uiState.telemetry.state
        if (currentState is TunnelState.Connected || currentState is TunnelState.Handshaking) {
            viewModel.stopTunnel()
        } else {
            val needsNotifPerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (needsNotifPerm) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                val prepareIntent = viewModel.prepareVpnIntent()
                if (prepareIntent != null) {
                    vpnPermissionLauncher.launch(prepareIntent)
                } else {
                    viewModel.startTunnel()
                }
            }
        }
    }

    val tabs = remember {
        listOf(
            NavTabItem(AppRoute.Tunnel, R.string.nav_tunnel, Icons.Rounded.Shield, Icons.Outlined.Shield, "tab_tunnel"),
            NavTabItem(AppRoute.Chains, R.string.nav_chains, Icons.Rounded.Route, Icons.Outlined.Route, "tab_chains"),
            NavTabItem(AppRoute.Inspector, R.string.nav_inspector, Icons.Rounded.Radar, Icons.Outlined.Radar, "tab_inspector"),
            NavTabItem(AppRoute.Settings, R.string.nav_settings, Icons.Rounded.Settings, Icons.Outlined.Settings, "tab_settings")
        )
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val navigateToTab: (AppRoute) -> Unit = { target ->
        navController.navigate(target) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!useNavRail) {
                    NavigationBar(
                        windowInsets = WindowInsets.navigationBars,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        tabs.forEach { tab ->
                            val selected = currentDestination?.hasRoute(tab.route::class) == true
                            val label = stringResource(tab.labelRes)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateToTab(tab.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        tabs.forEach { tab ->
                            val selected = currentDestination?.hasRoute(tab.route::class) == true
                            val label = stringResource(tab.labelRes)
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateToTab(tab.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = AppRoute.Tunnel
                    ) {
                        composable<AppRoute.Tunnel> {
                            DashboardScreen(
                                uiState = uiState,
                                onToggleConnect = triggerVpnToggle,
                                onSelectChain = viewModel::selectChain,
                                onRefreshEgress = viewModel::refreshEgressDiagnostics,
                                onNavigateToChains = { navigateToTab(AppRoute.Chains) }
                            )
                        }
                        composable<AppRoute.Chains> {
                            ChainsScreen(
                                uiState = uiState,
                                onSelectChain = viewModel::selectChain,
                                onCreateChain = viewModel::createHopChain,
                                onDeleteChain = viewModel::deleteChain,
                                onAddNode = viewModel::addRelayNode,
                                onImportConfig = viewModel::importRelayConfig,
                                onDeleteNode = viewModel::deleteNode,
                                onProbeNode = viewModel::probeNode,
                                onProbeAllNodes = viewModel::probeAllNodes
                            )
                        }
                        composable<AppRoute.Inspector> {
                            InspectorScreen(
                                uiState = uiState,
                                onClearLogs = viewModel::clearLogs
                            )
                        }
                        composable<AppRoute.Settings> {
                            SettingsScreen(
                                uiState = uiState,
                                onSetDns = viewModel::setDnsServers,
                                onSetMtu = viewModel::setMtu,
                                onSetSplitMode = viewModel::setSplitTunnelMode,
                                onToggleSplitApp = viewModel::toggleSplitApp,
                                onSetBlockLan = viewModel::setBlockLanTraffic,
                                onSetIpv6Protection = viewModel::setIpv6LeakProtection,
                                onSetStealthDark = viewModel::setStealthDarkTheme
                            )
                        }
                    }
                }
            }
        }
    }
}
