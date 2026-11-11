package dev.minusgix.hyperbeam.web

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.minusgix.hyperbeam.room.RoomSession

class HbViewClient(
    private val onPageStarted: (String) -> Unit,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        if (!request.isForMainFrame) return false
        if (RoomSession.isHyperbeamUrl(uri) || isSignInHost(uri)) return false
        if (uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "intent" || uri.scheme == "mailto") {
            RoomSession.host?.openExternal(uri)
            return true
        }
        return true // ignore unknown schemes rather than show an error page
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        onPageStarted(url)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        // The renderer crashed or was killed for memory. Drop this WebView and
        // start a fresh one at the same address instead of crashing the app.
        val url = view.url
        RoomSession.close()
        RoomSession.open(url)
        return true
    }

    companion object {
        /** Sign-in pages that must stay inside the WebView so the session cookie lands there. */
        private val SIGN_IN_HOSTS = setOf(
            "accounts.google.com",
            "accounts.youtube.com",
            "discord.com",
            "appleid.apple.com",
            "www.facebook.com",
            "m.facebook.com",
            "x.com",
            "twitter.com",
            "api.twitter.com",
        )

        fun isSignInHost(uri: Uri): Boolean {
            val host = uri.host ?: return false
            return uri.scheme == "https" &&
                (host in SIGN_IN_HOSTS || host.endsWith(".firebaseapp.com") || host.endsWith(".googleusercontent.com"))
        }
    }
}
