package com.github.vermilion10.disqrpc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import org.json.JSONObject

/** The parts of an activity the preview shows. */
data class PresenceInfo(
    val name: String,
    val details: String? = null,
    val state: String? = null,
    val largeImage: Any? = null,
    val smallImage: Any? = null,
    val startedAt: Long? = null
) {
    companion object {
        /** Reads the first activity of a gateway presence payload; null when there is none. */
        fun fromPayload(payload: String?): PresenceInfo? = runCatching {
            val activity = JSONObject(payload ?: return null)
                .optJSONArray("activities")?.optJSONObject(0) ?: return null
            val assets = activity.optJSONObject("assets")
            PresenceInfo(
                name = activity.optString("name"),
                details = activity.optString("details").ifBlank { null },
                state = activity.optString("state").ifBlank { null },
                largeImage = assets?.optString("large_image")?.let(::imageUrl),
                smallImage = assets?.optString("small_image")?.let(::imageUrl),
                startedAt = activity.optJSONObject("timestamps")?.optLong("start")?.takeIf { it > 0 }
            )
        }.getOrNull()

        /**
         * Maps an asset key back to something an image loader can fetch. Registered external
         * assets look like `mp:external/<hash>/<params>/https/host/path`.
         */
        fun imageUrl(key: String): String? {
            if (key.isBlank()) return null
            if (key.startsWith("http") || key.startsWith("content:") || key.startsWith("file:")) return key
            val marker = key.indexOf("/https/")
            return if (key.startsWith("mp:") && marker >= 0) "https://" + key.substring(marker + 7) else null
        }
    }
}

/** A Discord-style activity card drawn with Material color roles. */
@Composable
fun PresenceCard(info: PresenceInfo, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(modifier = Modifier.size(72.dp)) {
                ArtworkImage(
                    model = info.largeImage,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(MaterialTheme.shapes.medium)
                )
                if (info.smallImage != null) {
                    AsyncImage(
                        model = info.smallImage,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(28.dp)
                            .clip(CircleShape)
                            .border(3.dp, MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Playing",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = info.name.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                info.details?.let { SecondaryLine(it) }
                info.state?.let { SecondaryLine(it) }
                info.startedAt?.let { ElapsedTime(it) }
            }
        }
    }
}

/** Square artwork, or a neutral placeholder when there is none. */
@Composable
fun ArtworkImage(model: Any?, modifier: Modifier = Modifier) {
    if (model == null) {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.SportsEsports,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    } else {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.background(MaterialTheme.colorScheme.secondaryContainer)
        )
    }
}

@Composable
private fun SecondaryLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun ElapsedTime(startedAt: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val seconds = ((now - startedAt) / 1000).coerceAtLeast(0)
    val text = if (seconds >= 3600) {
        "%d:%02d:%02d elapsed".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
    } else {
        "%02d:%02d elapsed".format(seconds / 60, seconds % 60)
    }
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
}
