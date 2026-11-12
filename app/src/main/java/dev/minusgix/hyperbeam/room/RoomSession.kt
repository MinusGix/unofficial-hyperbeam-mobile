package dev.minusgix.hyperbeam.room

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import dev.minusgix.hyperbeam.BuildConfig
import dev.minusgix.hyperbeam.core.RoomLink
import dev.minusgix.hyperbeam.web.BridgeChannel
import dev.minusgix.hyperbeam.web.BridgeEvent
import dev.minusgix.hyperbeam.web.HbChromeClient
import dev.minusgix.hyperbeam.web.HbViewClient
import dev.minusgix.hyperbeam.web.HbWebView
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns the room's WebView for the life of the process, not of a screen.
 *
 * The activity only borrows the view to show it. Rotating, switching apps, or
 * closing the screen leaves the page, its connection and its audio running;
 * [RoomService] keeps the process in the foreground meanwhile.
 */
@SuppressLint("StaticFieldLeak") // Holds only the application context when no activity is attached.
object RoomSession {
    const val HOME_URL = "https://hyperbeam.com/app/"

    /** The account-based app on [server] (rooms can also be joined without one). */
    fun appUrl(server: String) = "https://$server/app/"
    private const val TAG = "RoomSession"

    private lateinit var app: Context
    private var contextWrapper: MutableContextWrapper? = null

    var webView: HbWebView? = null
        private set
    var bridge: BridgeChannel? = null
        private set

    private val _state = MutableStateFlow(RoomState())
    val state: StateFlow<RoomState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<BridgeEvent>(extraBufferCapacity = 64)
    /** Raw bridge events, for screens that care (snapshots, focus changes). */
    val events: SharedFlow<BridgeEvent> = _events.asSharedFlow()

    private val _viewVersion = MutableStateFlow(0)
    /** Bumped whenever a new WebView replaces the old one (e.g. after a renderer crash). */
    val viewVersion: StateFlow<Int> = _viewVersion.asStateFlow()

    /** The activity currently showing the page, for permission prompts and pickers. */
    var host: PageHost? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    val isOpen: Boolean get() = webView != null

    /** Create the WebView if needed and load [url] (or home on first open). */
    fun open(url: String? = null): HbWebView {
        val existing = webView
        if (existing != null) {
            if (url != null) existing.loadUrl(url)
            return existing
        }
        val wrapper = MutableContextWrapper(app).also { contextWrapper = it }
        val wv = HbWebView(wrapper)
        configure(wv)
        val bridgeJs = app.assets.open("bridge.js").bufferedReader().use { it.readText() }
        val channel = BridgeChannel(wv, bridgeJs, ::onBridgeEvent)
        wv.sink = channel
        wv.webViewClient = HbViewClient(onPageStarted = { u ->
            channel.onPageStarted()
            rememberRoom(u)
            _state.update { it.copy(url = u, clientActive = false, connection = null, inputPath = null) }
        })
        wv.webChromeClient = HbChromeClient(onTitle = { t -> _state.update { it.copy(title = t) } })
        webView = wv
        bridge = channel
        _viewVersion.update { it + 1 }
        wv.loadUrl(url ?: HOME_URL)
        return wv
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(wv: HbWebView) {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        wv.setBackgroundColor(Color.BLACK)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            builtInZoomControls = false
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            allowFileAccess = false
            allowContentAccess = false
            // Sign-in providers refuse embedded WebViews by their user agent.
            userAgentString = browserUserAgent(userAgentString)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        // Keep the renderer at foreground priority even while the app is hidden.
        wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
    }

    /** Show the WebView inside [container], owned by [activity]. */
    fun attach(activity: Activity, container: ViewGroup) {
        val wv = webView ?: open()
        contextWrapper?.baseContext = activity
        (wv.parent as? ViewGroup)?.removeView(wv)
        container.addView(wv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // Never pause the page when it's hidden; only resume in case something did.
        wv.onResume()
        wv.resumeTimers()
    }

    /** The activity is going away; keep the page alive without it. */
    fun detach(activity: Activity) {
        val wv = webView ?: return
        if (contextWrapper?.baseContext === activity) contextWrapper?.baseContext = app
        (wv.parent as? ViewGroup)?.removeView(wv)
        if (host === activity) host = null
    }

    /** Leave everything: tear down the page and its connection. */
    fun close() {
        val wv = webView ?: return
        webView = null
        bridge = null
        (wv.parent as? ViewGroup)?.removeView(wv)
        // The renderer may already be gone (crash); destroy() is the only safe call then.
        runCatching { wv.stopLoading() }
        wv.destroy()
        contextWrapper = null
        _state.value = RoomState()
    }

    fun setMuted(muted: Boolean) {
        bridge?.setVolume(if (muted) 0.0 else 1.0)
        _state.update { it.copy(muted = muted) }
    }

    private fun onBridgeEvent(ev: BridgeEvent) {
        when (ev) {
            is BridgeEvent.Client -> _state.update {
                when (ev.state) {
                    "destroyed" -> it.copy(clientActive = false, connection = null)
                    else -> it.copy(
                        clientActive = true,
                        connection = if (ev.state == "connected") "playing" else it.connection ?: "connecting",
                        remoteWidth = ev.width ?: it.remoteWidth,
                        remoteHeight = ev.height ?: it.remoteHeight,
                    )
                }
            }
            is BridgeEvent.Connection -> _state.update { it.copy(connection = ev.state) }
            is BridgeEvent.Disconnect -> _state.update { it.copy(lastDisconnect = ev.reason, connection = "failed") }
            is BridgeEvent.Resize -> _state.update { it.copy(remoteWidth = ev.width, remoteHeight = ev.height) }
            is BridgeEvent.Location -> {
                rememberRoom(ev.url)
                _state.update { it.copy(url = ev.url, title = ev.title.ifBlank { it.title }) }
            }
            is BridgeEvent.InputPath -> _state.update { it.copy(inputPath = ev.path) }
            is BridgeEvent.Log -> Log.i(TAG, "bridge ${ev.level}: ${ev.msg}")
            else -> {}
        }
        _events.tryEmit(ev)
    }

    private fun rememberRoom(url: String) {
        RoomLink.fromUrl(url)?.let { RecentRooms.record(app, it) }
    }

    /** True for URLs that belong in the room WebView rather than an external browser. */
    fun isHyperbeamUrl(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme == "https" && RoomLink.isHyperbeamHost(host)
    }

    /**
     * Drop the WebView markers from the default user agent ("; wv" and
     * "Version/4.0"), so the site and sign-in providers see ordinary Chrome
     * for Android, which is what the engine is.
     */
    fun browserUserAgent(default: String): String =
        default.replace("; wv)", ")").replace(Regex("""Version/\d+(\.\d+)* """), "")
}
