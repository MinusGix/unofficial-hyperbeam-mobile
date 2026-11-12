package dev.minusgix.hyperbeam.room

import android.content.Context
import dev.minusgix.hyperbeam.core.RoomLink
import org.json.JSONArray
import org.json.JSONObject

/** Rooms you've joined, newest first. Stored only on this device. */
object RecentRooms {
    private const val PREFS = "rooms"
    private const val KEY = "recent"
    private const val MAX = 12

    data class Entry(val link: RoomLink, val lastJoined: Long)

    fun list(context: Context): List<Entry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val server = o.optString("server")
                val name = o.optString("name")
                if (server.isEmpty() || name.isEmpty()) null else Entry(RoomLink(server, name), o.optLong("last"))
            }
        }.getOrDefault(emptyList())
    }

    fun record(context: Context, link: RoomLink, now: Long = System.currentTimeMillis()) {
        val updated = listOf(Entry(link, now)) + list(context).filter { it.link != link }
        save(context, updated.take(MAX))
    }

    fun remove(context: Context, link: RoomLink) {
        save(context, list(context).filter { it.link != link })
    }

    private fun save(context: Context, entries: List<Entry>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().put("server", e.link.server).put("name", e.link.name).put("last", e.lastJoined))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
