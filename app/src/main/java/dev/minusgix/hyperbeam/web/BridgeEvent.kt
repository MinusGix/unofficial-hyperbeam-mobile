package dev.minusgix.hyperbeam.web

import org.json.JSONObject

/** Messages from the injected bridge script (see bridge/src/protocol.ts). */
sealed interface BridgeEvent {
    data class Ready(val version: Int, val top: Boolean, val url: String) : BridgeEvent
    data class Client(val state: String, val width: Int?, val height: Int?, val userId: String?) : BridgeEvent
    data class Connection(val state: String) : BridgeEvent
    data class Disconnect(val reason: String) : BridgeEvent
    data class Resize(val width: Int, val height: Int) : BridgeEvent
    data class Location(val url: String, val title: String) : BridgeEvent
    data class InputPath(val path: String) : BridgeEvent
    data class PageFocus(val editable: Boolean, val inClient: Boolean) : BridgeEvent
    data class Snapshot(val id: Int, val data: Any?) : BridgeEvent
    data class Log(val level: String, val msg: String) : BridgeEvent
    data class ChatMessage(val author: String, val text: String, val time: String)
    data class Chat(val initial: Boolean, val messages: List<ChatMessage>) : BridgeEvent
    data class ChatSent(val id: Int, val ok: Boolean) : BridgeEvent
    data class Unknown(val type: String) : BridgeEvent

    companion object {
        fun parse(json: String): BridgeEvent? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            fun intOrNull(k: String) = if (o.has(k) && !o.isNull(k)) o.optInt(k) else null
            fun strOrNull(k: String) = if (o.has(k) && !o.isNull(k)) o.optString(k) else null
            return when (val type = o.optString("type")) {
                "ready" -> Ready(o.optInt("version"), o.optBoolean("top"), o.optString("url"))
                "client" -> Client(o.optString("state"), intOrNull("width"), intOrNull("height"), strOrNull("userId"))
                "connection" -> Connection(o.optString("state"))
                "disconnect" -> Disconnect(o.optString("reason"))
                "resize" -> Resize(o.optInt("width"), o.optInt("height"))
                "location" -> Location(o.optString("url"), o.optString("title"))
                "inputPath" -> InputPath(o.optString("path"))
                "pageFocus" -> PageFocus(o.optBoolean("editable"), o.optBoolean("inClient"))
                "snapshot" -> Snapshot(o.optInt("id"), o.opt("data"))
                "log" -> Log(o.optString("level"), o.optString("msg"))
                "chat" -> {
                    val arr = o.optJSONArray("messages")
                    val msgs = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                        val m = arr!!.optJSONObject(i) ?: return@mapNotNull null
                        ChatMessage(m.optString("author"), m.optString("text"), m.optString("time"))
                    }
                    Chat(o.optBoolean("initial"), msgs)
                }
                "chatSent" -> ChatSent(o.optInt("id"), o.optBoolean("ok"))
                else -> Unknown(type)
            }
        }
    }
}
