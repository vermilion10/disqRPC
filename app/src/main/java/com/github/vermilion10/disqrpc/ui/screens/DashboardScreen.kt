package com.github.vermilion10.disqrpc.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.vermilion10.disqrpc.data.catalog.DetectableApp
import com.github.vermilion10.disqrpc.data.local.GameConfig
import com.github.vermilion10.disqrpc.ui.MainViewModel
import com.github.vermilion10.disqrpc.ui.StatusDropdown
import com.github.vermilion10.disqrpc.ui.components.ArtworkImage
import com.github.vermilion10.disqrpc.ui.components.PresenceCard
import com.github.vermilion10.disqrpc.ui.components.PresenceInfo
import com.github.vermilion10.disqrpc.util.ConnectionManager

@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    showMessage: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val games by viewModel.allConfigs.collectAsState()
    val matches by viewModel.catalogMatches.collectAsState()
    val isAddingGame by viewModel.isAddingGame
    val presence by viewModel.currentPresence.collectAsState()
    val state by viewModel.connectionState.collectAsState()
    val username by viewModel.username.collectAsState()
    val failure by viewModel.failureReason.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<GameConfig?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // Bottom padding keeps the last game clear of the FAB.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                ConnectionCard(
                    state = state,
                    username = username,
                    failure = failure,
                    hasToken = viewModel.hasToken(),
                    onConnect = viewModel::connect,
                    onDisconnect = viewModel::disconnect,
                    onOpenSettings = onOpenSettings
                )
            }

            item { SectionHeader("Now showing") }
            item {
                val info = PresenceInfo.fromPayload(presence)
                if (info != null) {
                    PresenceCard(info)
                } else {
                    Text(
                        text = if (state == ConnectionManager.State.CONNECTED) {
                            "Nothing right now. Open a game from your list to show it."
                        } else {
                            "Connect to show your games on Discord."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }

            item { SectionHeader("Games") }
            if (isAddingGame) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            if (games.isEmpty() && !isAddingGame) {
                item {
                    Text(
                        text = "No games yet. Open a game while connected and tap Add on the " +
                            "notification, or add one with the button below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
            items(games, key = { it.packageName }) { game ->
                GameRow(
                    game = game,
                    official = matches[game.packageName],
                    appIcon = remember(game.packageName) { viewModel.appIcon(game.packageName) },
                    onToggle = { viewModel.toggleGameEnabled(game) },
                    onClick = { editing = game }
                )
            }
        }

        ExtendedFloatingActionButton(
            onClick = { showAddDialog = true },
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("Add game") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        )
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

    editing?.let { game ->
        EditGameDialog(
            game = game,
            onDismiss = { editing = null },
            onSave = { updated ->
                viewModel.updateGameConfig(updated)
                editing = null
            },
            onDelete = {
                viewModel.deleteGame(game)
                editing = null
                showMessage("Removed ${game.gameName}")
            }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun ConnectionCard(
    state: ConnectionManager.State,
    username: String?,
    failure: String?,
    hasToken: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val failed = state == ConnectionManager.State.FAILED
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (failed) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (state) {
                ConnectionManager.State.CONNECTING -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                else -> Icon(
                    imageVector = when (state) {
                        ConnectionManager.State.CONNECTED -> Icons.Default.CloudDone
                        ConnectionManager.State.FAILED -> Icons.Default.ErrorOutline
                        else -> Icons.Default.CloudOff
                    },
                    contentDescription = null
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (state) {
                        ConnectionManager.State.CONNECTED -> "Connected"
                        ConnectionManager.State.CONNECTING -> "Connecting"
                        ConnectionManager.State.FAILED -> "Connection failed"
                        ConnectionManager.State.DISCONNECTED -> "Not connected"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                val detail = when {
                    state == ConnectionManager.State.CONNECTED && username != null -> "Signed in as $username"
                    failed && failure != null -> failure
                    !hasToken -> "Add your token in Settings first."
                    else -> null
                }
                detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            when {
                !hasToken -> FilledTonalButton(onClick = onOpenSettings) { Text("Settings") }
                state == ConnectionManager.State.CONNECTED || state == ConnectionManager.State.CONNECTING ->
                    OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                else -> Button(onClick = onConnect) {
                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Connect")
                }
            }
        }
    }
}

@Composable
private fun GameRow(
    game: GameConfig,
    official: DetectableApp?,
    appIcon: Any?,
    onToggle: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        ListItem(
            modifier = Modifier.clickable(onClick = onClick),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            leadingContent = {
                ArtworkImage(
                    model = game.customLargeImage ?: official?.iconUrl ?: appIcon,
                    modifier = Modifier
                        .size(48.dp)
                        .clipToMediumShape()
                )
            },
            headlineContent = { Text(game.gameName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                if (official != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(
                            Icons.Default.Verified,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text("Official Discord game")
                    }
                } else {
                    Text(game.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            trailingContent = {
                Switch(checked = game.isEnabled, onCheckedChange = { onToggle() })
            }
        )
    }
}

@Composable
private fun Modifier.clipToMediumShape(): Modifier =
    this.then(Modifier.clip(MaterialTheme.shapes.medium))

@Composable
fun EditGameDialog(
    game: GameConfig,
    onDismiss: () -> Unit,
    onSave: (GameConfig) -> Unit,
    onDelete: () -> Unit
) {
    var gameName by remember(game) { mutableStateOf(game.gameName) }
    var details by remember(game) { mutableStateOf(game.customDetails.orEmpty()) }
    var state by remember(game) { mutableStateOf(game.customState.orEmpty()) }
    var largeImage by remember(game) { mutableStateOf(game.customLargeImage.orEmpty()) }
    var status by remember(game) { mutableStateOf(game.status) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(game.gameName) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    game.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = gameName,
                    onValueChange = { gameName = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it },
                    label = { Text("Details (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state,
                    onValueChange = { state = it },
                    label = { Text("State (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = largeImage,
                    onValueChange = { largeImage = it },
                    label = { Text("Image URL (optional)") },
                    supportingText = { Text("Leave empty to use the game's own icon.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                StatusDropdown(selected = status, onSelect = { status = it }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    game.copy(
                        gameName = gameName.ifBlank { game.gameName },
                        customDetails = details.ifBlank { null },
                        customState = state.ifBlank { null },
                        customLargeImage = largeImage.ifBlank { null },
                        status = status
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddGameDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var packageName by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }
    val installedApps by viewModel.installedApps

    LaunchedEffect(tab) {
        if (tab == 0 && installedApps.isEmpty()) viewModel.loadInstalledApps()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add game") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Installed") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Package name") })
                }
                if (tab == 0) {
                    Box(modifier = Modifier.heightIn(min = 120.dp, max = 360.dp)) {
                        if (installedApps.isEmpty()) {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        } else {
                            LazyColumn {
                                items(installedApps, key = { it.packageName }) { app ->
                                    ListItem(
                                        headlineContent = { Text(app.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        supportingContent = { Text(app.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        leadingContent = {
                                            ArtworkImage(
                                                model = remember(app.packageName) { viewModel.appIcon(app.packageName) },
                                                modifier = Modifier.size(40.dp).clipToMediumShape()
                                            )
                                        },
                                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                                        modifier = Modifier.clickable { onConfirm(app.packageName) }
                                    )
                                }
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = packageName,
                        onValueChange = { packageName = it },
                        label = { Text("Package name") },
                        placeholder = { Text("com.mojang.minecraftpe") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            if (tab == 1) {
                Button(onClick = { onConfirm(packageName) }, enabled = packageName.isNotBlank()) { Text("Add") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
