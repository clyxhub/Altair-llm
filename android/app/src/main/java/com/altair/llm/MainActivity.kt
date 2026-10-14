package com.altair.llm

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Phase 0 shell: renders the bundled web UI and proves the JNI bridge is live.
 * The embedded engine (llama.cpp via JNI + agent runtime) lands in Phase 1.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val nativeVersion = AltairJni.version()
        android.util.Log.i("Altair", "native bridge version: $nativeVersion")

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
        }
        web.webViewClient = WebViewClient()
        setContentView(web)
        web.loadUrl("file:///android_asset/www/index.html")
    }

    override fun onBackPressed() {
        if (this::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
