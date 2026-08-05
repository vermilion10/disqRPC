package com.github.vermilion10.disqrpc.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.json.JSONObject
import com.github.vermilion10.disqrpc.data.local.GameConfig
import com.github.vermilion10.disqrpc.ui.MainViewModel
import com.github.vermilion10.disqrpc.ui.StatusDropdown

@Composable
fun DashboardScreen(viewModel: MainViewModel) {
    val games by viewModel.allConfigs.collectAsState()
    val isScraping by viewModel.isScraping
    val currentPresence by viewModel.currentPresence.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingGame by remember { mutableStateOf<GameConfig?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Game")
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            Column {
                if (isScraping) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (currentPresence != null) {
                        item {
                            Text(
                                text = "Active Presence",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            PresencePreview(currentPresence)
                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }

                    item {
                        Text(
                            text = "Whitelisted Games",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    if (games.isEmpty() && !isScraping) {
                        item {
                            Box(
                                modifier = Modifier.fillParentMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No games added yet.\nTap + to add a game by package name.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    items(games) { game ->
                        GameCard(
                            game = game,
                            onToggle = { viewModel.toggleGameEnabled(game) },
                            onEdit = { editingGame = game },
                            onDelete = { viewModel.deleteGame(game) }
                        )
                    }
                }
            }

            if (showAddDialog) {
                AddGameDialog(
                    viewModel = viewModel,
                    onDismiss = { showAddDialog = false },
                    onConfirm = { packageName ->
                        viewModel.addGameByPackageName(packageName)
                        showAddDialog = false
                    }
                )
            }

            editingGame?.let { game ->
                EditGameDialog(
                    game = game,
                    onDismiss = { editingGame = null },
                    onSave = { updated ->
                        viewModel.updateGameConfig(updated)
                        editingGame = null
                    }
                )
            }
        }
    }
}

@Composable
fun EditGameDialog(
    game: GameConfig,
    onDismiss: () -> Unit,
    onSave: (GameConfig) -> Unit
) {
    var gameName by remember(game) { mutableStateOf(game.gameName) }
    var details by remember(game) { mutableStateOf(game.customDetails ?: "") }
    var state by remember(game) { mutableStateOf(game.customState ?: "") }
    var status by remember(game) { mutableStateOf(game.status) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${game.gameName}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = gameName,
                    onValueChange = { gameName = it },
                    label = { Text("Game Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it },
                    label = { Text("Details") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state,
                    onValueChange = { state = it },
                    label = { Text("State") },
                    modifier = Modifier.fillMaxWidth()
                )
                StatusDropdown(
                    selected = status,
                    onSelect = { status = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    game.copy(
                        gameName = gameName.ifBlank { game.gameName },
                        customDetails = details.ifBlank { null },
                        customState = state.ifBlank { null },
                        status = status
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun GameCard(game: GameConfig, onToggle: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = game.gameName, style = MaterialTheme.typography.titleMedium)
                Text(text = game.packageName, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
            Switch(
                checked = game.isEnabled,
                onCheckedChange = { onToggle() }
            )
        }
    }
}

@Composable
fun AddGameDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var packageName by remember { mutableStateOf("") }
    var useAppList by remember { mutableStateOf(false) }
    val installedApps by viewModel.installedApps

    LaunchedEffect(useAppList) {
        if (useAppList && installedApps.isEmpty()) {
            viewModel.loadInstalledApps()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Game") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    FilterChip(
                        selected = !useAppList,
                        onClick = { useAppList = false },
                        label = { Text("Manual ID") }
                    )
                    FilterChip(
                        selected = useAppList,
                        onClick = { useAppList = true },
                        label = { Text("Select App") }
                    )
                }

                if (useAppList) {
                    Box(modifier = Modifier.heightIn(max = 300.dp)) {
                        if (installedApps.isEmpty()) {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        } else {
                            LazyColumn {
                                items(installedApps) { app ->
                                    ListItem(
                                        headlineContent = { Text(app.name) },
                                        supportingContent = { Text(app.packageName) },
                                        modifier = Modifier.clickable {
                                            packageName = app.packageName
                                            onConfirm(packageName)
                                        }
                                    )
                                }
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = packageName,
                        onValueChange = { packageName = it },
                        label = { Text("Package Name") },
                        placeholder = { Text("e.g., com.mojang.minecraftpe") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            if (!useAppList) {
                Button(onClick = { onConfirm(packageName) }, enabled = packageName.isNotBlank()) {
                    Text("Add")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun PresencePreview(payloadJson: String?) {
    if (payloadJson == null) return

    val activity = try {
        val root = JSONObject(payloadJson)
        val activities = root.getJSONArray("activities")
        if (activities.length() > 0) activities.getJSONObject(0) else null
    } catch (e: Exception) {
        null
    } ?: return

    val name = activity.optString("name", "")
    val details = activity.optString("details", "")
    val state = activity.optString("state", "")
    val assets = activity.optJSONObject("assets")
    val largeImage = assets?.optString("large_image", "") ?: ""
    val smallImage = assets?.optString("small_image", "") ?: ""

    fun resolveImageUrl(key: String): String {
        return if (key.startsWith("mp:external/https/")) {
            "https://" + key.substring("mp:external/https/".length)
        } else key
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1F22))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(64.dp)) {
                if (largeImage.isNotBlank()) {
                    AsyncImage(
                        model = resolveImageUrl(largeImage),
                        contentDescription = null,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(Color.DarkGray, RoundedCornerShape(8.dp))
                    )
                }

                if (smallImage.isNotBlank()) {
                    AsyncImage(
                        model = resolveImageUrl(smallImage),
                        contentDescription = null,
                        modifier = Modifier
                            .size(24.dp)
                            .align(Alignment.BottomEnd)
                            .clip(CircleShape)
                            .background(Color(0xFF1E1F22))
                            .padding(2.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column {
                Text(text = name, color = Color.White, style = MaterialTheme.typography.titleSmall)
                if (details.isNotBlank()) Text(text = details, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                if (state.isNotBlank()) Text(text = state, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
