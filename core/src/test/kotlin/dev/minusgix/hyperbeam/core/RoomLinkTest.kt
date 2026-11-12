package dev.minusgix.hyperbeam.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoomLinkTest {
    @Test
    fun bareNamesUseTheDefaultServer() {
        assertEquals("https://canary.hyperbeam.com/r/movie-night", RoomLink.parse("movie-night")!!.url)
        assertEquals("https://hyperbeam.com/r/movie-night", RoomLink.parse(" movie-night ", RoomLink.MAIN)!!.url)
        assertEquals(RoomLink(RoomLink.CANARY, "abc"), RoomLink.parse("/r/abc"))
        assertEquals(RoomLink(RoomLink.CANARY, "abc"), RoomLink.parse("r/abc"))
    }

    @Test
    fun linksKeepTheirOwnServer() {
        assertEquals(RoomLink("hyperbeam.com", "abc"), RoomLink.parse("https://hyperbeam.com/r/abc"))
        assertEquals(RoomLink("canary.hyperbeam.com", "abc"), RoomLink.parse("canary.hyperbeam.com/r/abc?x=1#y"))
        assertEquals(RoomLink("canary.hyperbeam.com", "abc"), RoomLink.parse("https://Canary.Hyperbeam.com/r/abc/"))
    }

    @Test
    fun namesRoundTripThroughEncoding() {
        val link = RoomLink(RoomLink.CANARY, "café+club")
        assertEquals("https://canary.hyperbeam.com/r/caf%C3%A9%2Bclub", link.url)
        assertEquals(link, RoomLink.fromUrl(link.url))
    }

    @Test
    fun nonRoomsAreRejected() {
        assertNull(RoomLink.parse(""))
        assertNull(RoomLink.parse("two words"))
        assertNull(RoomLink.parse("https://example.com/r/abc"))
        assertNull(RoomLink.parse("http://hyperbeam.com/r/abc"))
        assertNull(RoomLink.fromUrl("https://hyperbeam.com/app/"))
        assertNull(RoomLink.fromUrl("https://evilhyperbeam.com/r/abc"))
    }
}
