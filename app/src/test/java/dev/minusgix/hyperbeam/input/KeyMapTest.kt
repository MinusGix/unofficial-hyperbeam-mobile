package dev.minusgix.hyperbeam.input

import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyMapTest {
    @Test
    fun namedKeysUseDomNames() {
        assertEquals("Enter", KeyMap.domKey(KeyEvent.KEYCODE_ENTER, 0))
        assertEquals("Backspace", KeyMap.domKey(KeyEvent.KEYCODE_DEL, 0))
        assertEquals("Delete", KeyMap.domKey(KeyEvent.KEYCODE_FORWARD_DEL, 0))
        assertEquals("ArrowLeft", KeyMap.domKey(KeyEvent.KEYCODE_DPAD_LEFT, 0))
        assertEquals("F5", KeyMap.domKey(KeyEvent.KEYCODE_F5, 0))
        assertEquals(" ", KeyMap.domKey(KeyEvent.KEYCODE_SPACE, ' '.code))
    }

    @Test
    fun printableKeysUseTheirCharacter() {
        assertEquals("a", KeyMap.domKey(KeyEvent.KEYCODE_A, 'a'.code))
        assertEquals("A", KeyMap.domKey(KeyEvent.KEYCODE_A, 'A'.code))
        assertEquals("é", KeyMap.domKey(KeyEvent.KEYCODE_E, 'é'.code))
    }

    @Test
    fun keysWithNoBrowserMeaningAreDropped() {
        assertNull(KeyMap.domKey(KeyEvent.KEYCODE_VOLUME_UP, 0))
        // Dead keys (combining accents) wait for the next key instead.
        assertNull(KeyMap.domKey(KeyEvent.KEYCODE_GRAVE, 0x80000000.toInt() or 0x300))
    }
}
