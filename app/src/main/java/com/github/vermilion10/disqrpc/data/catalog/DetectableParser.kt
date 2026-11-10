package com.github.vermilion10.disqrpc.data.catalog

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Reader

/**
 * Streams `GET /applications/detectable` (about 13 MB, 25k entries). Every entry becomes a
 * [DetectableGame] (id, name, icon); entries that list a Google Play package in
 * `third_party_skus` also become [DetectableApp] rows. Other fields are skipped without
 * being materialised.
 */
object DetectableParser {

    data class Result(val apps: List<DetectableApp>, val games: List<DetectableGame>)

    private const val GOOGLE_PLAY = "google_play"

    fun parse(source: Reader): Result {
        val apps = LinkedHashMap<String, DetectableApp>()
        val games = LinkedHashMap<String, DetectableGame>()
        JsonReader(source).use { reader ->
            reader.beginArray()
            while (reader.hasNext()) {
                readEntry(
                    reader,
                    onGame = { games.putIfAbsent(it.applicationId, it) },
                    onApp = { apps.putIfAbsent(it.packageName, it) }
                )
            }
            reader.endArray()
        }
        return Result(apps.values.toList(), games.values.toList())
    }

    private inline fun readEntry(
        reader: JsonReader,
        onGame: (DetectableGame) -> Unit,
        onApp: (DetectableApp) -> Unit
    ) {
        var id: String? = null
        var name: String? = null
        var iconHash: String? = null
        var packages: List<String> = emptyList()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.nextStringOrNull()
                "name" -> name = reader.nextStringOrNull()
                "icon_hash" -> iconHash = reader.nextStringOrNull()
                "third_party_skus" -> packages = readPlayPackages(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val appId = id?.takeIf { it.isNotBlank() } ?: return
        val appName = name?.takeIf { it.isNotBlank() } ?: return
        val icon = iconHash?.takeIf(String::isNotBlank)
        onGame(DetectableGame(appId, appName, icon))
        packages.forEach { onApp(DetectableApp(it, appId, appName, icon)) }
    }

    private fun readPlayPackages(reader: JsonReader): List<String> {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return emptyList()
        }
        val packages = ArrayList<String>(1)
        reader.beginArray()
        while (reader.hasNext()) {
            var distributor: String? = null
            var skuId: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "distributor" -> distributor = reader.nextStringOrNull()
                    "id" -> skuId = reader.nextStringOrNull()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            val pkg = skuId?.trim()
            if (distributor == GOOGLE_PLAY && !pkg.isNullOrEmpty()) packages += pkg
        }
        reader.endArray()
        return packages
    }

    private fun JsonReader.nextStringOrNull(): String? = when (peek()) {
        JsonToken.NULL -> { nextNull(); null }
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        else -> { skipValue(); null }
    }
}
