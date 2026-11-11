package dev.minusgix.hyperbeam

import dev.minusgix.hyperbeam.room.RoomSession
import kotlin.test.Test
import kotlin.test.assertEquals

class UserAgentTest {
    @Test
    fun webViewMarkersAreRemoved() {
        val wv = "Mozilla/5.0 (Linux; Android 14; Pixel 8; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/130.0.6723.58 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/130.0.6723.58 Mobile Safari/537.36",
            RoomSession.browserUserAgent(wv),
        )
    }
}
