package com.github.vermilion10.disqrpc.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.github.vermilion10.disqrpc.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var token by remember { mutableStateOf(viewModel.tokenManager.getToken() ?: "") }
    var appId by remember { mutableStateOf(viewModel.tokenManager.getApplicationId() ?: "") }
    var showToken by remember { mutableStateOf(false) }

    var isBatteryIgnored by remember { mutableStateOf(viewModel.isBatteryOptimizationIgnored()) }
    var isUsageGranted by remember { mutableStateOf(viewModel.isUsageAccessGranted()) }

    // Update status when returning to app
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isBatteryIgnored = viewModel.isBatteryOptimizationIgnored()
                isUsageGranted = viewModel.isUsageAccessGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Connection Settings",
            style = MaterialTheme.typography.headlineSmall
        )

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Discord Token") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                    Icon(
                        imageVector = if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "Toggle Visibility"
                    )
                }
            },
            supportingText = {
                Text("Your User Token. Never share this with anyone.")
            }
        )

        OutlinedTextField(
            value = appId,
            onValueChange = { appId = it },
            label = { Text("Master Application ID") },
            modifier = Modifier.fillMaxWidth(),
            supportingText = {
                Text("The Client ID of your Discord Application.")
            }
        )

        Button(
            onClick = {
                viewModel.saveCredentials(token, appId)
                Toast.makeText(context, "Credentials Saved", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Credentials")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { 
                    viewModel.connect()
                    Toast.makeText(context, "Connecting...", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Connect")
            }
            Button(
                onClick = { 
                    viewModel.disconnect()
                    Toast.makeText(context, "Disconnected", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Disconnect")
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "Permissions",
            style = MaterialTheme.typography.titleMedium
        )

        OutlinedButton(
            onClick = {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Grant Notification Access")
        }

        OutlinedButton(
            onClick = {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isBatteryIgnored
        ) {
            Text(if (isBatteryIgnored) "Battery Optimization Disabled" else "Disable Battery Optimization")
        }

        OutlinedButton(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isUsageGranted
        ) {
            Text(if (isUsageGranted) "Usage Access Granted" else "Grant Usage Access")
        }

        Text(
            text = "Developer Options",
            style = MaterialTheme.typography.titleMedium
        )
        
        OutlinedButton(
            onClick = { /* TODO: Navigate to logs */ },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("View Network Logs")
        }
    }
}
