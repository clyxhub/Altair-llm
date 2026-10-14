package com.altair.llm

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages the native llama-server process shipped in the APK as
 * `libaltairserver.so` (built in CI from llama.cpp with the Vulkan backend).
 *
 * Executed from `nativeLibraryDir` — the only reliably executable location
 * for app-owned binaries on Android 10+ (exec from app data dirs is blocked).
 */
object EngineManager {

    const val DEFAULT_PORT = 8077

    @Volatile var state: String = "stopped" // stopped | starting | running | error
        private set
    @Volatile var port: Int = DEFAULT_PORT
        private set
    @Volatile var modelPath: String? = null
        private set
    @Volatile var errorMessage: String? = null
        private set

    private var process: Process? = null
    private val log = ArrayDeque<String>()
    private val logLock = Any()
    private var appContext: Context? = null

    fun init(ctx: Context) {
        if (appContext == null) appContext = ctx.applicationContext
    }

    fun modelsDir(): File {
        val ctx = appContext ?: throw IllegalStateException("EngineManager not initialized")
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return File(base, "models").apply { mkdirs() }
    }

    /** Scan the model library for GGUF files; auto-pair mmproj when present. */
    fun listLocalModels(): String {
        val arr = org.json.JSONArray()
        val dir = modelsDir()
        val ggufFilter = java.io.FileFilter { f -> f.isFile && f.name.endsWith(".gguf") }
        val files: List<File> = dir.listFiles(ggufFilter)?.sortedBy { it.name } ?: emptyList()
        val mmprojs: List<File> = files.filter { it.name.contains("mmproj", ignoreCase = true) }
        for (f in files) {
            if (f.name.contains("mmproj", ignoreCase = true)) continue
            val entry = org.json.JSONObject()
                .put("id", f.name.removeSuffix(".gguf"))
                .put("path", f.absolutePath)
                .put("size", f.length())
            val proj = mmprojs.firstOrNull()
            if (proj != null) entry.put("mmprojPath", proj.absolutePath).put("mmprojName", proj.name)
            arr.put(entry)
        }
        return arr.toString()
    }

    fun start(modelPath: String, mmprojPath: String?, port: Int, ctxSize: Int, threads: Int, ngl: Int): Boolean {
        val ctx = appContext ?: return false
        stop()
        val exe = File(ctx.applicationInfo.nativeLibraryDir, "libaltairserver.so")
        if (!exe.exists()) {
            state = "error"; errorMessage = "native server missing (unsupported ABI?)"; return false
        }

        val args = mutableListOf(
            exe.absolutePath,
            "-m", modelPath,
            "--host", "127.0.0.1",
            "--port", port.toString(),
            "-c", ctxSize.toString(),
            "-t", threads.toString(),
            "-ngl", ngl.toString(),
            "--parallel", "1",
            "--no-webui"
        )
        if (!mmprojPath.isNullOrBlank()) args += listOf("--mmproj", mmprojPath)

        this.port = port
        this.modelPath = modelPath
        state = "starting"
        errorMessage = null
        synchronized(logLock) { log.clear() }
        appendLog("launch: ${args.joinToString(" ")}")

        try {
            val pb = ProcessBuilder(args).redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = ctx.applicationInfo.nativeLibraryDir
            pb.environment()["TMPDIR"] = ctx.cacheDir.absolutePath
            pb.environment()["HOME"] = ctx.filesDir.absolutePath
            val p = pb.start()
            process = p

            Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { line -> appendLog(line) }
                } catch (_: Exception) {}
                if (state != "running" && state != "stopped") {
                    state = "error"
                    errorMessage = "server exited during startup (see log)"
                }
            }.start()

            // readiness probe
            Thread {
                val deadline = System.currentTimeMillis() + 90_000
                while (System.currentTimeMillis() < deadline) {
                    if (state == "stopped") return@Thread
                    try {
                        val conn = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
                        conn.connectTimeout = 2000
                        conn.readTimeout = 2000
                        val code = conn.responseCode
                        conn.disconnect()
                        if (code == 200) {
                            state = "running"
                            appendLog("ready on http://127.0.0.1:$port")
                            return@Thread
                        }
                    } catch (_: Exception) {}
                    if (!isAlive()) return@Thread
                    Thread.sleep(500)
                }
                if (state == "starting") {
                    state = "error"
                    errorMessage = "server did not become ready in 90s"
                }
            }.start()
            return true
        } catch (e: Exception) {
            state = "error"
            errorMessage = e.message ?: e.javaClass.simpleName
            return false
        }
    }

    fun stop() {
        state = "stopped"
        process?.destroy()
        process = null
    }

    fun isAlive(): Boolean = process?.isAlive == true

    fun statusJson(): String {
        val o = org.json.JSONObject()
            .put("state", state)
            .put("port", port)
            .put("model", modelPath ?: org.json.JSONObject.NULL)
            .put("alive", isAlive())
            .put("error", errorMessage ?: org.json.JSONObject.NULL)
        val tail = org.json.JSONArray()
        synchronized(logLock) { for (l in log.takeLast(12)) tail.put(l) }
        o.put("log", tail)
        return o.toString()
    }

    private fun appendLog(line: String) {
        synchronized(logLock) {
            log.addLast(line)
            while (log.size > 200) log.removeFirst()
        }
    }

    /** Suggested context size from device RAM (tokens). */
    fun suggestCtx(): Int {
        val ctx = appContext ?: return 4096
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val gb = mi.totalMem / (1024.0 * 1024 * 1024)
        return when {
            gb >= 10.0 -> 8192
            gb >= 6.0 -> 4096
            gb >= 3.5 -> 3072
            else -> 2048
        }
    }
}
