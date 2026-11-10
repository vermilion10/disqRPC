package com.github.vermilion10.disqrpc.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresencePayloadBuilderTest {

    private fun activity(payload: JSONObject) = payload.getJSONArray("activities").getJSONObject(0)

    @Test
    fun buildsFullActivity() {
        val payload = PresencePayloadBuilder.build(
            ActivitySpec(
                name = "Test",
                appId = "123",
                type = ActivitySpec.TYPE_LISTENING,
                statusDisplay = ActivitySpec.DISPLAY_STATE,
                details = "d",
                detailsUrl = "https://example.com/d",
                state = "s",
                largeImage = "mp:external/abc",
                largeText = "hover",
                largeUrl = "https://example.com/l",
                smallImage = "https://example.com/s.png",
                startTime = 1000,
                endTime = 2000,
                partySize = 3,
                partyMax = 5,
                buttons = listOf(PresenceButton("Open", "https://example.com")),
                accountStatus = "dnd"
            )
        )
        assertEquals("dnd", payload.getString("status"))
        val a = activity(payload)
        assertEquals("Test", a.getString("name"))
        assertEquals(2, a.getInt("type"))
        assertEquals(1, a.getInt("status_display_type"))
        assertEquals("123", a.getString("application_id"))
        assertEquals("https://example.com/d", a.getString("details_url"))
        val assets = a.getJSONObject("assets")
        assertEquals("mp:external/abc", assets.getString("large_image"))
        assertEquals("hover", assets.getString("large_text"))
        assertEquals("https://example.com/l", assets.getString("large_url"))
        val ts = a.getJSONObject("timestamps")
        assertEquals(1000, ts.getLong("start"))
        assertEquals(2000, ts.getLong("end"))
        assertEquals(listOf(3, 5), a.getJSONObject("party").getJSONArray("size").let { listOf(it.getInt(0), it.getInt(1)) })
        assertEquals("Open", a.getJSONArray("buttons").getString(0))
        assertEquals("https://example.com", a.getJSONObject("metadata").getJSONArray("button_urls").getString(0))
    }

    @Test
    fun dropsBlankAndLocalAndInvalidValues() {
        val a = activity(
            PresencePayloadBuilder.build(
                ActivitySpec(
                    name = "Test",
                    details = "   ",
                    detailsUrl = "not-a-url",
                    largeImage = "content://photo/1",
                    buttons = listOf(PresenceButton("NoUrl", "ftp://x"), PresenceButton("", "https://y"))
                )
            )
        )
        assertFalse(a.has("details"))
        assertFalse(a.has("details_url"))
        assertFalse(a.has("assets"))
        assertFalse(a.has("buttons"))
    }

    @Test
    fun partyMaxNeverBelowSize() {
        val a = activity(PresencePayloadBuilder.build(ActivitySpec(name = "x", partySize = 8, partyMax = 2)))
        val size = a.getJSONObject("party").getJSONArray("size")
        assertEquals(8, size.getInt(0))
        assertTrue(size.getInt(1) >= 8)
    }

    @Test
    fun clearHasNoActivities() {
        val payload = PresencePayloadBuilder.clear("idle")
        assertEquals("idle", payload.getString("status"))
        assertEquals(0, payload.getJSONArray("activities").length())
    }
}
