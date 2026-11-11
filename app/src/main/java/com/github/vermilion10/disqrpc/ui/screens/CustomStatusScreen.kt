package com.github.vermilion10.disqrpc.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.github.vermilion10.disqrpc.data.catalog.DetectableGame
import com.github.vermilion10.disqrpc.data.local.StatusPreset
import com.github.vermilion10.disqrpc.presence.CustomStatusForm
import com.github.vermilion10.disqrpc.presence.TimestampMode
import com.github.vermilion10.disqrpc.ui.MainViewModel
import com.github.vermilion10.disqrpc.ui.StatusDropdown
import com.github.vermilion10.disqrpc.ui.components.ArtworkImage
import com.github.vermilion10.disqrpc.ui.components.PresenceCard
import com.github.vermilion10.disqrpc.ui.components.PresenceInfo
import com.github.vermilion10.disqrpc.util.ActivitySpec
import com.github.vermilion10.disqrpc.util.AssetResolver
import kotlinx.coroutines.delay

private val ACTIVITY_TYPES = listOf(
    ActivitySpec.TYPE_PLAYING to "Playing",
    ActivitySpec.TYPE_LISTENING to "Listening to",
    ActivitySpec.TYPE_WATCHING to "Watching",
    ActivitySpec.TYPE_COMPETING to "Competing in"
)

private val DISPLAY_OPTIONS = listOf(
    ActivitySpec.DISPLAY_NAME to "Name",
    ActivitySpec.DISPLAY_STATE to "State",
    ActivitySpec.DISPLAY_DETAILS to "Details"
)

