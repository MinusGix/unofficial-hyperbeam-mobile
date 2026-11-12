package dev.minusgix.hyperbeam.room

/** What the app knows about the page and the shared browser, for UI and notifications. */
data class ChatLine(val author: String, val text: String, val time: String, val receivedAt: Long)

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
    /** Most recent chat messages in this room, oldest first. */
    val chat: List<ChatLine> = emptyList(),
) {
    /** In a room: the shared browser is running, or the page is a room with chat. */
    val inRoom: Boolean get() = clientActive || roomName != null

    /** Room name when the page is a /r/<name> room. */
    val roomName: String?
        get() = dev.minusgix.hyperbeam.core.RoomLink.fromUrl(url)?.name

    val statusText: String
        get() = when {
            !inRoom -> "Browsing"
            connection == "reconnecting" -> "Reconnecting…"
            connection == "connecting" -> "Connecting…"
            connection == "failed" -> "Connection lost"
            !clientActive -> "In the room"
            else -> "Watching"
        }
}
