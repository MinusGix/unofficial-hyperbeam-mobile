package dev.minusgix.hyperbeam.room

/** What the app knows about the page and the shared browser, for UI and notifications. */
data class RoomState(
    val url: String = "",
    val title: String = "",
    /** The bridge hooked Hyperbeam's streaming client: we're in a room. */
    val clientActive: Boolean = false,
    /** "connecting", "playing", "reconnecting", "failed", or null before any report. */
    val connection: String? = null,
    val remoteWidth: Int? = null,
    val remoteHeight: Int? = null,
    /** How typed keys reach the shared browser: "client" (direct) or "synthetic" (fallback). */
    val inputPath: String? = null,
    val muted: Boolean = false,
    val lastDisconnect: String? = null,
) {
    val inRoom: Boolean get() = clientActive

    val statusText: String
        get() = when {
            !clientActive -> "Browsing"
            connection == "reconnecting" -> "Reconnecting…"
            connection == "connecting" -> "Connecting…"
            connection == "failed" -> "Connection lost"
            else -> "In a room"
        }
}
