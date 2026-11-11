package dev.minusgix.hyperbeam.input

import android.view.KeyEvent

/** Modifier state to send alongside a key. */
data class Mods(
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
) {
    val any: Boolean get() = ctrl || alt || shift || meta

    companion object {
        val NONE = Mods()

        fun fromMetaState(meta: Int) = Mods(
            ctrl = meta and KeyEvent.META_CTRL_ON != 0,
            alt = meta and KeyEvent.META_ALT_ON != 0,
            shift = meta and KeyEvent.META_SHIFT_ON != 0,
            meta = meta and KeyEvent.META_META_ON != 0,
        )
    }
}

/** Maps Android key codes to DOM `KeyboardEvent.key` values. */
object KeyMap {
    private val named: Map<Int, String> = buildMap {
        put(KeyEvent.KEYCODE_ENTER, "Enter")
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, "Enter")
        put(KeyEvent.KEYCODE_DEL, "Backspace")
        put(KeyEvent.KEYCODE_FORWARD_DEL, "Delete")
        put(KeyEvent.KEYCODE_TAB, "Tab")
        put(KeyEvent.KEYCODE_ESCAPE, "Escape")
        put(KeyEvent.KEYCODE_SPACE, " ")
        put(KeyEvent.KEYCODE_DPAD_LEFT, "ArrowLeft")
        put(KeyEvent.KEYCODE_DPAD_RIGHT, "ArrowRight")
        put(KeyEvent.KEYCODE_DPAD_UP, "ArrowUp")
        put(KeyEvent.KEYCODE_DPAD_DOWN, "ArrowDown")
        put(KeyEvent.KEYCODE_MOVE_HOME, "Home")
        put(KeyEvent.KEYCODE_MOVE_END, "End")
        put(KeyEvent.KEYCODE_PAGE_UP, "PageUp")
        put(KeyEvent.KEYCODE_PAGE_DOWN, "PageDown")
        put(KeyEvent.KEYCODE_INSERT, "Insert")
        put(KeyEvent.KEYCODE_SHIFT_LEFT, "Shift")
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, "Shift")
        put(KeyEvent.KEYCODE_CTRL_LEFT, "Control")
        put(KeyEvent.KEYCODE_CTRL_RIGHT, "Control")
        put(KeyEvent.KEYCODE_ALT_LEFT, "Alt")
        put(KeyEvent.KEYCODE_ALT_RIGHT, "Alt")
        put(KeyEvent.KEYCODE_META_LEFT, "Meta")
        put(KeyEvent.KEYCODE_META_RIGHT, "Meta")
        put(KeyEvent.KEYCODE_CAPS_LOCK, "CapsLock")
        put(KeyEvent.KEYCODE_MENU, "ContextMenu")
        val f = listOf(
            KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_F4,
            KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F6, KeyEvent.KEYCODE_F7, KeyEvent.KEYCODE_F8,
            KeyEvent.KEYCODE_F9, KeyEvent.KEYCODE_F10, KeyEvent.KEYCODE_F11, KeyEvent.KEYCODE_F12,
        )
        f.forEachIndexed { i, code -> put(code, "F${i + 1}") }
    }

    /** True for keys that only change modifier state. */
    fun isModifier(keyCode: Int): Boolean = KeyEvent.isModifierKey(keyCode)

    /**
     * DOM key for a key code, given the character the key would produce with
     * only Shift/Caps applied ([unicodeChar], 0 if none). Returns null for keys
     * the remote browser has no use for (volume, media, back...).
     */
    fun domKey(keyCode: Int, unicodeChar: Int): String? {
        named[keyCode]?.let { return it }
        if (unicodeChar != 0 && unicodeChar and KeyCharacterMapCombining == 0) {
            return String(Character.toChars(unicodeChar))
        }
        return null
    }

    fun domKey(event: KeyEvent): String? {
        // Ctrl/Alt change what getUnicodeChar() returns; browsers report the
        // plain (shifted) character in `key` for shortcuts like Ctrl+Shift+A.
        val shiftOnly = event.metaState and (KeyEvent.META_SHIFT_MASK or KeyEvent.META_CAPS_LOCK_ON)
        return domKey(event.keyCode, event.getUnicodeChar(shiftOnly))
    }

    // KeyCharacterMap.COMBINING_ACCENT; inlined to keep this testable.
    private const val KeyCharacterMapCombining = 0x80000000.toInt()
}
