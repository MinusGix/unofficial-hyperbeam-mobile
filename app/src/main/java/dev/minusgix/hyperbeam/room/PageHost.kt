package dev.minusgix.hyperbeam.room

import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient

/** What the page needs from whichever screen is currently showing it. */
interface PageHost {
    /** Ask for Android permissions; call [onResult] with whether all were granted. */
    fun requestPermissions(permissions: Array<String>, onResult: (Boolean) -> Unit)

    /** Show a file picker for an <input type=file>. */
    fun pickFiles(params: WebChromeClient.FileChooserParams, callback: ValueCallback<Array<Uri>>): Boolean

    /** Open a link that doesn't belong in the room (external site). */
    fun openExternal(uri: Uri)
}
