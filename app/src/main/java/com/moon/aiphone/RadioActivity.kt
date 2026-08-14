package com.moon.aiphone

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random

class RadioActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())

    // 播放器
    private var ttsPlayer: MediaPlayer? = null
    private var songPlayer: MediaPlayer? = null
    private var bgPlayer: MediaPlayer? = null
    private var currentBgAssetPath: String = ""
    private var pendingBgmProgramType: String = "story"
    private var pendingBgmReplaceMode: Boolean = true
    private val maxBookChars = 2_000_000
    private val isTtsSpeaking = AtomicBoolean(false)
    private val isSongPlaying = AtomicBoolean(false)
    private var ttsManager: TTSManager? = null
    private var pendingTtsFallback: Runnable? = null
    private var pendingTtsWatchdog: Runnable? = null
    @Volatile private var sleepTimerDeadlineMs = 0L
    private val sleepTimerRunnable = Runnable {
        sleepTimerDeadlineMs = 0L
        stopAll()
        callJs("onStopped(); if(window.onRadioSleepTimerChanged){window.onRadioSleepTimerChanged(0);}")
        callJs("setStatus(${jsStr("定时结束，已停止播放")})")
        Toast.makeText(this, "定时结束，电台已停止播放", Toast.LENGTH_SHORT).show()
    }

    // 节目状态
    private var isPlaying = AtomicBoolean(false)
    private var shouldStop = AtomicBoolean(false)
    private var currentProgramType = "story"
    private var currentVoiceId = ""
    private var currentAiId = ""
    private var currentAiName = ""
    private var currentAiLang = "默认 (中文)"
    private val programGenerationSeq = AtomicInteger(0)

    // 分段队列
    // 每个元素是 Pair<文本段, 音乐标记或null>
    // 音乐节目里每段介绍后面紧跟一首歌
    data class Segment(val text: String, val musicQuery: String? = null, val sourceEndIndex: Int? = null)
    private val segmentQueue = mutableListOf<Segment>()
    private var currentSegmentIndex = 0

    private val bgmPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) {
            Toast.makeText(this, "没有选择音乐", Toast.LENGTH_SHORT).show()
        } else {
            importUserBgms(pendingBgmProgramType, uris, pendingBgmReplaceMode)
        }
    }

    private val bookPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            Toast.makeText(this, "没有选择电子书", Toast.LENGTH_SHORT).show()
        } else {
            importRadioBook(uri)
        }
    }

    // ── WebView 初始化 ───────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        // 建表：存电台标题
        try {
            DatabaseHelper(this).writableDatabase.execSQL(
                "CREATE TABLE IF NOT EXISTS RadioStation (aiId TEXT PRIMARY KEY, stationTitle TEXT, playCount INTEGER DEFAULT 0)"
            )
        } catch (_: Exception) {}

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            addJavascriptInterface(RadioBridge(), "Android")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    loadInitialData()
                    removeRadioEmojiFromWebView()
                    injectRadioAudioSettings()
                }
            }
            loadUrl("file:///android_asset/radio.html")
        }
        setContentView(webView)
    }

    private fun callJs(js: String) {
        mainHandler.post { webView.evaluateJavascript(js, null) }
    }

    private fun jsStr(s: String): String {
        val escaped = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    // ── 电台音量设置：保存到 AppConfig，用户下次打开仍然生效 ──
    private fun getRadioBgmVolumePercent(): Int {
        return getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            .getInt("radioBgmVolume", 65)
            .coerceIn(0, 100)
    }

    private fun getRadioVoiceVolumePercent(): Int {
        return getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            .getInt("radioVoiceVolume", 70)
            .coerceIn(0, 100)
    }

    private fun getRadioBgmVolumeFloat(): Float {
        return (getRadioBgmVolumePercent().coerceIn(0, 100) / 100f).coerceIn(0f, 1f)
    }

    private fun getRadioVoiceVolumeFloat(): Float {
        return (getRadioVoiceVolumePercent().coerceIn(0, 100) / 100f).coerceIn(0f, 1f)
    }

    private fun getRadioAutoContinueEnabled(): Boolean {
        return getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            .getBoolean("radioAutoContinue", true)
    }

    private fun getSleepTimerRemainingSeconds(): Long {
        val remainingMs = sleepTimerDeadlineMs - SystemClock.elapsedRealtime()
        return if (sleepTimerDeadlineMs == 0L || remainingMs <= 0L) 0L else (remainingMs + 999L) / 1000L
    }

    private fun setSleepTimer(minutes: Int) {
        val safeMinutes = minutes.coerceIn(1, 24 * 60)
        mainHandler.removeCallbacks(sleepTimerRunnable)
        sleepTimerDeadlineMs = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(safeMinutes.toLong())
        mainHandler.postDelayed(sleepTimerRunnable, TimeUnit.MINUTES.toMillis(safeMinutes.toLong()))
        callJs("if(window.onRadioSleepTimerChanged){window.onRadioSleepTimerChanged(${safeMinutes * 60});}")
    }

    private fun cancelSleepTimer() {
        mainHandler.removeCallbacks(sleepTimerRunnable)
        sleepTimerDeadlineMs = 0L
        callJs("if(window.onRadioSleepTimerChanged){window.onRadioSleepTimerChanged(0);}")
    }

    private fun programNameFor(type: String): String {
        return when (normalizeProgramType(type)) {
            "story" -> "故事"
            "news" -> "新闻"
            "music" -> "音乐"
            "sleep" -> "哄睡"
            "book" -> "读书"
            else -> "当前"
        }
    }

    private fun applyCurrentAudioVolumes() {
        if (isTtsSpeaking.get()) duckBgMusic() else restoreBgMusic()
        try { ttsManager?.setPlaybackVolume(getRadioVoiceVolumePercent()) } catch (_: Exception) {}
    }

    // 不改 radio.html 文件，直接往电台 WebView 注入一个音量设置浮层。
    private fun injectRadioAudioSettings() {
        callJs("""
            (function(){
              if (document.getElementById('radioVolumeFab')) return;

              try {
                PROG_NAMES.book = '读书电台';
                PROG_ICONS.book = '📚';
                var tabs = document.querySelector('.program-tabs');
                if (tabs && !document.querySelector('.program-tab[data-type="book"]')) {
                  var btn = document.createElement('button');
                  btn.className = 'program-tab';
                  btn.setAttribute('data-type', 'book');
                  btn.type = 'button';
                  btn.onclick = function(){ selectProgram(btn, 'book'); };
                  btn.innerHTML = '<span class="tab-icon">📚</span><span class="tab-name">读书</span>';
                  tabs.appendChild(btn);
                }
              } catch(e) {}

              var style = document.createElement('style');
              style.textContent = `
                #radioVolumeFab{
                  position:fixed;right:14px;top:10px;z-index:9999;
                  width:34px;height:34px;border-radius:50%;border:1px solid rgba(200,160,80,.32);
                  background:rgba(20,20,24,.92);color:#c8a050;font-size:17px;
                  display:flex;align-items:center;justify-content:center;
                  box-shadow:0 4px 14px rgba(0,0,0,.24);cursor:pointer;
                }
                #radioVolumePanel{
                  position:fixed;right:12px;top:52px;z-index:9999;width:238px;
                  background:rgba(20,20,24,.98);border:1px solid rgba(200,160,80,.22);
                  border-radius:14px;padding:12px 12px 10px;display:none;
                  box-shadow:0 12px 34px rgba(0,0,0,.42);backdrop-filter:blur(16px);
                  color:#e8e0d0;font-family:-apple-system,"PingFang SC",sans-serif;
                }
                #radioVolumePanel.show{display:block;}
                .rv-title{font-size:13px;font-weight:700;color:#e8c870;margin-bottom:10px;letter-spacing:1px;}
                .rv-row{margin:10px 0 12px;}
                .rv-label{display:flex;justify-content:space-between;align-items:center;font-size:12px;color:#b8aa8c;margin-bottom:7px;}
                .rv-label b{font-weight:600;color:#e8e0d0;}
                .rv-val{font-size:11px;color:#c8a050;}
                .rv-range{width:100%;accent-color:#c8a050;}
                .rv-tip{font-size:10px;line-height:1.5;color:#706858;margin-top:6px;}
                .rv-check{display:flex;align-items:center;gap:7px;font-size:12px;color:#e8e0d0;margin:8px 0 12px;}
                .rv-timer-controls{display:flex;gap:7px;align-items:center;}
                .rv-select{min-width:0;flex:1;border:1px solid rgba(200,160,80,.22);background:#242126;
                  color:#e8e0d0;border-radius:9px;padding:7px 8px;font-size:12px;}
                .rv-cancel{border:1px solid rgba(224,80,80,.28);background:rgba(224,80,80,.10);
                  color:#e58b8b;border-radius:9px;padding:7px 9px;font-size:12px;cursor:pointer;display:none;}
                .rv-actions{display:grid;grid-template-columns:1fr;gap:7px;margin-top:8px;}
                .rv-btn{width:100%;border:1px solid rgba(200,160,80,.22);background:rgba(200,160,80,.10);
                  color:#e8c870;border-radius:10px;padding:8px 9px;font-size:12px;text-align:left;cursor:pointer;}
                .rv-btn:active{opacity:.72;transform:scale(.98);}
                .rv-small{font-size:10px;color:#706858;margin-top:2px;}
              `;
              document.head.appendChild(style);

              var fab = document.createElement('button');
              fab.id = 'radioVolumeFab';
              fab.type = 'button';
              fab.textContent = '☊';
              document.body.appendChild(fab);

              var panel = document.createElement('div');
              panel.id = 'radioVolumePanel';
              panel.innerHTML = `
                <div class="rv-title">声音设置</div>
                <div class="rv-row">
                  <div class="rv-label"><b>背景音乐</b><span class="rv-val" id="rvBgmVal">65%</span></div>
                  <input class="rv-range" id="rvBgm" type="range" min="0" max="100" step="1" value="65">
                </div>
                <div class="rv-row">
                  <div class="rv-label"><b>人声</b><span class="rv-val" id="rvVoiceVal">70%</span></div>
                  <input class="rv-range" id="rvVoice" type="range" min="0" max="100" step="1" value="70">
                </div>
                <label class="rv-check"><input id="rvAutoContinue" type="checkbox" checked> 自动续播，直到手动停止</label>
                <div class="rv-row">
                  <div class="rv-label"><b>定时停止</b><span class="rv-val" id="rvTimerVal">未设置</span></div>
                  <div class="rv-timer-controls">
                    <select class="rv-select" id="rvTimerSelect">
                      <option value="">选择时长</option>
                      <option value="15">15 分钟</option>
                      <option value="30">30 分钟</option>
                      <option value="45">45 分钟</option>
                      <option value="60">1 小时</option>
                      <option value="90">1.5 小时</option>
                      <option value="120">2 小时</option>
                    </select>
                    <button class="rv-cancel" id="rvTimerCancel" type="button">取消</button>
                  </div>
                </div>
                <div class="rv-actions">
                  <button class="rv-btn" id="rvSwitchBgm" type="button">切换当前背景音乐</button>
                  <button class="rv-btn" id="rvReplaceBgm" type="button">更换当前栏目背景音乐</button>
                  <button class="rv-btn" id="rvAddBgm" type="button">再添加几首到当前栏目</button>
                  <button class="rv-btn" id="rvClearBgm" type="button">清空当前栏目自定义音乐</button>
                  <button class="rv-btn" id="rvImportBook" type="button">导入读书文件</button>
                  <button class="rv-btn" id="rvRestartBook" type="button">从头开始读当前书</button>
                  <button class="rv-btn" id="rvClearBook" type="button">清空读书文件</button>
                </div>
                <div class="rv-small" id="rvProgramHint">当前栏目：故事</div>
                <div class="rv-tip">调整后会自动保存；自定义音乐会优先播放，没有上传时才用内置音乐。</div>
              `;
              document.body.appendChild(panel);

              var bgm = document.getElementById('rvBgm');
              var voice = document.getElementById('rvVoice');
              var bgmVal = document.getElementById('rvBgmVal');
              var voiceVal = document.getElementById('rvVoiceVal');
              var autoContinue = document.getElementById('rvAutoContinue');
              var timerSelect = document.getElementById('rvTimerSelect');
              var timerCancel = document.getElementById('rvTimerCancel');
              var timerVal = document.getElementById('rvTimerVal');
              var switchBgm = document.getElementById('rvSwitchBgm');
              var replaceBgm = document.getElementById('rvReplaceBgm');
              var addBgm = document.getElementById('rvAddBgm');
              var clearBgm = document.getElementById('rvClearBgm');
              var importBook = document.getElementById('rvImportBook');
              var restartBook = document.getElementById('rvRestartBook');
              var clearBook = document.getElementById('rvClearBook');
              var programHint = document.getElementById('rvProgramHint');

              function clamp(v){ v = parseInt(v || 0); if(isNaN(v)) v = 0; return Math.max(0, Math.min(100, v)); }
              function refreshLabels(){
                bgmVal.textContent = clamp(bgm.value) + '%';
                voiceVal.textContent = clamp(voice.value) + '%';
              }
              function currentProgram(){
                try { return (typeof selectedProgram === 'string' && selectedProgram) ? selectedProgram : 'story'; } catch(e) { return 'story'; }
              }
              function currentProgramName(){
                var t = currentProgram();
                return t === 'news' ? '新闻' : (t === 'music' ? '音乐' : (t === 'sleep' ? '哄睡' : (t === 'book' ? '读书' : '故事')));
              }
              function refreshProgramHint(){
                if(programHint) programHint.textContent = '当前栏目：' + currentProgramName();
              }
              function save(){
                refreshLabels();
                try { Android.setRadioAudioSettings(clamp(bgm.value), clamp(voice.value)); } catch(e) {}
              }
              function saveAutoContinue(){
                try { Android.setRadioAutoContinue(!!autoContinue.checked); } catch(e) {}
              }
              function formatTimer(seconds){
                seconds = Math.max(0, parseInt(seconds || 0));
                if (!seconds) return '未设置';
                var h = Math.floor(seconds / 3600);
                var m = Math.floor((seconds % 3600) / 60);
                var s = seconds % 60;
                return (h ? h + ':' : '') + String(m).padStart(h ? 2 : 1, '0') + ':' + String(s).padStart(2, '0');
              }
              function refreshTimer(){
                var seconds = 0;
                try { seconds = Android.getRadioSleepTimerRemainingSeconds(); } catch(e) {}
                timerVal.textContent = formatTimer(seconds);
                timerCancel.style.display = seconds > 0 ? 'block' : 'none';
                if (seconds <= 0) timerSelect.value = '';
              }
              function load(){
                try {
                  var raw = Android.getRadioAudioSettings();
                  var data = JSON.parse(raw || '{}');
                  bgm.value = clamp(data.bgmVolume == null ? 65 : data.bgmVolume);
                  voice.value = clamp(data.voiceVolume == null ? 70 : data.voiceVolume);
                  if(autoContinue) autoContinue.checked = data.autoContinue !== false;
                  refreshLabels();
                  refreshProgramHint();
                } catch(e) { refreshLabels(); }
              }

              fab.onclick = function(){ panel.classList.toggle('show'); refreshProgramHint(); };
              bgm.oninput = save;
              voice.oninput = save;
              if(autoContinue) autoContinue.onchange = saveAutoContinue;
              if(timerSelect) timerSelect.onchange = function(){
                var minutes = parseInt(timerSelect.value || 0);
                if (minutes > 0) {
                  try { Android.setRadioSleepTimer(minutes); } catch(e) {}
                  timerSelect.value = '';
                  refreshTimer();
                }
              };
              if(timerCancel) timerCancel.onclick = function(){
                try { Android.cancelRadioSleepTimer(); } catch(e) {}
                refreshTimer();
              };
              switchBgm.onclick = function(){ try { Android.switchRadioBgm(currentProgram()); } catch(e) {} };
              replaceBgm.onclick = function(){ try { Android.replaceRadioBgm(currentProgram()); } catch(e) {} };
              addBgm.onclick = function(){ try { Android.addRadioBgm(currentProgram()); } catch(e) {} };
              clearBgm.onclick = function(){ try { Android.clearRadioBgm(currentProgram()); } catch(e) {} };
              if(importBook) importBook.onclick = function(){ try { Android.importRadioBook(); } catch(e) {} };
              if(restartBook) restartBook.onclick = function(){ try { Android.restartRadioBook(); } catch(e) {} };
              if(clearBook) clearBook.onclick = function(){ try { Android.clearRadioBook(); } catch(e) {} };
              window.refreshRadioAudioSettings = load;
              window.refreshRadioBgmProgramHint = refreshProgramHint;
              window.onRadioSleepTimerChanged = refreshTimer;
              setInterval(refreshProgramHint, 800);
              setInterval(refreshTimer, 1000);
              load();
              refreshTimer();
            })();
        """.trimIndent())
    }
    private fun avatarToWebSrc(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        if (s.startsWith("data:image", ignoreCase = true)) return s
        if (s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)) return s
        return uriToBase64DataUri(s)
    }

    private fun uriToBase64DataUri(uriStr: String): String {
        if (uriStr.isBlank()) return ""
        return try {
            val rawBmp: android.graphics.Bitmap? = when {
                uriStr.startsWith("/") -> android.graphics.BitmapFactory.decodeFile(uriStr)
                uriStr.startsWith("file://") -> android.graphics.BitmapFactory.decodeFile(uriStr.removePrefix("file://"))
                uriStr.startsWith("content://") -> contentResolver.openInputStream(android.net.Uri.parse(uriStr))?.use { input ->
                    android.graphics.BitmapFactory.decodeStream(input)
                }
                else -> {
                    android.graphics.BitmapFactory.decodeFile(uriStr)
                        ?: contentResolver.openInputStream(android.net.Uri.parse(uriStr))?.use { input ->
                            android.graphics.BitmapFactory.decodeStream(input)
                        }
                }
            }

            val bmp: android.graphics.Bitmap = rawBmp ?: return ""

            val maxSize = 500
            val scale = minOf(
                maxSize.toFloat() / bmp.width.toFloat(),
                maxSize.toFloat() / bmp.height.toFloat(),
                1f
            )
            val scaled: android.graphics.Bitmap = if (scale < 1f) {
                android.graphics.Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bmp
            }

            val baos = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, baos)
            "data:image/jpeg;base64," + android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }

    private fun removeRadioEmojiFromWebView() {
        callJs("""
            (function(){
                function clean(node){
                    if(!node) return;
                    if(node.nodeType === 3){
                        if((node.nodeValue || '').trim() === '📻') node.nodeValue = '';
                        return;
                    }
                    if(node.childNodes){
                        Array.prototype.forEach.call(node.childNodes, clean);
                    }
                }
                clean(document.body);
            })();
        """.trimIndent())
    }


    // ── 初始加载：联系人+电台标题 ────────────────────────────
    private fun loadInitialData() {
        thread {
            try {
                val db = DatabaseHelper(this).readableDatabase
                val arr = JSONArray()
                db.rawQuery(
                    "SELECT c.userId, c.realName, c.avatarUri, IFNULL(r.stationTitle,''), IFNULL(r.playCount,0) FROM Contacts c LEFT JOIN RadioStation r ON c.userId=r.aiId ORDER BY c.id ASC",
                    null
                ).use { c ->
                    while (c.moveToNext()) {
                        val rawAvatar = c.getString(2) ?: ""
                        arr.put(JSONObject().apply {
                            put("id", c.getString(0) ?: "")
                            put("name", c.getString(1) ?: "未知")
                            // WebView 的 file:///android_asset 页面直接吃 content:// 或本地绝对路径很容易裂图。
                            // 这里统一转成 data:image/base64，前端 <img src="..."> 就能稳定显示。
                            put("avatar", avatarToWebSrc(rawAvatar))
                            put("stationTitle", c.getString(3) ?: "")
                            put("playCount", c.getInt(4))
                        })
                    }
                }
                callJs("setContacts(${jsStr(arr.toString())})")

                // 对没有电台标题的角色，自动生成
                val needGenerate = mutableListOf<Triple<String, String, String>>() // id, name, persona
                db.rawQuery(
                    "SELECT c.userId, c.realName, c.identityInfo FROM Contacts c LEFT JOIN RadioStation r ON c.userId=r.aiId WHERE r.stationTitle IS NULL OR r.stationTitle=''",
                    null
                ).use { c ->
                    while (c.moveToNext()) {
                        needGenerate.add(Triple(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: ""))
                    }
                }

                if (needGenerate.isNotEmpty()) {
                    generateStationTitles(needGenerate)
                }
            } catch (_: Exception) {}
        }
    }

    // ── 批量生成电台标题 ─────────────────────────────────────
    private fun generateStationTitles(list: List<Triple<String, String, String>>) {
        val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        var apiUrl = pref.getString("apiUrl", "") ?: ""
        val apiKey = pref.getString("apiKey", "") ?: ""
        val model = pref.getString("modelName", "") ?: ""
        if (apiUrl.isEmpty() || apiKey.isEmpty()) return
        while (apiUrl.endsWith("/")) apiUrl = apiUrl.dropLast(1)
        if (!apiUrl.endsWith("/chat/completions"))
            apiUrl += if (apiUrl.contains("/v1")) "/chat/completions" else "/v1/chat/completions"
        val finalUrl = apiUrl

        thread {
            for ((aiId, aiName, persona) in list) {
                try {
                    val prompt = """
你是 $aiName，人设：${persona.take(300)}。
请为你的个人电台起一个独特的名字（6-12字），风格要符合你的人设气质。
只输出电台名称，不要任何解释。
例如：「深夜信号站」「午夜频率」「碎片电波」「暗夜广播局」
                    """.trimIndent()

                    val body = JSONObject().apply {
                        put("model", model)
                        put("temperature", 0.9)
                        put("max_tokens", 30)
                        put("messages", JSONArray().apply {
                            put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                        })
                    }.toString().toRequestBody("application/json".toMediaTypeOrNull())

                    val resp = Http.client.newBuilder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build()
                        .newCall(Request.Builder().url(finalUrl).addHeader("Authorization", "Bearer $apiKey").post(body).build())
                        .execute()

                    val title = JSONObject(resp.body?.string() ?: continue)
                        .getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim()
                        .replace("「", "").replace("」", "").replace("\"", "").trim()

                    if (title.isNotEmpty()) {
                        DatabaseHelper(this).writableDatabase.execSQL(
                            "INSERT OR REPLACE INTO RadioStation(aiId, stationTitle, playCount) VALUES(?,?,0)",
                            arrayOf(aiId, title)
                        )
                        callJs("updateStationTitle(${jsStr(aiId)}, ${jsStr(title)})")
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // ── 搜索真实新闻 ─────────────────────────────────────────
    private fun fetchRealNews(): String {
        val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val searchKey = pref.getString("radioSearchKey", "") ?: ""
        val searchType = pref.getString("radioSearchType", "serper") ?: "serper"
        if (searchKey.isEmpty()) return ""

        return try {
            when (searchType) {
                "serper" -> {
                    val body = JSONObject().apply {
                        put("q", "今日新闻 最新")
                        put("num", 5)
                        put("gl", "cn")
                        put("hl", "zh-cn")
                    }.toString().toRequestBody("application/json".toMediaTypeOrNull())
                    val resp = Http.client.newBuilder()
                        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
                        .newCall(Request.Builder().url("https://google.serper.dev/news")
                            .addHeader("X-API-KEY", searchKey)
                            .addHeader("Content-Type", "application/json")
                            .post(body).build()).execute()
                    val json = JSONObject(resp.body?.string() ?: return "")
                    val news = json.optJSONArray("news") ?: return ""
                    val sb = StringBuilder()
                    for (i in 0 until minOf(news.length(), 5)) {
                        val item = news.getJSONObject(i)
                        sb.append("・${item.optString("title")}\n")
                    }
                    sb.toString().trim()
                }
                "bing" -> {
                    val resp = Http.client.newBuilder()
                        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
                        .newCall(Request.Builder()
                            .url("https://api.bing.microsoft.com/v7.0/news/search?q=今日新闻&count=5&mkt=zh-CN")
                            .addHeader("Ocp-Apim-Subscription-Key", searchKey)
                            .build()).execute()
                    val json = JSONObject(resp.body?.string() ?: return "")
                    val articles = json.optJSONArray("value") ?: return ""
                    val sb = StringBuilder()
                    for (i in 0 until minOf(articles.length(), 5)) {
                        val item = articles.getJSONObject(i)
                        sb.append("・${item.optString("name")}\n")
                    }
                    sb.toString().trim()
                }
                "tavily" -> {
                    val body = JSONObject().apply {
                        put("api_key", searchKey)
                        put("query", "今日最新新闻")
                        put("search_depth", "basic")
                        put("max_results", 5)
                        put("topic", "news")
                    }.toString().toRequestBody("application/json".toMediaTypeOrNull())
                    val resp = Http.client.newBuilder()
                        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
                        .newCall(Request.Builder().url("https://api.tavily.com/search")
                            .addHeader("Content-Type", "application/json")
                            .post(body).build()).execute()
                    val json = JSONObject(resp.body?.string() ?: return "")
                    val results = json.optJSONArray("results") ?: return ""
                    val sb = StringBuilder()
                    for (i in 0 until minOf(results.length(), 5)) {
                        val item = results.getJSONObject(i)
                        sb.append("・${item.optString("title")}: ${item.optString("content").take(60)}\n")
                    }
                    sb.toString().trim()
                }
                else -> ""
            }
        } catch (_: Exception) { "" }
    }

    // ── 搜索歌曲ID（网易云）────────────────────────────────
    private fun searchSongId(query: String): String? {
        return try {
            val keyword = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://music.163.com/api/search/get?s=$keyword&type=1&limit=1"
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("Referer", "https://music.163.com/")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            val resp = conn.inputStream.bufferedReader().readText(); conn.disconnect()
            val songs = JSONObject(resp).optJSONObject("result")?.optJSONArray("songs")
            if (songs != null && songs.length() > 0) songs.getJSONObject(0).optString("id")
            else null
        } catch (_: Exception) { null }
    }

    // ── 播放网易云歌曲 ───────────────────────────────────────
    private fun playSong(songId: String, onDone: () -> Unit) {
        stopSong()
        val songUrl = "https://music.163.com/song/media/outer/url?id=$songId.mp3"
        thread {
            try {
                mainHandler.post {
                    try {
                        val mp = MediaPlayer()
                        mp.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        mp.setDataSource(songUrl)
                        mp.setOnPreparedListener { player ->
                            // 网易云对无版权歌曲返回一个极短的占位音频（通常 < 3秒），检测并跳过。
                            if (player.duration in 1..4999) {
                                isSongPlaying.set(false)
                                try { player.reset(); player.release() } catch (_: Exception) {}
                                if (songPlayer === player) songPlayer = null
                                callJs("onSongError()")
                                onDone()
                            } else {
                                player.start()
                                isSongPlaying.set(true)
                            }
                        }
                        mp.setOnCompletionListener {
                            isSongPlaying.set(false)
                            try { it.reset(); it.release() } catch (_: Exception) {}
                            if (songPlayer === it) songPlayer = null
                            onDone()
                        }
                        mp.setOnErrorListener { p, _, _ ->
                            // 歌曲播放失败，跳过继续
                            isSongPlaying.set(false)
                            try { p.reset(); p.release() } catch (_: Exception) {}
                            if (songPlayer === p) songPlayer = null
                            callJs("onSongError()")
                            onDone()
                            true
                        }
                        songPlayer = mp
                        mp.prepareAsync()
                        callJs("onSongStart(${jsStr(songId)})")
                    } catch (_: Exception) { onDone() }
                }
            } catch (_: Exception) { mainHandler.post { onDone() } }
        }
    }

    private fun stopSong() {
        try {
            songPlayer?.let { if (it.isPlaying) it.stop(); it.reset(); it.release() }
        } catch (_: Exception) {}
        songPlayer = null
        isSongPlaying.set(false)
    }

    // ── 停止一切 ─────────────────────────────────────────────
    private fun stopAll() {
        programGenerationSeq.incrementAndGet()
        shouldStop.set(true)
        isPlaying.set(false)
        clearTtsTimers()
        stopTts()
        stopSong()
        stopBgMusic()
        segmentQueue.clear()
        currentSegmentIndex = 0
    }

    private fun stopTts() {
        try {
            ttsManager?.stop()
        } catch (_: Exception) {}
        try {
            ttsPlayer?.let { if (it.isPlaying) it.stop(); it.reset(); it.release() }
        } catch (_: Exception) {}
        ttsPlayer = null
        isTtsSpeaking.set(false)
    }

    private fun stopBgMusic() {
        try {
            bgPlayer?.let {
                if (it.isPlaying) it.stop()
                it.reset()
                it.release()
            }
        } catch (_: Exception) {}
        bgPlayer = null
        currentBgAssetPath = ""
    }

    private fun normalizeProgramType(programType: String): String {
        return when (programType) {
            "story", "news", "music", "sleep", "book" -> programType
            else -> "story"
        }
    }

    private fun customBgmDir(programType: String): File {
        val safeType = normalizeProgramType(programType)
        return File(filesDir, "radio_bgm_custom/$safeType").apply { mkdirs() }
    }

    private fun isAudioFileName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".mp3") || lower.endsWith(".wav") ||
                lower.endsWith(".m4a") || lower.endsWith(".ogg")
    }

    private fun clearCustomBgmFiles(programType: String): Int {
        val dir = customBgmDir(programType)
        var count = 0
        dir.listFiles()?.forEach { f ->
            try {
                if (f.isFile && f.delete()) count++
            } catch (_: Exception) {}
        }
        return count
    }

    private fun guessAudioExtension(uri: Uri): String {
        val type = try { contentResolver.getType(uri) ?: "" } catch (_: Exception) { "" }
        val byType = when {
            type.contains("mpeg", ignoreCase = true) || type.contains("mp3", ignoreCase = true) -> "mp3"
            type.contains("mp4", ignoreCase = true) || type.contains("m4a", ignoreCase = true) -> "m4a"
            type.contains("wav", ignoreCase = true) -> "wav"
            type.contains("ogg", ignoreCase = true) -> "ogg"
            else -> ""
        }
        if (byType.isNotEmpty()) return byType
        val last = uri.lastPathSegment ?: return "mp3"
        val name = last.substringAfterLast('/', last).substringAfterLast(':', last)
        val ext = name.substringAfterLast('.', "").lowercase()
        return if (ext in setOf("mp3", "m4a", "wav", "ogg")) ext else "mp3"
    }

    private fun importUserBgms(programTypeRaw: String, uris: List<Uri>, replace: Boolean) {
        val programType = normalizeProgramType(programTypeRaw)
        thread {
            var imported = 0
            try {
                if (replace) clearCustomBgmFiles(programType)
                val dir = customBgmDir(programType)
                uris.forEachIndexed { index, uri ->
                    try {
                        val ext = guessAudioExtension(uri)
                        val outFile = File(dir, "custom_${System.currentTimeMillis()}_${index}.$ext")
                        contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(outFile, false).use { output -> input.copyTo(output) }
                        }
                        if (outFile.exists() && outFile.length() > 0L) {
                            imported++
                        } else {
                            try { outFile.delete() } catch (_: Exception) {}
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("RadioBGM", "导入用户背景音乐失败: ${e.message}", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("RadioBGM", "导入用户背景音乐异常: ${e.message}", e)
            }

            mainHandler.post {
                if (imported > 0) {
                    Toast.makeText(this, "已为${programNameFor(programType)}栏目导入 ${imported} 首背景音乐", Toast.LENGTH_SHORT).show()
                    callJs("setStatus(${jsStr("已更换${programNameFor(programType)}栏目背景音乐")})")
                    if (isPlaying.get() && normalizeProgramType(currentProgramType) == programType) {
                        stopBgMusic()
                        startBgMusic(programType)
                    }
                } else {
                    Toast.makeText(this, "没有导入成功，换一个音频文件试试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private data class BgTrack(val key: String, val file: File? = null, val assetPath: String? = null)

    private fun shouldUseBgMusic(programType: String): Boolean {
        // 四个栏目都允许垫背景音乐。
        // music 栏目在主播说介绍词时垫底；真正播放歌曲时会自动暂停垫底 BGM，避免两层音乐打架。
        return programType == "story" || programType == "sleep" || programType == "news" || programType == "music" || programType == "book"
    }

    private fun bgNormalVolume(programType: String = currentProgramType): Float {
        // 背景音乐由用户滑杆直接控制：0%~100%。
        return getRadioBgmVolumeFloat()
    }

    private fun bgDuckVolume(programType: String = currentProgramType): Float {
        // 角色说话时背景音乐自动压低，但仍按用户设置保留可听见的底噪。
        return (getRadioBgmVolumeFloat() * 0.45f).coerceIn(0f, 1f)
    }

    private fun setBgVolume(v: Float) {
        try { bgPlayer?.setVolume(v, v) } catch (_: Exception) {}
    }

    private fun duckBgMusic() {
        setBgVolume(bgDuckVolume())
    }

    private fun restoreBgMusic() {
        setBgVolume(bgNormalVolume())
    }

    private fun ensureBgMusicRunning() {
        if (!shouldUseBgMusic(currentProgramType)) return
        if (shouldStop.get() || !isPlaying.get()) return
        if (bgPlayer == null) startBgMusic(currentProgramType)
    }

    private fun bgFolderFor(programType: String): String {
        return when (programType) {
            "sleep" -> "radio_bgm/sleep"
            "news" -> "radio_bgm/news"
            "music" -> "radio_bgm/music"
            "book" -> "radio_bgm/book"
            else -> "radio_bgm/story"
        }
    }

    private fun legacyBgAssetName(programType: String): String {
        // 兼容你以前直接放在 assets 根目录的单首背景音乐。
        return when (programType) {
            "sleep" -> "radio_sleep_bg.mp3"
            "news" -> "radio_news_bg.mp3"
            "music" -> "radio_music_bg.mp3"
            "book" -> "radio_book_bg.mp3"
            else -> "radio_story_bg.mp3"
        }
    }

    private fun isAudioAsset(name: String): Boolean {
        return isAudioFileName(name)
    }

    private fun customBgCandidates(programType: String): List<BgTrack> {
        return try {
            customBgmDir(programType).listFiles()
                ?.filter { it.isFile && it.length() > 0L && isAudioFileName(it.name) }
                ?.sortedBy { it.name }
                ?.map { BgTrack(key = "custom:${it.absolutePath}", file = it) }
                .orEmpty()
        } catch (e: Exception) {
            android.util.Log.e("RadioBGM", "读取用户背景音乐失败: ${e.message}", e)
            emptyList()
        }
    }

    private fun assetBgCandidates(programType: String): List<BgTrack> {
        val folder = bgFolderFor(programType)
        return try {
            assets.list(folder)
                ?.toList()
                .orEmpty()
                .filter { isAudioAsset(it) }
                .map { fileName -> "$folder/$fileName" }
                .map { assetPath -> BgTrack(key = "asset:$assetPath", assetPath = assetPath) }
        } catch (e: Exception) {
            android.util.Log.e("RadioBGM", "读取背景音乐目录失败: $folder / ${e.message}", e)
            emptyList()
        }
    }

    private fun bgCandidates(programType: String): List<BgTrack> {
        val custom = customBgCandidates(programType)
        if (custom.isNotEmpty()) return custom

        val assetsList = assetBgCandidates(programType)
        if (assetsList.isNotEmpty()) return assetsList

        // 如果新文件夹里没放歌，就退回旧写法：assets/radio_story_bg.mp3 这种。
        val legacy = legacyBgAssetName(programType)
        android.util.Log.w("RadioBGM", "${bgFolderFor(programType)} 里没有可播放音频，尝试旧路径 $legacy")
        return listOf(BgTrack(key = "asset:$legacy", assetPath = legacy))
    }

    private fun pickRandomBgTrack(programType: String, avoidKey: String = ""): BgTrack {
        val candidates = bgCandidates(programType).toMutableList()
        val pool = if (candidates.size > 1) {
            candidates.filter { it.key != avoidKey }.ifEmpty { candidates }
        } else {
            candidates
        }
        return pool[Random.nextInt(pool.size)]
    }

    private fun copyAssetToCache(assetPath: String): File {
        val safeName = assetPath.replace("/", "_").replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val outFile = File(cacheDir, "radio_bgm_$safeName")

        // 重新覆盖一次，避免用户换了同名音乐但缓存还拿旧文件。
        assets.open(assetPath).use { input ->
            FileOutputStream(outFile, false).use { output ->
                input.copyTo(output)
            }
        }
        return outFile
    }

    private fun prepareBgFile(track: BgTrack): File {
        track.file?.let { return it }
        val assetPath = track.assetPath ?: throw IllegalArgumentException("背景音乐路径为空")
        return copyAssetToCache(assetPath)
    }

    // ── 背景音调度：优先播放用户上传音乐；没有上传时播放 assets 内置音乐。播完继续随机下一首。 ──
    private fun startBgMusic(programType: String, avoidAssetPath: String = "") {
        if (!shouldUseBgMusic(programType)) return

        val track = pickRandomBgTrack(programType, avoidAssetPath)

        // MediaPlayer 尽量在主线程创建，避免 prepared 回调不稳定。
        mainHandler.post {
            try {
                if (shouldStop.get() || !isPlaying.get()) return@post

                stopBgMusic()

                val bgFile = prepareBgFile(track)
                if (!bgFile.exists() || bgFile.length() <= 0L) {
                    android.util.Log.e("RadioBGM", "背景音乐文件无效: ${bgFile.absolutePath}")
                    startGeneratedBgMusic(programType)
                    return@post
                }

                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )

                mp.setDataSource(bgFile.absolutePath)

                currentBgAssetPath = track.key
                val normal = bgNormalVolume(programType)
                mp.setVolume(normal, normal)

                mp.setOnPreparedListener { player ->
                    try {
                        if (!shouldStop.get() && isPlaying.get()) {
                            android.util.Log.d("RadioBGM", "开始播放背景音乐: ${track.key}")
                            player.start()
                            if (isTtsSpeaking.get()) duckBgMusic() else restoreBgMusic()
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("RadioBGM", "背景音乐 start 失败: ${e.message}", e)
                    }
                }

                mp.setOnCompletionListener { player ->
                    val finishedKey = currentBgAssetPath
                    try {
                        player.reset()
                        player.release()
                    } catch (_: Exception) {}

                    if (bgPlayer === player) {
                        bgPlayer = null
                        currentBgAssetPath = ""
                    }

                    if (!shouldStop.get() && isPlaying.get() && shouldUseBgMusic(programType)) {
                        startBgMusic(programType, finishedKey)
                    }
                }

                mp.setOnErrorListener { player, what, extra ->
                    val failedKey = currentBgAssetPath
                    android.util.Log.e("RadioBGM", "背景音乐播放失败: $failedKey / what=$what extra=$extra")
                    try {
                        player.reset()
                        player.release()
                    } catch (_: Exception) {}

                    if (bgPlayer === player) {
                        bgPlayer = null
                        currentBgAssetPath = ""
                    }

                    // 随机音乐失败时，先尝试换一首；还是没有就走兜底音。
                    try {
                        val nextTrack = pickRandomBgTrack(programType, failedKey)
                        if (nextTrack.key != failedKey) {
                            startBgMusic(programType, failedKey)
                        } else {
                            startGeneratedBgMusic(programType)
                        }
                    } catch (_: Exception) {
                        startGeneratedBgMusic(programType)
                    }
                    true
                }

                bgPlayer = mp
                mp.prepareAsync()
            } catch (e: Exception) {
                android.util.Log.e("RadioBGM", "背景音乐启动异常: ${track.key} / ${e.message}", e)
                startGeneratedBgMusic(programType)
            }
        }
    }

    // ── 手动切换背景音乐：当前栏目有多首时，尽量避开正在播放的那首 ──
    private fun switchBgMusicNow(programType: String = currentProgramType) {
        val type = normalizeProgramType(programType)
        mainHandler.post {
            try {
                if (!isPlaying.get() || shouldStop.get()) {
                    callJs("setStatus(${jsStr("开播后才能切换背景音乐")})")
                    return@post
                }
                if (!shouldUseBgMusic(type)) {
                    callJs("setStatus(${jsStr("当前栏目没有背景音乐")})")
                    return@post
                }

                val candidates = bgCandidates(type)
                if (candidates.isEmpty()) {
                    callJs("setStatus(${jsStr("当前栏目没有可切换的背景音乐")})")
                    return@post
                }

                val oldKey = currentBgAssetPath
                stopBgMusic()
                startBgMusic(type, oldKey)
                callJs("setStatus(${jsStr("已切换${programNameFor(type)}栏目背景音乐")})")
            } catch (e: Exception) {
                android.util.Log.e("RadioBGM", "手动切换背景音乐失败: ${e.message}", e)
                callJs("setStatus(${jsStr("切换背景音乐失败，换一首音频试试")})")
            }
        }
    }

    // assets 没有背景音乐时的兜底背景音。
    // 不是好听的歌，只是为了避免电台死寂，同时不影响角色声音。
    private fun startGeneratedBgMusic(programType: String) {
        // 音乐栏目的主内容本身就是歌曲，不需要合成背景音衬底（否则听起来像噪音）。
        if (programType == "music") return
        thread {
            try {
                val f = File(cacheDir, "radio_${programType}_fallback_bg.wav")
                if (!f.exists() || f.length() < 1000L) createFallbackWav(f, programType)
                mainHandler.post {
                    try {
                        if (shouldStop.get()) return@post
                        stopBgMusic()
                        val mp = MediaPlayer()
                        mp.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        mp.setDataSource(f.absolutePath)
                        mp.isLooping = true
                        val normal = bgNormalVolume(programType)
                        mp.setVolume(normal, normal)
                        mp.setOnPreparedListener { it.start() }
                        mp.setOnErrorListener { p, _, _ ->
                            try { p.reset(); p.release() } catch (_: Exception) {}
                            if (bgPlayer === p) bgPlayer = null
                            true
                        }
                        bgPlayer = mp
                        mp.prepareAsync()
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    private fun createFallbackWav(file: File, programType: String) {
        val sampleRate = 22050
        val seconds = 24
        val totalSamples = sampleRate * seconds
        val dataSize = totalSamples * 2
        val baseFreq = when (programType) {
            "sleep" -> 174.0
            "news" -> 196.0
            "book" -> 185.0
            else -> 220.0
        }

        fun FileOutputStream.writeAscii(v: String) = write(v.toByteArray(Charsets.US_ASCII))
        fun FileOutputStream.writeLE16(v: Int) {
            write(byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte()))
        }
        fun FileOutputStream.writeLE32(v: Int) {
            write(byteArrayOf(
                (v and 0xff).toByte(),
                ((v shr 8) and 0xff).toByte(),
                ((v shr 16) and 0xff).toByte(),
                ((v shr 24) and 0xff).toByte()
            ))
        }

        FileOutputStream(file).use { out ->
            out.writeAscii("RIFF")
            out.writeLE32(36 + dataSize)
            out.writeAscii("WAVE")
            out.writeAscii("fmt ")
            out.writeLE32(16)
            out.writeLE16(1)       // PCM
            out.writeLE16(1)       // mono
            out.writeLE32(sampleRate)
            out.writeLE32(sampleRate * 2)
            out.writeLE16(2)
            out.writeLE16(16)
            out.writeAscii("data")
            out.writeLE32(dataSize)

            for (i in 0 until totalSamples) {
                val t = i.toDouble() / sampleRate.toDouble()
                val lfo = 0.55 + 0.45 * kotlin.math.sin(2.0 * kotlin.math.PI * 0.08 * t)
                val wave = kotlin.math.sin(2.0 * kotlin.math.PI * baseFreq * t) * 0.55 +
                        kotlin.math.sin(2.0 * kotlin.math.PI * baseFreq * 1.5 * t) * 0.25 +
                        kotlin.math.sin(2.0 * kotlin.math.PI * baseFreq * 0.5 * t) * 0.20
                val sample = (wave * lfo * 0.22 * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out.writeLE16(sample)
            }
        }
    }


    // ── 读书栏目：导入 txt / md / html / htm / epub，解析成纯文本后由当前角色朗读 ──
    private fun radioBookDir(): File {
        return File(filesDir, "radio_books").apply { mkdirs() }
    }

    private fun radioBookTextFile(): File = File(radioBookDir(), "current_book.txt")
    private fun radioBookTitleFile(): File = File(radioBookDir(), "current_book_title.txt")

    private fun getBookProgress(): Int {
        return getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            .getInt("radioBookProgress", 0)
            .coerceAtLeast(0)
    }

    private fun saveBookProgress(value: Int) {
        getSharedPreferences("AppConfig", Context.MODE_PRIVATE).edit()
            .putInt("radioBookProgress", value.coerceAtLeast(0))
            .apply()
    }

    private fun getCurrentBookTitle(): String {
        return try { radioBookTitleFile().takeIf { it.exists() }?.readText()?.trim().orEmpty() } catch (_: Exception) { "" }
    }

    private fun getCurrentBookText(): String {
        return try { radioBookTextFile().takeIf { it.exists() }?.readText()?.trim().orEmpty() } catch (_: Exception) { "" }
    }

    private fun clearCurrentBookFiles() {
        try { radioBookTextFile().delete() } catch (_: Exception) {}
        try { radioBookTitleFile().delete() } catch (_: Exception) {}
        saveBookProgress(0)
    }

    private fun getDisplayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) ?: "" else ""
            }.orEmpty().ifBlank { uri.lastPathSegment ?: "book" }
        } catch (_: Exception) {
            uri.lastPathSegment ?: "book"
        }
    }

    private fun guessBookExtension(uri: Uri, displayName: String): String {
        val nameExt = displayName.substringAfterLast('.', "").lowercase()
        if (nameExt in setOf("txt", "md", "html", "htm", "epub")) return nameExt
        val type = try { contentResolver.getType(uri) ?: "" } catch (_: Exception) { "" }
        return when {
            type.contains("epub", ignoreCase = true) -> "epub"
            type.contains("html", ignoreCase = true) -> "html"
            type.startsWith("text/", ignoreCase = true) -> "txt"
            else -> nameExt
        }
    }

    private fun decodeTextBytes(bytes: ByteArray): String {
        return try {
            bytes.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            try { bytes.toString(Charset.forName("GB18030")) } catch (_: Exception) { bytes.toString(Charsets.UTF_8) }
        }
    }

    private fun stripHtml(raw: String): String {
        return raw
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</div>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
    }

    private fun normalizeBookText(raw: String): String {
        return raw
            .replace("\u0000", "")
            .replace(Regex("[\\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .replace(Regex("[ ]{2,}"), " ")
            .trim()
            .take(maxBookChars)
    }

    private fun parseEpubBytes(bytes: ByteArray): String {
        val sb = StringBuilder()
        ZipInputStream(bytes.inputStream()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name.lowercase()
                if (!entry.isDirectory && (name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm"))) {
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = zis.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        if (sb.length + out.size() > maxBookChars + 50_000) break
                    }
                    sb.append(stripHtml(decodeTextBytes(out.toByteArray()))).append("\n\n")
                    if (sb.length >= maxBookChars) break
                }
                zis.closeEntry()
            }
        }
        return sb.toString()
    }

    private fun parseBookText(uri: Uri, ext: String): String {
        val bytes = contentResolver.openInputStream(uri)?.use { input -> input.readBytes() } ?: ByteArray(0)
        if (bytes.isEmpty()) return ""
        val raw = when (ext) {
            "epub" -> parseEpubBytes(bytes)
            "html", "htm" -> stripHtml(decodeTextBytes(bytes))
            "txt", "md" -> decodeTextBytes(bytes)
            else -> ""
        }
        return normalizeBookText(raw)
    }

    private fun importRadioBook(uri: Uri) {
        thread {
            try {
                val title = getDisplayName(uri).ifBlank { "未命名电子书" }
                val ext = guessBookExtension(uri, title)
                if (ext !in setOf("txt", "md", "html", "htm", "epub")) {
                    mainHandler.post {
                        Toast.makeText(this, "暂时只支持 txt / md / html / htm / epub", Toast.LENGTH_LONG).show()
                        callJs("setStatus(${jsStr("这个格式暂时不能读，请换 txt、epub 或 html")})")
                    }
                    return@thread
                }

                val text = parseBookText(uri, ext)
                if (text.isBlank()) {
                    mainHandler.post {
                        Toast.makeText(this, "没有解析到正文，换一个文件试试", Toast.LENGTH_LONG).show()
                        callJs("setStatus(${jsStr("没有解析到电子书正文")})")
                    }
                    return@thread
                }

                radioBookTextFile().writeText(text)
                radioBookTitleFile().writeText(title)
                saveBookProgress(0)

                mainHandler.post {
                    Toast.makeText(this, "已导入《$title》", Toast.LENGTH_SHORT).show()
                    callJs("setStatus(${jsStr("已导入《$title》，切到读书栏目即可开播")})")
                    if (isPlaying.get() && currentProgramType == "book") {
                        stopAll()
                        val voiceId = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                            .getString("voiceId_$currentAiId", "") ?: ""
                        callJs("onGenerating()")
                        startBookProgram(currentAiId, currentAiName, voiceId)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("RadioBook", "导入电子书失败: ${e.message}", e)
                mainHandler.post {
                    Toast.makeText(this, "导入失败，换一个文件试试", Toast.LENGTH_LONG).show()
                    callJs("setStatus(${jsStr("导入电子书失败")})")
                }
            }
        }
    }

    private fun findBookSegmentEnd(text: String, start: Int): Int {
        val len = text.length
        if (start >= len) return len
        val maxEnd = (start + 90).coerceAtMost(len)
        val minEnd = (start + 35).coerceAtMost(maxEnd)
        val punct = setOf('。', '！', '？', '；', '.', '!', '?', ';')
        var best = -1
        for (i in minEnd until maxEnd) {
            if (text[i] in punct) best = i + 1
        }
        if (best > start) return best
        // No punctuation found — walk back from maxEnd to avoid cutting mid-word
        var wordEnd = maxEnd
        while (wordEnd > minEnd && !text[wordEnd - 1].isWhitespace()) wordEnd--
        return if (wordEnd > start) wordEnd else maxEnd
    }

    private fun buildBookSegments(text: String, startRaw: Int, maxSegments: Int = 10): List<Segment> {
        val result = mutableListOf<Segment>()
        var pos = startRaw.coerceIn(0, text.length)
        while (pos < text.length && result.size < maxSegments) {
            while (pos < text.length && text[pos].isWhitespace()) pos++
            if (pos >= text.length) break
            val end = findBookSegmentEnd(text, pos).coerceIn(pos + 1, text.length)
            val part = text.substring(pos, end).trim()
            if (part.isNotBlank()) result.add(Segment(part, null, end))
            pos = end
        }
        return result
    }

    private fun startBookProgram(aiId: String, aiName: String, voiceId: String, autoContinue: Boolean = false) {
        val generationId = programGenerationSeq.incrementAndGet()
        currentProgramType = "book"
        currentVoiceId = voiceId
        currentAiId = aiId
        currentAiName = aiName
        if (!autoContinue) shouldStop.set(false)

        thread {
            try {
                val bookText = getCurrentBookText()
                val bookTitle = getCurrentBookTitle().ifBlank { "当前电子书" }
                if (bookText.isBlank()) {
                    mainHandler.post {
                        isPlaying.set(false)
                        Toast.makeText(this, "请先在声音设置里导入电子书", Toast.LENGTH_LONG).show()
                        callJs("onError(${jsStr("请先导入一本 txt / epub / html 电子书")})")
                    }
                    return@thread
                }

                var progress = getBookProgress().coerceIn(0, bookText.length)
                if (progress >= bookText.length) {
                    progress = 0
                    saveBookProgress(0)
                }

                val bookSegments = buildBookSegments(bookText, progress)
                if (shouldStop.get() || generationId != programGenerationSeq.get()) return@thread
                if (bookSegments.isEmpty()) {
                    mainHandler.post { callJs("onError(${jsStr("没有可朗读的正文")})") }
                    return@thread
                }

                segmentQueue.clear()
                segmentQueue.addAll(bookSegments)
                currentSegmentIndex = 0
                shouldStop.set(false)
                isPlaying.set(true)

                val subtitles = JSONArray()
                bookSegments.forEach { seg ->
                    subtitles.put(JSONObject().apply {
                        put("text", seg.text)
                        put("trans", "")
                        put("hasMusic", false)
                    })
                }

                mainHandler.post {
                    callJs("onProgramReady(${jsStr(subtitles.toString())})")
                    callJs("setStatus(${jsStr("正在读《$bookTitle》")})")
                    startBgMusic("book")
                    playNextSegment()
                }
            } catch (e: Exception) {
                android.util.Log.e("RadioBook", "读书栏目启动失败: ${e.message}", e)
                mainHandler.post { callJs("onError(${jsStr(e.message ?: "读书栏目启动失败")})") }
            }
        }
    }

    // ── 生成节目 ─────────────────────────────────────────────
    private fun generateProgram(aiId: String, aiName: String, programType: String, voiceId: String, autoContinue: Boolean = false) {
        val generationId = programGenerationSeq.incrementAndGet()
        currentProgramType = normalizeProgramType(programType)
        currentVoiceId = voiceId
        currentAiId = aiId
        currentAiName = aiName
        if (!autoContinue) shouldStop.set(false)

        val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        var apiUrl = pref.getString("apiUrl", "") ?: ""
        val apiKey = pref.getString("apiKey", "") ?: ""
        val model = pref.getString("modelName", "") ?: ""
        val aiLang = pref.getString("aiLang_$aiId", "默认 (中文)") ?: "默认 (中文)"
        currentAiLang = aiLang
        val isChinese = aiLang == "默认 (中文)"

        if (apiUrl.isEmpty() || apiKey.isEmpty()) { callJs("onError(\"未配置API\")"); return }
        while (apiUrl.endsWith("/")) apiUrl = apiUrl.dropLast(1)
        if (!apiUrl.endsWith("/chat/completions"))
            apiUrl += if (apiUrl.contains("/v1")) "/chat/completions" else "/v1/chat/completions"
        val finalUrl = apiUrl

        thread {
            try {
                val db = DatabaseHelper(this).readableDatabase
                var persona = ""
                var memory = ""
                db.rawQuery("SELECT identityInfo FROM Contacts WHERE userId=?", arrayOf(aiId)).use { c ->
                    if (c.moveToFirst()) persona = c.getString(0) ?: ""
                }
                db.rawQuery("SELECT memoryText FROM MemoryBank WHERE aiId=? ORDER BY insertTime DESC LIMIT 8", arrayOf(aiId)).use { c ->
                    val sb = StringBuilder()
                    while (c.moveToNext()) sb.append(c.getString(0)).append("\n")
                    memory = sb.toString().trim()
                }

                val langRule = if (!isChinese)
                    "【语言铁律：你必须100%用${aiLang}说话，绝对禁止出现任何中文。字幕翻译单独写在每段末尾，格式：[译：中文翻译]】"
                else ""

                // 新闻节目先搜真实新闻
                val realNews = if (currentProgramType == "news") fetchRealNews() else ""
                val newsContext = if (realNews.isNotEmpty())
                    "\n【今日真实新闻摘要（必须以这些为基础进行播报，用你的口吻和世界观重新演绎）】：\n$realNews"
                else ""

                val programDesc = when (currentProgramType) {
                    "story" -> """
【节目：故事时间】
讲述一个完整小故事（约1000字），风格温柔治愈有画面感，结尾留有余韵。
故事来自你的世界观，可以是亲历的、听说的或想象的。
                    """.trimIndent()

                    "news" -> """
【节目：新闻播报】$newsContext
用你的口吻播报3-5条新闻。如有真实新闻摘要，必须以此为基础用角色世界观重新演绎；
没有真实新闻则完全基于你的世界观虚构。语气专业但带个人色彩。
                    """.trimIndent()

                    "music" -> """
【节目：音乐推荐】
推荐5首歌。每首格式：
先用你的口吻说一段介绍旁白（结合心情人设，40字内），然后在旁白末尾加 [MUSIC:歌名 歌手名]
系统会自动播放这首歌，你只需要说介绍词，绝对不要写歌词、不要模仿唱歌。
示例：「接下来这首歌，每次听到前奏我就会想起某件事…… [MUSIC:海阔天空 Beyond]」
5首推荐完后说一段收台语。
                    """.trimIndent()

                    "sleep" -> """
【节目：哄睡电台】
语气极度轻柔缓慢温暖，像在耳边低声说话。
内容：1.开场问候今天过得怎样 2.引导放松冥想（描述安静美好场景100字） 3.轻声道晚安
全程禁止任何激动高亢表达。
                    """.trimIndent()

                    "book" -> """
【节目：读书】
读书栏目不使用大模型生成正文；这里仅作为兜底说明。
                    """.trimIndent()

                    else -> ""
                }

                val systemPrompt = """
你是 $aiName，人设：${persona.take(500)}。
【核心记忆】：$memory
$langRule

$programDesc

【格式要求——极其重要】：
将节目内容分成若干自然段，每段之间用 <|PAUSE|> 分隔。
每段不超过80字（外语内容也一样）。
${if (!isChinese) "每段末尾必须加 [译：该段的中文翻译]" else ""}
禁止markdown格式、动作描写、*号、括号动作。
音乐节目：每首歌的介绍是一段，[MUSIC:] 标记必须在该段末尾。
只输出节目正文。
                """.trimIndent()

                val body = JSONObject().apply {
                    put("model", model)
                    put("temperature", 0.85)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                        put(JSONObject().apply { put("role", "user"); put("content", "请开始节目") })
                    })
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())

                val resp = Http.client.newBuilder()
                    .connectTimeout(60, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
                    .newCall(Request.Builder().url(finalUrl).addHeader("Authorization", "Bearer $apiKey").post(body).build())
                    .execute()

                val raw = JSONObject(resp.body?.string() ?: return@thread)
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()

                // 解析分段
                val rawSegments = raw.split("<|PAUSE|>").map { it.trim() }.filter { it.isNotEmpty() }

                if (shouldStop.get() || generationId != programGenerationSeq.get()) return@thread

                segmentQueue.clear()
                for (seg in rawSegments) {
                    val musicMatch = Regex("\\[MUSIC:([^\\]]+)\\]").find(seg)
                    val musicQuery = musicMatch?.groupValues?.get(1)?.trim()
                    segmentQueue.add(Segment(seg, musicQuery))
                }

                if (segmentQueue.isEmpty()) { callJs("onError(\"生成内容为空\")"); return@thread }

                currentSegmentIndex = 0
                shouldStop.set(false)
                isPlaying.set(true)

                // 更新播放次数
                try {
                    DatabaseHelper(this).writableDatabase.execSQL(
                        "UPDATE RadioStation SET playCount=playCount+1 WHERE aiId=?", arrayOf(aiId)
                    )
                } catch (_: Exception) {}

                // 构建字幕数据发给前端
                val subtitles = JSONArray()
                segmentQueue.forEach { seg ->
                    val displayText = seg.text
                        .replace(Regex("\\[MUSIC:[^\\]]+\\]"), "")
                        .replace(Regex("\\[译：[^\\]]+\\]"), "")
                        .trim()
                    val transMatch = Regex("\\[译：([^\\]]+)\\]").find(seg.text)
                    val trans = transMatch?.groupValues?.get(1)?.trim() ?: ""
                    subtitles.put(JSONObject().apply {
                        put("text", displayText)
                        put("trans", trans)
                        put("hasMusic", seg.musicQuery != null)
                    })
                }

                callJs("onProgramReady(${jsStr(subtitles.toString())})")
                startBgMusic(currentProgramType)
                playNextSegment()

            } catch (e: Exception) {
                callJs("onError(${jsStr(e.message ?: "未知错误")})")
            }
        }
    }

    // ── 逐段播放调度 ─────────────────────────────────────────
    private fun playNextSegment() {
        if (shouldStop.get()) {
            isPlaying.set(false)
            callJs("onProgramEnd()")
            stopBgMusic()
            return
        }

        if (currentSegmentIndex >= segmentQueue.size) {
            if (currentProgramType == "book") {
                val bookText = getCurrentBookText()
                val progress = getBookProgress().coerceAtMost(bookText.length)
                if (getRadioAutoContinueEnabled() && currentAiId.isNotBlank() && currentAiName.isNotBlank() && progress < bookText.length) {
                    isPlaying.set(true)
                    ensureBgMusicRunning()
                    callJs("setStatus(${jsStr("继续读书中…")})")
                    startBookProgram(currentAiId, currentAiName, currentVoiceId, autoContinue = true)
                } else {
                    isPlaying.set(false)
                    callJs("onProgramEnd()")
                    stopBgMusic()
                    if (progress >= bookText.length && bookText.isNotBlank()) {
                        callJs("setStatus(${jsStr("这本书已经读完了")})")
                    }
                }
            } else if (getRadioAutoContinueEnabled() && currentAiId.isNotBlank() && currentAiName.isNotBlank()) {
                isPlaying.set(true)
                ensureBgMusicRunning()
                callJs("setStatus(${jsStr("正在准备下一期…")})")
                callJs("onGenerating()")
                generateProgram(currentAiId, currentAiName, currentProgramType, currentVoiceId, autoContinue = true)
            } else {
                isPlaying.set(false)
                callJs("onProgramEnd()")
                stopBgMusic()
            }
            return
        }

        val seg = segmentQueue[currentSegmentIndex]
        ensureBgMusicRunning()
        callJs("onSegmentStart(${currentSegmentIndex})")

        // 清理TTS文本
        val cleanText = seg.text
            .replace(Regex("\\[MUSIC:[^\\]]+\\]"), "")
            .replace(Regex("\\[译：[^\\]]+\\]"), "")
            .replace(Regex("[【】]"), "")
            .trim()

        if (cleanText.isEmpty()) {
            // 如果有音乐直接播歌
            if (seg.musicQuery != null && currentProgramType == "music") {
                playMusicThenNext(seg.musicQuery)
            } else {
                currentSegmentIndex++
                playNextSegment()
            }
            return
        }

        // 先TTS朗读这段
        speakSegment(cleanText) {
            if (shouldStop.get()) return@speakSegment
            // TTS说完后，如果有音乐标记且是音乐节目，播歌
            if (seg.musicQuery != null && currentProgramType == "music") {
                mainHandler.postDelayed({ playMusicThenNext(seg.musicQuery) }, 400L)
            } else {
                seg.sourceEndIndex?.let { saveBookProgress(it) }
                currentSegmentIndex++
                mainHandler.postDelayed({ playNextSegment() }, 600L)
            }
        }
    }

    // ── 播歌后进入下一段 ─────────────────────────────────────
    private fun playMusicThenNext(query: String) {
        callJs("onSearchingSong(${jsStr(query)})")
        thread {
            val songId = searchSongId(query)
            if (songId != null && !shouldStop.get()) {
                // 播真正歌曲时，暂停垫底背景音，避免两层音乐打架。
                try { bgPlayer?.pause() } catch (_: Exception) {}
                playSong(songId) {
                    if (!shouldStop.get()) {
                        if (shouldUseBgMusic(currentProgramType)) {
                            try { bgPlayer?.start(); restoreBgMusic() } catch (_: Exception) {}
                        }
                        currentSegmentIndex++
                        mainHandler.postDelayed({ playNextSegment() }, 800L)
                    }
                }
            } else {
                // 找不到歌，跳过
                callJs("onSongError()")
                currentSegmentIndex++
                mainHandler.postDelayed({ playNextSegment() }, 400L)
            }
        }
    }

    private fun estimateSegmentDelayMs(text: String): Long {
        val cleanLen = text.replace(Regex("\\s+"), "").length.coerceAtLeast(1)
        val isSleep = currentProgramType == "sleep"
        val perChar = if (isSleep) 190L else 125L
        val base = if (isSleep) 3800L else 2400L
        val minDelay = if (isSleep) 8000L else 4500L
        val maxDelay = if (isSleep) 32000L else 22000L
        return (base + cleanLen * perChar).coerceIn(minDelay, maxDelay)
    }

    private fun clearTtsTimers() {
        pendingTtsFallback?.let { mainHandler.removeCallbacks(it) }
        pendingTtsWatchdog?.let { mainHandler.removeCallbacks(it) }
        pendingTtsFallback = null
        pendingTtsWatchdog = null
    }

    private fun finishSegmentByTimer(text: String, onDone: () -> Unit) {
        clearTtsTimers()
        val delay = estimateSegmentDelayMs(text)
        val r = Runnable {
            if (!shouldStop.get()) onDone()
        }
        pendingTtsFallback = r
        mainHandler.postDelayed(r, delay)
    }

    // ── 单段TTS ──────────────────────────────────────────────
    private fun speakSegment(text: String, onDone: () -> Unit) {
        val clean = text.trim()
        if (clean.isEmpty()) {
            onDone()
            return
        }

        val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val provider = pref.getString("ttsProvider", "siliconflow") ?: "siliconflow"
        val hasSiliconKey = (pref.getString("ttsApiKey", "") ?: "").isNotBlank()
        val hasGuannanKey = (pref.getString("guannanApiKey", "") ?: "").isNotBlank()
        val hasProviderKey = if (provider == "guannan") hasGuannanKey else hasSiliconKey

        // 没有TTS Key时才走无声字幕节奏。
        // 注意：朋友服务器 guannan 支持全局默认声音，角色没有单独 voiceId 也应该能播。
        // 所以这里不能再用 currentVoiceId.isBlank() 直接拦截，否则会永远没有角色声音。
        if (!hasProviderKey) {
            ensureBgMusicRunning()
            restoreBgMusic()
            callJs("setStatus(${jsStr("TTS密钥为空，按字幕节奏播出")})")
            finishSegmentByTimer(clean, onDone)
            return
        }

        mainHandler.post {
            try {
                clearTtsTimers()
                val done = AtomicBoolean(false)
                fun finishOnce() {
                    if (done.compareAndSet(false, true)) {
                        clearTtsTimers()
                        isTtsSpeaking.set(false)
                        restoreBgMusic()
                        if (!shouldStop.get()) onDone()
                    }
                }

                val watchdog = Runnable {
                    // 防止 TTSManager 网络失败但没有回调，导致节目卡死。
                    finishOnce()
                }
                pendingTtsWatchdog = watchdog
                mainHandler.postDelayed(watchdog, estimateSegmentDelayMs(clean) + 15000L)

                val mgr = ttsManager ?: TTSManager(this@RadioActivity).also { ttsManager = it }
                mgr.setPlaybackVolume(getRadioVoiceVolumePercent())
                mgr.onTtsStart = {
                    isTtsSpeaking.set(true)
                    ensureBgMusicRunning()
                    duckBgMusic()
                    callJs("setStatus(${jsStr("正在播出")})")
                }
                mgr.onTtsEnd = {
                    restoreBgMusic()
                    mainHandler.postDelayed({ finishOnce() }, 350L)
                }

                // 关键：不要把空 voiceId 传给 TTSManager。
                // guannan 会用设置里的 guannanVoice；siliconflow 则走 TTSManager 的默认参数。
                if (currentVoiceId.isBlank()) {
                    mgr.speak(clean)
                } else {
                    mgr.speak(clean, currentVoiceId)
                }
            } catch (_: Exception) {
                // 出错也不要秒滚到底，走无声字幕节奏。
                finishSegmentByTimer(clean, onDone)
            }
        }
    }

    // ── 桥接 ─────────────────────────────────────────────────
    inner class RadioBridge {

        @JavascriptInterface
        fun onBack() { runOnUiThread { finish() } }

        @JavascriptInterface
        fun startProgram(aiId: String, aiName: String, programType: String) {
            if (isPlaying.get()) return
            val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            val voiceId = pref.getString("voiceId_$aiId", "") ?: ""
            // 没有音色也允许开播：字幕按真实朗读节奏慢慢走，不再几秒滚到底。
            callJs("onGenerating()")
            if (normalizeProgramType(programType) == "book") {
                startBookProgram(aiId, aiName, voiceId)
            } else {
                generateProgram(aiId, aiName, programType, voiceId)
            }
        }

        @JavascriptInterface
        fun stopProgram() {
            cancelSleepTimer()
            stopAll()
            callJs("onStopped()")
        }

        @JavascriptInterface
        fun getRadioAudioSettings(): String {
            return JSONObject().apply {
                put("bgmVolume", getRadioBgmVolumePercent())
                put("voiceVolume", getRadioVoiceVolumePercent())
                put("autoContinue", getRadioAutoContinueEnabled())
            }.toString()
        }

        @JavascriptInterface
        fun setRadioAudioSettings(bgmVolume: Int, voiceVolume: Int) {
            val bgm = bgmVolume.coerceIn(0, 100)
            val voice = voiceVolume.coerceIn(0, 100)
            getSharedPreferences("AppConfig", Context.MODE_PRIVATE).edit()
                .putInt("radioBgmVolume", bgm)
                .putInt("radioVoiceVolume", voice)
                .apply()
            mainHandler.post {
                applyCurrentAudioVolumes()
                callJs("setStatus(${jsStr("声音已调整：音乐 ${bgm}% · 人声 ${voice}%")})")
            }
        }

        @JavascriptInterface
        fun setRadioAutoContinue(enabled: Boolean) {
            getSharedPreferences("AppConfig", Context.MODE_PRIVATE).edit()
                .putBoolean("radioAutoContinue", enabled)
                .apply()
            mainHandler.post {
                callJs("setStatus(${jsStr(if (enabled) "已开启自动续播" else "已关闭自动续播")})")
            }
        }

        @JavascriptInterface
        fun getRadioSleepTimerRemainingSeconds(): Long = getSleepTimerRemainingSeconds()

        @JavascriptInterface
        fun setRadioSleepTimer(minutes: Int) {
            runOnUiThread {
                if (!isPlaying.get()) {
                    Toast.makeText(this@RadioActivity, "开播后才能设置定时停止", Toast.LENGTH_SHORT).show()
                    callJs("setStatus(${jsStr("开播后才能设置定时停止")})")
                    callJs("if(window.onRadioSleepTimerChanged){window.onRadioSleepTimerChanged(0);}")
                    return@runOnUiThread
                }
                val safeMinutes = minutes.coerceIn(1, 24 * 60)
                setSleepTimer(safeMinutes)
                callJs("setStatus(${jsStr("将在 $safeMinutes 分钟后停止播放")})")
                Toast.makeText(this@RadioActivity, "将在 $safeMinutes 分钟后停止播放", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun cancelRadioSleepTimer() {
            runOnUiThread {
                val wasActive = getSleepTimerRemainingSeconds() > 0L
                cancelSleepTimer()
                if (wasActive) {
                    callJs("setStatus(${jsStr("已取消定时停止")})")
                    Toast.makeText(this@RadioActivity, "已取消定时停止", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun switchRadioBgm(programType: String) {
            runOnUiThread {
                val type = normalizeProgramType(programType)
                if (!isPlaying.get()) {
                    Toast.makeText(this@RadioActivity, "开播后才能切换背景音乐", Toast.LENGTH_SHORT).show()
                    callJs("setStatus(${jsStr("开播后才能切换背景音乐")})")
                    return@runOnUiThread
                }
                switchBgMusicNow(type)
            }
        }

        @JavascriptInterface
        fun replaceRadioBgm(programType: String) {
            runOnUiThread {
                pendingBgmProgramType = normalizeProgramType(programType)
                pendingBgmReplaceMode = true
                Toast.makeText(this@RadioActivity, "选择${programNameFor(pendingBgmProgramType)}栏目的背景音乐，可一次多选", Toast.LENGTH_SHORT).show()
                bgmPickerLauncher.launch(arrayOf("audio/*"))
            }
        }

        @JavascriptInterface
        fun addRadioBgm(programType: String) {
            runOnUiThread {
                pendingBgmProgramType = normalizeProgramType(programType)
                pendingBgmReplaceMode = false
                Toast.makeText(this@RadioActivity, "添加${programNameFor(pendingBgmProgramType)}栏目的背景音乐，可一次多选", Toast.LENGTH_SHORT).show()
                bgmPickerLauncher.launch(arrayOf("audio/*"))
            }
        }

        @JavascriptInterface
        fun clearRadioBgm(programType: String) {
            runOnUiThread {
                val type = normalizeProgramType(programType)
                val count = clearCustomBgmFiles(type)
                Toast.makeText(this@RadioActivity, "已清空${programNameFor(type)}栏目自定义音乐 $count 首", Toast.LENGTH_SHORT).show()
                callJs("setStatus(${jsStr("已清空${programNameFor(type)}栏目自定义背景音乐")})")
                if (isPlaying.get() && normalizeProgramType(currentProgramType) == type) {
                    stopBgMusic()
                    startBgMusic(type)
                }
            }
        }

        @JavascriptInterface
        fun importRadioBook() {
            runOnUiThread {
                Toast.makeText(this@RadioActivity, "选择 txt / md / html / epub 电子书", Toast.LENGTH_SHORT).show()
                bookPickerLauncher.launch(arrayOf("text/*", "application/epub+zip", "application/xhtml+xml", "text/html", "application/octet-stream"))
            }
        }

        @JavascriptInterface
        fun restartRadioBook() {
            runOnUiThread {
                saveBookProgress(0)
                Toast.makeText(this@RadioActivity, "已重置读书进度", Toast.LENGTH_SHORT).show()
                callJs("setStatus(${jsStr("已从头开始读当前书")})")
                if (isPlaying.get() && currentProgramType == "book") {
                    stopAll()
                    val voiceId = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                        .getString("voiceId_$currentAiId", "") ?: ""
                    callJs("onGenerating()")
                    startBookProgram(currentAiId, currentAiName, voiceId)
                }
            }
        }

        @JavascriptInterface
        fun clearRadioBook() {
            runOnUiThread {
                clearCurrentBookFiles()
                Toast.makeText(this@RadioActivity, "已清空读书文件", Toast.LENGTH_SHORT).show()
                callJs("setStatus(${jsStr("已清空读书文件")})")
                if (isPlaying.get() && currentProgramType == "book") {
                    stopAll()
                    callJs("onStopped()")
                }
            }
        }

        @JavascriptInterface
        fun pauseResume() {
            try {
                val ttsPlaying = isTtsSpeaking.get() || ttsPlayer?.isPlaying == true
                val songPlaying = isSongPlaying.get() || songPlayer?.isPlaying == true
                val bgPlaying = bgPlayer?.isPlaying == true
                if (ttsPlaying || songPlaying || bgPlaying) {
                    // TTSManager 没有 pause 接口，暂停时只能停止本段语音，背景和歌曲正常暂停。
                    try { ttsManager?.stop() } catch (_: Exception) {}
                    try { ttsPlayer?.pause() } catch (_: Exception) {}
                    try { songPlayer?.pause() } catch (_: Exception) {}
                    try { bgPlayer?.pause() } catch (_: Exception) {}
                    isTtsSpeaking.set(false)
                    callJs("onPaused()")
                } else {
                    try { songPlayer?.start() } catch (_: Exception) {}
                    try { bgPlayer?.start() } catch (_: Exception) {}
                    restoreBgMusic()
                    callJs("onResumed()")
                }
            } catch (_: Exception) {}
        }
    }

    override fun onPause() {
        super.onPause()
        try { setBgVolume((getRadioBgmVolumeFloat() * 0.25f).coerceIn(0f, 1f)) } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        try { restoreBgMusic() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(sleepTimerRunnable)
        sleepTimerDeadlineMs = 0L
        stopAll()
        webView.destroy()
        super.onDestroy()
    }
}
