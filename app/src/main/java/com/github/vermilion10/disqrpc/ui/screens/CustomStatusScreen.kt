package com.github.vermilion10.disqrpc.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import coil.compose.AsyncImage
import org.json.JSONObject
import com.github.vermilion10.disqrpc.ui.MainViewModel
import com.github.vermilion10.disqrpc.ui.StatusDropdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomStatusScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    
    var name by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("") }
    var largeImageKey by remember { mutableStateOf("") }
    var smallImageKey by remember { mutableStateOf("") }
    var startTimeEnabled by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("online") }

    val largeImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { largeImageKey = it.toString() } }

    val smallImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { smallImageKey = it.toString() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Manual Presence Override",
            style = MaterialTheme.typography.headlineSmall
        )

        // Live Preview
        Text(text = "Live Preview", style = MaterialTheme.typography.labelMedium)
        PresencePreview(
            name = if (name.isBlank()) "Custom RPC" else name,
            details = details,
            state = state,
            largeImage = largeImageKey,
            smallImage = smallImageKey
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Game Name") },
            placeholder = { Text("e.g., Custom RPC") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = details,
            onValueChange = { details = it },
            label = { Text("Details") },
            placeholder = { Text("e.g., Working on an Assignment") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = state,
            onValueChange = { state = it },
            label = { Text("State") },
            placeholder = { Text("e.g., Programming in Kotlin") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = largeImageKey,
                onValueChange = { largeImageKey = it },
                label = { Text("Large Image Key/URL") },
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { largeImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Icon(Icons.Default.Image, contentDescription = "Pick Image")
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = smallImageKey,
                onValueChange = { smallImageKey = it },
                label = { Text("Small Image Key/URL") },
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { smallImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Icon(Icons.Default.Image, contentDescription = "Pick Image")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Show Elapsed Time")
            Switch(checked = startTimeEnabled, onCheckedChange = { startTimeEnabled = it })
        }

        StatusDropdown(
            selected = status,
            onSelect = { status = it },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                val startTime = if (startTimeEnabled) System.currentTimeMillis() else null
                viewModel.updateCustomStatus(name, details, state, largeImageKey, smallImageKey, startTime, status)
                Toast.makeText(context, "Status Updated", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Set Custom Status")
        }

        OutlinedButton(
            onClick = { 
                viewModel.clearStatus()
                Toast.makeText(context, "Status Cleared", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Clear Status")
        }
    }
}

@Composable
fun PresencePreview(
    name: String,
    details: String,
    state: String,
    largeImage: String,
    smallImage: String
) {
    fun resolveImageUrl(key: String): String {
        return if (key.startsWith("mp:external/https/")) {
            "https://" + key.substring("mp:external/https/".length)
        } else key
    }

    Card(
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
