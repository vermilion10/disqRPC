package com.github.vermilion10.disqrpc.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "Dashboard", Icons.Default.Dashboard)
    object CustomStatus : Screen("custom_status", "Status", Icons.Default.Edit)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    object Logs : Screen("logs", "Logs", Icons.Default.List)
}
