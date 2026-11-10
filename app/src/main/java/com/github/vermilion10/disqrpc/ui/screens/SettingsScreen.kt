package com.github.vermilion10.disqrpc.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.github.vermilion10.disqrpc.ui.MainViewModel

private data class PermissionState(
    val notifications: Boolean,
    val usageAccess: Boolean,
    val batteryUnrestricted: Boolean,
    val notificationAccess: Boolean
)

@Composable
fun SettingsScreen(viewModel: MainViewModel, showMessage: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var token by remember { mutableStateOf(viewModel.tokenManager.getToken().orEmpty()) }
    var appId by remember { mutableStateOf(viewModel.tokenManager.getApplicationId().orEmpty()) }
    var showToken by remember { mutableStateOf(false) }
    var allowUploads by remember { mutableStateOf(viewModel.allowImageUploads) }

    fun readPermissions() = PermissionState(
        notifications = viewModel.areNotificationsEnabled(),
        usageAccess = viewModel.isUsageAccessGranted(),
        batteryUnrestricted = viewModel.isBatteryOptimizationIgnored(),
        notificationAccess = viewModel.isNotificationListenerEnabled()
    )
    var permissions by remember { mutableStateOf(readPermissions()) }

    // Re-check when coming back from the system settings screens.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissions = readPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionTitle("Account")

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Text(
                text = "disqRPC signs in with your Discord user token. Automating a user account " +
                    "is against Discord's Terms of Service and can get the account disabled. " +
                    "Use it at your own risk.",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("User token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                    Icon(
                        imageVector = if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showToken) "Hide token" else "Show token"
                    )
                }
            },
            supportingText = { Text("Stored encrypted on this device. Never share it.") }
        )

        OutlinedTextField(
            value = appId,
            onValueChange = { appId = it },
            label = { Text("Fallback application ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                Text(
                    "Used for apps that aren't in Discord's game list, and for custom statuses. " +
                        "Listed games use their official application automatically."
                )
            }
        )

        Button(
            onClick = {
                val problem = viewModel.saveCredentials(token, appId)
                showMessage(problem ?: "Saved. Connect from the Dashboard.")
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save") }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SectionTitle("Permissions")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionRow(
                title = "Notifications",
                description = "Connection status and the \"Add game?\" prompt.",
                granted = permissions.notifications,
                onOpen = { context.openAppNotificationSettings() }
            )
        }
        PermissionRow(
            title = "Usage access",
            description = "Required to detect which game is open.",
            granted = permissions.usageAccess,
            onOpen = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        )
        PermissionRow(
            title = "Unrestricted battery",
            description = "Keeps the connection alive in the background.",
            granted = permissions.batteryUnrestricted,
            onOpen = {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:${context.packageName}"))
                )
            }
        )
        PermissionRow(
            title = "Notification access",
            description = "Optional. Detects games from their notifications when usage access is off.",
            granted = permissions.notificationAccess,
            onOpen = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SectionTitle("Privacy")

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Upload images from this phone", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Discord can only show images that are online. When this is on, images you pick " +
                        "from your phone are uploaded to catbox.moe, a public file host, where anyone " +
                        "with the link can see them. Image links you paste are never uploaded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = allowUploads,
                onCheckedChange = {
                    allowUploads = it
                    viewModel.allowImageUploads = it
                }
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun PermissionRow(title: String, description: String, granted: Boolean, onOpen: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        leadingContent = {
            Icon(
                imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = if (granted) "Granted" else "Not granted",
                tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        },
        trailingContent = {
            if (!granted) TextButton(onClick = onOpen) { Text("Grant") }
        }
    )
}

private fun Context.openAppNotificationSettings() {
    startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    )
}
