package com.github.vermilion10.disqrpc.presence

import com.github.vermilion10.disqrpc.util.PresenceButton
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomStatusFormTest {

    @Test
    fun resolveTimestampsPerMode() {
        val form = CustomStatusForm(customStart = 10, customEnd = 20)
        assertEquals(null to null, form.copy(timestampMode = TimestampMode.NONE).resolveTimestamps(100, 50))
        assertEquals(100L to null, form.copy(timestampMode = TimestampMode.NOW).resolveTimestamps(100, 50))
        assertEquals(50L to null, form.copy(timestampMode = TimestampMode.LOCAL_TIME).resolveTimestamps(100, 50))
        assertEquals(10L to 20L, form.copy(timestampMode = TimestampMode.CUSTOM).resolveTimestamps(100, 50))
    }

    @Test
    fun buttonsKeepOnlyCompleteOnes() {
        val form = CustomStatusForm(
            button1Label = "A", button1Url = "https://a",
            button2Label = "", button2Url = "https://b"
        )
        assertEquals(listOf(PresenceButton("A", "https://a")), form.buttons)
    }
}
