package com.altair.llm

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Phase 1 shell: renders the bundled web UI, exposes the AndroidBridge for
 * model management (HuggingFace downloads, SAF import, local llama-server control).
 */
class MainActivity : Activity() {

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val nativeVersion = AltairJni.version()
        android.util.Log.i("Altair", "native bridge version: $nativeVersion")

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            // the UI (file://) talks to the local engine over http://127.0.0.1
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
        }
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(this), "AndroidBridge")
        setContentView(web)
        web.loadUrl("file:///android_asset/www/index.html")
    }

    /** SAF picker — import GGUF model / mmproj files already on the device. */
    fun startModelImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_IMPORT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT) return
        val results = JSONArray()

        if (resultCode == RESULT_OK && data != null) {
            val uris = mutableListOf<Uri>()
            data.clipData?.let { cd ->
                for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri)
            }
            if (uris.isEmpty()) data.data?.let { uris.add(it) }

            for (uri in uris) {
                try {
                    val name = queryDisplayName(uri)
                        ?: "imported-${System.currentTimeMillis().toString(36)}.gguf"
                    val dest = File(EngineManager.modelsDir(), name)
                    contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("cannot open $uri")
                    results.put(
                        JSONObject()
                            .put("ok", true)
                            .put("name", name)
                            .put("size", dest.length())
                    )
                    android.util.Log.i("Altair", "imported $name (${dest.length()} bytes)")
                } catch (e: Exception) {
                    android.util.Log.e("Altair", "import failed: ${e.message}")
                    results.put(
                        JSONObject().put("ok", false).put("error", e.message ?: "import failed")
                    )
                }
            }
        }

        val js = "window.__altair && window.__altair.onImportDone(" + results.toString() + ")"
        runOnUiThread {
            if (this::web.isInitialized) web.evaluateJavascript(js, null)
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    override fun onBackPressed() {
        if (this::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    companion object {
        private const val REQUEST_IMPORT = 4201
    }
}
