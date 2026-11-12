package dev.minusgix.hyperbeam.web

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.minusgix.hyperbeam.core.RoomLink
import dev.minusgix.hyperbeam.input.Mods
import dev.minusgix.hyperbeam.input.RemoteKeySink
import org.json.JSONObject

/**
 * Two-way channel to the bridge script, origin-locked to Hyperbeam.
 *
 * The bridge is injected at document start with addDocumentStartJavaScript and
 * talks back through a WebMessageListener exposed to the page as `HbNative`.
 * Neither is available to any other site the WebView may show.
 */
class BridgeChannel(
    private val webView: WebView,
    bridgeSource: String,
    private val onEvent: (BridgeEvent) -> Unit,
) : RemoteKeySink {

    private var mainFrame: JavaScriptReplyProxy? = null

    val supported: Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    init {
        if (supported) install(bridgeSource) else Log.w(TAG, "WebView too old for the bridge; running the plain site")
    }

    @SuppressLint("RequiresFeature")
    private fun install(source: String) {
        WebViewCompat.addWebMessageListener(webView, "HbNative", ALLOWED_ORIGINS) { _, message: WebMessageCompat, origin: Uri, isMainFrame, proxy ->
            if (!isAllowed(origin)) return@addWebMessageListener
            val data = message.data ?: return@addWebMessageListener
            val ev = BridgeEvent.parse(data) ?: return@addWebMessageListener
            if (isMainFrame && ev is BridgeEvent.Ready) mainFrame = proxy
            if (isMainFrame) onEvent(ev)
        }
        WebViewCompat.addDocumentStartJavaScript(webView, source, ALLOWED_ORIGINS)
    }

    private fun isAllowed(origin: Uri): Boolean {
        val host = origin.host?.lowercase() ?: return false
        return origin.scheme == "https" && RoomLink.isHyperbeamHost(host)
    }

    fun command(type: String, build: JSONObject.() -> Unit = {}) {
        val proxy = mainFrame ?: return
        val o = JSONObject().put("type", type).apply(build)
        runCatching { proxy.postMessage(o.toString()) }
            .onFailure { Log.w(TAG, "bridge post failed", it) }
    }

    /** The page navigated; the old frame's proxy is dead until the new page says ready. */
    fun onPageStarted() {
        mainFrame = null
    }

    val connected: Boolean get() = mainFrame != null

    override fun key(key: String, mods: Mods, phase: RemoteKeySink.Phase) = command("key") {
        put("key", key)
        put("phase", phase.wire)
        if (mods.ctrl) put("ctrl", true)
        if (mods.alt) put("alt", true)
        if (mods.shift) put("shift", true)
        if (mods.meta) put("meta", true)
    }

    override fun text(text: String) = command("text") { put("text", text) }

    override fun backspace(count: Int) = command("backspace") { put("count", count) }

    fun pinVisibility(on: Boolean) = command("pinVisibility") { put("on", on) }

    fun setVideoPaused(on: Boolean) = command("videoPaused") { put("on", on) }

    fun setVolume(value: Double) = command("volume") { put("value", value) }

    fun ping() = command("ping")

    fun sendChat(id: Int, text: String) = command("chatSend") {
        put("id", id)
        put("text", text)
    }

    /** Mouse [action] ("down", "move", "up") at x, y normalised 0..1 over the shared screen. */
    fun mouse(action: String, x: Float, y: Float, button: Int = 0) = command("mouse") {
        put("action", action)
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("button", button)
    }

    /** Scroll by deltaX/deltaY remote pixels with the pointer at x, y (0..1). */
    fun wheel(x: Float, y: Float, deltaX: Float, deltaY: Float) = command("wheel") {
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("deltaX", deltaX.toDouble())
        put("deltaY", deltaY.toDouble())
    }

    fun theater(on: Boolean) = command("theater") { put("on", on) }

    fun requestSnapshot(id: Int) = command("snapshot") { put("id", id) }

    companion object {
        private const val TAG = "HbBridge"
        val ALLOWED_ORIGINS = setOf("https://hyperbeam.com", "https://*.hyperbeam.com")
    }
}
