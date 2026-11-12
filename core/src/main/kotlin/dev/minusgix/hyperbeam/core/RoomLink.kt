package dev.minusgix.hyperbeam.core

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Hyperbeam room addresses.
 *
 * Joining a room directly at `https://<server>/r/<name>` only asks for a display
 * name and birth date, no account. This turns whatever the user typed or pasted
 * (a bare room name, `/r/name`, or a full link on any Hyperbeam host) into
 * that URL.
 */
data class RoomLink(val server: String, val name: String) {
    val url: String get() = "https://$server/r/${encode(name)}"

    companion object {
        const val CANARY = "canary.hyperbeam.com"
        const val MAIN = "hyperbeam.com"

        private val NAME_OK = Regex("""^[^\s/?#]{1,128}$""")

        fun isHyperbeamHost(host: String): Boolean =
            host == MAIN || host.endsWith(".$MAIN")

        /**
         * Parse user input into a room link, using [defaultServer] for bare
         * names. Returns null if the input isn't a room.
         */
        fun parse(input: String, defaultServer: String = CANARY): RoomLink? {
            val s = input.trim()
            if (s.isEmpty()) return null
            if (s.contains("://") || s.startsWith("hyperbeam.com") || s.contains(".hyperbeam.com")) {
                return fromUrl(if (s.contains("://")) s else "https://$s")
            }
            val name = s.removePrefix("/").removePrefix("r/")
            return if (NAME_OK.matches(name)) RoomLink(defaultServer, name) else null
        }

        /** The room a Hyperbeam URL points at, or null for any other page. */
        fun fromUrl(url: String): RoomLink? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (uri.scheme != "https" || !isHyperbeamHost(host)) return null
            val parts = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }
            if (parts.size < 2 || parts[0] != "r") return null
            val name = decode(parts[1])
            return if (NAME_OK.matches(name)) RoomLink(host, name) else null
        }

        private fun encode(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
        private fun decode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8)
    }
}
