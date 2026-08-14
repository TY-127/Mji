package com.moon.aiphone

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class UniverseActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val homeUrl = "https://codgames.pages.dev/"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true          // 游戏需要 JS
            domStorageEnabled = true          // 游戏存档用 localStorage
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
        }

        // 让站内链接都留在 WebView 里打开
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean = false
        }
        webView.webChromeClient = WebChromeClient()

        webView.loadUrl(homeUrl)
    }

    // 返回键先在网页里后退，退到头再退出页面
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}