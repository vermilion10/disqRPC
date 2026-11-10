package com.github.vermilion10.disqrpc.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import com.github.vermilion10.disqrpc.data.catalog.DetectableGame
import com.github.vermilion10.disqrpc.ui.components.ArtworkImage
import kotlinx.coroutines.delay
import com.github.vermilion10.disqrpc.ui.MainViewModel
import com.github.vermilion10.disqrpc.ui.StatusDropdown
import com.github.vermilion10.disqrpc.ui.components.PresenceCard
import com.github.vermilion10.disqrpc.ui.components.PresenceInfo
import com.github.vermilion10.disqrpc.util.AssetResolver

@Composable
fun CustomStatusScreen(viewModel: MainViewModel, showMessage: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var details by rememberSaveable { mutableStateOf("") }
    var state by rememberSaveable { mutableStateOf("") }
    var largeImage by rememberSaveable { mutableStateOf("") }
    var smallImage by rememberSaveable { mutableStateOf("") }
    var showElapsed by rememberSaveable { mutableStateOf(true) }
    var status by rememberSaveable { mutableStateOf("online") }
    val previewStart = remember { System.currentTimeMillis() }
    var official by rememberSaveable(stateSaver = OfficialGameSaver) { mutableStateOf<DetectableGame?>(null) }
    var showPicker by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    "A custom status replaces game presence and stays until you clear it. " +
                        "Clearing it brings back the game you're playing, if any.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Text("Preview", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        PresenceCard(
            PresenceInfo(
                name = official?.name ?: name.ifBlank { "Custom status" },
                details = details.ifBlank { null },
                state = state.ifBlank { null },
                largeImage = PresenceInfo.imageUrl(largeImage) ?: official?.iconUrl,
                smallImage = PresenceInfo.imageUrl(smallImage),
                startedAt = if (showElapsed) previewStart else null
            )
        )

        OfficialGameField(
            selected = official,
            onChoose = { showPicker = true },
            onClear = { official = null }
        )

        if (official == null) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
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

        ImageField(
            label = "Large image",
            value = largeImage,
            onValueChange = { largeImage = it },
            uploadsAllowed = viewModel.allowImageUploads
        )
        ImageField(
            label = "Small image",
            value = smallImage,
            onValueChange = { smallImage = it },
            uploadsAllowed = viewModel.allowImageUploads
        )

        ListItem(
            headlineContent = { Text("Show elapsed time") },
            trailingContent = { Switch(checked = showElapsed, onCheckedChange = { showElapsed = it }) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
        )

        StatusDropdown(selected = status, onSelect = { status = it }, modifier = Modifier.fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    viewModel.clearStatus()
                    showMessage("Custom status cleared")
                },
                modifier = Modifier.weight(1f)
            ) { Text("Clear") }
            Button(
                onClick = {
                    val start = if (showElapsed) System.currentTimeMillis() else null
                    viewModel.updateCustomStatus(name, details, state, largeImage, smallImage, start, status, official)
                    showMessage("Custom status set")
                },
                modifier = Modifier.weight(1f)
            ) { Text("Set status") }
        }
    }

    if (showPicker) {
        OfficialGamePicker(
            search = viewModel::searchOfficialGames,
            onPick = {
                official = it
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}

/** Keeps the chosen game across tab switches and configuration changes. */
private val OfficialGameSaver = Saver<DetectableGame?, List<String?>>(
    save = { game -> game?.let { listOf(it.applicationId, it.name, it.iconHash) } ?: emptyList() },
    restore = { saved -> if (saved.size == 3) DetectableGame(saved[0]!!, saved[1]!!, saved[2]) else null }
)

/** Shows the chosen official game, or a prompt to choose one. */
@Composable
private fun OfficialGameField(selected: DetectableGame?, onChoose: () -> Unit, onClear: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            leadingContent = {
                ArtworkImage(
                    model = selected?.iconUrl,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(MaterialTheme.shapes.medium)
                )
            },
            headlineContent = { Text(selected?.name ?: "Official game") },
            supportingContent = {
                Text(
                    if (selected != null) "Shown under the game's official Discord app"
                    else "Optional. Use the name and icon of any game Discord lists."
                )
            },
            trailingContent = {
                if (selected != null) {
                    IconButton(onClick = onClear) { Icon(Icons.Default.Clear, contentDescription = "Remove game") }
                } else {
                    FilledTonalButton(onClick = onChoose) { Text("Choose") }
                }
            },
            modifier = Modifier.clickable(onClick = onChoose)
        )
    }
}

/** Searches Discord's detectable list (about 25,000 games) by name. */
@Composable
private fun OfficialGamePicker(
    search: suspend (String) -> List<DetectableGame>,
    onPick: (DetectableGame) -> Unit,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<DetectableGame>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(250) // let typing settle before querying
        searching = true
        results = search(query)
        searching = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a game") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search games") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Box(
                    modifier = Modifier
                        .heightIn(min = 160.dp, max = 400.dp)
                        .fillMaxWidth()
                ) {
                    when {
                        searching && results.isEmpty() ->
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        query.isBlank() -> CenteredHint("Type a game name, for example Minecraft or Valorant.")
                        results.isEmpty() -> CenteredHint("No games found.")
                        else -> LazyColumn {
                            items(results, key = { it.applicationId }) { game ->
                                ListItem(
                                    colors = ListItemDefaults.colors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                    ),
                                    leadingContent = {
                                        ArtworkImage(
                                            model = game.iconUrl,
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(MaterialTheme.shapes.small)
                                        )
                                    },
                                    headlineContent = { Text(game.name) },
                                    modifier = Modifier.clickable { onPick(game) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun BoxScope.CenteredHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .align(Alignment.Center)
            .padding(16.dp)
    )
}

@Composable
private fun ImageField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    uploadsAllowed: Boolean
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { onValueChange(it.toString()) }
    }
    val local = AssetResolver.isLocal(value)
    OutlinedTextField(
        value = if (local) "Picked from this phone" else value,
        onValueChange = { if (!local) onValueChange(it) },
        readOnly = local,
        label = { Text("$label (optional)") },
        placeholder = { Text("https://...") },
        singleLine = true,
        isError = local && !uploadsAllowed,
        supportingText = {
            Text(
                when {
                    local && !uploadsAllowed -> "Turn on image uploads in Settings, or paste an image link."
                    local -> "Will be uploaded to catbox.moe when you set the status."
                    else -> "Paste an image link or pick one from your phone."
                }
            )
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Remove $label")
                }
            } else {
                IconButton(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Pick $label")
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}
