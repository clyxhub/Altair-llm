package com.altair.llm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * §3 Staged memory — long-term store.
 *
 * Three stages feed the model distilled knowledge instead of raw transcripts:
 *   working     : the live chat turns (see ContextBudgeter)
 *   episodic    : compacted summaries of older turns, per chat
 *   long-term   : extracted facts/procedures, tagged
 *
 * Persisted as JSON in app preferences (no new dependencies). Retrieval is
 * lexical (token overlap) for now; a quantized vector index is a later upgrade
 * behind the same [search] signature.
 */
data class MemoryItem(
    val id: String,
    val text: String,
    val kind: String,          // "fact" | "episode"
    val chatId: String?,
    val created: Long,
    val tags: List<String> = emptyList()
)

object MemoryStore {

    private const val KEY = "memory_items_v1"
    private const val MAX_ITEMS = 500

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("altair_llm", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<MemoryItem> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val text = o.optString("text", "")
                if (text.isBlank()) return@mapNotNull null
                MemoryItem(
                    id = o.optString("id"),
                    text = text,
                    kind = o.optString("kind", "fact"),
                    chatId = o.optString("chatId", "").takeIf { it.isNotBlank() },
                    created = o.optLong("created", 0L),
                    tags = o.optJSONArray("tags")?.let { t -> (0 until t.length()).map { t.optString(it) } } ?: emptyList()
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(ctx: Context, items: List<MemoryItem>) {
        val arr = JSONArray()
        items.takeLast(MAX_ITEMS).forEach { m ->
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("text", m.text)
                    .put("kind", m.kind)
                    .put("chatId", m.chatId ?: "")
                    .put("created", m.created)
                    .put("tags", JSONArray(m.tags))
            )
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, item: MemoryItem) {
        val items = all(ctx).toMutableList()
        // de-dupe identical text
        if (items.any { it.text.equals(item.text, ignoreCase = true) }) return
        items.add(item)
        save(ctx, items)
    }

    fun addFact(ctx: Context, text: String, tags: List<String> = emptyList()) {
        if (text.isBlank()) return
        add(ctx, MemoryItem("m${System.currentTimeMillis().toString(36)}", text.trim().take(2000), "fact", null, System.currentTimeMillis(), tags))
    }

    fun addEpisode(ctx: Context, chatId: String?, text: String) {
        if (text.isBlank()) return
        add(ctx, MemoryItem("e${System.currentTimeMillis().toString(36)}", text.trim().take(2000), "episode", chatId, System.currentTimeMillis()))
    }

    fun clear(ctx: Context) = prefs(ctx).edit().remove(KEY).apply()

    fun count(ctx: Context): Int = all(ctx).size

    private fun tokens(s: String): Set<String> =
        s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()

    /**
     * Lexical retrieval: rank items by query token overlap, newest first on
     * ties. Returns at most [limit] items with a positive score.
     */
    fun search(ctx: Context, query: String, limit: Int): List<MemoryItem> {
        if (query.isBlank() || limit <= 0) return emptyList()
        val q = tokens(query)
        if (q.isEmpty()) return emptyList()
        return all(ctx)
            .map { m ->
                val t = tokens(m.text)
                val overlap = q.count { it in t }
                m to overlap
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<MemoryItem, Int>> { it.second }.thenByDescending { it.first.created })
            .take(limit)
            .map { it.first }
    }
}