@Composable
fun CustomStatusScreen(viewModel: MainViewModel, showMessage: (String) -> Unit) {
    val form by viewModel.customForm
    val set: (CustomStatusForm) -> Unit = viewModel::updateForm
    val presets by viewModel.presets.collectAsState()
    var showPicker by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    val previewStart = remember { System.currentTimeMillis() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PresetBar(
            presets = presets,
            onLoad = { viewModel.loadPreset(it); showMessage("Loaded ${it.name}") },
            onDelete = { viewModel.deletePreset(it); showMessage("Deleted ${it.name}") },
            onSave = { showSave = true }
        )

        SectionLabel("Preview")
        PresenceCard(
            PresenceInfo(
                name = form.officialName ?: form.name.ifBlank { "Custom status" },
                details = form.details.ifBlank { null },
                state = form.state.ifBlank { null },
                largeImage = PresenceInfo.imageUrl(form.largeImage) ?: form.officialIconUrl,
                smallImage = PresenceInfo.imageUrl(form.smallImage),
                startedAt = if (form.timestampMode != TimestampMode.NONE) previewStart else null
            )
        )

        OfficialGameField(
            name = form.officialName,
            iconUrl = form.officialIconUrl,
            onChoose = { showPicker = true },
            onClear = { set(form.copy(officialAppId = null, officialName = null, officialIconUrl = null)) }
        )

        if (form.officialAppId == null) {
            Field("Name", form.name) { set(form.copy(name = it)) }
        }

        SectionLabel("Activity type")
        ChipRow(
            options = ACTIVITY_TYPES,
            selected = form.type,
            onSelect = { set(form.copy(type = it)) }
        )

        LabeledField("Details", form.details, form.detailsUrl,
            onText = { set(form.copy(details = it)) }, onUrl = { set(form.copy(detailsUrl = it)) })
        LabeledField("State", form.state, form.stateUrl,
            onText = { set(form.copy(state = it)) }, onUrl = { set(form.copy(stateUrl = it)) })

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Show in member list", style = MaterialTheme.typography.bodyMedium)
            ChipRow(options = DISPLAY_OPTIONS, selected = form.statusDisplay,
                onSelect = { set(form.copy(statusDisplay = it)) })
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SectionLabel("Large image")
        ImageField("Large image", form.largeImage, { set(form.copy(largeImage = it)) }, viewModel.allowImageUploads)
        Field("Hover text", form.largeText) { set(form.copy(largeText = it)) }
        Field("Image link", form.largeUrl) { set(form.copy(largeUrl = it)) }

        SectionLabel("Small image")
        ImageField("Small image", form.smallImage, { set(form.copy(smallImage = it)) }, viewModel.allowImageUploads)
        Field("Hover text", form.smallText) { set(form.copy(smallText = it)) }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SectionLabel("Timestamp")
        TimestampSection(form, set)

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SectionLabel("Party")
        PartySection(form, set)

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SectionLabel("Buttons")
        LabeledField("Button 1", form.button1Label, form.button1Url, textLabel = "Label", urlLabel = "URL",
            onText = { set(form.copy(button1Label = it)) }, onUrl = { set(form.copy(button1Url = it)) })
        LabeledField("Button 2", form.button2Label, form.button2Url, textLabel = "Label", urlLabel = "URL",
            onText = { set(form.copy(button2Label = it)) }, onUrl = { set(form.copy(button2Url = it)) })
        Text(
            "Buttons from a user account may not show for everyone. Test before relying on them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        StatusDropdown(selected = form.accountStatus, onSelect = { set(form.copy(accountStatus = it)) },
            modifier = Modifier.fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { viewModel.clearStatus(); showMessage("Custom status cleared") },
                modifier = Modifier.weight(1f)
            ) { Text("Clear") }
            Button(
                onClick = { viewModel.applyCustomStatus(form); showMessage("Custom status set") },
                modifier = Modifier.weight(1f)
            ) { Text("Set status") }
        }
    }

    if (showPicker) {
        OfficialGamePicker(
            search = viewModel::searchOfficialGames,
            onPick = {
                set(form.copy(officialAppId = it.applicationId, officialName = it.name, officialIconUrl = it.iconUrl))
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }

    if (showSave) {
        SavePresetDialog(
            suggested = form.officialName ?: form.name,
            onSave = { name ->
                viewModel.saveCurrentAsPreset(name)
                showSave = false
                showMessage("Saved preset")
            },
            onDismiss = { showSave = false }
        )
    }
}

@Composable
private fun PresetBar(
    presets: List<StatusPreset>,
    onLoad: (StatusPreset) -> Unit,
    onDelete: (StatusPreset) -> Unit,
    onSave: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Presets")
            Box(modifier = Modifier.weight(1f))
            TextButton(onClick = onSave) { Text("Save current") }
        }
        if (presets.isEmpty()) {
            Text(
                "Save the current status as a preset to reuse it with one tap.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { preset ->
                    androidx.compose.material3.InputChip(
                        selected = false,
                        onClick = { onLoad(preset) },
                        label = { Text(preset.name) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = "Delete ${preset.name}",
                                modifier = Modifier.size(18.dp).clickable { onDelete(preset) }
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SavePresetDialog(suggested: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(suggested) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save preset") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Preset name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

/** A text line plus its optional link, which turns the text into a hyperlink. */
@Composable
private fun LabeledField(
    section: String,
    text: String,
    url: String,
    textLabel: String = section,
    urlLabel: String = "Link",
    onText: (String) -> Unit,
    onUrl: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            label = { Text(textLabel) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = url,
            onValueChange = onUrl,
            label = { Text("$urlLabel (optional)") },
            placeholder = { Text("https://...") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

@Composable
private fun TimestampSection(form: CustomStatusForm, set: (CustomStatusForm) -> Unit) {
    val modes = listOf(
        TimestampMode.NONE to "None",
        TimestampMode.NOW to "Since now",
        TimestampMode.LOCAL_TIME to "Your local time",
        TimestampMode.CUSTOM to "Custom"
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        modes.forEach { (mode, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { set(form.copy(timestampMode = mode)) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                androidx.compose.material3.RadioButton(
                    selected = form.timestampMode == mode,
                    onClick = { set(form.copy(timestampMode = mode)) }
                )
                Text(label)
            }
        }
        if (form.timestampMode == TimestampMode.CUSTOM) {
            Text(
                "Custom start and end times can be set once the picker is wired up; " +
                    "for now this behaves like \"Since now\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (form.timestampMode == TimestampMode.LOCAL_TIME) {
            Text(
                "Shows your clock: the timer counts up from midnight.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PartySection(form: CustomStatusForm, set: (CustomStatusForm) -> Unit) {
    ListItem(
        headlineContent = { Text("Show party size") },
        supportingContent = { Text("For example, \"3 of 5\" next to the status.") },
        trailingContent = {
            Switch(checked = form.partyEnabled, onCheckedChange = { set(form.copy(partyEnabled = it)) })
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
    )
    if (form.partyEnabled) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("In party", form.partySize, Modifier.weight(1f)) {
                set(form.copy(partySize = it, partyMax = maxOf(form.partyMax, it)))
            }
            NumberField("Max", form.partyMax, Modifier.weight(1f)) {
                set(form.copy(partyMax = it, partySize = minOf(form.partySize, it)))
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text -> text.toIntOrNull()?.coerceIn(1, 999)?.let(onChange) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

@Composable
internal fun OfficialGameField(
    name: String?,
    iconUrl: String?,
    onChoose: () -> Unit,
    onClear: (() -> Unit)?,
    chosenHint: String = "Shown under the game's official Discord app",
    emptyHint: String = "Optional. Use the name and icon of any game Discord lists."
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            leadingContent = {
                ArtworkImage(model = iconUrl, modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.medium))
            },
            headlineContent = { Text(name ?: "Official game") },
            supportingContent = {
                Text(if (name != null) chosenHint else emptyHint)
            },
            trailingContent = {
                when {
                    name != null && onClear != null ->
                        IconButton(onClick = onClear) { Icon(Icons.Default.Clear, contentDescription = "Remove game") }
                    name != null -> TextButton(onClick = onChoose) { Text("Change") }
                    else -> FilledTonalButton(onClick = onChoose) { Text("Choose") }
                }
            },
            modifier = Modifier.clickable(onClick = onChoose)
        )
    }
}

/**
 * Searches Discord's detectable list (about 25,000 games) by name. A pasted application ID
 * finds any Discord game, including ones that aren't in the list.
 */
@Composable
internal fun OfficialGamePicker(
    search: suspend (String) -> List<DetectableGame>,
    onPick: (DetectableGame) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<DetectableGame>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(250)
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
                    placeholder = { Text("Game name, ID or game page link") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Box(modifier = Modifier.heightIn(min = 160.dp, max = 400.dp).fillMaxWidth()) {
                    when {
                        searching && results.isEmpty() ->
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        query.isBlank() -> CenteredHint("Type a game name, for example Minecraft or Valorant.")
                        results.isEmpty() -> CenteredHint(
                            "No games found. Games missing here can still be found by " +
                                "pasting their Discord game page link or application ID."
                        )
                        else -> LazyColumn {
                            items(results, key = { it.applicationId }) { game ->
                                ListItem(
                                    colors = ListItemDefaults.colors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                    ),
                                    leadingContent = {
                                        ArtworkImage(
                                            model = game.iconUrl,
                                            modifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.small)
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
internal fun BoxScope.CenteredHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.align(Alignment.Center).padding(16.dp)
    )
}

@Composable
private fun ImageField(label: String, value: String, onValueChange: (String) -> Unit, uploadsAllowed: Boolean) {
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
