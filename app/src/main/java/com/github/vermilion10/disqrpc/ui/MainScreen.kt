package com.github.vermilion10.disqrpc.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.github.vermilion10.disqrpc.ui.screens.CustomStatusScreen
import com.github.vermilion10.disqrpc.ui.screens.DashboardScreen
import com.github.vermilion10.disqrpc.ui.screens.LogConsoleScreen
import com.github.vermilion10.disqrpc.ui.screens.LoginScreen
import com.github.vermilion10.disqrpc.ui.screens.SettingsScreen
import com.github.vermilion10.disqrpc.util.ConnectionManager
import kotlinx.coroutines.launch

private const val LOGIN_ROUTE = "login"

private val destinations = listOf(Screen.Dashboard, Screen.CustomStatus, Screen.Logs, Screen.Settings)

/**
 * App shell: a navigation bar on phones and a navigation rail on wider windows
 * (NavigationSuiteScaffold picks per window size), one top app bar and one snackbar host.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination
    val connectionState by viewModel.connectionState.collectAsState()
    val username by viewModel.username.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }

    fun navigate(screen: Screen) {
        navController.navigate(screen.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            destinations.forEach { screen ->
                item(
                    selected = current?.hierarchy?.any { it.route == screen.route } == true,
                    onClick = { navigate(screen) },
                    icon = { Icon(screen.icon, contentDescription = null) },
                    label = { Text(screen.title) }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (current?.route == LOGIN_ROUTE) "Log in to Discord"
                            else destinations.firstOrNull { it.route == current?.route }?.title ?: "disqRPC"
                        )
                    },
                    actions = { ConnectionStatusChip(connectionState, username) }
                )
            },
            snackbarHost = { SnackbarHost(snackbar) }
        ) { innerPadding ->
            // Keep content readable on wide windows instead of stretching edge to edge.
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                NavHost(
                    navController = navController,
                    startDestination = Screen.Dashboard.route,
                    modifier = Modifier.widthIn(max = 840.dp)
                ) {
                    composable(Screen.Dashboard.route) {
                        DashboardScreen(viewModel, showMessage, onOpenSettings = { navigate(Screen.Settings) })
                    }
                    composable(Screen.CustomStatus.route) { CustomStatusScreen(viewModel, showMessage) }
                    composable(Screen.Logs.route) { LogConsoleScreen(viewModel) }
                    composable(Screen.Settings.route) {
                        SettingsScreen(viewModel, showMessage, onLogIn = { navController.navigate(LOGIN_ROUTE) })
                    }
                    composable(LOGIN_ROUTE) {
                        LoginScreen(onToken = { token ->
                            val problem = viewModel.saveToken(token)
                            navController.popBackStack()
                            showMessage(problem ?: "Logged in. Connect from Home.")
                        })
                    }
                }
            }
        }
    }
}

/** Compact connection indicator for the top app bar, colored with theme roles. */
@Composable
fun ConnectionStatusChip(state: ConnectionManager.State, username: String?) {
    val (dot, label) = when (state) {
        ConnectionManager.State.CONNECTED -> MaterialTheme.colorScheme.primary to (username ?: "Connected")
        ConnectionManager.State.CONNECTING -> MaterialTheme.colorScheme.tertiary to "Connecting"
        ConnectionManager.State.FAILED -> MaterialTheme.colorScheme.error to "Failed"
        ConnectionManager.State.DISCONNECTED -> MaterialTheme.colorScheme.outline to "Offline"
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = CircleShape,
        modifier = Modifier
            .padding(end = 16.dp)
            .semantics { contentDescription = "Connection: $label" }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(color = dot, shape = CircleShape, modifier = Modifier.size(8.dp)) {}
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp)
            )
        }
    }
}
