package com.github.vermilion10.disqrpc.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.StringReader

class DetectableParserTest {

    private fun parse(json: String) = DetectableParser.parse(StringReader(json)).apps
    private fun parseGames(json: String) = DetectableParser.parse(StringReader(json)).games

    @Test
    fun keepsOnlyGooglePlayPackagesWithOnePerPackage() {
        val apps = parse(
            """
            [
              {
                "aliases": ["Genshin Impact"],
                "content_classification": {"agency_ratings": {"esrb": {"descriptors": [1, 2], "rating": 3}}},
                "executables": [{"is_launcher": false, "name": "genshinimpact.exe", "os": "win32"}],
                "hook": true,
                "icon_hash": "0a7cc00267310bf4afdd78b175a7aeea",
                "id": "762434991303950386",
                "name": "Genshin Impact",
                "third_party_skus": [
                  {"distributor": "epic", "id": "879B0D87"},
                  {"distributor": "google_play", "id": "com.miHoYo.GenshinImpact"},
                  {"distributor": "google_play", "id": "com.hoyoverse.cloudgames.GenshinImpact"}
                ]
              },
              {
                "id": "356875221078245376",
                "name": "Overwatch",
                "icon_hash": "a60bb76b",
                "third_party_skus": [{"distributor": "steam", "id": "2357570"}]
              }
            ]
            """
        )

        assertEquals(
            listOf("com.miHoYo.GenshinImpact", "com.hoyoverse.cloudgames.GenshinImpact"),
            apps.map { it.packageName }
        )
        val genshin = apps.first()
        assertEquals("762434991303950386", genshin.applicationId)
        assertEquals("Genshin Impact", genshin.name)
        assertEquals(
            "https://cdn.discordapp.com/app-icons/762434991303950386/0a7cc00267310bf4afdd78b175a7aeea.png?size=512",
            genshin.iconUrl
        )
    }

    @Test
    fun handlesNullsAndSkipsUnusableEntries() {
        val apps = parse(
            """
            [
              {"id": "1", "name": "No Icon", "icon_hash": null,
               "third_party_skus": [{"distributor": "google_play", "id": "com.example.noicon"}]},
              {"id": "2", "name": "",
               "third_party_skus": [{"distributor": "google_play", "id": "com.example.noname"}]},
              {"id": null, "name": "No Id",
               "third_party_skus": [{"distributor": "google_play", "id": "com.example.noid"}]},
              {"id": "3", "name": "Null Skus", "third_party_skus": null},
              {"id": "4", "name": "Blank Sku",
               "third_party_skus": [{"distributor": "google_play", "id": " "}, {"distributor": null, "id": "x"}]}
            ]
            """
        )

        assertEquals(1, apps.size)
        assertEquals("com.example.noicon", apps[0].packageName)
        assertNull(apps[0].iconHash)
        assertNull(apps[0].iconUrl)
    }

    @Test
    fun firstEntryWinsWhenAPackageIsListedTwice() {
        val apps = parse(
            """
            [
              {"id": "10", "name": "Original", "third_party_skus": [{"distributor": "google_play", "id": "com.example.game"}]},
              {"id": "11", "name": "Duplicate", "third_party_skus": [{"distributor": "google_play", "id": "com.example.game"}]}
            ]
            """
        )
        assertEquals(1, apps.size)
        assertEquals("10", apps[0].applicationId)
    }

    @Test
    fun everyNamedEntryIsAGameEvenWithoutAndroidPackage() {
        val games = parseGames(
            """
            [
              {"id": "1", "name": "Desktop Only", "icon_hash": "abc", "third_party_skus": [{"distributor": "steam", "id": "1"}]},
              {"id": "2", "name": "Mobile", "third_party_skus": [{"distributor": "google_play", "id": "com.x"}]},
              {"id": "3", "name": "", "third_party_skus": []},
              {"id": "1", "name": "Duplicate Id"}
            ]
            """
        )
        assertEquals(listOf("Desktop Only", "Mobile"), games.map { it.name })
        assertEquals("https://cdn.discordapp.com/app-icons/1/abc.png?size=512", games[0].iconUrl)
    }
}
