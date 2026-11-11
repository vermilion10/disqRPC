package com.github.vermilion10.disqrpc.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AppIconFinderTest {

    @Test
    fun playArtworkIsResizedTo512() {
        assertEquals(
            "https://play-lh.googleusercontent.com/abc=s512",
            AppIconFinder.sized("https://play-lh.googleusercontent.com/abc=s0-br30")
        )
    }

    @Test
    fun otherUrlsAreKept() {
        assertEquals("https://example.com/i.png", AppIconFinder.sized("https://example.com/i.png"))
    }
}
