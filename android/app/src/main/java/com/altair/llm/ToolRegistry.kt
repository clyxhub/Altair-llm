package com.altair.llm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Phase 4 — tool interface shared by every agent.
 *
 * Tools are the single interface agents call. External capability arrives two
 * ways, both funnelling through the same registry:
 *   • MCP servers (see [McpClient]) — connect Claude Code / Operit-style servers
 *   • Plugins ([PluginStore]) — bundles that may ship MCP configs, and later
 *     agent-role templates
 */
data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON-schema-ish string for the model, kept compact for small contexts. */
    val params: String,
    val source: String          // "builtin" | "mcp:<server>"
)

interface Tool {
    val spec: ToolSpec
    /** Execute and return a plain-text result (already capped for context). */
    fun run(args: JSONObject): String
}

object ToolRegistry {

    private const val KEY_DISABLED = "tools_disabled"

    private val builtins: List<Tool> = listOf(
        object : Tool {
            override val spec = ToolSpec(
                "web_search",
                "Search the web (DuckDuckGo) and return top results.",
                "{\"query\":\"string\"}",
                "builtin"
            )
            override fun run(args: JSONObject): String {
                val q = args.optString("query").ifBlank { return "missing 'query'" }
                val hits = runCatching { WebSearch.search(q) }.getOrDefault(emptyList())
                if (hits.isEmpty()) return "no results"
                return hits.take(5).joinToString("\n") { "- ${it.title}: ${it.url}" }.take(2000)
            }
        },
        object : Tool {
            override val spec = ToolSpec(
                "fetch_url",
                "Fetch a URL and return its text content (capped).",
                "{\"url\":\"string\"}",
                "builtin"
            )
            override fun run(args: JSONObject): String {
                val u = args.optString("url").ifBlank { return "missing 'url'" }
                return runCatching {
                    val conn = URL(u).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 20000
                    conn.setRequestProperty("User-Agent", "Altair/0.1")
                    val text = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                    conn.disconnect()
                    text.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(4000)
                }.getOrElse { "fetch failed: ${it.message}" }
            }
        },
        object : Tool {
            override val spec = ToolSpec(
                "current_time",
                "Return the current date and time on this device.",
                "{}",
                "builtin"
            )
            override fun run(args: JSONObject): String =
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())
        }
    )

    fun disabled(ctx: Context): Set<String> =
        ctx.getSharedPreferences("altair_llm", Context.MODE_PRIVATE)
            .getStringSet(KEY_DISABLED, emptySet()) ?: emptySet()

    fun setEnabled(ctx: Context, name: String, enabled: Boolean) {
        val set = disabled(ctx).toMutableSet()
        if (enabled) set.remove(name) else set.add(name)
        ctx.getSharedPreferences("altair_llm", Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_DISABLED, set).apply()
    }

    /** All tools: built-ins plus whatever MCP servers expose. */
    fun all(ctx: Context): List<Tool> {
        val list = ArrayList<Tool>(builtins)
        list.addAll(McpClient.tools(ctx))
        return list
    }

    fun enabled(ctx: Context): List<Tool> {
        val off = disabled(ctx)
        return all(ctx).filter { it.spec.name !in off }
    }

    fun find(ctx: Context, name: String): Tool? = all(ctx).firstOrNull { it.spec.name == name }

    /** Compact catalogue for the system prompt (budget-friendly). */
    fun catalogue(ctx: Context): String =
        enabled(ctx).joinToString("\n") { "- ${it.spec.name}: ${it.spec.description}" }

    // The prompt hot path must never block on MCP network calls, so the
    // catalogue is computed off-thread and read from this cache.
    @Volatile private var cached = "- web_search: Search the web (DuckDuckGo) and return top results.\n- fetch_url: Fetch a URL and return its text content (capped).\n- current_time: Return the current date and time on this device."

    fun cachedCatalogue(): String = cached

    /** Rebuild the tool catalogue (safe to call off the main thread). */
    fun prefetch(ctx: Context) {
        cached = runCatching { catalogue(ctx) }.getOrDefault(cached)
    }

    /**
     * Parse a model reply for tool directives and run the first one found.
     * Protocol (kept text-only so tiny local models can use it):
     *   TOOL: <name> {json args}
     */
    fun takeDirective(ctx: Context, reply: String): Pair<Tool, JSONObject>? {
        val line = reply.lineSequence().firstOrNull { it.trimStart().startsWith("TOOL:") } ?: return null
        val rest = line.trimStart().removePrefix("TOOL:").trim()
        val name = rest.substringBefore(' ').trim()
        val jsonPart = rest.substringAfter(' ', "").trim().ifBlank { "{}" }
        val tool = find(ctx, name) ?: return null
        val args = runCatching { JSONObject(jsonPart) }.getOrElse { JSONObject() }
        return tool to args
    }
}
