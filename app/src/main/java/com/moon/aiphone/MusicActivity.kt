package com.moon.aiphone

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

class MusicActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var lastTitle = ""
    private var isWebViewPaused = false
    private var lastFetchSong = ""
    private val keepPlayingReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            try {
                if (!isWebViewPaused) {
                    webView.evaluateJavascript("""
                        try {
                            var audios = document.querySelectorAll('audio');
                            audios.forEach(function(a){ if(a.paused) a.play(); });
                        } catch(e) {}
                    """, null)
                }
            } catch (e: Exception) {}
        }
    }

    private val togglePlayReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            try {
                webView.evaluateJavascript("""
                    try {
                        var audio = document.querySelector('audio');
                        if(audio) {
                            if(audio.paused) { audio.play(); } 
                            else { audio.pause(); }
                        }
                    } catch(e) {}
                """, null)
            } catch (e: Exception) {}
        }
    }

    private val syncRunnable = object : Runnable {
        override fun run() {
            try {
                webView.evaluateJavascript("""
                    (function() {
                        var title = document.title || '';
                        var audio = document.querySelector('audio');
                        var duration = audio ? audio.duration : 0;
                        var currentTime = audio ? audio.currentTime : 0;
                        return JSON.stringify({
                            title: title,
                            duration: duration,
                            currentTime: currentTime
                        });
                    })()
                """.trimIndent()) { result ->
                    try {
                        val clean = result?.trim('"')?.replace("\\\"", "\"") ?: return@evaluateJavascript
                        val json = org.json.JSONObject(clean)
                        var title = json.optString("title").trim()
                        title = title.replace("▶", "").replace("⏸", "").replace("▷", "").trim()
                        if (title.contains(" - ")) title = title.split(" - ")[0].trim()
                        title = title.trim()
                        android.util.Log.d("TitleDebug", "处理后title: $title")
                        val duration = json.optDouble("duration", 0.0)
                        val currentTime = json.optDouble("currentTime", 0.0)
                        val progress = if (duration > 0) (currentTime / duration * 100).toInt() else 0
                        val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                        if (title.isNotEmpty() && title != "网易云音乐" && !title.contains("网易云")) {
                            pref.edit().putString("currentSongProgress", progress.toString()).apply()
                            if (title != lastTitle) {
                                lastTitle = title
                                android.util.Log.d("SyncDebug", "歌名变化: $title")
                                pref.edit()
                                    .putString("currentSong", title)
                                    .putString("currentSongCover", "")
                                    .putString("currentSongArtist", "")
                                    .apply()


                            }
                        }
                    } catch (e: Exception) {}
                }
            } catch (e: Exception) {}
            handler.postDelayed(this, 1000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        webView = WebView(this)
        // 悬浮暂停按钮
        val frameLayout = android.widget.FrameLayout(this)
        frameLayout.addView(webView)

        val pauseBtn = android.widget.ImageButton(this)
        pauseBtn.setImageResource(android.R.drawable.ic_media_pause)
        pauseBtn.setBackgroundColor(android.graphics.Color.parseColor("#AA000000"))
        val btnParams = android.widget.FrameLayout.LayoutParams(120, 120).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            bottomMargin = 80
            rightMargin = 40
        }
        pauseBtn.layoutParams = btnParams
        pauseBtn.setOnClickListener {
            webView.evaluateJavascript("""
        try {
            var audio = document.querySelector('audio');
            if(audio) {
                if(audio.paused) audio.play();
                else audio.pause();
            }
        } catch(e) {}
    """, null)
        }
        frameLayout.addView(pauseBtn)
        setContentView(frameLayout)
        webView.webChromeClient = android.webkit.WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript("""
            try {
    Object.defineProperty(document, 'hidden', {value: false, writable: false});
    Object.defineProperty(document, 'visibilityState', {value: 'visible', writable: false});
    document.addEventListener('visibilitychange', function(e) {
        e.stopImmediatePropagation();
    }, true);
    var audios = document.querySelectorAll('audio');
    audios.forEach(function(a){
        a.addEventListener('pause', function(e){
            e.stopImmediatePropagation();
            setTimeout(function(){ a.play(); }, 300);
        }, true);
    });
} catch(e) {}
                """, null)
            }
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowContentAccess = true
            allowFileAccess = true
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }
        val songId = intent.getStringExtra("song_id")
        val startUrl = if (!songId.isNullOrEmpty()) {
            "https://music.163.com/#/song?id=$songId"
        } else {
            "https://music.163.com/#/discover"
        }
        webView.loadUrl(startUrl)
        startService(Intent(this, MusicService::class.java))
        androidx.core.content.ContextCompat.registerReceiver(
            this, musicPauseReceiver,
            android.content.IntentFilter("MUSIC_PAUSE"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        androidx.core.content.ContextCompat.registerReceiver(
            this, musicResumeReceiver,
            android.content.IntentFilter("MUSIC_RESUME"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // 注册广播
        androidx.core.content.ContextCompat.registerReceiver(
            this, keepPlayingReceiver,
            android.content.IntentFilter("MUSIC_KEEP_PLAYING"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        androidx.core.content.ContextCompat.registerReceiver(
            this, togglePlayReceiver,
            android.content.IntentFilter("MUSIC_TOGGLE_PLAY"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        isWebViewPaused = false
        webView.onResume()
        webView.resumeTimers()
        handler.post(syncRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(syncRunnable)
        // 不暂停WebView，让音乐继续
    }

    override fun onStop() {
        super.onStop()
        // 不调用webView.onPause()，保持音乐播放
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            finish()
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(keepPlayingReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(togglePlayReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(musicPauseReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(musicResumeReceiver) } catch (_: Exception) {}
        handler.removeCallbacks(syncRunnable)
        stopService(Intent(this, MusicService::class.java))
        try {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        } catch (_: Exception) {}
    }

    private fun updateWidgetFromPrefs() {
        sendBroadcast(android.content.Intent("MUSIC_INFO_UPDATED"))
    }
    private val musicPauseReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            webView.evaluateJavascript("try { document.querySelector('audio')?.pause(); } catch(e) {}", null)
        }
    }

    private val musicResumeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            webView.evaluateJavascript("try { document.querySelector('audio')?.play(); } catch(e) {}", null)
        }
    }
    private fun fetchSongInfo(songName: String) {
        Thread {
            try {
                val url = "https://music.163.com/api/search/get?s=${java.net.URLEncoder.encode(songName, "UTF-8")}&type=1&limit=1"
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("Referer", "https://music.163.com/")
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val resp = conn.inputStream.bufferedReader().readText()
                val songs = org.json.JSONObject(resp).optJSONObject("result")?.optJSONArray("songs")
                if (songs != null && songs.length() > 0) {
                    val song = songs.getJSONObject(0)
                    val songId = song.optString("id")
                    val albumObj = song.optJSONObject("album")
                    var albumPic = albumObj?.optString("picUrl") ?: ""
                    if (albumPic.isEmpty()) albumPic = "https://music.163.com/api/img/blur/$songId"
                    if (albumPic.startsWith("http://")) albumPic = albumPic.replace("http://", "https://")
                    val artist = song.optJSONArray("artists")?.optJSONObject(0)?.optString("name") ?: ""

                    var coverBase64 = ""
                    try {
                        val imgConn = java.net.URL(albumPic).openConnection() as java.net.HttpURLConnection
                        imgConn.setRequestProperty("Referer", "https://music.163.com/")
                        imgConn.setRequestProperty("User-Agent", "Mozilla/5.0")
                        imgConn.connectTimeout = 5000
                        imgConn.readTimeout = 5000
                        val imgBytes = imgConn.inputStream.readBytes()
                        coverBase64 = "data:image/jpeg;base64," + android.util.Base64.encodeToString(imgBytes, android.util.Base64.NO_WRAP)
                    } catch (e: Exception) {}

                    getSharedPreferences("AppConfig", Context.MODE_PRIVATE).edit()
                        .putString("currentSongCover", coverBase64)
                        .putString("currentSongArtist", artist)
                        .apply()

                    runOnUiThread { updateWidgetFromPrefs() }

                    try {
                        val commentUrl = "https://music.163.com/api/v1/resource/comments/R_SO_4_$songId?limit=3&offset=0"
                        val conn2 = java.net.URL(commentUrl).openConnection() as java.net.HttpURLConnection
                        conn2.setRequestProperty("User-Agent", "Mozilla/5.0")
                        conn2.setRequestProperty("Referer", "https://music.163.com/")
                        conn2.connectTimeout = 5000
                        conn2.readTimeout = 5000
                        val resp2 = conn2.inputStream.bufferedReader().readText()
                        val hotComments = org.json.JSONObject(resp2).optJSONArray("hotComments")
                        val sb = StringBuilder()
                        if (hotComments != null) {
                            for (i in 0 until minOf(3, hotComments.length())) {
                                sb.append("「${hotComments.getJSONObject(i).optString("content")}」\n")
                            }
                        }
                        getSharedPreferences("AppConfig", Context.MODE_PRIVATE).edit()
                            .putString("currentSongComments", sb.toString()).apply()
                    } catch (e: Exception) {}
                }
            } catch (e: Exception) {}
        }.start()
    }
}