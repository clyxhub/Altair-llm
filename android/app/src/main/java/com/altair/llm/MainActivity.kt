package com.altair.llm

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Phase 1 shell: renders the bundled web UI, exposes the AndroidBridge for
 * model management (HuggingFace downloads, local llama-server control).
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

    override fun onBackPressed() {
        if (this::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
