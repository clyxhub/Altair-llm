package com.altair.llm

import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 6 — shared rooms.
 *
 * The embedded OpenAI-compatible server already makes this device reachable on
 * the LAN. Rooms add a tiny shared chat store on top so multiple devices (or
 * people) can talk in the same named room. Messages are tiny JSON objects with a
 * monotonically increasing index, so clients poll `?since=` cheaply.
 *
 * In-memory by design: rooms are ephemeral sessions, not long-term history
 * (that lives in [ChatStore]/[MemoryStore] per device).
 */
object Rooms {

    data class Message(val index: Int, val author: String, val text: String, val time: Long)

    private const val MAX_ROOM = 500

    private val rooms = LinkedHashMap<String, MutableList<Message>>()
    private var counter = 0

    @Synchronized
    fun list(): List<String> = rooms.keys.toList()

    @Synchronized
    fun post(room: String, author: String, text: String): Message {
        val key = room.ifBlank { "general" }
        val list = rooms.getOrPut(key) { mutableListOf() }
        val msg = Message(++counter, author.ifBlank { "anon" }, text.take(4000), System.currentTimeMillis())
        list.add(msg)
        while (list.size > MAX_ROOM) list.removeAt(0)
        return msg
    }

    @Synchronized
    fun since(room: String, since: Int): List<Message> =
        rooms[room.ifBlank { "general" }]?.filter { it.index > since } ?: emptyList()

    @Synchronized
    fun clear(room: String) {
        rooms.remove(room.ifBlank { "general" })
    }

    fun listJson(): String = JSONArray(list()).toString()

    fun sinceJson(room: String, since: Int): String {
        val arr = JSONArray()
        since(room, since).forEach {
            arr.put(
                JSONObject()
                    .put("index", it.index)
                    .put("author", it.author)
                    .put("text", it.text)
                    .put("time", it.time)
            )
        }
        return arr.toString()
    }
}
