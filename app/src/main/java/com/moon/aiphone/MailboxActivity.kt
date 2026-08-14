package com.moon.aiphone

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MailboxActivity : AppCompatActivity() {

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

        // databasePath 已废弃，不再需要手动设置

        webView.addJavascriptInterface(MailboxBridge(this, db), "Android")
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                android.util.Log.e("MailboxWebView", "加载错误: $errorCode - $description - $failingUrl")
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                android.util.Log.d("MailboxWebView", "页面加载完成: $url")
            }
        }
        webView.loadUrl("file:///android_asset/anonymous_mailbox.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
    override fun onDestroy() {
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