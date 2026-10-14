package com.altair.llm

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * JS bridge exposed to the web UI as `window.AndroidBridge`.
 * Every method returns a JSON string; the UI polls for status updates
 * (downloads, server state) so no push plumbing is needed.
 */
class Bridge(private val activity: Activity) {

    init {
        EngineManager.init(activity)
    }

    @JavascriptInterface
    fun deviceInfo(): String {
        val am = activity.getSystemService(Activity.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return JSONObject()
            .put("totalMem", mi.totalMem)
            .put("freeMem", mi.availMem)
            .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            .put("device", Build.MANUFACTURER + " " + Build.MODEL)
            .put("suggestCtx", EngineManager.suggestCtx())
            .toString()
    }

    @JavascriptInterface
    fun listLocalModels(): String = EngineManager.listLocalModels()

    @JavascriptInterface
    fun hfSearch(query: String): String = runCatching { HfApi.search(query) }
        .getOrElse { JSONObject().put("error", it.message ?: "search failed").toString() }

    @JavascriptInterface
    fun hfTree(repoId: String): String = runCatching { HfApi.tree(repoId) }
        .getOrElse { JSONObject().put("error", it.message ?: "tree failed").toString() }

    @JavascriptInterface
    fun startDownload(repoId: String, path: String, size: Long): String {
        return try {
            val name = path.substringAfterLast('/')
            val dest = File(EngineManager.modelsDir(), name)
            if (dest.exists() && dest.length() == size) {
                JSONObject().put("id", "already").put("status", "done").toString()
            } else {
                val job = ModelDownloader.start(HfApi.resolveUrl(repoId, path), dest)
                JSONObject().put("id", job.id).put("status", job.status).toString()
            }
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "download failed").toString()
        }
    }

    @JavascriptInterface
    fun downloadsStatus(): String = ModelDownloader.statusJson()

    @JavascriptInterface
    fun pauseDownload(id: String) = ModelDownloader.pause(id)

    @JavascriptInterface
    fun resumeDownload(id: String) = ModelDownloader.resume(id)

    @JavascriptInterface
    fun cancelDownload(id: String) = ModelDownloader.cancel(id)

    @JavascriptInterface
    fun deleteModel(name: String): String {
        return try {
            val dir = EngineManager.modelsDir()
            val all = dir.listFiles(java.io.FileFilter { x -> x.isFile }) ?: emptyArray()
            var deleted = false
            for (f in all) {
                if (f.name == name || f.name == "$name.part") { f.delete(); deleted = true }
            }
            JSONObject().put("ok", deleted).toString()
        } catch (e: Exception) {
            JSONObject().put("error", e.message).toString()
        }
    }

    /** cfg JSON: {modelPath, mmprojPath?, port?, ctx?, threads?, ngl?} */
    @JavascriptInterface
    fun startServer(cfgJson: String): String {
        return try {
            val cfg = JSONObject(cfgJson)
            val modelPath = cfg.getString("modelPath")
            val mmproj = cfg.optString("mmprojPath", "").takeIf { it.isNotBlank() }
            val port = cfg.optInt("port", EngineManager.DEFAULT_PORT)
            val ctxSize = cfg.optInt("ctx", EngineManager.suggestCtx())
            val threads = cfg.optInt("threads", defaultThreads())
            val ngl = cfg.optInt("ngl", 99)

            // keep the process alive in background via the foreground service
            val svc = Intent(activity, AltairEngineService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) activity.startForegroundService(svc)
            else activity.startService(svc)

            val ok = EngineManager.start(modelPath, mmproj, port, ctxSize, threads, ngl)
            JSONObject().put("ok", ok).put("status", EngineManager.statusJson()).toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "start failed").toString()
        }
    }

    @JavascriptInterface
    fun stopServer() {
        EngineManager.stop()
        activity.stopService(Intent(activity, AltairEngineService::class.java))
    }

    @JavascriptInterface
    fun serverStatus(): String = EngineManager.statusJson()

    private fun defaultThreads(): Int =
        (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 8)
}
