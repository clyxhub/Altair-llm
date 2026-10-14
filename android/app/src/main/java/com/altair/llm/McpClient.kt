package com.altair.llm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

data class McpServer(val name: String, val url: String, val enabled: Boolean)

/**
 * Phase 4 — MCP client (HTTP JSON-RPC 2.0 transport).
 *
 * Connects external MCP servers (Claude Code / Operit-style ecosystems) and
 * exposes their tools through the same [ToolSpec] interface as built-ins, so any
 * agent can call them. No allowlist: safety comes from process isolation and the
 * confirmation gates, not from blocking servers.
 */
object McpClient {

    private const val KEY = "mcp_servers_v1"
    private const val PROTO = "2025-06-18"
    private val toolsCache = HashMap<String, Pair<Long, List<JSONObject>>>()
    private const val CACHE_MS = 60_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("altair_llm", Context.MODE_PRIVATE)

    fun servers(ctx: Context): List<McpServer> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name")
                val url = o.optString("url")
                if (name.isBlank() || url.isBlank()) null
                else McpServer(name, url, o.optBoolean("enabled", true))
            }
        }.getOrDefault(emptyList())
    }

    private fun save(ctx: Context, list: List<McpServer>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("url", it.url).put("enabled", it.enabled)) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
        toolsCache.clear()
    }

    fun addServer(ctx: Context, name: String, url: String) {
        val clean = name.trim().ifBlank { "server" }
        val list = servers(ctx).filterNot { it.name.equals(clean, true) } +
            McpServer(clean, url.trim(), true)
        save(ctx, list)
    }

    fun removeServer(ctx: Context, name: String) = save(ctx, servers(ctx).filterNot { it.name == name })

    fun setEnabled(ctx: Context, name: String, on: Boolean) =
        save(ctx, servers(ctx).map { if (it.name == name) it.copy(enabled = on) else it })

    /** Import a plugin manifest: {"mcpServers":{name:{url}}} or [{name,url}]. */
    fun importPlugins(ctx: Context, json: String): Int {
        var added = 0
        runCatching {
            val obj = JSONObject(json)
            val servers = obj.optJSONObject("mcpServers")
            if (servers != null) {
                for (key in servers.keys()) {
                    val url = servers.optJSONObject(key)?.optString("url").orEmpty()
                    if (url.isNotBlank()) { addServer(ctx, key, url); added++ }
                }
            } else {
                val arr = obj.optJSONArray("plugins") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val n = o.optString("name")
                    val u = o.optString("url")
                    if (n.isNotBlank() && u.isNotBlank()) { addServer(ctx, n, u); added++ }
                }
            }
        }
        return added
    }

    private fun rpc(url: String, method: String, params: JSONObject, id: Int = 1): JSONObject? = runCatching {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
            .toString()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 12000
        conn.readTimeout = 30000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        conn.disconnect()
        if (text.isBlank()) return@runCatching null
        val obj = JSONObject(text)
        obj.optJSONObject("result")
    }.getOrNull()

    private fun listTools(ctx: Context, server: McpServer): List<JSONObject> {
        val now = System.currentTimeMillis()
        toolsCache[server.name]?.let { (t, cached) -> if (now - t < CACHE_MS) return cached }
        // initialize handshake is best-effort; many servers do not require it
        runCatching {
            rpc(
                server.url, "initialize",
                JSONObject()
                    .put("protocolVersion", PROTO)
                    .put("capabilities", JSONObject())
                    .put("clientInfo", JSONObject().put("name", "Altair").put("version", "0.1"))
            )
        }
        val result = rpc(server.url, "tools/list", JSONObject()) ?: return emptyList()
        val arr = result.optJSONArray("tools") ?: return emptyList()
        val out = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        toolsCache[server.name] = now to out
        return out
    }

    fun tools(ctx: Context): List<Tool> {
        val out = ArrayList<Tool>()
        for (server in servers(ctx)) {
            if (!server.enabled) continue
            val defs = runCatching { listTools(ctx, server) }.getOrDefault(emptyList())
            for (def in defs) {
                val name = def.optString("name")
                if (name.isBlank()) continue
                val desc = def.optString("description", "").take(160)
                val schema = def.optJSONObject("inputSchema")?.toString()?.take(400) ?: "{}"
                out.add(object : Tool {
                    override val spec = ToolSpec("$name", desc, schema, "mcp:${server.name}")
                    override fun run(args: JSONObject): String {
                        val res = rpc(
                            server.url, "tools/call",
                            JSONObject().put("name", name).put("arguments", args)
                        ) ?: return "mcp call failed"
                        // MCP returns {content:[{type:"text",text:...}]}
                        val content = res.optJSONArray("content")
                        if (content != null) {
                            val sb = StringBuilder()
                            for (i in 0 until content.length()) {
                                val c = content.optJSONObject(i) ?: continue
                                if (c.optString("type") == "text") sb.append(c.optString("text")).append('\n')
                            }
                            return sb.toString().trim().take(4000)
                        }
                        return res.toString().take(4000)
                    }
                })
            }
        }
        return out
    }
}
