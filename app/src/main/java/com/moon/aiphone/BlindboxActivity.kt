package com.moon.aiphone

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class BlindboxActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var db: DatabaseHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        db = DatabaseHelper(this)
        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            databaseEnabled = true
        }

        webView.addJavascriptInterface(MailboxBridge(this, db), "Android")
        webView.addJavascriptInterface(object : Any() {
            @android.webkit.JavascriptInterface
            fun onBack() {
                runOnUiThread { finish() }
            }
        }, "ActivityBridge")
        webView.webViewClient = WebViewClient()
        webView.loadUrl("file:///android_asset/blindbox.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }override fun onDestroy() {
        try {
            webView.removeJavascriptInterface("Android")
            webView.stopLoading()
            webView.clearHistory()
            webView.destroy()
        } catch (_: Exception) {}

        try {
            db.close()
        } catch (_: Exception) {}

        super.onDestroy()
    }
}