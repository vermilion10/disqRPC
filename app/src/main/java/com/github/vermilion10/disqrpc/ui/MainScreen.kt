package com.github.vermilion10.disqrpc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.vermilion10.disqrpc.ui.screens.DashboardScreen
import com.github.vermilion10.disqrpc.ui.screens.SettingsScreen
import com.github.vermilion10.disqrpc.ui.screens.CustomStatusScreen
import com.github.vermilion10.disqrpc.ui.screens.LogConsoleScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val navController = rememberNavController()
    val connectionState by viewModel.connectionState.collectAsState()
    val username by viewModel.username.collectAsState()
    val screens = listOf(
        Screen.Dashboard,
        Screen.CustomStatus,
        Screen.Logs,
        Screen.Settings
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("disqRPC") },
                actions = {
                    ConnectionStatusIndicator(connectionState, username)
                }
            )
        },
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                screens.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = null) },
                        label = { Text(screen.title) },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Dashboard.route) { DashboardScreen(viewModel) }
            composable(Screen.CustomStatus.route) { CustomStatusScreen(viewModel) }
            composable(Screen.Logs.route) { LogConsoleScreen(viewModel) }
            composable(Screen.Settings.route) { SettingsScreen(viewModel) }
        }
    }
}

@Composable
fun ConnectionStatusIndicator(
    state: com.github.vermilion10.disqrpc.util.ConnectionManager.State,
    username: String?
) {
    val color = when (state) {
        com.github.vermilion10.disqrpc.util.ConnectionManager.State.CONNECTED -> Color.Green
        com.github.vermilion10.disqrpc.util.ConnectionManager.State.CONNECTING -> Color.Yellow
        com.github.vermilion10.disqrpc.util.ConnectionManager.State.FAILED -> Color.Red
        com.github.vermilion10.disqrpc.util.ConnectionManager.State.DISCONNECTED -> Color.Gray
    }
    
    val text = when (state) {
        com.github.vermilion10.disqrpc.util.ConnectionManager.State.CONNECTED -> {
            if (username != null) "Connected as $username" else "Connected"
        }
        com.github.vermilion10.disqrpc.util.ConnectionManager.State.CONNECTING -> "Connecting..."
        com.github.vermilion10.disqrpc.util.ConnectionManager.State.FAILED -> "Failed"
        com.github.vermilion10.disqrpc.util.ConnectionManager.State.DISCONNECTED -> "Disconnected"
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = CircleShape,
        modifier = Modifier.padding(end = 16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
