package com.altair.llm

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal HuggingFace API client (search + file tree). No external deps. */
object HfApi {

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.requestMethod = "GET"
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code for $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Search GGUF model repos. Returns JSON array of {repoId, downloads, likes}. */
    fun search(query: String, limit: Int = 20): String {
        val q = URLEncoder.encode(query, "UTF-8")
        val raw = get("https://huggingface.co/api/models?search=$q&filter=gguf&sort=downloads&direction=-1&limit=$limit")
        val arr = JSONArray(raw)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            out.put(
                JSONObject()
                    .put("repoId", m.getString("id"))
                    .put("downloads", m.optLong("downloads", 0))
                    .put("likes", m.optLong("likes", 0))
            )
        }
        return out.toString()
    }

    /** List GGUF files (+ sizes) of a repo. Returns JSON array of {path, size}. */
    fun tree(repoId: String): String {
        val raw = get("https://huggingface.co/api/models/$repoId/tree/main?recursive=true")
        val arr = JSONArray(raw)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i)
            if (f.optString("type") != "file") continue
            val p = f.optString("path", "")
            if (!(p.endsWith(".gguf") || p.endsWith(".GGUF"))) continue
            out.put(JSONObject().put("path", p).put("size", f.optLong("size", 0)))
        }
        return out.toString()
    }

    fun resolveUrl(repoId: String, path: String): String =
        "https://huggingface.co/$repoId/resolve/main/$path"
}
