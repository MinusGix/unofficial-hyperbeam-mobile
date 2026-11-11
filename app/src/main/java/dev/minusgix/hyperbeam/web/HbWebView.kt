package dev.minusgix.hyperbeam.web

import android.annotation.SuppressLint
import android.content.Context
import android.text.Editable
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import dev.minusgix.hyperbeam.core.TextSync
import dev.minusgix.hyperbeam.input.KeyMap
import dev.minusgix.hyperbeam.input.Mods
import dev.minusgix.hyperbeam.input.RemoteInputConnection
import dev.minusgix.hyperbeam.input.RemoteKeySink

/**
 * The room's WebView, with a "remote typing" mode.
 *
 * Normally it behaves like any WebView, so the site's own chat box and forms
 * get the keyboard. In remote typing mode it hands the soft keyboard a
 * [RemoteInputConnection] instead, and hardware keys go straight to the shared
 * browser with their real modifiers.
 */
@SuppressLint("ViewConstructor")
class HbWebView(context: Context) : WebView(context) {

    var sink: RemoteKeySink? = null

    /** Autocorrect and suggestions on (smart) or a plain key-per-key keyboard (direct). */
    var smartKeyboard: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                if (remoteTyping) restartInput()
            }
        }

    var remoteTyping: Boolean = false
        private set

    /** Modifiers armed from the extra-keys row; consumed by the next key. */
    var stickyMods: Mods = Mods.NONE
    var onStickyModsConsumed: () -> Unit = {}

    private val sync = TextSync()
    private val buffer: Editable = Editable.Factory.getInstance().newEditable("")
    private var connection: RemoteInputConnection? = null

    private val imm: InputMethodManager
        get() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    fun startRemoteTyping() {
        remoteTyping = true
        resetBuffer(restart = false)
        isFocusableInTouchMode = true
        requestFocus()
        restartInput()
        post { imm.showSoftInput(this, 0) }
    }

    fun stopRemoteTyping(hideKeyboard: Boolean = true) {
        if (!remoteTyping) return
        remoteTyping = false
        connection = null
        resetBuffer(restart = false)
        restartInput()
        if (hideKeyboard) imm.hideSoftInputFromWindow(windowToken, 0)
    }

    /** Forget local text, e.g. after the remote caret moved for reasons we can't see. */
    fun resetBuffer(restart: Boolean = true) {
        buffer.clear()
        sync.reset()
        if (restart && remoteTyping) restartInput()
    }

    private fun restartInput() {
        imm.restartInput(this)
    }

    override fun onCheckIsTextEditor(): Boolean = remoteTyping || super.onCheckIsTextEditor()

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val s = sink
        if (!remoteTyping || s == null) return super.onCreateInputConnection(outAttrs)
        RemoteInputConnection.configure(outAttrs, smartKeyboard, buffer)
        return RemoteInputConnection(
            view = this,
            sink = s,
            sync = sync,
            editable = buffer,
            stickyMods = { stickyMods },
            consumeStickyMods = {
                if (stickyMods.any) {
                    stickyMods = Mods.NONE
                    onStickyModsConsumed()
                }
            },
            requestRestart = { post { if (remoteTyping) restartInput() } },
        ).also { connection = it }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (remoteTyping && event.actionMasked == MotionEvent.ACTION_DOWN) {
            // A tap may move focus or the caret in the shared browser.
            resetBuffer()
        }
        return super.onTouchEvent(event)
    }

    // Hardware keyboards: forward real key down/up with modifiers.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        forwardHardwareKey(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        forwardHardwareKey(event) || super.onKeyUp(keyCode, event)

    private fun forwardHardwareKey(event: KeyEvent): Boolean {
        val s = sink ?: return false
        if (!remoteTyping) return false
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return false
        if (event.isSystem && !KeyMap.isModifier(event.keyCode)) return false
        val key = KeyMap.domKey(event) ?: return false
        val phase = if (event.action == KeyEvent.ACTION_DOWN) RemoteKeySink.Phase.DOWN else RemoteKeySink.Phase.UP
        s.key(key, Mods.fromMetaState(event.metaState), phase)
        resetBuffer(restart = false)
        return true
    }
}
