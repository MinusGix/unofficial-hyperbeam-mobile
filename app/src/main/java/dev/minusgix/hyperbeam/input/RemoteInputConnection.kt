package dev.minusgix.hyperbeam.input

import android.text.Editable
import android.text.Selection
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import dev.minusgix.hyperbeam.core.TextSync

/**
 * An InputConnection that turns whatever the soft keyboard does into key
 * presses for the remote browser.
 *
 * The keyboard edits a local [Editable] as if it were a normal text field
 * (so autocorrect, swipe typing, voice input and caret sliding all work); after
 * every change [TextSync] works out the arrows, backspaces and characters that
 * make the remote field match.
 */
class RemoteInputConnection(
    private val view: View,
    private val sink: RemoteKeySink,
    private val sync: TextSync,
    private val editable: Editable,
    /** One-shot modifiers armed from the extra-keys row (Ctrl, Alt...). */
    private val stickyMods: () -> Mods,
    private val consumeStickyMods: () -> Unit,
    /** Ask the view to restart input after we changed the buffer under the IME. */
    private val requestRestart: () -> Unit,
) : BaseInputConnection(view, true) {

    override fun getEditable(): Editable = editable

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (text != null && sendAsShortcut(text)) return true
        val r = super.commitText(text, newCursorPosition)
        flush()
        return r
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (text != null && text.isNotEmpty() && sendAsShortcut(text)) {
            requestRestart()
            return true
        }
        val r = super.setComposingText(text, newCursorPosition)
        flush()
        return r
    }

    override fun finishComposingText(): Boolean {
        val r = super.finishComposingText()
        flush()
        return r
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        val r = super.setComposingRegion(start, end)
        flush()
        return r
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (editable.isEmpty() && beforeLength > 0) {
            // Nothing local to delete; the user wants to erase remote text.
            sink.backspace(beforeLength.coerceAtMost(64))
            return true
        }
        val r = super.deleteSurroundingText(beforeLength, afterLength)
        flush()
        return r
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        val r = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        flush()
        return r
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        val r = super.setSelection(start, end)
        flush()
        return r
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        pressEnter(Mods.NONE)
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val mods = Mods.fromMetaState(event.metaState).merge(stickyMods())
        when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                if (!mods.any && editable.isNotEmpty() && caret() > 0) {
                    // Keep the local buffer authoritative; flush() sends the Backspace.
                    val end = caret()
                    val start = previousGrapheme(editable, end)
                    editable.delete(start, end)
                    flush()
                } else {
                    sink.key("Backspace", mods)
                    consumeStickyMods()
                }
                return true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                pressEnter(mods)
                return true
            }
        }
        val key = KeyMap.domKey(event) ?: return super.sendKeyEvent(event)
        if (!mods.ctrl && !mods.alt && !mods.meta && key.length <= 2 && key != " " && key.codePointCount(0, key.length) == 1) {
            // A plain character sent as a key event (some keyboards do this for digits).
            commitText(key, 1)
            return true
        }
        sink.key(key, mods)
        consumeStickyMods()
        // Arrows, Tab etc. move the remote caret; our buffer no longer matches.
        clearBuffer()
        return true
    }

    /** With Ctrl/Alt armed, typed characters become shortcuts instead of text. */
    private fun sendAsShortcut(text: CharSequence): Boolean {
        val mods = stickyMods()
        if (!(mods.ctrl || mods.alt || mods.meta)) return false
        val s = text.toString()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            sink.key(String(Character.toChars(cp)), mods)
            i += Character.charCount(cp)
        }
        consumeStickyMods()
        return true
    }

    private fun pressEnter(mods: Mods) {
        finishComposingQuietly()
        flush()
        sink.key("Enter", mods)
        consumeStickyMods()
        // Most single-line fields (chat boxes, URL bars) clear or submit on Enter.
        clearBuffer()
    }

    private fun finishComposingQuietly() {
        super.finishComposingText()
    }

    private fun caret(): Int = Selection.getSelectionEnd(editable).let { if (it < 0) editable.length else it }

    /** Push the difference between the local buffer and the remote field. */
    fun flush() {
        val text = editable.toString()
        for (edit in sync.update(text, caret())) {
            when (edit) {
                is TextSync.Edit.Insert -> sink.text(edit.text)
                is TextSync.Edit.Delete -> sink.backspace(edit.count)
                is TextSync.Edit.Move -> {
                    val key = if (edit.delta > 0) "ArrowRight" else "ArrowLeft"
                    repeat(kotlin.math.abs(edit.delta)) { sink.key(key) }
                }
            }
        }
        val nl = text.lastIndexOf('\n')
        if (nl >= 0 || text.length > MAX_BUFFER) {
            // A committed newline was typed as Enter; start a fresh line locally.
            clearBuffer()
        }
    }

    private fun clearBuffer() {
        if (editable.isEmpty() && sync.sent.isEmpty()) return
        editable.clear()
        Selection.setSelection(editable, 0)
        sync.reset()
        requestRestart()
    }

    private fun Mods.merge(o: Mods) = Mods(ctrl || o.ctrl, alt || o.alt, shift || o.shift, meta || o.meta)

    companion object {
        /** Past this, we forget older text so the IME's context stays small. */
        const val MAX_BUFFER = 400

        fun configure(outAttrs: EditorInfo, smart: Boolean, editable: Editable) {
            outAttrs.inputType = if (smart) {
                EditorInfo.TYPE_CLASS_TEXT or
                    EditorInfo.TYPE_TEXT_FLAG_AUTO_CORRECT or
                    EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES
            } else {
                EditorInfo.TYPE_CLASS_TEXT or
                    EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                    EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
                EditorInfo.IME_FLAG_NO_ENTER_ACTION or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN
            val sel = Selection.getSelectionEnd(editable).let { if (it < 0) editable.length else it }
            outAttrs.initialSelStart = sel
            outAttrs.initialSelEnd = sel
            outAttrs.initialCapsMode = 0
        }

        fun previousGrapheme(text: CharSequence, end: Int): Int {
            val it = java.text.BreakIterator.getCharacterInstance()
            it.setText(text.toString())
            return if (end <= 0) 0 else it.preceding(end).coerceAtLeast(0)
        }
    }
}
