package com.github.vermilion10.disqrpc.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApplicationIdTest {

    @Test
    fun readsIdsAndGamePageLinks() {
        val id = "1442716949728727182"
        assertEquals(id, DetectableCatalog.applicationIdIn(id))
        assertEquals(id, DetectableCatalog.applicationIdIn("https://discord.com/games/$id"))
        assertEquals(id, DetectableCatalog.applicationIdIn("discord.com/application-directory/$id/store"))
    }

    @Test
    fun namesAreNotIds() {
        assertNull(DetectableCatalog.applicationIdIn("Gakuen Idolmaster"))
        assertNull(DetectableCatalog.applicationIdIn("2048"))
    }
}
