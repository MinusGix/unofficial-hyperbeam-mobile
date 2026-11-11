package dev.minusgix.hyperbeam.web

import android.Manifest
import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import dev.minusgix.hyperbeam.room.RoomSession

class HbChromeClient(
    private val onTitle: (String) -> Unit,
) : WebChromeClient() {

    override fun onReceivedTitle(view: WebView, title: String?) {
        if (!title.isNullOrBlank()) onTitle(title)
    }

    /** Camera and microphone for the site's voice and video calls. */
    override fun onPermissionRequest(request: PermissionRequest) {
        val origin = request.origin
        if (!RoomSession.isHyperbeamUrl(origin)) {
            request.deny()
            return
        }
        val wanted = request.resources.filter {
            it == PermissionRequest.RESOURCE_AUDIO_CAPTURE || it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
        }
        if (wanted.isEmpty()) {
            request.deny()
            return
        }
        val android = wanted.map {
            if (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE) Manifest.permission.RECORD_AUDIO else Manifest.permission.CAMERA
        }.toTypedArray()
        val host = RoomSession.host
        if (host == null) {
            request.deny()
            return
        }
        host.requestPermissions(android) { granted ->
            if (granted) request.grant(wanted.toTypedArray()) else request.deny()
        }
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean = RoomSession.host?.pickFiles(fileChooserParams, filePathCallback) ?: false
}
