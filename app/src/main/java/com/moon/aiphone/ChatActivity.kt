package com.moon.aiphone

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

class ChatActivity : AppCompatActivity() {

    // ── 基础变量 (保持不变) ──────────────────────────────────────
    private val msgList = mutableListOf<Message>()
    private val fullMsgList = mutableListOf<Message>()
    private var aiId: String = ""
    private var aiName: String = "未知 AI"
    private var isGenerating: Boolean = false
    private var isInvading: Boolean = false
    private var quotingMessage: Message? = null
    private var characterStatus: String = "在线"
    private var lastKnownMyAvatar = ""
    private val invasionHandler = Handler(Looper.getMainLooper())
    private val patienceHandler = Handler(Looper.getMainLooper())
    private var patienceRunnable: Runnable? = null
    // 每次重排定时器自增；后台线程排定前校验，防止旧线程在取消后仍把过期定时器排上去
    @Volatile private var patienceScheduleSeq = 0L

    // ── 密语时刻 ─────────────────────────────────────────────────
    private var whisperMode = false
    private val toyTagRegex = Regex("\\[TOY[:：]\\s*(STOP|停止|[0-9])\\s*\\]", RegexOption.IGNORE_CASE)

    // ★ 时间戳发号器：保证每条消息的 timestamp 唯一且递增，避免同毫秒撞车
    private var lastTs = 0L
    @Synchronized
    private fun nextTimestamp(): Long {
        val now = System.currentTimeMillis()
        lastTs = if (now > lastTs) now else lastTs + 1
        return lastTs
    }

    // ── WebView ──────────────────────────────────────────────────
    private lateinit var webView: WebView
    private var webViewReady = false
    private var openHistoryOnLoad = false
    private val pendingJsCalls = mutableListOf<String>()

    private val httpClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    // ── Image picker ─────────────────────────────────────────────
    private val pickChatImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            val base64 = uriToBase64DataUri(uri.toString())
            val myMsg = Message("【图片】", true, false, false).apply {
                imageDesc = uri.toString()
                timestamp = nextTimestamp()
            }
            msgList.add(myMsg)
            addMessageToWebView(myMsg, base64)
            saveMsgToDb(myMsg, 1, "")
            startPatienceTimer()
        }
    }

    private var historyExportFormat = "txt"
    private val createHistoryDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) {
            Thread {
                try {
                    val document = buildHistoryExport(historyExportFormat)
                    contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(document) }
                        ?: throw IllegalStateException("无法创建文件")
                    runOnUiThread { Toast.makeText(this, "聊天记录已导出", Toast.LENGTH_LONG).show() }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "导出失败：${e.message ?: "无法写入文件"}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    // ── 密语时刻：BLE 权限申请 ───────────────────────────────────
    private val blePermLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) ToyBleManager.connect(this)
        else Toast.makeText(this, "缺少蓝牙权限，无法连接玩具", Toast.LENGTH_LONG).show()
    }

    private fun blePermissions(): Array<String> =
        if (android.os.Build.VERSION.SDK_INT >= 31)
            arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)

    private fun connectToyWithPermission() {
        val missing = blePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) ToyBleManager.connect(this)
        else blePermLauncher.launch(missing.toTypedArray())
    }

    // ── 屏幕共享 ─────────────────────────────────────────────────
    private val screenShareHandler = Handler(Looper.getMainLooper())
    private var screenShareRunnable: Runnable? = null
    private var lastScreenHash = 0L
    private var isActivityResumed = false
    private val SCREEN_SHARE_INTERVAL_MS = 40_000L
    // 帧差阈值：8x8 均值哈希汉明距离 ≤3 视为画面没变，跳过识图省 token
    private val SCREEN_HASH_SAME_THRESHOLD = 3

    private val screenShareLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenShareService::class.java).apply {
                putExtra("action", ScreenShareService.ACTION_START)
                putExtra(ScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenShareService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenShareService.EXTRA_AI_ID, aiId)
                putExtra(ScreenShareService.EXTRA_AI_NAME, aiName)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            Toast.makeText(this, "屏幕共享已开启，$aiName 能看到你的屏幕了（注意画面里的隐私信息）", Toast.LENGTH_LONG).show()
            lastScreenHash = 0L
            startScreenShareLoop(firstDelayMs = 4_000L)
        } else {
            Toast.makeText(this, "已取消屏幕共享", Toast.LENGTH_SHORT).show()
        }
    }

    private val screenShareStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            stopScreenShareLoop()
            if (isActivityResumed) Toast.makeText(this@ChatActivity, "屏幕共享已结束", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Broadcast receiver ───────────────────────────────────────
    private val gameSummaryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val summaryAiId = intent?.getStringExtra("aiId") ?: return
            val summary = intent.getStringExtra("summary") ?: return
            if (summaryAiId != aiId) return
            val msg = Message(summary, false, false, false).apply { timestamp = nextTimestamp() }
            msgList.add(msg)
            addMessageToWebView(msg)
            saveMsgToDb(msg, 0, "")
            try {
                val today = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA).format(Date())
                val cv = ContentValues().apply {
                    put("aiId", summaryAiId)
                    put("memoryText", "【真心话大冒险 $today】$summary")
                    put("category", "shared_event")
                    put("insertTime", System.currentTimeMillis())
                }
                DatabaseHelper(this@ChatActivity).writableDatabase.insert("MemoryBank", null, cv)
            } catch (_: Exception) {}
        }
    }

    // ════════════════════════════════════════════════════════════
    // onCreate
    // ════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        supportActionBar?.hide()

        aiName = intent.getStringExtra("AI_NAME") ?: intent.getStringExtra("USER_NAME") ?: "未知 AI"
        aiId = intent.getStringExtra("AI_ID") ?: intent.getStringExtra("USER_ID") ?: ""
        characterStatus = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            .getString("chat_status_$aiId", "在线") ?: "在线"
        whisperMode = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            .getBoolean("whisperMode_$aiId", false)
        openHistoryOnLoad = intent.getBooleanExtra("OPEN_HISTORY", false)

        try {
            ContextCompat.registerReceiver(this, screenShareStoppedReceiver,
                IntentFilter(ScreenShareService.BROADCAST_STOPPED), ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (_: Exception) {}

        setupWebView()
    }

    // ════════════════════════════════════════════════════════════
    // WebView 初始化
    // ════════════════════════════════════════════════════════════
    private fun setupWebView() {
        webView = findViewById(R.id.chatWebView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // 注入 Android 桥接对象
        webView.addJavascriptInterface(AndroidBridge(), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                webViewReady = true

                // 加载头像和名字
                var aiAvatar = ""
                var myAvatar = ""
                try {
                    DatabaseHelper(this@ChatActivity).readableDatabase.query("Contacts", null, "userId=?", arrayOf(aiId), null, null, null).use { c ->
                        if (c.moveToFirst()) {
                            aiAvatar = c.getSafeString("avatarUri")
                            if (aiAvatar.startsWith("/")) aiAvatar = "file://$aiAvatar"
                        }
                    }
                    DatabaseHelper(this@ChatActivity).readableDatabase.query("MyProfile", null, null, null, null, null, null).use { c ->
                        if (c.moveToFirst()) {
                            myAvatar = c.getSafeString("myAvatarUri")
                            if (myAvatar.startsWith("/")) myAvatar = "file://$myAvatar"
                        }
                    }
                } catch (_: Exception) {}

                val aiAvatarB64 = if (aiAvatar.isNotEmpty()) uriToBase64DataUri(aiAvatar) else ""
                val myAvatarB64 = if (myAvatar.isNotEmpty()) uriToBase64DataUri(myAvatar) else ""

                callJs("initChat(${jsStr(aiName)}, ${jsStr(aiAvatarB64)}, ${jsStr(myAvatarB64)})")
                callJs("setCharacterStatus(${jsStr(characterStatus)})")

                // 应用用户自定义CSS
                loadAndApplyUserCSS()

                // 应用壁纸
                loadChatBackground()

                // 加载历史消息
                loadChatHistory()

                if (openHistoryOnLoad) {
                    openHistoryOnLoad = false
                    callJs("openHistory()")
                }

                // 执行积压的JS
                pendingJsCalls.forEach { callJs(it) }
                pendingJsCalls.clear()
            }
        }

        // 从 assets 加载模板
        webView.loadUrl("file:///android_asset/chat_template.html")
    }

    // ════════════════════════════════════════════════════════════
    // Android → JS 桥接（发消息给页面）
    // ════════════════════════════════════════════════════════════
    private fun callJs(js: String) {
        runOnUiThread {
            if (webViewReady) {
                webView.evaluateJavascript(js, null)
            } else {
                pendingJsCalls.add(js)
            }
        }
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

    // ── 引用消息：不再把「[引用:xxx]」塞进正文，而是单独传给 WebView 渲染成引用卡片 ──
    private data class QuotePayload(
        val who: String = "",
        val text: String = "",
        val image: String = "",
        val popupInner: String = ""
    )

    private fun cleanQuotePart(s: String): String {
        return s.replace("|", "｜")
            .replace("\n", " ")
            .replace("\r", " ")
            .trim()
    }

    private fun buildQuoteInner(who: String, text: String, image: String = "", inner: String = ""): String {
        val quoteLine = "[QUOTE]${cleanQuotePart(who)}|${cleanQuotePart(text)}|${image.trim()}"
        val pureInner = stripQuoteForPopup(inner)
        return if (pureInner.isNotEmpty()) "$quoteLine\n$pureInner" else quoteLine
    }

    private fun stripQuoteForPopup(inner: String): String {
        if (inner.isEmpty()) return ""
        return when {
            inner.startsWith("[QUOTE]") -> inner.substringAfter("\n", "").trim()
            inner.startsWith("[QUOTE_JSON]") -> inner.substringAfter("\n", "").trim()
            else -> inner.trim()
        }
    }

    private fun parseQuoteFromInner(inner: String): QuotePayload {
        if (inner.isEmpty()) return QuotePayload()
        return try {
            when {
                inner.startsWith("[QUOTE_JSON]") -> {
                    val line = inner.removePrefix("[QUOTE_JSON]").substringBefore("\n")
                    val obj = JSONObject(line)
                    QuotePayload(
                        who = obj.optString("who", ""),
                        text = obj.optString("text", ""),
                        image = obj.optString("image", ""),
                        popupInner = inner.substringAfter("\n", "").trim()
                    )
                }
                inner.startsWith("[QUOTE]") -> {
                    val line = inner.removePrefix("[QUOTE]").substringBefore("\n")
                    val parts = line.split("|", limit = 3)
                    QuotePayload(
                        who = parts.getOrElse(0) { "" },
                        text = parts.getOrElse(1) { "" },
                        image = parts.getOrElse(2) { "" },
                        popupInner = inner.substringAfter("\n", "").trim()
                    )
                }
                else -> QuotePayload(popupInner = inner.trim())
            }
        } catch (_: Exception) {
            QuotePayload(popupInner = stripQuoteForPopup(inner))
        }
    }

    /** 把一条 Message 转成 JSON 并注入 WebView */
    private fun addMessageToWebView(msg: Message, imageBase64: String = "") {
        val dateStr = SimpleDateFormat("MM月dd日", Locale.CHINA).format(Date(if (msg.timestamp > 0) msg.timestamp else System.currentTimeMillis()))
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(if (msg.timestamp > 0) msg.timestamp else System.currentTimeMillis()))

        // 处理引用
        val inner = msg.innerThoughts ?: ""
        val quote = parseQuoteFromInner(inner)
        val quoteWho = quote.who
        val quoteText = quote.text
        val quoteImage = quote.image

        // 处理图片
        var finalImageB64 = imageBase64
        if (finalImageB64.isEmpty() && !msg.imageDesc.isNullOrEmpty()) {
            val desc = msg.imageDesc!!
            when {
                desc.startsWith("[RECALLED]") -> { /* recalled, no image */ }
                desc.startsWith("[MUSIC_CARD]") -> { /* handled via musicCardJson */ }
                desc.startsWith("[REAL_IMG]") -> finalImageB64 = uriToBase64DataUri(desc.replace("[REAL_IMG]", ""))
                // ★ 新增：网址（表情包等）直接交给 WebView 当 img src，不必转 base64
                desc.startsWith("http://") || desc.startsWith("https://") -> finalImageB64 = desc
                desc.startsWith("file://") || desc.startsWith("/") || desc.startsWith("content://") ->
                    finalImageB64 = uriToBase64DataUri(desc)
            }
        }

        // 处理音乐卡片
        var musicCardJson = ""
        if (msg.imageDesc?.startsWith("[MUSIC_CARD]") == true) {
            musicCardJson = msg.imageDesc!!.removePrefix("[MUSIC_CARD]")
        }

        var moneyCardJson = ""
        var moneyCardType = ""
        when {
            msg.imageDesc?.startsWith("[TRANSFER_CARD]") == true -> {
                moneyCardType = "transfer"
                moneyCardJson = msg.imageDesc!!.removePrefix("[TRANSFER_CARD]")
            }
            msg.imageDesc?.startsWith("[REDPACKET_CARD]") == true -> {
                moneyCardType = "redpacket"
                moneyCardJson = msg.imageDesc!!.removePrefix("[REDPACKET_CARD]")
            }
        }

        val isRecalled = msg.imageDesc?.startsWith("[RECALLED]") == true ||
                (msg.content.endsWith("撤回了一条消息") && !msg.isFromMe)

        val innerForPopup = quote.popupInner

        val obj = JSONObject().apply {
            put("id", msg.timestamp.toString())
            put("content", if (isRecalled) msg.content else msg.content)
            put("isFromMe", msg.isFromMe)
            put("timestamp", msg.timestamp)
            put("timeStr", timeStr)
            put("dateStr", dateStr)
            put("isSystem", msg.isSystem || msg.imageDesc?.startsWith("[POKE]") == true)
            put("isVoice", msg.isVoice)
            put("voiceDuration", msg.voiceDuration)
            put("imageUri", finalImageB64)
            put("musicCardJson", musicCardJson)
            put("moneyCardJson", moneyCardJson)
            put("moneyCardType", moneyCardType)
            put("isRecalled", isRecalled)
            put("innerThoughts", innerForPopup)
            put("translatedText", msg.translatedText ?: "")
            put("quoteWho", quoteWho)
            put("quoteText", quoteText)
            put("quoteImage", quoteImage)
        }

        callJs("addMessage(${jsStr(obj.toString())})")
    }

    // ════════════════════════════════════════════════════════════
    // JS → Android 桥接（页面回调 Android）
    // ════════════════════════════════════════════════════════════
    inner class AndroidBridge {

        @JavascriptInterface
        fun onStopVoice() {
            runOnUiThread {
                try {
                    val ttsManager = TTSManager(this@ChatActivity)
                    ttsManager.stop()
                    sendBroadcast(Intent("MUSIC_RESUME"))
                } catch (_: Exception) {}
            }
        }
        @JavascriptInterface
        fun onBack() {
            runOnUiThread { finish() }
        }

        @JavascriptInterface
        fun onCall() {
            runOnUiThread {
                val intent = Intent(this@ChatActivity, CallActivity::class.java)
                intent.putExtra("AI_ID", aiId)
                intent.putExtra("AI_NAME", aiName)
                startActivity(intent)
            }
        }

        @JavascriptInterface
        fun onSettings() {
            runOnUiThread {
                startActivity(Intent(this@ChatActivity, CharacterSettingsActivity::class.java).putExtra("AI_ID", aiId))
            }
        }

        @JavascriptInterface
        fun onOpenHistory() {
            Thread { sendHistoryStatsToWebView() }.start()
        }

        @JavascriptInterface
        fun onHistoryDateSelected(dateKey: String) {
            Thread { sendHistoryDayToWebView(dateKey) }.start()
        }

        @JavascriptInterface
        fun onExportHistory() {
            runOnUiThread {
                val items = arrayOf("TXT 文档（便于阅读和分享）", "JSON 文件（保留完整结构）")
                androidx.appcompat.app.AlertDialog.Builder(this@ChatActivity)
                    .setTitle("导出聊天记录")
                    .setItems(items) { _, which ->
                        historyExportFormat = if (which == 1) "json" else "txt"
                        val safeName = aiName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        val day = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
                        createHistoryDocument.launch("与${safeName}的聊天记录_${day}.${historyExportFormat}")
                    }.show()
            }
        }

        @JavascriptInterface
        fun onSend(text: String) {
            runOnUiThread { handleSend(text) }
        }

        @JavascriptInterface
        fun onPokeAi() {
            runOnUiThread {
                val prefs = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                val suffix = prefs.getString("poke_user_to_ai_$aiId", "")?.trim().orEmpty()
                addPokeEvent(fromUser = true, suffix = suffix)
                setCharacterStatus("被你拍了一下")
                triggerAiResponse()
            }
        }

        @JavascriptInterface
        fun onPokeSettings() {
            runOnUiThread { showPokeSettingsDialog() }
        }

        @JavascriptInterface
        fun onMoneyCardClick(msgId: String, type: String) {
            runOnUiThread { receiveMoneyCard(msgId, type) }
        }

        @JavascriptInterface
        fun onAddImage() {
            runOnUiThread {
                val sharingToMe = ScreenShareService.isRunning && ScreenShareService.sharingAiId == aiId
                val items = if (sharingToMe) arrayOf("发送图片", "停止共享屏幕", "一起玩", "设置拍一拍")
                else arrayOf("发送图片", "共享屏幕给$aiName", "一起玩", "设置拍一拍")
                androidx.appcompat.app.AlertDialog.Builder(this@ChatActivity)
                    .setItems(items) { _, which ->
                        when (which) {
                            0 -> pickChatImage.launch(arrayOf("image/*"))
                            1 -> if (sharingToMe) stopScreenShare() else requestScreenShare()
                            2 -> showWhisperDialog()
                            3 -> showPokeSettingsDialog()
                        }
                    }
                    .show()
            }
        }

        @JavascriptInterface
        fun onSticker() {
            runOnUiThread {
                StickerPanelHelper(this@ChatActivity) { sticker ->
                    val myMsg = Message("[表情包: ${sticker.name}]", true, false, false).apply {
                        timestamp = nextTimestamp()
                        imageDesc = sticker.url
                    }
                    msgList.add(myMsg)
                    addMessageToWebView(myMsg)
                    saveMsgToDb(myMsg, 1, "")
                }.show()
            }
        }

        @JavascriptInterface
        fun onAiTrigger() {
            runOnUiThread { triggerAiResponse() }
        }

        @JavascriptInterface
        fun onQuote(quoteJson: String) {
            runOnUiThread {
                try {
                    val q = JSONObject(quoteJson)
                    val content = q.optString("content", "").ifEmpty { "【图片】" }
                    val isFromMe = q.optBoolean("isFromMe", false)
                    val imageUri = q.optString("imageUri", "")
                    val fakeMsg = Message(content, isFromMe, false, false).apply {
                        imageDesc = imageUri
                    }
                    quotingMessage = fakeMsg
                    val who = if (isFromMe) "你" else aiName
                    val previewRaw = if (imageUri.isNotEmpty() && content == "【图片】") "【图片】" else content
                    val preview = if (previewRaw.length > 30) previewRaw.take(30) + "..." else previewRaw
                    callJs("setInputHint(${jsStr("引用「$who: $preview」")})")
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun onCopy(text: String) {
            runOnUiThread {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("msg", text))
                Toast.makeText(this@ChatActivity, "已复制", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun onDeleteMsg(msgId: String) {
            runOnUiThread {
                try {
                    val ts = msgId.toLongOrNull() ?: return@runOnUiThread
                    DatabaseHelper(this@ChatActivity).writableDatabase
                        .execSQL("DELETE FROM ChatHistory WHERE timestamp=? AND aiId=?", arrayOf(ts, aiId))
                    msgList.removeAll { it.timestamp == ts }
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun onMusicCardClick(songId: String) {
            runOnUiThread {
                try {
                    val intent = Intent(this@ChatActivity, MusicActivity::class.java).apply {
                        putExtra("song_id", songId)
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://music.163.com/song?id=$songId")))
                    } catch (_: Exception) {}
                }
            }
        }

        @JavascriptInterface
        fun onPlayVoice(content: String, msgId: String) {
            runOnUiThread {
                try {
                    val sharedPref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                    val voiceId = sharedPref.getString("voiceId_$aiId", "") ?: ""
                    if (voiceId.isEmpty()) {
                        Toast.makeText(this@ChatActivity, "未配置语音", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    val ttsManager = TTSManager(this@ChatActivity)
                    val aiLang = sharedPref.getString("aiLang_$aiId", "默认 (中文)") ?: "默认 (中文)"
                    val ttsText = if (aiLang != "默认 (中文)" && !aiLang.contains("中")) {
                        content.filter { c -> c.code !in 0x4E00..0x9FFF }.trim()
                    } else content
                    ttsManager.speak(ttsText, voiceId)
                    sendBroadcast(Intent("MUSIC_PAUSE"))
                    Toast.makeText(this@ChatActivity, "正在播放", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@ChatActivity, "播放失败", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun onPeekRecalled(msgId: String) {
            runOnUiThread {
                try {
                    val ts = msgId.toLongOrNull() ?: return@runOnUiThread
                    var recalled = msgList.firstOrNull { it.timestamp == ts }
                        ?.imageDesc
                        ?.takeIf { it.startsWith("[RECALLED]") }
                        ?.removePrefix("[RECALLED]")
                        ?: ""

                    // 当前 WebView 刚刷新、msgList 没取到时，从数据库兜底读一次
                    if (recalled.isEmpty()) {
                        try {
                            DatabaseHelper(this@ChatActivity).readableDatabase.rawQuery(
                                "SELECT imageDesc FROM ChatHistory WHERE timestamp=? AND aiId=? LIMIT 1",
                                arrayOf(ts.toString(), aiId)
                            ).use { c ->
                                if (c.moveToFirst()) {
                                    val raw = c.getString(0) ?: ""
                                    if (raw.startsWith("[RECALLED]")) recalled = raw.removePrefix("[RECALLED]")
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    if (recalled.isEmpty()) {
                        Toast.makeText(this@ChatActivity, "没有可偷看的撤回内容", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }

                    val layout = android.widget.LinearLayout(this@ChatActivity).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setPadding(60, 60, 60, 60)
                        setBackgroundColor(android.graphics.Color.parseColor("#E6111111"))
                    }
                    layout.addView(android.widget.TextView(this@ChatActivity).apply {
                        text = "👀 偷看撤回"
                        setTextColor(android.graphics.Color.parseColor("#888888"))
                        textSize = 12f
                        setPadding(0, 0, 0, 20)
                    })
                    layout.addView(android.widget.TextView(this@ChatActivity).apply {
                        text = recalled
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 16f
                    })
                    android.app.AlertDialog.Builder(this@ChatActivity)
                        .setView(layout)
                        .create().also {
                            it.window?.setBackgroundDrawable(
                                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                            it.show()
                        }
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun onRecallMsg(msgId: String) {
            runOnUiThread {
                try {
                    val ts = msgId.toLongOrNull() ?: return@runOnUiThread
                    val msg = msgList.firstOrNull { it.timestamp == ts } ?: return@runOnUiThread
                    if (!msg.isFromMe) {
                        Toast.makeText(this@ChatActivity, "不能撤回别人的消息！", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    val originalContent = msg.content
                    msg.content = "（撤回了一条消息）"
                    msg.imageDesc = ""
                    DatabaseHelper(this@ChatActivity).writableDatabase.execSQL(
                        "UPDATE ChatHistory SET content=?, imageDesc='' WHERE timestamp=? AND aiId=?",
                        arrayOf("（撤回了一条消息）", ts, aiId)
                    )
                    val updateObj = JSONObject().apply {
                        put("isRecalled", true)
                        put("content", "（撤回了一条消息）")
                        put("isFromMe", true)
                    }
                    callJs("updateMessage(${jsStr(msgId)}, ${jsStr(updateObj.toString())})")
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun onDeleteMsgAndMemory(msgId: String, content: String) {
            runOnUiThread {
                try {
                    val ts = msgId.toLongOrNull() ?: return@runOnUiThread
                    val db = DatabaseHelper(this@ChatActivity).writableDatabase
                    db.execSQL("DELETE FROM ChatHistory WHERE timestamp=? AND aiId=?", arrayOf(ts, aiId))
                    // Only delete memories whose text STARTS with the exact message content prefix
                    // (length >= 30 to avoid over-broad matches on short common phrases)
                    val keyword = content.trim()
                    if (keyword.length >= 30) {
                        db.delete("MemoryBank", "aiId=? AND memoryText LIKE ?",
                            arrayOf(aiId, "${keyword.take(30)}%"))
                    }
                    msgList.removeAll { it.timestamp == ts }
                    Toast.makeText(this@ChatActivity, "消息已删除", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun onTranslateVoice(content: String, msgId: String) {
            Thread {
                try {
                    val sharedPref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                    var apiUrl = sharedPref.getString("apiUrl", "") ?: ""
                    val apiKey = sharedPref.getString("apiKey", "") ?: ""
                    val transModel = sharedPref.getString("transModel_$aiId", "") ?: ""
                    val modelName = if (transModel.isNotEmpty()) transModel
                    else sharedPref.getString("modelName", "gemini-pro") ?: "gemini-pro"

                    while (apiUrl.endsWith("/")) apiUrl = apiUrl.dropLast(1)
                    if (!apiUrl.endsWith("/chat/completions")) {
                        apiUrl = if (apiUrl.endsWith("/v1")) "$apiUrl/chat/completions"
                        else "$apiUrl/v1/chat/completions"
                    }

                    val bodyJson = JSONObject().apply {
                        put("model", modelName)
                        put("temperature", 0.3)
                        put("messages", JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "user")
                                put("content", "请将以下内容翻译成简体中文，只输出翻译结果，不要任何解释：\n${content.take(3500)}")
                            })
                        })
                    }

                    val request = Request.Builder()
                        .url(apiUrl)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .post(bodyJson.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                        .build()

                    val response = Http.client.newBuilder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build().newCall(request).execute()

                    val body = response.body?.string() ?: run {
                        runOnUiThread { callJs("onVoiceTranslateFail(${jsStr(msgId)})") }
                        return@Thread
                    }

                    val translated = JSONObject(body)
                        .getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim()

                    if (translated.isEmpty()) {
                        runOnUiThread { callJs("onVoiceTranslateFail(${jsStr(msgId)})") }
                        return@Thread
                    }

                    // 写库
                    val ts = msgId.toLongOrNull()
                    if (ts != null) {
                        try {
                            DatabaseHelper(this@ChatActivity).writableDatabase.execSQL(
                                "UPDATE ChatHistory SET translatedText=? WHERE timestamp=? AND isFromMe=0 AND aiId=?",
                                arrayOf(translated, ts, aiId)
                            )
                            msgList.firstOrNull { it.timestamp == ts }?.translatedText = translated
                        } catch (_: Exception) {}
                    }

                    runOnUiThread {
                        callJs("onVoiceTranslateDone(${jsStr(msgId)}, ${jsStr(translated)})")
                    }
                } catch (_: Exception) {
                    runOnUiThread { callJs("onVoiceTranslateFail(${jsStr(msgId)})") }
                }
            }.start()
        }

        @JavascriptInterface
        fun onShowThoughtsCard(thoughts: String) {
            // 在后台线程做数据解析，避免主线程卡顿
            Thread {
                try {
                    val db = DatabaseHelper(this@ChatActivity).readableDatabase

                    // ── 读取角色信息 ──────────────────────────────────
                    var persona = ""
                    var avatarUri = ""
                    var aiNameLocal = aiName
                    try {
                        db.rawQuery("SELECT * FROM Contacts WHERE userId=?", arrayOf(aiId)).use { c ->
                            if (c.moveToFirst()) {
                                val idxIdentity = c.getColumnIndex("identityInfo")
                                if (idxIdentity != -1) persona = c.getString(idxIdentity) ?: ""
                                val idxAvatar = c.getColumnIndex("avatarUri")
                                if (idxAvatar != -1) avatarUri = c.getString(idxAvatar) ?: ""
                                val idxRealName = c.getColumnIndex("realName")
                                if (idxRealName != -1) {
                                    val rn = c.getString(idxRealName) ?: ""
                                    if (rn.isNotEmpty()) aiNameLocal = rn
                                }
                            }
                        }
                    } catch (_: Exception) {}

                    // ── 提取组织 ──────────────────────────────────────
                    val orgMatch = Regex("(所属|单位|组织|团队|公司|学校|队伍|部队|学院|小队|战队)[：:：]?\\s*([^，。\\n]{2,20})")
                        .find(persona)
                    var org = orgMatch?.groupValues?.get(2)?.trim() ?: ""
                    if (org.isEmpty()) {
                        org = persona.lines().firstOrNull { it.isNotEmpty() }?.take(20)?.trim() ?: ""
                    }
                    if (org.isEmpty()) org = "未知组织"
                    if (org.startsWith("的")) org = org.removePrefix("的").trim()

                    // ── 从日程读当前位置 ──────────────────────────────
                    var location = ""
                    try {
                        val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                        val nowCal = java.util.Calendar.getInstance()
                        val nowTotal = nowCal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + nowCal.get(java.util.Calendar.MINUTE)
                        db.rawQuery(
                            "SELECT startTime, eventDesc FROM Schedules WHERE aiId=? AND dateStr=? ORDER BY startTime ASC",
                            arrayOf(aiId, todayStr)
                        ).use { c ->
                            var lastDesc = ""
                            while (c.moveToNext()) {
                                val t = c.getString(0) ?: ""
                                val desc = c.getString(1) ?: ""
                                val parts = t.split(":")
                                if (parts.size == 2) {
                                    val h = parts[0].toIntOrNull() ?: 0
                                    val m = parts[1].toIntOrNull() ?: 0
                                    if (h * 60 + m <= nowTotal) lastDesc = desc
                                }
                            }
                            if (lastDesc.isNotEmpty()) location = lastDesc.take(40)
                        }
                    } catch (_: Exception) {}

                    // 清理和兜底位置
                    var safeLocation = location.trim()
                        .replace("当前位置：", "").replace("LOCATION:", "")
                        .replace("Location:", "").replace("📍", "").trim()
                        .substringBefore("，").substringBefore(",")
                        .substringBefore("。").substringBefore(".").substringBefore("\n").trim()
                    val bannedLocations = setOf("安全屋","基地","房间","宿舍","某处","秘密地点","当前位置","未知地点","unknown")
                    if (safeLocation.isBlank() || bannedLocations.contains(safeLocation)) {
                        safeLocation = listOf("厨房","窗边","阳台","走廊","浴室门口","训练场","车里","办公室","会议室","便利店门口","停车场","天台").random()
                    }

                    // ── 提取动作 ──────────────────────────────────────
                    val actionText = Regex("【动作】\\s*(.+?)\\s*(?=【|$)", RegexOption.DOT_MATCHES_ALL)
                        .find(thoughts)?.groupValues?.get(1)?.trim()
                        ?: Regex("\\[ACTION\\](.+?)(?=\\[|$)", RegexOption.DOT_MATCHES_ALL)
                            .find(thoughts)?.groupValues?.get(1)?.trim()
                        ?: run {
                            val sentences = thoughts.split("。", "，", "！", "？", "\n")
                            val actionKw = listOf("走","拿","转身","坐","站","看","拉","推","靠","躺","跑","回","起","接","递","摸","按","敲","抱","拧","缩","紧","点")
                            sentences.firstOrNull { s -> actionKw.any { s.contains(it) } && s.length in 4..25 }?.trim() ?: "常规待机中"
                        }

                    // ── 提取穿着 ──────────────────────────────────────
                    val outfitText = Regex("【穿着】\\s*(.+?)\\s*(?=【|$)", RegexOption.DOT_MATCHES_ALL)
                        .find(thoughts)?.groupValues?.get(1)?.trim()
                        ?: Regex("\\[OUTFIT\\](.+?)(?=\\[|$)", RegexOption.DOT_MATCHES_ALL)
                            .find(thoughts)?.groupValues?.get(1)?.trim()
                        ?: run {
                            val sentences = (thoughts + persona).split("。", "，", "！", "？", "\n")
                            val outfitKw = listOf("穿","戴","衬衫","外套","夹克","T恤","裤子","裙子","西装","制服","运动服","毛衣","卫衣","校服","军装")
                            sentences.firstOrNull { s -> outfitKw.any { s.contains(it) } && s.length in 3..25 }?.trim() ?: "日常便服"
                        }

                    // ── 洗出纯心声 ────────────────────────────────────
                    val pureThoughts = thoughts
                        .replace(Regex("【动作】.*?(\\s*(?=【)|$)"), "")
                        .replace(Regex("【穿着】.*?(\\s*(?=【)|$)"), "")
                        .replace(Regex("【语气】.*?(\\s*(?=【)|$)"), "")
                        .replace(Regex("\\[ACTION\\].*?(\\s*(?=\\[)|$)"), "")
                        .replace(Regex("\\[OUTFIT\\].*?(\\s*(?=\\[)|$)"), "")
                        .trim().ifEmpty { "意识流信号微弱，无法解析……" }

                    // ── 心率 ──────────────────────────────────────────
                    val bpm = when {
                        actionText.any { it in "跑步训练作战冲刺搏击" } -> (140..185).random()
                        actionText.any { it in "走路移动巡逻" } -> (90..110).random()
                        actionText.any { it in "睡觉躺休息" } -> (55..70).random()
                        else -> (72..95).random()
                    }

                    val nowStr = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())

                    // ── 头像base64 ────────────────────────────────────
                    val avatarB64 = if (avatarUri.isNotEmpty()) uriToBase64DataUri(avatarUri) else ""

                    // ── 替换模板占位符 ────────────────────────────────
                    val template = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                        .getString("thoughtsCardTemplate", "") ?: ""

                    // ── 自定义字段：读取用户定义的字段，调AI生成 ────────
                    var customFieldsDef = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                        .getString("thoughtsCardFields", "") ?: ""

                    // 兜底：如果没有单独保存的字段定义，尝试从模板注释里解析
                    if (customFieldsDef.isEmpty() && template.isNotEmpty()) {
                        customFieldsDef = Regex("""<!--FIELDS\s*([\s\S]*?)-->""", RegexOption.IGNORE_CASE)
                            .find(template)?.groupValues?.get(1)?.trim() ?: ""
                    }

                    // 解析自定义字段定义，格式：fieldName=生成指令，每行一个
                    val customFields = mutableMapOf<String, String>()
                    if (customFieldsDef.isNotEmpty()) {
                        // 先检查模板里用到了哪些自定义字段（只生成用到的）
                        val usedFields = customFieldsDef.lines()
                            .filter { it.contains("=") }
                            .map { it.substringBefore("=").trim() }
                            .filter { fieldName ->
                                fieldName.isNotEmpty() && template.contains("{{$fieldName}}")
                            }

                        if (usedFields.isNotEmpty()) {
                            try {
                                val sharedPref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                                var apiUrl = sharedPref.getString("apiUrl", "") ?: ""
                                val apiKey = sharedPref.getString("apiKey", "") ?: ""
                                val model = sharedPref.getString("modelName", "gemini-pro") ?: "gemini-pro"

                                while (apiUrl.endsWith("/")) apiUrl = apiUrl.dropLast(1)
                                if (!apiUrl.endsWith("/chat/completions"))
                                    apiUrl += if (apiUrl.contains("/v1")) "/chat/completions" else "/v1/chat/completions"

                                // 只生成模板里用到的字段
                                val fieldInstructions = customFieldsDef.lines()
                                    .filter { it.contains("=") }
                                    .filter { usedFields.contains(it.substringBefore("=").trim()) }
                                    .joinToString("\n")

                                val prompt = """
你是 $aiNameLocal，当前心声：「$pureThoughts」
当前动作：$actionText
当前穿着：$outfitText
当前位置：$safeLocation

请根据以上信息，生成下列字段的内容。严格按格式输出，每个字段一行：

$fieldInstructions

输出格式（fieldName和内容之间用=号，每行一个字段）：
${usedFields.joinToString("\n") { "$it=（内容）" }}

要求：
1. 内容符合角色人设和当前状态
2. 禁止输出字段名以外的任何解释
3. 内容不能包含换行
""".trimIndent()

                                val bodyJson = JSONObject().apply {
                                    put("model", model)
                                    put("temperature", 0.85)
                                    put("messages", JSONArray().apply {
                                        put(JSONObject().apply {
                                            put("role", "user")
                                            put("content", prompt)
                                        })
                                    })
                                }

                                val request = Request.Builder()
                                    .url(apiUrl)
                                    .addHeader("Authorization", "Bearer $apiKey")
                                    .post(bodyJson.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                                    .build()

                                val resp = Http.client.newBuilder()
                                    .connectTimeout(30, TimeUnit.SECONDS)
                                    .readTimeout(30, TimeUnit.SECONDS)
                                    .build().newCall(request).execute()

                                val respBody = resp.body?.string() ?: ""
                                if (resp.isSuccessful && respBody.isNotEmpty()) {
                                    val content = JSONObject(respBody)
                                        .getJSONArray("choices").getJSONObject(0)
                                        .getJSONObject("message").getString("content").trim()

                                    // 解析返回的字段
                                    content.lines().forEach { line ->
                                        val eqIdx = line.indexOf("=")
                                        if (eqIdx > 0) {
                                            val key = line.substring(0, eqIdx).trim()
                                            val value = line.substring(eqIdx + 1).trim()
                                                .removePrefix("（").removeSuffix("）").trim()
                                            if (key.isNotEmpty() && value.isNotEmpty()) {
                                                customFields[key] = value
                                            }
                                        }
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    fun String.fillPlaceholders(): String {
                        var result = this
                            .replace("{{thoughts}}", pureThoughts)
                            .replace("{{action}}", actionText)
                            .replace("{{outfit}}", outfitText)
                            .replace("{{location}}", safeLocation)
                            .replace("{{org}}", org)
                            .replace("{{bpm}}", bpm.toString())
                            .replace("{{time}}", nowStr)
                            .replace("{{name}}", aiNameLocal)
                            .replace("{{avatar}}", avatarB64)
                        // 替换自定义字段
                        customFields.forEach { (key, value) ->
                            result = result.replace("{{$key}}", value)
                        }
                        return result
                    }

                    val html = if (template.isNotEmpty()) {
                        template.fillPlaceholders()
                    } else {
                        try {
                            assets.open("thoughts_card_default.html")
                                .bufferedReader().readText()
                                .fillPlaceholders()
                        } catch (_: Exception) { "" }
                    }

                    if (html.isNotEmpty()) {
                        runOnUiThread { callJs("renderThoughtsCard(${jsStr(html)})") }
                    }
                } catch (_: Exception) {}
            }.start()
        }

        @JavascriptInterface
        fun onThoughtsCardSettings() {
            runOnUiThread {
                startActivity(Intent(this@ChatActivity, ThoughtsCardSettingsActivity::class.java))
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    // 发送消息
    // ════════════════════════════════════════════════════════════
    // ════════════════════════════════════════════════════════════
    // 屏幕共享：授权 → 前台服务抓帧 → 定时识图 → 角色主动搭话
    // ════════════════════════════════════════════════════════════
    private fun requestScreenShare() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? android.media.projection.MediaProjectionManager
        if (mpm == null) {
            Toast.makeText(this, "此设备不支持屏幕共享", Toast.LENGTH_SHORT).show()
            return
        }
        // 其他角色正在共享的话先停掉，一次只共享给一个角色
        if (ScreenShareService.isRunning) ScreenShareService.stop(this)
        screenShareLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun stopScreenShare() {
        ScreenShareService.stop(this)
        stopScreenShareLoop()
    }

    private fun startScreenShareLoop(firstDelayMs: Long) {
        stopScreenShareLoop()
        val r = object : Runnable {
            override fun run() {
                screenShareTick()
                screenShareHandler.postDelayed(this, SCREEN_SHARE_INTERVAL_MS)
            }
        }
        screenShareRunnable = r
        screenShareHandler.postDelayed(r, firstDelayMs)
    }

    private fun stopScreenShareLoop() {
        screenShareRunnable?.let { screenShareHandler.removeCallbacks(it) }
        screenShareRunnable = null
    }

    private fun screenShareTick() {
        if (!ScreenShareService.isRunning || ScreenShareService.sharingAiId != aiId) {
            stopScreenShareLoop(); return
        }
        if (isGenerating) return  // 正在生成回复，这一轮跳过
        Thread {
            val frame = ScreenShareService.captureFrame() ?: return@Thread
            val (b64, hash) = frame
            // 画面基本没变就不浪费一次识图调用
            if (lastScreenHash != 0L && java.lang.Long.bitCount(lastScreenHash xor hash) <= SCREEN_HASH_SAME_THRESHOLD) return@Thread
            lastScreenHash = hash
            runOnUiThread {
                if (!isDestroyed && !isGenerating) triggerAiResponse(screenFrameB64 = b64)
            }
        }.start()
    }

    /** 用户切到别的 app 时，角色对屏幕的评论用通知提醒 */
    private fun notifyScreenReply(text: String) {
        try {
            val channelId = "screen_share_msg"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(channelId, "屏幕共享消息",
                    android.app.NotificationManager.IMPORTANCE_HIGH)
                getSystemService(android.app.NotificationManager::class.java)?.createNotificationChannel(channel)
            }
            val tapIntent = Intent(this, ChatActivity::class.java).apply {
                putExtra("AI_ID", aiId); putExtra("AI_NAME", aiName)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pi = android.app.PendingIntent.getActivity(this, aiId.hashCode(), tapIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(aiName)
                .setContentText(text.take(60))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .build()
            getSystemService(android.app.NotificationManager::class.java)
                ?.notify(("screen$aiId").hashCode(), notification)
        } catch (_: Exception) {}
    }

    private fun getMyDisplayName(): String {
        return try {
            DatabaseHelper(this).readableDatabase.rawQuery(
                "SELECT IFNULL(myName,'') FROM MyProfile ORDER BY id DESC LIMIT 1", null
            ).use { c -> if (c.moveToFirst()) c.getString(0).orEmpty().ifBlank { "我" } else "我" }
        } catch (_: Exception) { "我" }
    }

    private fun setCharacterStatus(rawStatus: String) {
        val allowed = listOf("开心", "想你", "思念", "生气", "郁闷", "emo", "委屈", "害羞", "吃醋", "平静", "忙碌", "困了", "兴奋", "被你拍了一下", "在线")
        val normalized = rawStatus.trim().take(12)
        characterStatus = allowed.firstOrNull { it.equals(normalized, ignoreCase = true) } ?: normalized.ifBlank { "在线" }
        getSharedPreferences("AppConfig", Context.MODE_PRIVATE).edit()
            .putString("chat_status_$aiId", characterStatus).apply()
        callJs("setCharacterStatus(${jsStr(characterStatus)})")
    }

    private fun showPokeSettingsDialog() {
        val prefs = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        val helpText = android.widget.TextView(this).apply {
            text = "双击角色头像可拍一拍；长按头像可再次打开本设置。"
            textSize = 13f
            setPadding(0, 0, 0, dp(12))
        }
        val userToAi = android.widget.EditText(this).apply {
            hint = "我拍角色后的话，例如：说赔钱！"
            setText(prefs.getString("poke_user_to_ai_$aiId", ""))
            setSingleLine(true)
        }
        val aiToUser = android.widget.EditText(this).apply {
            hint = "角色拍我后的话，例如：说别不理我"
            setText(prefs.getString("poke_ai_to_user_$aiId", ""))
            setSingleLine(true)
        }
        root.addView(helpText)
        root.addView(userToAi)
        root.addView(aiToUser)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("设置拍一拍")
            .setView(root)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                prefs.edit()
                    .putString("poke_user_to_ai_$aiId", userToAi.text.toString().trim().take(30))
                    .putString("poke_ai_to_user_$aiId", aiToUser.text.toString().trim().take(30))
                    .apply()
                Toast.makeText(this, "拍一拍话语已保存", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun addPokeEvent(fromUser: Boolean, suffix: String = "") {
        val sender = if (fromUser) getMyDisplayName() else aiName
        val target = if (fromUser) aiName else getMyDisplayName()
        val tail = suffix.trim().let { if (it.isEmpty()) "" else " $it" }
        val msg = Message("$sender 拍了拍 $target$tail", fromUser, true, true).apply {
            timestamp = nextTimestamp()
            imageDesc = "[POKE]"
        }
        msgList.add(msg)
        addMessageToWebView(msg)
        saveMsgToDb(msg, if (fromUser) 1 else 0, "")
    }

    private fun addMoneyCard(type: String, amount: Double, note: String) {
        val safeAmount = amount.coerceIn(0.01, 200000.0)
        val kind = if (type == "redpacket") "redpacket" else "transfer"
        val card = JSONObject().apply {
            put("amount", safeAmount)
            put("note", note.trim().take(40))
            put("opened", false)
        }
        val prefix = if (kind == "redpacket") "[REDPACKET_CARD]" else "[TRANSFER_CARD]"
        val label = if (kind == "redpacket") "[红包]" else "[转账]"
        val msg = Message("$label ¥${"%.2f".format(Locale.US, safeAmount)} ${note.trim()}", false, false, false).apply {
            timestamp = nextTimestamp()
            imageDesc = prefix + card.toString()
        }
        msgList.add(msg)
        addMessageToWebView(msg)
        saveMsgToDb(msg, 0, "")
    }

    private fun receiveMoneyCard(msgId: String, type: String) {
        val ts = msgId.toLongOrNull() ?: return
        val msg = msgList.firstOrNull { it.timestamp == ts } ?: return
        val prefix = if (type == "redpacket") "[REDPACKET_CARD]" else "[TRANSFER_CARD]"
        val raw = msg.imageDesc.orEmpty()
        if (!raw.startsWith(prefix)) return
        val card = try { JSONObject(raw.removePrefix(prefix)) } catch (_: Exception) { return }
        if (card.optBoolean("opened", false)) return
        val amount = card.optDouble("amount", 0.0)
        if (amount <= 0.0) return
        card.put("opened", true)
        card.put("openedAt", System.currentTimeMillis())
        msg.imageDesc = prefix + card.toString()
        try {
            val db = DatabaseHelper(this).writableDatabase
            db.execSQL("UPDATE ChatHistory SET imageDesc=? WHERE aiId=? AND timestamp=?", arrayOf(msg.imageDesc, aiId, ts))
            db.execSQL("CREATE TABLE IF NOT EXISTS LedgerRecords (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, category TEXT, amount REAL, note TEXT, dateStr TEXT, timestamp INTEGER)")
            db.insert("LedgerRecords", null, ContentValues().apply {
                put("type", "income")
                put("category", if (type == "redpacket") "红包" else "转账")
                put("amount", amount)
                put("note", "收到$aiName${if (type == "redpacket") "的红包" else "的转账"}")
                put("dateStr", SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()))
                put("timestamp", System.currentTimeMillis())
            })
        } catch (e: Exception) { Log.e("MONEY_CARD", e.stackTraceToString()) }
        callJs("updateMoneyCard(${jsStr(ts.toString())}, ${jsStr(type)}, ${jsStr(card.toString())})")
        val action = if (type == "redpacket") "领取了红包" else "确认了收款"
        val notice = Message("${getMyDisplayName()} $action ¥${"%.2f".format(Locale.US, amount)}", true, true, true).apply {
            timestamp = nextTimestamp(); imageDesc = "[POKE]"
        }
        msgList.add(notice); addMessageToWebView(notice); saveMsgToDb(notice, 1, "")
    }

    private fun handleSend(content: String) {
        val blockPref = getSharedPreferences("BlockList", Context.MODE_PRIVATE)

        val myMsg = Message(content, true, false, false).apply {
            timestamp = nextTimestamp()
            val q = quotingMessage
            if (q != null) {
                val who = if (q.isFromMe) "你" else aiName
                val rawQuoteText = q.content.ifEmpty { "【图片】" }
                val qText = if (rawQuoteText.length > 50) rawQuoteText.take(50) + "..." else rawQuoteText
                innerThoughts = buildQuoteInner(who, qText, q.imageDesc ?: "")
            }
        }

        msgList.add(myMsg)
        addMessageToWebView(myMsg)
        callJs("setInputHint(${jsStr("消息...")})")
        quotingMessage = null
        saveMsgToDb(myMsg, 1, "")
        startPatienceTimer()
    }

    // ════════════════════════════════════════════════════════════
    // AI 响应（核心逻辑保持不变，只把输出改为注入WebView）
    // ════════════════════════════════════════════════════════════
    private fun processAiSpecialActions(rawDialog: String): String {
        var dialog = rawDialog

        val statusRegex = Regex("\\[STATUS[:：]([^\\]]{1,12})]", RegexOption.IGNORE_CASE)
        statusRegex.find(dialog)?.groupValues?.getOrNull(1)?.let { setCharacterStatus(it) }
        dialog = dialog.replace(statusRegex, "").trim()

        val pokeRegex = Regex("\\[POKE_USER(?:[:：]([^\\]]{0,40}))?]", RegexOption.IGNORE_CASE)
        pokeRegex.findAll(dialog).toList().forEach { match ->
            val custom = match.groupValues.getOrNull(1).orEmpty().trim()
            val configured = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                .getString("poke_ai_to_user_$aiId", "")?.trim().orEmpty()
            addPokeEvent(fromUser = false, suffix = custom.ifEmpty { configured })
        }
        dialog = dialog.replace(pokeRegex, "").trim()

        val transferRegex = Regex("\\[TRANSFER[:：]([0-9]+(?:\\.[0-9]{1,2})?)[:：]([^\\]]{0,40})]", RegexOption.IGNORE_CASE)
        transferRegex.findAll(dialog).toList().forEach { match ->
            match.groupValues[1].toDoubleOrNull()?.let { addMoneyCard("transfer", it, match.groupValues[2]) }
        }
        dialog = dialog.replace(transferRegex, "").trim()

        val redPacketRegex = Regex("\\[REDPACKET[:：]([0-9]+(?:\\.[0-9]{1,2})?)[:：]([^\\]]{0,40})]", RegexOption.IGNORE_CASE)
        redPacketRegex.findAll(dialog).toList().forEach { match ->
            match.groupValues[1].toDoubleOrNull()?.let { addMoneyCard("redpacket", it, match.groupValues[2]) }
        }
        dialog = dialog.replace(redPacketRegex, "").trim()
        return dialog
    }

    private fun confirmDangerousMcpCall(binding: McpToolBinding, arguments: JSONObject): Boolean {
        if (!binding.dangerous) return true
        if (isFinishing || isDestroyed) return false
        val latch = java.util.concurrent.CountDownLatch(1)
        val approved = java.util.concurrent.atomic.AtomicBoolean(false)
        runOnUiThread {
            if (isFinishing || isDestroyed) { latch.countDown(); return@runOnUiThread }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("允许角色使用工具？")
                .setMessage("服务：${binding.server.name}\n工具：${binding.originalName}\n\n参数：${arguments.toString(2).take(1200)}\n\n这个工具可能会发送、修改、删除或产生费用。")
                .setNegativeButton("拒绝") { _, _ -> latch.countDown() }
                .setPositiveButton("仅本次允许") { _, _ -> approved.set(true); latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .show()
        }
        latch.await(120, java.util.concurrent.TimeUnit.SECONDS)
        return approved.get()
    }

    private fun triggerAiResponse(screenFrameB64: String? = null) {
        if (isGenerating) return
        val blockPref = getSharedPreferences("BlockList", Context.MODE_PRIVATE)
        if (blockPref.getBoolean("ai_blocks_user_$aiId", false)) return
        if (blockPref.getBoolean(aiId, false)) return

        isGenerating = true
        callJs("showTyping()")

        val sharedPref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val url = sharedPref.getString("apiUrl", "") ?: ""
        val key = sharedPref.getString("apiKey", "") ?: ""

        if (url.isBlank() || key.isBlank()) {
            runOnUiThread {
                Toast.makeText(this, "请先设置API", Toast.LENGTH_LONG).show()
                isGenerating = false
                callJs("hideTyping()")
            }
            return
        }

        val model = sharedPref.getString("modelName", "gemini-pro") ?: "gemini-pro"
        val temp = sharedPref.getInt("temperature", 6) / 10.0
        val aiLang = sharedPref.getString("aiLang_$aiId", "默认 (中文)") ?: "默认 (中文)"
        val isTtsEnabled = sharedPref.getBoolean("ttsEnable_$aiId", false)
        val voiceId = sharedPref.getString("voiceId_$aiId", "") ?: ""
        val voiceProb = sharedPref.getInt("voiceProb_$aiId", 30).coerceIn(0, 100)

        var finalUrl = if (url.endsWith("/")) url.dropLast(1) else url
        if (!finalUrl.endsWith("/chat/completions")) {
            finalUrl += if (finalUrl.contains("/v1")) "/chat/completions" else "/v1/chat/completions"
        }

        val mainHandler = Handler(Looper.getMainLooper())
        val activity = this

        Thread {
            try {
                val db = DatabaseHelper(activity).readableDatabase
                var aiPersona = ""
                db.query("Contacts", null, "userId=?", arrayOf(aiId), null, null, null).use { c ->
                    if (c.moveToFirst()) aiPersona = c.getSafeString("identityInfo")
                }

                val cyberMemory = MemoryManager.recallRelevantMemory(this, aiId,
                    msgList.lastOrNull { it.isFromMe && !it.isSystem }?.content ?: "")
                val lastUserText = msgList.lastOrNull { it.isFromMe && !it.isSystem }?.content ?: ""
                val worldBookContext = buildWorldBookContext(db, aiId, lastUserText)
                val lifeContext = buildLifeContext(db)
                val petContext = buildPetContext(db, aiId)

                val nowTime = SimpleDateFormat("yyyy年MM月dd日 EEEE HH:mm", Locale.CHINA).format(Date())
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val timeContext = when (hour) {
                    in 5..8 -> "清晨"; in 9..11 -> "上午"; in 12..13 -> "中午"
                    in 14..16 -> "下午"; in 17..18 -> "傍晚"; in 19..21 -> "晚上"
                    in 22..23 -> "深夜"; else -> "凌晨"
                }

                val whisperOn = whisperMode
                val isChinese = aiLang == "默认 (中文)"
                // ★ 把格式写死一点，从源头减少掉翻译
                val langRule = if (isChinese) (if (whisperOn) "台词必须使用中文" else "台词必须使用中文，禁止动作描写")
                else "【台词】只写${aiLang}，一个中文都不许出现；【翻译】另起一行写对应的完整中文翻译，必须有不能省"

                val chatRules = if (whisperOn) """
【一起玩模式】：现在是你们两人的私密亲密时刻。
1. 【台词】里允许使用 *动作描写*，感官细节直白细腻，不回避
2. 节奏跟随用户推进，语言直白，同时保持你的人设和语气
3. 依旧是发消息的形式，一条条短消息有呼吸感，不写大段小说
【玩具控制】：用户身上戴着一个由你控制的互动玩具。需要时在【台词】末尾加 [TOY:n]（n=1~9，一档最轻、九档最强）调整档位，[TOY:STOP] 停止。根据情境和节奏自然升降档，不必每条消息都带指令。""".trim() else """
【线上聊天铁律】：
1. 严禁任何动作描写、旁白、*号动作
2. 【台词】只包含纯文字聊天内容
3. 说话简短自然，符合真实微信聊天风格""".trim()

                val interactiveFeatureRules = """
【聊天互动能力】
1. 每次回复最前面输出一个角色动态标签，格式 [STATUS:状态]。状态从开心、想你、思念、生气、郁闷、emo、委屈、害羞、吃醋、平静、忙碌、困了、兴奋中选择；标签不会显示在气泡里。
2. 你想主动拍一拍用户时，可在台词任意位置加入 [POKE_USER:拍一拍后的话]。例如 [POKE_USER:说别不理我]。不要每轮都使用。
3. 你确实想给用户转账时，加入 [TRANSFER:金额:备注]，例如 [TRANSFER:52.00:买杯奶茶]。
4. 你确实想发红包时，加入 [REDPACKET:金额:祝福语]，例如 [REDPACKET:8.88:恭喜发财]。
5. 金额必须为正数且最多两位小数。转账、红包应符合人设和对话情境，偶尔使用，不能每轮发送。所有标签必须保持英文大写格式。
""".trimIndent()

                val systemPrompt = """
你是 $aiName，人设：${aiPersona.take(1500)}。
【当前时间】：$nowTime（$timeContext）
【潜意识】：${cyberMemory.take(800)}
${if (worldBookContext.isNotEmpty()) "【世界书】：\n${worldBookContext.take(1800)}" else ""}
${if (lifeContext.isNotEmpty()) "【用户生活事项】：\n${lifeContext.take(1200)}" else ""}
${if (petContext.isNotEmpty()) "【共同宠物与近期照料】：\n${petContext.take(1600)}" else ""}
$chatRules
$interactiveFeatureRules
【点歌功能】：当用户请求推歌/点歌/想听歌/问喜欢什么歌时，在【台词】末尾追加 [MUSIC:歌名 歌手名]（如 [MUSIC:海阔天空 Beyond]），系统会自动弹出音乐卡片。每次最多推荐一首。
【语言规则】：$langRule
【格式指令】：每条消息用 <|SPLIT|> 分隔，每段包含：
【内心】心理活动(中文)
【台词】发送内容${if (!isChinese) "\n【翻译】中文翻译" else ""}
""".trimIndent()

                // ── 提前计算需要注入的上下文数据 ────────────────────
                // 一起听歌
                val listeningAiId = sharedPref.getString("listeningTogetherAiId", "") ?: ""
                val currentSong = if (listeningAiId == aiId)
                    (sharedPref.getString("currentSong", "") ?: "").replace("▶", "").replace("⏸", "").replace("▷", "").trim()
                else ""
                val currentArtist = if (currentSong.isNotEmpty()) sharedPref.getString("currentSongArtist", "") ?: "" else ""
                val currentComments = if (currentSong.isNotEmpty()) sharedPref.getString("currentSongComments", "") ?: "" else ""
                if (currentSong.isNotEmpty()) {
                    sharedPref.edit()
                        .remove("currentSong").remove("currentSongArtist")
                        .remove("currentSongComments").remove("currentSongCover")
                        .remove("listeningTogetherAiId").apply()
                }

                // 小红书
                val lastUserMsgForXhs = msgList.lastOrNull { it.isFromMe && !it.isSystem }
                val xhsPattern = Regex("(https?://)?(www\\.)?(xhslink\\.com|xiaohongshu\\.com)/\\S+")
                val xhsLink = lastUserMsgForXhs?.content?.let { xhsPattern.find(it)?.value }
                    ?.let { if (it.startsWith("http")) it else "https://$it" }
                val xhsContent: String? = if (xhsLink != null) activity.fetchXhsContent(xhsLink) else null

                // UsageStats
                val currentHourForUsage = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val isLateNight = currentHourForUsage in 0..4
                val lastUserMsgForUsage = msgList.lastOrNull { it.isFromMe && !it.isSystem }
                val gapMinutes = if (lastUserMsgForUsage != null)
                    (System.currentTimeMillis() - lastUserMsgForUsage.timestamp) / 60_000L else 0L
                val leaveWords = listOf("去睡","睡觉","睡了","去忙","忙去了","有事","去做事",
                    "待会","一会回来","先去","去洗澡","去吃饭","出门了",
                    "go to sleep","going to sleep","gotta go","be back","brb")
                val userSaidLeave = leaveWords.any { (lastUserMsgForUsage?.content ?: "").contains(it, ignoreCase = true) }
                val shouldInjectUsage = (!isLateNight && gapMinutes >= 15) || userSaidLeave
                val usageSummaryInject: String = if (shouldInjectUsage)
                    activity.getRecentAppUsageSummary(if (gapMinutes > 0) gapMinutes.coerceAtMost(120) else 30L)
                else ""
                val gapDesc = when {
                    gapMinutes >= 60 -> "约${gapMinutes / 60}小时${gapMinutes % 60}分钟"
                    gapMinutes > 0 -> "约${gapMinutes}分钟"
                    else -> "一段时间"
                }

                // AI自动引用
                val shouldAiQuote = (1..100).random() <= 5
                val lastUserForQuote = msgList.lastOrNull { it.isFromMe && !it.isSystem }
                val quoteText = if (shouldAiQuote && lastUserForQuote != null) {
                    if (lastUserForQuote.content.length > 40)
                        lastUserForQuote.content.take(40) + "..." else lastUserForQuote.content
                } else ""

                val messagesArray = JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })

                    // ── 一起听歌上下文注入 ────────────────────────────
                    if (currentSong.isNotEmpty()) {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", "【当前播放】你和用户正在一起听《$currentSong》- $currentArtist。你只需要在这次回复里自然地提一次这首歌就好，之后不要再反复提及歌名。热门评论：${currentComments.substring(0, minOf(300, currentComments.length))}")
                        })
                    }

                    // ── 小红书链接解析注入 ────────────────────────────
                    if (xhsContent != null) {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", "【用户分享的小红书内容】：\n${(xhsContent as String).take(1500)}\n请基于以上内容自然地回应用户。")
                        })
                    }

                    // ── UsageStats感知注入 ────────────────────────────
                    if (usageSummaryInject.isNotEmpty()) {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", "【时间流逝感知】用户刚刚消失了${gapDesc}后重新回来发消息。这段时间的手机使用记录：\n${usageSummaryInject.substring(0, minOf(500, usageSummaryInject.length))}\n你必须在这次回复的【台词】里，先自然地回应他消失这件事。禁止无视时间流逝直接接着之前的话题聊。")
                        })
                    }

                    // ── AI自动引用 ────────────────────────────────────
                    if (quoteText.isNotEmpty()) {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", "【引用提示】你可以在这次回复里引用用户刚才说的「$quoteText」，在台词开头用格式：[引用:$quoteText] 来表示你在引用这句话，然后继续你的回复。这是可选的，自然的时候再用。")
                        })
                    }

                    // 历史消息
                    val depth = sharedPref.getString("history_depth", "40")?.toIntOrNull()?.coerceIn(10, 40) ?: 40
                    val historyMsgs = mutableListOf<Message>()
                    try {
                        db.rawQuery(
                            "SELECT content, isFromMe, timestamp, innerThoughts, imageDesc FROM ChatHistory " +
                                    "WHERE aiId=? AND (groupId IS NULL OR groupId='') AND content != '正在输入...' " +
                                    "ORDER BY timestamp DESC LIMIT ?",
                            arrayOf(aiId, depth.toString())
                        ).use { hc ->
                            while (hc.moveToNext()) {
                                historyMsgs.add(0, Message(hc.getString(0) ?: "", hc.getInt(1) == 1, false, false).apply {
                                    timestamp = hc.getLong(2)
                                    innerThoughts = hc.getString(3) ?: ""
                                    imageDesc = hc.getString(4) ?: ""
                                })
                            }
                        }
                    } catch (_: Exception) {}
                    for (m in historyMsgs) putMsg(this, m)

                    // ── 屏幕共享：把当前屏幕帧作为多模态消息注入（不落库，只给模型看）──
                    if (screenFrameB64 != null) {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", "【屏幕共享】我正在给你实时共享手机屏幕，这是此刻的画面。像朋友凑在旁边看我手机那样，自然地主动搭话：可以吐槽、好奇、提问、接梗。不要逐条描述画面，也别提「截图」「画面」这类词，就当你亲眼看着我的手机。如果这个画面实在没什么值得说的，就让【台词】留空。")
                                })
                                put(JSONObject().apply {
                                    put("type", "image_url")
                                    put("image_url", JSONObject().apply { put("url", screenFrameB64) })
                                })
                            })
                        })
                    }
                }

                val bodyJson = JSONObject().apply {
                    put("model", model); put("temperature", temp); put("messages", messagesArray)
                    // 硬上限：防止模型陷入复读/数数退化时无限吐 token（既烧钱又灌爆聊天记录）
                    put("max_tokens", 1500)
                }

                val mcpManager = McpManager(activity)
                val mcpBindings = try { mcpManager.toolBindings() } catch (_: Exception) { emptyList() }
                if (mcpBindings.isNotEmpty()) bodyJson.put("tools", mcpManager.openAiTools(mcpBindings))

                var body: String? = null
                var responseCode = 0
                var apiFailed = false
                // 模型可连续选择工具；工具结果作为 role=tool 回传，最多四轮防止失控循环。
                for (toolRound in 0 until 4) {
                    val request = Request.Builder()
                        .url(finalUrl)
                        .addHeader("Authorization", "Bearer $key")
                        .post(bodyJson.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                        .build()
                    httpClient.newCall(request).execute().use { response ->
                        responseCode = response.code
                        body = response.body?.string()
                        apiFailed = !response.isSuccessful || body == null
                    }
                    if (apiFailed) break

                    val assistantNode = JSONObject(body!!).getJSONArray("choices")
                        .getJSONObject(0).getJSONObject("message")
                    val toolCalls = assistantNode.optJSONArray("tool_calls")
                    if (toolCalls == null || toolCalls.length() == 0) break

                    messagesArray.put(assistantNode)
                    for (i in 0 until toolCalls.length()) {
                        val call = toolCalls.optJSONObject(i) ?: continue
                        val function = call.optJSONObject("function") ?: continue
                        val exposedName = function.optString("name")
                        val binding = mcpBindings.firstOrNull { it.exposedName == exposedName }
                        val arguments = try { JSONObject(function.optString("arguments", "{}")) }
                            catch (_: Exception) { JSONObject() }
                        val toolResult = when {
                            binding == null -> "工具不存在或已被卸载"
                            !confirmDangerousMcpCall(binding, arguments) -> "用户拒绝了本次工具调用。不要声称操作已经完成。"
                            else -> try { mcpManager.callTool(binding, arguments) }
                                catch (e: Exception) { "工具调用失败：${e.message}" }
                        }
                        messagesArray.put(JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", call.optString("id"))
                            put("content", toolResult)
                        })
                    }
                    bodyJson.put("messages", messagesArray)
                    if (toolRound == 3) {
                        messagesArray.put(JSONObject().apply {
                            put("role", "system")
                            put("content", "工具调用轮数已达到上限，请根据已有结果直接回答用户。")
                        })
                    }
                }

                // 第四轮仍返回工具请求时，去掉工具定义再请求一次总结，避免把空 content 当回复。
                val stillRequestsTools = try {
                    JSONObject(body ?: "{}").optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("message")?.optJSONArray("tool_calls")?.length()?.let { it > 0 } == true
                } catch (_: Exception) { false }
                if (!apiFailed && stillRequestsTools) {
                    bodyJson.remove("tools")
                    val finalRequest = Request.Builder().url(finalUrl)
                        .addHeader("Authorization", "Bearer $key")
                        .post(bodyJson.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                        .build()
                    httpClient.newCall(finalRequest).execute().use { response ->
                        responseCode = response.code
                        body = response.body?.string()
                        apiFailed = !response.isSuccessful || body == null
                    }
                }

                if (apiFailed || body == null) {
                    val errDetail = try {
                        if (body != null) JSONObject(body).optJSONObject("error")?.optString("message")
                            ?.takeIf { it.isNotBlank() } ?: body.take(300)
                        else "无响应内容"
                    } catch (_: Exception) { body?.take(300) ?: "无响应内容" }
                    android.util.Log.e("CHAT_API_ERROR", "code=$responseCode body=$body")
                    mainHandler.post {
                        isGenerating = false
                        callJs("hideTyping()")
                        Toast.makeText(this@ChatActivity, "API错误 $responseCode：$errDetail", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }

                // content 可能是 null（部分推理模型只回 reasoning_content），getString 会直接抛异常
                // 被当成"网络错误"，用户只看到正在输入结束却没内容。宽容提取 + reasoning_content 兜底
                val msgNode = JSONObject(body)
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message")
                var replyContent = msgNode.optString("content", "").trim()
                if (replyContent == "null" || replyContent.isEmpty()) {
                    replyContent = msgNode.optString("reasoning_content", "").trim()
                    if (replyContent == "null") replyContent = ""
                }

                val leadingStatusRegex = Regex("\\[STATUS[:：]([^\\]]{1,12})]", RegexOption.IGNORE_CASE)
                val replyStatus = leadingStatusRegex.find(replyContent)?.groupValues?.getOrNull(1).orEmpty()
                replyContent = replyContent.replace(leadingStatusRegex, "").trim()

                // 双保险：即使模型退化吐出大量 <|SPLIT|>，最多只取前 12 段，绝不灌爆气泡/数据库
                val rawBlocks = replyContent.split("<|SPLIT|>").map { it.trim() }.filter { it.isNotEmpty() }
                // 模型有时不写 <|SPLIT|> 而是连写多组【内心】【台词】：按【内心】边界再拆，避免多轮回复挤在一条或丢失
                val expandedBlocks = rawBlocks.flatMap { b ->
                    val normalized = normalizeMarkers(b)
                    if (Regex("【台词】").findAll(normalized).count() >= 2) {
                        Regex("(?=【内心】)").split(normalized).map { it.trim() }.filter { it.isNotEmpty() }
                    } else listOf(b)
                }
                // 非中文角色：模型可能把中文翻译当成独立分段输出（导致翻译变成单独气泡）。
                // 纯中文、无任何标记的分段，如果上一段是不含中文的外语台词，就并回去当【翻译】
                val blocks = if (isChinese) expandedBlocks.take(12) else {
                    val merged = mutableListOf<String>()
                    for (b in expandedBlocks) {
                        val prev = merged.lastOrNull()
                        val isPureCjk = b.isNotEmpty() && !b.contains("【") &&
                                b.any { it.code in 0x4E00..0x9FFF } &&
                                b.none { it in 'a'..'z' || it in 'A'..'Z' }
                        val prevIsForeign = prev != null && !prev.contains("【翻译】") &&
                                normalizeMarkers(prev).let { p ->
                                    val d = Regex("【台词】\\s*([\\s\\S]*?)(?=【|$)").find(p)?.groupValues?.get(1) ?: p
                                    d.none { it.code in 0x4E00..0x9FFF } && d.any { it.isLetter() }
                                }
                        if (isPureCjk && prevIsForeign) {
                            merged[merged.size - 1] = prev + "\n【翻译】" + b
                        } else merged.add(b)
                    }
                    merged.take(12)
                }

                mainHandler.post {
                    if (isDestroyed) return@post
                    isGenerating = false
                    callJs("hideTyping()")
                    if (replyStatus.isNotBlank()) setCharacterStatus(replyStatus)

                    var emittedMessages = 0
                    for (block in blocks) {
                        // ★ 解析：标记更宽容（内心/台词/翻译/译都认），翻译一路抓到结尾
                        val parsed = parseReplyBlock(block, isChinese, keepActions = whisperOn)
                        val inner = parsed.inner
                        var dialog = processAiSpecialActions(parsed.dialog)
                        val trans = parsed.trans

                        // AI 自动引用：把模型输出的 [引用:xxx] 从正文里剥离，转成真正的引用卡片
                        var aiQuoteText = ""
                        var aiQuoteImage = ""
                        Regex("^\\s*\\[(?:引用|QUOTE)[:：](.*?)\\]\\s*", RegexOption.DOT_MATCHES_ALL)
                            .find(dialog)?.let { m ->
                                aiQuoteText = m.groupValues.getOrElse(1) { "" }.trim()
                                val lastQuoted = lastUserForQuote
                                if (lastQuoted != null && lastQuoted.content == "【图片】") {
                                    aiQuoteText = aiQuoteText.ifEmpty { "【图片】" }
                                    val rawImg = lastQuoted.imageDesc ?: ""
                                    aiQuoteImage = when {
                                        rawImg.startsWith("[REAL_IMG]") -> uriToBase64DataUri(rawImg.removePrefix("[REAL_IMG]"))
                                        rawImg.startsWith("http://") || rawImg.startsWith("https://") -> rawImg
                                        rawImg.startsWith("file://") || rawImg.startsWith("/") || rawImg.startsWith("content://") -> uriToBase64DataUri(rawImg)
                                        else -> ""
                                    }
                                }
                                dialog = dialog.removeRange(m.range).trim()
                            }

                        // 音乐点歌
                        val musicTag = Regex("\\[MUSIC:([^\\]]+)\\]").find(dialog)
                        if (musicTag != null) {
                            dialog = dialog.replace(musicTag.value, "").trim()
                            searchAndInsertMusicCard(musicTag.groupValues[1].trim())
                        }

                        // 密语时刻：提取玩具档位指令（气泡渲染后再执行，胶囊贴在消息下面）
                        val toyCmds = toyTagRegex.findAll(dialog).map { it.groupValues[1] }.toList()
                        if (toyCmds.isNotEmpty()) dialog = dialog.replace(toyTagRegex, "").trim()

                        if (dialog.isBlank()) {
                            toyCmds.forEach { execToyCommand(it) }
                            continue
                        }

                        val aiMsg = Message(dialog, false, false, false).apply {
                            innerThoughts = if (aiQuoteText.isNotEmpty()) buildQuoteInner("你", aiQuoteText, aiQuoteImage, inner) else inner
                            translatedText = trans
                            timestamp = nextTimestamp()
                            if (isTtsEnabled && voiceId.isNotEmpty() && (1..100).random() <= voiceProb) {
                                isVoice = true; voiceDuration = (3..12).random()
                            }
                        }
                        msgList.add(aiMsg)
                        addMessageToWebView(aiMsg)
                        saveMsgToDb(aiMsg, 0, "")
                        toyCmds.forEach { execToyCommand(it) }
                        emittedMessages++
                        maybeAiRecall(aiMsg)
                        MemoryManager.checkAndSummarizeMemory(this@ChatActivity, aiId, aiName)

                        // ── 记忆：检测关键事件 + AI生活事件 ──────────────
                        val lastAiMsg = msgList.lastOrNull { !it.isFromMe && !it.isSystem }
                        if (lastAiMsg != null) {
                            MemoryManager.checkAiLifeEvent(this@ChatActivity, aiId, aiName, lastAiMsg.content)
                        }
                        val lastUserContent2 = msgList.lastOrNull { it.isFromMe && !it.isSystem }?.content ?: ""
                        if (lastUserContent2.isNotEmpty()) {
                            val lastAiContent = msgList.lastOrNull { !it.isFromMe && !it.isSystem }?.content ?: ""
                            MemoryManager.checkKeyEvent(this@ChatActivity, aiId, aiName, lastUserContent2, lastAiContent)
                        }

                        // ── 生图检测 ──────────────────────────────────────
                        val imgEnabled = sharedPref.getBoolean("imgEnable_chat", false)
                        if (imgEnabled) {
                            val triggerWords = listOf(
                                "拍","拍张","拍一张","发张照片","发个图","照片发我","报备",
                                "在哪呢","在干嘛","干嘛呢","发图来","图来","让我看看",
                                "你在哪","你在哪里","吃的什么","吃了什么","你今天","你现在",
                                "你家","show me","send photo","take a pic"
                            )
                            val lastUserContent = msgList.lastOrNull { it.isFromMe && !it.isSystem }?.content ?: ""
                            val aiWantsImage = dialog.contains("[IMAGE:", ignoreCase = true) ||
                                    dialog.contains("[图片：") || dialog.contains("[图片:")
                            val shouldGenImg = aiWantsImage || triggerWords.any { lastUserContent.contains(it, ignoreCase = true) }

                            if (shouldGenImg) {
                                var cachedPersona = ""
                                var cachedAppearance = ""
                                try {
                                    DatabaseHelper(this@ChatActivity).readableDatabase
                                        .query("Contacts", null, "userId=?", arrayOf(aiId), null, null, null)
                                        .use { c ->
                                            if (c.moveToFirst()) {
                                                cachedPersona = c.getSafeString("identityInfo")
                                                cachedAppearance = c.getSafeString("appearance").ifEmpty { "暂无外貌描写" }
                                            }
                                        }
                                } catch (_: Exception) {}

                                val recentChat = msgList.takeLast(10)
                                    .filter { !it.isSystem && it.content != "正在输入..." }
                                    .joinToString("\n") { m ->
                                        if (m.isFromMe) "用户：${m.content}" else "$aiName：${m.content}"
                                    }

                                val placeholderMsg = Message("📷 正在翻相册...", false, false, false).apply {
                                    timestamp = nextTimestamp()
                                }
                                msgList.add(placeholderMsg)
                                addMessageToWebView(placeholderMsg)

                                ImageGenManager.generateFromChatContext(
                                    context = this@ChatActivity,
                                    aiName = aiName,
                                    aiPersona = cachedPersona,
                                    aiAppearance = cachedAppearance,
                                    recentChat = recentChat
                                ) { localPath, usedPrompt ->
                                    runOnUiThread {
                                        if (isDestroyed) return@runOnUiThread
                                        val idx = msgList.indexOf(placeholderMsg)
                                        if (idx != -1) {
                                            msgList.removeAt(idx)
                                            // 通知WebView移除占位消息
                                            callJs("removeMessageById(${jsStr(placeholderMsg.timestamp.toString())})")
                                        }
                                        if (localPath != null) {
                                            val imgMsg = Message("【我发了一张照片：$usedPrompt】", false, false, false).apply {
                                                imageDesc = localPath
                                                timestamp = nextTimestamp()
                                            }
                                            msgList.add(imgMsg)
                                            addMessageToWebView(imgMsg)
                                            saveMsgToDb(imgMsg, 0, "")
                                        } else {
                                            Toast.makeText(this@ChatActivity, "生图失败，请检查生图API配置", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // 屏幕共享触发：模型可以选择沉默（台词留空），不算异常；
                    // 用户在别的 app 里时，用通知提醒角色说话了
                    if (screenFrameB64 != null) {
                        if (emittedMessages > 0 && !isActivityResumed) {
                            val lastDialog = msgList.lastOrNull { !it.isFromMe && !it.isSystem }?.content ?: "发来一条消息"
                            notifyScreenReply(lastDialog)
                        }
                        return@post
                    }
                    if (emittedMessages == 0) {
                        val fallbackDialog = fallbackDialogFromRaw(replyContent)
                        if (fallbackDialog.isNotBlank()) {
                            val aiMsg = Message(fallbackDialog, false, false, false).apply {
                                timestamp = nextTimestamp()
                            }
                            msgList.add(aiMsg)
                            addMessageToWebView(aiMsg)
                            saveMsgToDb(aiMsg, 0, "")
                        } else {
                            Toast.makeText(this@ChatActivity, "AI返回为空或格式异常，未生成可显示内容", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (_: Exception) {
                mainHandler.post {
                    isGenerating = false
                    callJs("hideTyping()")
                    Toast.makeText(this@ChatActivity, "网络错误或超时", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // ════════════════════════════════════════════════════════════
    // 聊天记录月历与文档导出
    // ════════════════════════════════════════════════════════════
    private fun sendHistoryStatsToWebView() {
        val days = JSONObject()
        var total = 0
        var latest = ""
        try {
            DatabaseHelper(this).readableDatabase.rawQuery(
                """SELECT strftime('%Y-%m-%d', timestamp / 1000, 'unixepoch', 'localtime') day, COUNT(*)
                   FROM ChatHistory
                   WHERE aiId=? AND IFNULL(groupId,'')='' AND timestamp>0 AND content!='正在输入...'
                   GROUP BY day ORDER BY day""", arrayOf(aiId)
            ).use { c ->
                while (c.moveToNext()) {
                    val day = c.getString(0) ?: continue
                    val count = c.getInt(1)
                    days.put(day, count); total += count; latest = day
                }
            }
        } catch (e: Exception) { Log.e("CHAT_HISTORY", e.stackTraceToString()) }
        val result = JSONObject().apply { put("days", days); put("total", total); put("latest", latest) }
        callJs("setHistoryStats(${jsStr(result.toString())})")
    }

    private fun localDayRange(dateKey: String): Pair<Long, Long>? {
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
            val start = format.parse(dateKey)?.time ?: return null
            start to (start + 24L * 60L * 60L * 1000L)
        } catch (_: Exception) { null }
    }

    private fun sendHistoryDayToWebView(dateKey: String) {
        val range = localDayRange(dateKey) ?: run {
            callJs("showHistoryDay(${jsStr(dateKey)}, \"[]\")"); return
        }
        val messages = JSONArray()
        try {
            DatabaseHelper(this).readableDatabase.rawQuery(
                """SELECT content, isFromMe, isVoice, voiceDuration, timestamp
                   FROM ChatHistory WHERE aiId=? AND IFNULL(groupId,'')=''
                   AND timestamp>=? AND timestamp<? AND content!='正在输入...'
                   ORDER BY timestamp ASC, id ASC""",
                arrayOf(aiId, range.first.toString(), range.second.toString())
            ).use { c ->
                val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                while (c.moveToNext()) {
                    messages.put(JSONObject().apply {
                        put("content", c.getString(0) ?: "")
                        put("isFromMe", c.getInt(1) == 1)
                        put("isVoice", c.getInt(2) == 1)
                        put("voiceDuration", c.getInt(3))
                        put("timeStr", timeFormat.format(Date(c.getLong(4))))
                    })
                }
            }
        } catch (e: Exception) { Log.e("CHAT_HISTORY_DAY", e.stackTraceToString()) }
        callJs("showHistoryDay(${jsStr(dateKey)}, ${jsStr(messages.toString())})")
    }

    private fun buildHistoryExport(format: String): String {
        val exportedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val db = DatabaseHelper(this).readableDatabase
        val cursor = db.rawQuery(
            """SELECT id, content, isFromMe, isVoice, voiceDuration, translatedText,
                      innerThoughts, imageDesc, timestamp
               FROM ChatHistory WHERE aiId=? AND IFNULL(groupId,'')='' AND content!='正在输入...'
               ORDER BY timestamp ASC, id ASC""", arrayOf(aiId)
        )
        cursor.use { c ->
            if (format == "json") {
                val messages = JSONArray()
                while (c.moveToNext()) {
                    val timestamp = c.getLong(8)
                    messages.put(JSONObject().apply {
                        put("id", c.getLong(0)); put("content", c.getString(1) ?: "")
                        put("sender", if (c.getInt(2) == 1) "我" else aiName)
                        put("isFromMe", c.getInt(2) == 1)
                        put("isVoice", c.getInt(3) == 1); put("voiceDuration", c.getInt(4))
                        put("translatedText", c.getString(5) ?: "")
                        put("innerThoughts", c.getString(6) ?: "")
                        put("imageDesc", c.getString(7) ?: "")
                        put("timestamp", timestamp)
                        put("time", if (timestamp > 0) timeFormat.format(Date(timestamp)) else "")
                    })
                }
                return JSONObject().apply {
                    put("title", "与${aiName}的聊天记录")
                    put("contactId", aiId); put("contactName", aiName)
                    put("exportedAt", exportedAt); put("messageCount", messages.length())
                    put("messages", messages)
                }.toString(2)
            }

            val rows = mutableListOf<String>()
            while (c.moveToNext()) {
                val timestamp = c.getLong(8)
                val who = if (c.getInt(2) == 1) "我" else aiName
                val content = c.getString(1) ?: ""
                val image = c.getString(7) ?: ""
                val shown = when {
                    image.startsWith("[RECALLED]") -> content
                    image.isNotBlank() && content.isBlank() -> "[图片]"
                    else -> content
                }
                val time = if (timestamp > 0) timeFormat.format(Date(timestamp)) else "未知时间"
                rows.add("[$time] $who\n$shown")
            }
            return buildString {
                appendLine("与${aiName}的聊天记录")
                appendLine("导出时间：$exportedAt")
                appendLine("消息数量：${rows.size}")
                appendLine("========================================")
                rows.forEachIndexed { index, row ->
                    if (index > 0) appendLine()
                    appendLine(row)
                }
            }
        }
    }


    // ════════════════════════════════════════════════════════════
    // 加载历史消息
    // ════════════════════════════════════════════════════════════
    private fun loadChatHistory() {
        msgList.clear()
        callJs("clearMessages()")

        val allMsgs = mutableListOf<Message>()
        try {
            DatabaseHelper(this).readableDatabase.rawQuery(
                """SELECT id, content, isFromMe, msgTime, isVoice, voiceDuration,
                   localVoicePath, translatedText, innerThoughts, imageDesc, timestamp, isRead
                   FROM (SELECT id, content, isFromMe, msgTime, isVoice, voiceDuration,
                         localVoicePath, translatedText, innerThoughts, imageDesc, timestamp, isRead
                         FROM ChatHistory WHERE aiId=? AND (groupId IS NULL OR groupId='')
                         AND content != '正在输入...' ORDER BY timestamp DESC LIMIT 300)
                   ORDER BY timestamp ASC""", arrayOf(aiId)
            ).use { c ->
                while (c.moveToNext()) {
                    // 列顺序：0=id 1=content 2=isFromMe 3=msgTime 4=isVoice 5=voiceDuration
                    //        6=localVoicePath 7=translatedText 8=innerThoughts 9=imageDesc 10=timestamp 11=isRead
                    allMsgs.add(Message(c.getString(1) ?: "", c.getInt(2) == 1, false, false).apply {
                        dbId = c.getLong(0)
                        isVoice = c.getInt(4) == 1; voiceDuration = c.getInt(5)
                        localVoicePath = c.getString(6) ?: ""; translatedText = c.getString(7) ?: ""
                        innerThoughts = c.getString(8) ?: ""; imageDesc = c.getString(9) ?: ""
                        timestamp = c.getLong(10); isRead = c.getInt(11) == 1
                    })
                }
            }
        } catch (e: Exception) { Log.e("LOAD_CHAT", e.stackTraceToString()) }

        msgList.addAll(allMsgs)
        fullMsgList.clear(); fullMsgList.addAll(allMsgs)
        // ★ 让发号器从已加载的最新一条往后走，新消息不会和历史撞 id
        lastTs = allMsgs.maxOfOrNull { it.timestamp } ?: lastTs

        // 先立即显示最近50条，让用户马上能看到内容
        val recent = if (allMsgs.size > 50) allMsgs.takeLast(50) else allMsgs
        val older  = if (allMsgs.size > 50) allMsgs.dropLast(50) else emptyList()

        recent.forEach { addMessageToWebView(it) }

        // 剩余的历史消息在后台分批插入到列表顶部，每批20条
        if (older.isNotEmpty()) {
            Thread {
                val batches = older.chunked(20)
                // ★ 倒序插批次：prepend 是往最顶塞，必须从最后一批往前插，批间顺序才正确
                for ((i, batch) in batches.withIndex().reversed()) {
                    Thread.sleep(if (i == 0) 300L else 100L)
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        // 批量插入：用JS在消息列表顶部prepend
                        val jsonArr = org.json.JSONArray()
                        batch.forEach { msg ->
                            val dateStr = SimpleDateFormat("MM月dd日", Locale.CHINA)
                                .format(Date(if (msg.timestamp > 0) msg.timestamp else System.currentTimeMillis()))
                            val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault())
                                .format(Date(if (msg.timestamp > 0) msg.timestamp else System.currentTimeMillis()))
                            val inner = msg.innerThoughts ?: ""
                            val quote = parseQuoteFromInner(inner)
                            val quoteWho = quote.who
                            val quoteText = quote.text
                            val quoteImage = quote.image
                            var musicCardJson = ""
                            if (msg.imageDesc?.startsWith("[MUSIC_CARD]") == true)
                                musicCardJson = msg.imageDesc!!.removePrefix("[MUSIC_CARD]")
                            var moneyCardJson = ""
                            var moneyCardType = ""
                            if (msg.imageDesc?.startsWith("[TRANSFER_CARD]") == true) {
                                moneyCardType = "transfer"
                                moneyCardJson = msg.imageDesc!!.removePrefix("[TRANSFER_CARD]")
                            } else if (msg.imageDesc?.startsWith("[REDPACKET_CARD]") == true) {
                                moneyCardType = "redpacket"
                                moneyCardJson = msg.imageDesc!!.removePrefix("[REDPACKET_CARD]")
                            }
                            val isRecalled = msg.imageDesc?.startsWith("[RECALLED]") == true
                            val innerForPopup = quote.popupInner
                            jsonArr.put(JSONObject().apply {
                                put("id", msg.timestamp.toString())
                                put("content", msg.content)
                                put("isFromMe", msg.isFromMe)
                                put("timestamp", msg.timestamp)
                                put("timeStr", timeStr)
                                put("dateStr", dateStr)
                                put("isSystem", msg.isSystem || msg.imageDesc?.startsWith("[POKE]") == true)
                                put("isVoice", msg.isVoice)
                                put("voiceDuration", msg.voiceDuration)
                                put("imageUri", "")  // 历史图片不预加载，节省内存
                                put("musicCardJson", musicCardJson)
                                put("moneyCardJson", moneyCardJson)
                                put("moneyCardType", moneyCardType)
                                put("isRecalled", isRecalled)
                                put("innerThoughts", innerForPopup)
                                put("translatedText", msg.translatedText ?: "")
                                put("quoteWho", quoteWho)
                                put("quoteText", quoteText)
                                put("quoteImage", quoteImage)
                            })
                        }
                        callJs("prependMessages(${jsStr(jsonArr.toString())})")
                    }
                }
            }.start()
        }
    }

    // ════════════════════════════════════════════════════════════
    // CSS 主题加载
    // ════════════════════════════════════════════════════════════
    private fun loadAndApplyUserCSS() {
        val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        // 优先读全局主题，没有再读角色单独主题
        val css = pref.getString("globalThemeCSS", "")
            ?.takeIf { it.isNotEmpty() }
            ?: pref.getString("customCSS_$aiId", "") ?: ""
        if (css.isNotEmpty()) {
            callJs("applyUserCSS(${jsStr(css)})")
        }
    }

    /** 更新AI头像（WebView + Adapter) */
    fun updateAiAvatarInWebView(path: String) {
        val b64 = uriToBase64DataUri(path)
        callJs("updateAiAvatar(${jsStr(b64)})")
    }

    // ════════════════════════════════════════════════════════════
    // 壁纸
    // ════════════════════════════════════════════════════════════
    private fun loadChatBackground() {
        val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val bgUri = pref.getString("chatBg_$aiId", "") ?: ""
        if (bgUri.isNotEmpty()) {
            val b64 = uriToBase64DataUri(bgUri)
            callJs("setChatBackground(${jsStr(b64)})")
        } else {
            callJs("setChatBackground(\"\")")
        }
    }

    // ════════════════════════════════════════════════════════════
    // 图片转 base64
    // ════════════════════════════════════════════════════════════
    private fun uriToBase64DataUri(uriStr: String): String {
        if (uriStr.isEmpty()) return ""
        return try {
            val bmp: Bitmap? = when {
                uriStr.startsWith("/") -> BitmapFactory.decodeFile(uriStr)
                uriStr.startsWith("file://") -> BitmapFactory.decodeFile(uriStr.removePrefix("file://"))
                else -> contentResolver.openInputStream(android.net.Uri.parse(uriStr))?.use { BitmapFactory.decodeStream(it) }
            }
            if (bmp == null) return ""
            val maxSize = 800
            val scale = minOf(maxSize.toFloat() / bmp.width, maxSize.toFloat() / bmp.height, 1f)
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, baos)
            "data:image/jpeg;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) { "" }
    }

    // ════════════════════════════════════════════════════════════
    // 以下方法保持原版不变（复制原 ChatActivity 对应实现即可）
    // ════════════════════════════════════════════════════════════

    private fun buildWorldBookContext(
        db: android.database.sqlite.SQLiteDatabase,
        targetAiId: String,
        latestUserText: String
    ): String {
        fun matchesKeyword(keyword: String): Boolean {
            val kw = keyword.trim()
            if (kw.isEmpty()) return true
            return kw.split(Regex("[,，;；\\s]+"))
                .filter { it.isNotBlank() }
                .any { latestUserText.contains(it, ignoreCase = true) }
        }

        val high = mutableListOf<String>()
        val medium = mutableListOf<String>()
        val keyword = mutableListOf<String>()

        try {
            db.rawQuery(
                """
                SELECT keyword, content, IFNULL(priority,'keyword'), IFNULL(targetAiId, IFNULL(aiId,'global')), IFNULL(aiId,'')
                FROM UserWorldBook
                WHERE IFNULL(targetAiId, IFNULL(aiId,'global'))='global'
                   OR IFNULL(targetAiId, '')=?
                   OR IFNULL(aiId, '')=?
                ORDER BY id DESC
                LIMIT 80
                """.trimIndent(),
                arrayOf(targetAiId, targetAiId)
            ).use { c ->
                while (c.moveToNext()) {
                    val kw = c.getString(0) ?: ""
                    val content = (c.getString(1) ?: "").trim()
                    val priority = (c.getString(2) ?: "keyword").lowercase()
                    if (content.isEmpty()) continue
                    when (priority) {
                        "high" -> high.add(content)
                        "medium" -> medium.add(content)
                        else -> if (matchesKeyword(kw)) keyword.add(content)
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            db.rawQuery("SELECT keyword, content FROM AdminWorldBook ORDER BY id DESC LIMIT 40", null).use { c ->
                while (c.moveToNext()) {
                    val kw = c.getString(0) ?: ""
                    val content = (c.getString(1) ?: "").trim()
                    if (content.isNotEmpty() && matchesKeyword(kw)) high.add(content)
                }
            }
        } catch (_: Exception) {}

        return buildString {
            if (high.isNotEmpty()) append("【最高优先】\n").append(high.take(12).joinToString("\n")).append("\n")
            if (medium.isNotEmpty()) append("【常驻补充】\n").append(medium.take(12).joinToString("\n")).append("\n")
            if (keyword.isNotEmpty()) append("【关键词触发】\n").append(keyword.take(12).joinToString("\n"))
        }.trim()
    }

    private fun buildLifeContext(db: android.database.sqlite.SQLiteDatabase): String {
        val parts = mutableListOf<String>()
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS UserTravelPlans (id INTEGER PRIMARY KEY AUTOINCREMENT, travelType TEXT, fromPlace TEXT, toPlace TEXT, tripNo TEXT, departTime TEXT, note TEXT, status TEXT DEFAULT 'active', createdAt INTEGER)")
            db.rawQuery(
                "SELECT travelType, fromPlace, toPlace, tripNo, departTime, note FROM UserTravelPlans WHERE IFNULL(status,'active')='active' ORDER BY id DESC LIMIT 5",
                null
            ).use { c ->
                val rows = mutableListOf<String>()
                while (c.moveToNext()) {
                    val type = c.getString(0) ?: "出行"
                    val from = c.getString(1) ?: ""
                    val to = c.getString(2) ?: ""
                    val no = c.getString(3) ?: ""
                    val time = c.getString(4) ?: ""
                    val note = c.getString(5) ?: ""
                    rows.add("$type $no：$from → $to${if (time.isNotBlank()) "，出发时间 $time" else ""}${if (note.isNotBlank()) "，备注：$note" else ""}")
                }
                if (rows.isNotEmpty()) parts.add("出行计划：\n${rows.joinToString("\n")}")
            }
        } catch (_: Exception) {}

        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS UserPackages (id INTEGER PRIMARY KEY AUTOINCREMENT, trackingNo TEXT, carrier TEXT, itemName TEXT, status TEXT DEFAULT 'active', note TEXT, createdAt INTEGER)")
            db.rawQuery(
                "SELECT trackingNo, carrier, itemName, note FROM UserPackages WHERE IFNULL(status,'active')='active' ORDER BY id DESC LIMIT 8",
                null
            ).use { c ->
                val rows = mutableListOf<String>()
                while (c.moveToNext()) {
                    val no = c.getString(0) ?: ""
                    val carrier = c.getString(1) ?: ""
                    val item = c.getString(2) ?: ""
                    val note = c.getString(3) ?: ""
                    rows.add("${carrier.ifBlank { "快递" }} $no${if (item.isNotBlank()) "，物品：$item" else ""}${if (note.isNotBlank()) "，备注：$note" else ""}")
                }
                if (rows.isNotEmpty()) parts.add("待关注包裹：\n${rows.joinToString("\n")}")
            }
        } catch (_: Exception) {}

        return parts.joinToString("\n\n")
    }

    /**
     * 把共同宠物作为明确的生活事实注入聊天，而不只依赖关键词召回。
     * 这样角色能记得领养关系，也能记得自己在用户离开时做过的照料。
     */
    private fun buildPetContext(db: android.database.sqlite.SQLiteDatabase, targetAiId: String): String {
        return try {
            val pets = mutableListOf<String>()
            db.rawQuery(
                "SELECT id,name,type,breed,color,personality,likes,dislikes,mood,hunger,cleanliness FROM Pets WHERE bondedCharacterId=? ORDER BY isActive DESC, createdAt DESC LIMIT 6",
                arrayOf(targetAiId)
            ).use { c ->
                while (c.moveToNext()) {
                    pets += "共同养育：${c.getString(1)}（${c.getString(4)}${c.getString(3)}/${c.getString(2)}），性格${c.getString(5)}，喜欢${c.getString(6)}，不喜欢${c.getString(7)}；当前心情${c.getInt(8)}、饥饿${c.getInt(9)}、清洁${c.getInt(10)}。"
                }
            }
            if (pets.isEmpty()) return ""
            val events = mutableListOf<String>()
            db.rawQuery(
                "SELECT p.name,e.action,e.actor,e.dialogue,e.createdAt FROM PetEvents e JOIN Pets p ON p.id=e.petId WHERE p.bondedCharacterId=? ORDER BY e.createdAt DESC LIMIT 8",
                arrayOf(targetAiId)
            ).use { c ->
                val fmt = SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA)
                while (c.moveToNext()) {
                    val actionName = when (c.getString(1)) {
                        "adopt" -> "共同领养"; "play" -> "陪玩"; "groom" -> "梳毛"
                        "feed" -> "投喂"; "clean" -> "清洁小窝"; "walk" -> "遛弯"; else -> c.getString(1)
                    }
                    events += "${fmt.format(Date(c.getLong(4)))}，${c.getString(2)}为${c.getString(0)}$actionName：${c.getString(3)}"
                }
            }
            buildString {
                append(pets.joinToString("\n"))
                if (events.isNotEmpty()) append("\n近期真实事件：\n").append(events.joinToString("\n"))
                append("\n这些是你与用户共同经历的真实生活记忆。保持自身人设，自然记得即可，不要每句话都强行提起。")
            }
        } catch (_: Exception) { "" }
    }

    private fun checkPendingAiMessages() {
        Thread {
            try {
                val db = DatabaseHelper(this).writableDatabase
                // 先检查当前耐心值；若已禁用，清理残留队列并跳过
                val currentPatience = try {
                    db.rawQuery("SELECT patience FROM Contacts WHERE userId=?", arrayOf(aiId))
                        .use { c -> if (c.moveToFirst()) c.getInt(0) else 60 }
                } catch (_: Exception) { 60 }
                if (currentPatience <= 0) {
                    try { db.execSQL("UPDATE PendingAiMessages SET isDone=1 WHERE aiId=? AND isDone=0", arrayOf(aiId)) } catch (_: Exception) {}
                    return@Thread
                }
                val lastChat = try {
                    db.rawQuery(
                        "SELECT timestamp, isFromMe FROM ChatHistory WHERE aiId=? AND IFNULL(groupId,'')='' ORDER BY timestamp DESC LIMIT 1",
                        arrayOf(aiId)
                    ).use { c ->
                        if (c.moveToFirst()) c.getLong(0) to (c.getInt(1) == 1) else 0L to false
                    }
                } catch (_: Exception) { 0L to false }
                if (!lastChat.second || lastChat.first <= 0L) {
                    try { db.execSQL("UPDATE PendingAiMessages SET isDone=1 WHERE aiId=? AND isDone=0", arrayOf(aiId)) } catch (_: Exception) {}
                    return@Thread
                }
                val earliestAllowed = lastChat.first + currentPatience * 60_000L
                val now = System.currentTimeMillis()
                if (now < earliestAllowed) return@Thread
                try {
                    db.execSQL(
                        "UPDATE PendingAiMessages SET isDone=1 WHERE aiId=? AND isDone=0 AND simulatedTime<?",
                        arrayOf(aiId, earliestAllowed.toString())
                    )
                } catch (_: Exception) {}
                val cur = db.rawQuery(
                    "SELECT id, simulatedTime FROM PendingAiMessages WHERE aiId=? AND isDone=0 AND simulatedTime<=? AND simulatedTime>=? ORDER BY simulatedTime ASC LIMIT 1",
                    arrayOf(aiId, now.toString(), earliestAllowed.toString())
                )
                if (cur.moveToFirst()) {
                    val pendingId = cur.getLong(0)
                    cur.close()
                    val cv = android.content.ContentValues().apply { put("isDone", 1) }
                    db.update("PendingAiMessages", cv, "id=?", arrayOf(pendingId.toString()))
                    runOnUiThread { triggerAutoFollowUp(0L) }
                } else {
                    cur.close()
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun startPatienceTimer() {
        patienceHandler.removeCallbacksAndMessages(null)
        val token = ++patienceScheduleSeq
        Thread {
            try {
                var patienceMinutes = -1L
                DatabaseHelper(this).readableDatabase.query("Contacts", null, "userId=?", arrayOf(aiId), null, null, null).use { c ->
                    if (c.moveToFirst()) patienceMinutes = c.getSafeLong("patience")
                }
                if (patienceMinutes <= 0) return@Thread  // 0 = 禁用，不设计时器
                var lastTime = 0L
                var lastIsUser = false
                try {
                    DatabaseHelper(this).readableDatabase.rawQuery(
                        "SELECT timestamp, isFromMe FROM ChatHistory WHERE aiId=? AND IFNULL(groupId,'')='' ORDER BY timestamp DESC LIMIT 1",
                        arrayOf(aiId)
                    ).use { c ->
                        if (c.moveToFirst()) {
                            lastTime = c.getLong(0)
                            lastIsUser = c.getInt(1) == 1
                        }
                    }
                } catch (_: Exception) {}
                if (!lastIsUser || lastTime <= 0L) return@Thread
                // 从最后一条用户消息起算剩余等待时间，而不是从现在起再等完整耐心时长
                val delayMs = (lastTime + patienceMinutes * 60_000L - System.currentTimeMillis()).coerceAtLeast(1_000L)
                patienceHandler.post {
                    if (token != patienceScheduleSeq) return@post  // 已被更新的排定/取消取代
                    patienceRunnable = Runnable { triggerAutoFollowUp(patienceMinutes) }
                    patienceHandler.postDelayed(patienceRunnable!!, delayMs)
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun triggerAutoFollowUp(patienceMinutes: Long) {
        val blockPref = getSharedPreferences("BlockList", Context.MODE_PRIVATE)
        if (blockPref.getBoolean("ai_blocks_user_$aiId", false)) return

        val sharedPref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val url = sharedPref.getString("apiUrl", "") ?: return
        val key = sharedPref.getString("apiKey", "") ?: return
        val model = sharedPref.getString("modelName", "gemini-pro") ?: "gemini-pro"
        val aiLang = sharedPref.getString("aiLang_$aiId", "默认 (中文)") ?: "默认 (中文)"
        val isChinese = aiLang == "默认 (中文)"
        val autoKey = "lastAutoFollowUp_$aiId"
        val autoCooldownMs = ((if (patienceMinutes > 0) patienceMinutes else 10L) * 60_000L).coerceAtLeast(10 * 60_000L)
        if (System.currentTimeMillis() - sharedPref.getLong(autoKey, 0L) < autoCooldownMs) return
        // 先占用冷却，保证无论本次调用成功/失败/回复为空，10 分钟内都不会重复烧 API
        sharedPref.edit().putLong(autoKey, System.currentTimeMillis()).apply()

        var finalUrl = if (url.endsWith("/")) url.dropLast(1) else url
        if (!finalUrl.endsWith("/chat/completions")) {
            finalUrl += if (finalUrl.contains("/v1")) "/chat/completions" else "/v1/chat/completions"
        }

        Thread {
            try {
                val db = DatabaseHelper(this).readableDatabase
                // 触发前复核：最后一条私聊消息必须仍是用户发的，且距今已满耐心时长。
                // 防止定时器排定后 AI 已回复、或残留的旧定时器/待发队列提前触发主动消息。
                run {
                    var lastTime = 0L
                    var lastIsUser = false
                    try {
                        db.rawQuery(
                            "SELECT timestamp, isFromMe FROM ChatHistory WHERE aiId=? AND IFNULL(groupId,'')='' ORDER BY timestamp DESC LIMIT 1",
                            arrayOf(aiId)
                        ).use { c ->
                            if (c.moveToFirst()) {
                                lastTime = c.getLong(0)
                                lastIsUser = c.getInt(1) == 1
                            }
                        }
                    } catch (_: Exception) {}
                    if (!lastIsUser || lastTime <= 0L) return@Thread
                    val currentPatience = try {
                        db.rawQuery("SELECT patience FROM Contacts WHERE userId=?", arrayOf(aiId))
                            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
                    } catch (_: Exception) { 0L }
                    if (currentPatience <= 0L) return@Thread
                    if (System.currentTimeMillis() - lastTime < currentPatience * 60_000L) return@Thread
                }
                var aiPersona = ""
                var cyberMemory = ""
                val lastUserText = msgList.lastOrNull { it.isFromMe && !it.isSystem }?.content ?: ""
                var worldBookContext = ""
                var lifeContext = ""
                var petContext = ""
                db.query("Contacts", null, "userId=?", arrayOf(aiId), null, null, null).use { c ->
                    if (c.moveToFirst()) aiPersona = c.getSafeString("identityInfo")
                }
                db.rawQuery("SELECT memoryText FROM MemoryBank WHERE aiId=? ORDER BY insertTime DESC LIMIT 15",
                    arrayOf(aiId)).use { c ->
                    val sb = StringBuilder()
                    while (c.moveToNext()) sb.append(c.getString(0)).append("\n")
                    cyberMemory = sb.toString().trim()
                }
                worldBookContext = buildWorldBookContext(db, aiId, lastUserText)
                lifeContext = buildLifeContext(db)
                petContext = buildPetContext(db, aiId)

                val nowTime = SimpleDateFormat("yyyy年MM月dd日 EEEE HH:mm", Locale.CHINA).format(Date())
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val timeContext = when (hour) {
                    in 5..8 -> "清晨"; in 9..11 -> "上午"; in 12..13 -> "中午"
                    in 14..16 -> "下午"; in 17..18 -> "傍晚"; in 19..21 -> "晚上"
                    in 22..23 -> "深夜"; else -> "凌晨"
                }
                // ★ 同步把格式写死，主动催促也不掉翻译
                val langRule = if (isChinese) "台词必须使用中文，禁止动作描写"
                else "【台词】只写${aiLang}，一个中文都不许出现；【翻译】另起一行写对应的完整中文翻译，必须有不能省"

                val systemPrompt = "你是 $aiName，人设：${aiPersona.take(1000)}。严禁输出动作描写、*号或括号动作，只说聊天内容。"
                val userPrompt = """
【当前时间】：$nowTime（$timeContext）
【潜意识】：${cyberMemory.take(500)}
${if (worldBookContext.isNotEmpty()) "【世界书】：\n${worldBookContext.take(1200)}" else ""}
${if (lifeContext.isNotEmpty()) "【用户生活事项】：\n${lifeContext.take(900)}" else ""}
${if (petContext.isNotEmpty()) "【共同宠物与近期照料】：\n${petContext.take(1000)}" else ""}
【任务】：对方已经很久没有回复你了，你现在主动发一条消息。
内容要自然真实，结合你的人设和当前时间，可以分享一件小事、问对方近况，或表达你的心情。
禁止输出"check""在吗""你好"等无意义短语。消息必须有实际内容，至少10个字。
【语言规则】：$langRule
【格式指令】严格只输出：
【内心】真实想法(中文)
【台词】发送的消息内容(纯文字，禁止动作描写)${if (!isChinese) "\n【翻译】中文翻译" else ""}
""".trimIndent()

                val bodyJson = JSONObject().apply {
                    put("model", model)
                    put("temperature", 0.7)
                    put("max_tokens", 1500)  // 主动消息也加输出上限，防退化烧 token；太小会把外语+翻译截断导致"显示不全"
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                        put(JSONObject().apply { put("role", "user"); put("content", userPrompt) })
                    })
                }

                val request = Request.Builder()
                    .url(finalUrl)
                    .addHeader("Authorization", "Bearer $key")
                    .post(bodyJson.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()

                val resp = Http.client.newBuilder()
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build().newCall(request).execute()

                val body = resp.body?.string() ?: return@Thread
                if (!resp.isSuccessful) return@Thread

                val replyContent = JSONObject(body)
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content", "").trim()
                    .let { if (it == "null") "" else it }

                // ★ 解析：和主回复用同一套宽容规则
                val parsed = parseReplyBlock(replyContent, isChinese)
                val inner = parsed.inner
                val dialog = parsed.dialog
                val trans = parsed.trans

                if (dialog.isBlank()) return@Thread
                // 过滤无意义的模型确认词，防止发出"check"/"ok"/"好的"等
                val junkResponses = setOf("check", "ok", "okay", "好的", "收到", "明白", "知道了", "在的", "在")
                if (dialog.length < 4 || junkResponses.contains(dialog.trim().lowercase())) return@Thread

                val aiMsg = Message(dialog, false, false, false).apply {
                    innerThoughts = inner
                    translatedText = trans
                    timestamp = nextTimestamp()
                }

                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    msgList.add(aiMsg)
                    addMessageToWebView(aiMsg)
                    saveMsgToDb(aiMsg, 0, "")
                    sharedPref.edit().putLong(autoKey, System.currentTimeMillis()).apply()
                    sendBroadcast(Intent("CYBER_NEW_MSG"))
                    // 发完消息后重新开始计时，持续催促
                    startPatienceTimer()
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun maybeAiRecall(msg: Message) {
        val lastRecallTime = getSharedPreferences("AppConfig", Context.MODE_PRIVATE).getLong("lastRecallTime_$aiId", 0L)
        if (System.currentTimeMillis() - lastRecallTime < 300_000L) return
        val lastAiMsg = msgList.lastOrNull { !it.isFromMe && !it.isSystem }
        if (msg != lastAiMsg) return
        val recallTriggerWords = listOf("喜欢你", "爱你", "想你", "表白", "对不起", "我错了")
        if (!recallTriggerWords.any { msg.content.contains(it, ignoreCase = true) } && (1..100).random() > 3) return
        getSharedPreferences("AppConfig", Context.MODE_PRIVATE).edit().putLong("lastRecallTime_$aiId", System.currentTimeMillis()).apply()
        Handler(Looper.getMainLooper()).postDelayed({
            if (isDestroyed) return@postDelayed
            val originalContent = msg.content
            msg.imageDesc = "[RECALLED]$originalContent"
            msg.content = "${aiName}撤回了一条消息"
            msg.translatedText = ""
            try {
                DatabaseHelper(this).writableDatabase.execSQL(
                    "UPDATE ChatHistory SET content=?, imageDesc=?, translatedText='' WHERE timestamp=? AND isFromMe=0 AND aiId=?",
                    arrayOf("${aiName}撤回了一条消息", "[RECALLED]$originalContent", msg.timestamp, aiId)
                )
            } catch (_: Exception) {}
            val updateObj = JSONObject().apply {
                put("isRecalled", true)
                put("content", msg.content)
                put("isFromMe", false)
                put("recalledText", originalContent)
            }
            callJs("updateMessage(${jsStr(msg.timestamp.toString())}, ${jsStr(updateObj.toString())})")
        }, (3000L..8000L).random())
    }

    private fun searchAndInsertMusicCard(query: String) {
        Thread {
            try {
                val keyword = java.net.URLEncoder.encode(query, "UTF-8")
                val url = "https://music.163.com/api/search/get?s=$keyword&type=1&limit=1"
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("Referer", "https://music.163.com/")
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                val resp = conn.inputStream.bufferedReader().readText(); conn.disconnect()
                val songs = JSONObject(resp).optJSONObject("result")?.optJSONArray("songs")
                if (songs == null || songs.length() == 0) return@Thread
                val song = songs.getJSONObject(0)
                val songId = song.optString("id"); val songName = song.optString("name")
                val artist = song.optJSONArray("artists")?.optJSONObject(0)?.optString("name") ?: ""
                var coverUrl = song.optJSONObject("album")?.optString("picUrl") ?: ""
                if (coverUrl.startsWith("http://")) coverUrl = coverUrl.replace("http://", "https://")
                val cardJson = JSONObject().apply {
                    put("songId", songId); put("songName", songName)
                    put("artist", artist); put("coverUrl", coverUrl)
                }.toString()
                val cardMsg = Message("🎵 $songName - $artist", false, false, false).apply {
                    imageDesc = "[MUSIC_CARD]$cardJson"; timestamp = nextTimestamp()
                }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    msgList.add(cardMsg); addMessageToWebView(cardMsg); saveMsgToDb(cardMsg, 0, "")
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun putMsg(arr: JSONArray, msg: Message) {
        val role = if (msg.isFromMe) "user" else "assistant"
        val now = System.currentTimeMillis(); val diff = now - msg.timestamp
        val timeTag = when {
            diff < 60_000L -> "[刚刚]"
            diff < 3600_000L -> "[${diff / 60_000}分钟前]"
            diff < 86400_000L -> "[${diff / 3600_000}小时前]"
            else -> "[${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(msg.timestamp))}]"
        }

        // ★ 用户发的真实照片 → 用多模态格式把图真正发给模型（而不是只发"【图片】"两个字）
        val imgDesc = msg.imageDesc ?: ""
        val looksLikePhoto = msg.isFromMe &&
                (msg.content == "【图片】" || imgDesc.startsWith("[REAL_IMG]") ||
                        imgDesc.startsWith("content://") || imgDesc.startsWith("file://") || imgDesc.startsWith("/"))
        if (looksLikePhoto) {
            val b64 = uriToBase64DataUri(imgDesc.removePrefix("[REAL_IMG]"))
            if (b64.isNotEmpty()) {
                val contentArr = JSONArray().apply {
                    put(JSONObject().apply { put("type", "text"); put("text", "$timeTag 我发了一张图片，请仔细看图后再回复") })
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply { put("url", b64) })
                    })
                }
                arr.put(JSONObject().apply { put("role", role); put("content", contentArr) })
                return
            }
        }

        val content = if (msg.isFromMe) "$timeTag ${msg.content}" else msg.content
        arr.put(JSONObject().apply { put("role", role); put("content", content) })
    }
    // ════════════════════════════════════════════════════════════
// ════════════════════════════════════════════════════════════
// 密语时刻：弹窗 + 玩具指令执行
// ════════════════════════════════════════════════════════════
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun showWhisperDialog() {
        val ctx = this
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        // 连接状态 + 按钮
        val statusText = android.widget.TextView(ctx).apply {
            textSize = 13f
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(statusText)
        val connBtn = android.widget.Button(ctx)
        root.addView(connBtn)

        // 开始/结束一起玩
        val startBtn = android.widget.Button(ctx)
        root.addView(startBtn)

        fun refreshConn() {
            statusText.text = if (ToyBleManager.isConnected) "● 已连接" else "○ 未连接"
            connBtn.text = if (ToyBleManager.isConnected) "断开连接" else "连接"
            startBtn.text = if (whisperMode) "结束一起玩" else "开始一起玩"
        }
        refreshConn()
        connBtn.setOnClickListener {
            if (ToyBleManager.isConnected) ToyBleManager.disconnect() else connectToyWithPermission()
        }
        startBtn.setOnClickListener {
            whisperMode = !whisperMode
            getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                .edit().putBoolean("whisperMode_$aiId", whisperMode).apply()
            if (!whisperMode) ToyBleManager.stopAll()
            refreshConn()
        }

        // 九档九宫格
        val grid = android.widget.GridLayout(ctx).apply {
            columnCount = 3
            setPadding(0, dp(8), 0, 0)
        }
        for (i in 1..9) {
            val b = android.widget.Button(ctx).apply {
                text = ToyBleManager.presetName(i)
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                }
                setOnClickListener { ToyBleManager.applyPreset(i) }
            }
            grid.addView(b)
        }
        root.addView(grid)

        val stopBtn = android.widget.Button(ctx).apply {
            text = "⏹ 停止"
            setOnClickListener { ToyBleManager.stopAll() }
        }
        root.addView(stopBtn)

        val logText = android.widget.TextView(ctx).apply {
            textSize = 12f
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(logText)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("一起玩")
            .setView(root)
            .setNegativeButton("关闭", null)
            .create()

        ToyBleManager.setListener(object : ToyBleManager.Listener {
            override fun onStateChanged(connected: Boolean) { refreshConn() }
            override fun onLog(msg: String) { logText.text = msg; refreshConn() }
        })
        dialog.setOnDismissListener { ToyBleManager.setListener(null) }
        dialog.show()
    }

    /** 执行 AI 回复里的 [TOY:x] 指令并在聊天里显示档位胶囊（不落库） */
    private fun execToyCommand(cmd: String) {
        val c = cmd.trim().uppercase()
        val label: String
        if (c == "STOP" || c == "停止" || c == "0") {
            ToyBleManager.stopAll()
            label = "❤️ 停止"
        } else {
            val n = c.toIntOrNull() ?: return
            if (n !in 1..9) return
            ToyBleManager.applyPreset(n)
            label = "❤️ ${ToyBleManager.presetName(n)}"
        }
        val capsule = Message(label, false, false, true).apply { timestamp = nextTimestamp() }
        addMessageToWebView(capsule)
    }

// ════════════════════════════════════════════════════════════
// 统一解析 AI 回复：把 【内心】【台词】【翻译】 洗干净，防掉格式
// ════════════════════════════════════════════════════════════
    private data class ParsedReply(val inner: String, val dialog: String, val trans: String)

    private fun fallbackDialogFromRaw(raw: String): String {
        var s = raw.replace("\r", "").replace("<|SPLIT|>", "\n").trim()
        val dialog = Regex("[【\\[]\\s*(?:台词|对话|回复|内容)\\s*[】\\]]\\s*([\\s\\S]*?)(?=\\s*[【\\[]\\s*(?:内心|心声|翻译|译文|译)\\s*[】\\]]|$)")
            .find(s)?.groupValues?.get(1)?.trim()
        if (!dialog.isNullOrBlank()) s = dialog
        s = s
            .replace(Regex("[【\\[]\\s*(?:内心|心声|心理|OS)\\s*[】\\]][\\s\\S]*?(?=[【\\[]\\s*(?:台词|对话|回复|内容|翻译|译文|译)\\s*[】\\]]|$)"), "")
            .replace(Regex("[【\\[]\\s*(?:翻译|译文|译)\\s*[】\\]][\\s\\S]*$"), "")
            .replace(Regex("[【\\[][^】\\]]{1,20}[】\\]]"), "")
            .replace(Regex("\\*[^*]{1,80}\\*"), "")
            .trim()
        return s.take(1200).trim()
    }

    /** 把模型可能用的各种括号/写法统一成 【内心】【台词】【翻译】 */
    private fun normalizeMarkers(raw: String): String {
        var s = raw.replace("\r", "")
        s = s.replace("**", "")                                  // 去 markdown 粗体
        s = s.replace(Regex("(?m)^\\s*#{1,6}\\s*"), "")          // 去行首 markdown 标题号
        // 各种括号包裹的标签 → 统一成 【】
        s = s.replace(Regex("[\\[【「『（(]\\s*(内心|心声|心里|心里话|想法|心理|OS|os)\\s*[\\]】」』）)]"), "【内心】")
        s = s.replace(Regex("[\\[【「『（(]\\s*(台词|对白|回复|发送|内容)\\s*[\\]】」』）)]"), "【台词】")
        s = s.replace(Regex("[\\[【「『（(]\\s*(翻译|译文|译|translation|übersetzung|traduction|traducción|tradução|traduzione|翻訳|번역)\\s*[\\]】」』）)]", RegexOption.IGNORE_CASE), "【翻译】")
        // 行首无括号标签：内心：/ 台词- / 翻译 ：
        s = s.replace(Regex("(?m)^\\s*(内心|心声|想法|心理)\\s*[:：\\-—]\\s*"), "【内心】")
        s = s.replace(Regex("(?m)^\\s*(台词|对白|回复|内容)\\s*[:：\\-—]\\s*"), "【台词】")
        s = s.replace(Regex("(?m)^\\s*(翻译|译文|译|translation|übersetzung|traduction|traducción|tradução|traduzione|翻訳|번역)\\s*[:：\\-—]\\s*", RegexOption.IGNORE_CASE), "【翻译】")
        return s
    }

    private fun parseReplyBlock(rawBlock: String, isChinese: Boolean, keepActions: Boolean = false): ParsedReply {
        val block = normalizeMarkers(rawBlock)

        val inner = Regex("【内心】\\s*(.*?)\\s*(?=【台词】|【翻译】|$)", RegexOption.DOT_MATCHES_ALL)
            .find(block)?.groupValues?.get(1)?.trim() ?: ""

        var dialog = Regex("【台词】\\s*(.*?)\\s*(?=【内心】|【翻译】|$)", RegexOption.DOT_MATCHES_ALL)
            .find(block)?.groupValues?.get(1)?.trim() ?: ""

        var trans = Regex("【翻译】\\s*([\\s\\S]+?)(?=\\s*<\\|SPLIT\\|>|$)", RegexOption.DOT_MATCHES_ALL)
            .find(block)?.groupValues?.get(1)?.trim() ?: ""

        // 兜底：没抓到台词 → 删掉内心段和翻译段后，剩下的当台词（防止内心泄漏进气泡）
        if (dialog.isEmpty()) {
            var rest = block.replace(Regex("【内心】.*?(?=【台词】|【翻译】|$)", RegexOption.DOT_MATCHES_ALL), "")
            rest = rest.replace(Regex("【翻译】[\\s\\S]*$"), "")
            dialog = rest.replace(Regex("【[^】]*】"), "").trim()
        }

        // 清掉台词里的动作/星号描写（密语模式保留描写自由）
        if (!keepActions) {
            dialog = dialog
                .replace(Regex("\\*[^*]+\\*"), "")
                .replace(Regex("[（(][^）)]{1,15}[）)]"), "")
                .trim()
        }

        // 再洗一遍：防止标签残留在台词里
        dialog = dialog
            .replace(Regex("【翻译】[\\s\\S]*$"), "")
            .replace(Regex("【内心】[\\s\\S]*$"), "")
            .replace(Regex("【[^】]*】"), "")
            .replace(Regex("^内心\\s*[:：]?\\s*"), "")
            .trim()

        // 非中文角色：翻译没抓到、但台词里混进了中文 → 把中文拆出来当翻译
        if (!isChinese && trans.isEmpty() && dialog.any { it.code in 0x4E00..0x9FFF }) {
            val firstCjk = dialog.indexOfFirst { it.code in 0x4E00..0x9FFF }
            if (firstCjk > 0) {
                trans = dialog.substring(firstCjk).trim()
                dialog = dialog.substring(0, firstCjk).trim()
            }
        }
        // Strip 【内心】 block and any text that follows it before the next marker
        trans = trans.replace(Regex("【内心】[^【]*"), "").trim()
        // Strip any remaining 【...】 markers
        trans = trans.replace(Regex("【[^】]*】"), "").trim()

        return ParsedReply(inner, dialog, trans)
    }
    private fun saveMsgToDb(msg: Message, isFromMe: Int, localVoicePath: String = "") {
        try {
            DatabaseHelper(this).writableDatabase.insert("ChatHistory", null, ContentValues().apply {
                put("aiId", aiId); put("groupId", ""); put("content", msg.content)
                put("isFromMe", isFromMe)
                put("msgTime", SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
                put("timestamp", msg.timestamp)
                put("isVoice", if (msg.isVoice) 1 else 0); put("voiceDuration", msg.voiceDuration)
                put("localVoicePath", localVoicePath); put("translatedText", msg.translatedText ?: "")
                put("innerThoughts", msg.innerThoughts ?: ""); put("imageDesc", msg.imageDesc ?: "")
                put("isRead", if (isFromMe == 1) 1 else 0)
            })
        } catch (e: Exception) { Log.e("SAVE_MSG", e.stackTraceToString()) }
    }

    @Deprecated("Handled for the WebView history overlay")
    override fun onBackPressed() {
        if (!webViewReady) { finish(); return }
        webView.evaluateJavascript(
            "document.getElementById('history-overlay').classList.contains('visible')"
        ) { visible ->
            if (visible == "true") callJs("closeHistory()") else finish()
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        // 共享还在但定时器丢了（比如 Activity 重建）就把循环续上
        if (ScreenShareService.isRunning && ScreenShareService.sharingAiId == aiId && screenShareRunnable == null) {
            startScreenShareLoop(firstDelayMs = SCREEN_SHARE_INTERVAL_MS)
        }
        try { ContextCompat.registerReceiver(this, gameSummaryReceiver, IntentFilter("GAME_SUMMARY"), ContextCompat.RECEIVER_NOT_EXPORTED) } catch (_: Exception) {}
        try {
            DatabaseHelper(this).writableDatabase.execSQL("UPDATE ChatHistory SET isRead=1 WHERE aiId=? AND isFromMe=0 AND IFNULL(groupId,'')=''", arrayOf(aiId))
            sendBroadcast(Intent("CYBER_NEW_MSG"))
        } catch (_: Exception) {}
        startPatienceTimer()
        checkPendingAiMessages()

        // 小窗返回后 WebView 还在但消息列表可能空了，重新加载
        if (webViewReady) {
            loadChatHistory()
            loadAndApplyUserCSS()
            loadChatBackground()
        }
    }

    override fun onPause() {
        super.onPause()
        isActivityResumed = false
        // 注意：屏幕共享循环不在这里停 —— 用户切去别的 app 正是共享的主要场景
        invasionHandler.removeCallbacksAndMessages(null); isInvading = false
    }

    override fun onDestroy() {
        super.onDestroy()
        // 聊天窗口关闭 = 本次共享结束
        if (ScreenShareService.isRunning && ScreenShareService.sharingAiId == aiId) {
            ScreenShareService.stop(this)
        }
        stopScreenShareLoop()
        try { unregisterReceiver(screenShareStoppedReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(gameSummaryReceiver) } catch (_: Exception) {}
        patienceHandler.removeCallbacksAndMessages(null)
        invasionHandler.removeCallbacksAndMessages(null)
        webView.destroy()
    }
    private fun fetchXhsContent(url: String): String? {
        android.util.Log.d("XHS_FETCH", "fetchXhsContent开始")
        try {
            val client = Http.client.newBuilder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val req = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Referer", "https://www.xiaohongshu.com/")
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) { resp.close(); return null }

            val html = resp.body?.string() ?: run { resp.close(); return null }
            resp.close()

            val descMatch = Regex("\"desc\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(html)
            if (descMatch != null) {
                val raw = descMatch.groupValues[1]
                    .replace("\\n", "\n")
                    .replace("\\u002F", "/")
                    .replace("\\\"", "\"")
                    .replace(Regex("#[^#\\s]+\\[话题\\]"), "")
                    .trim()
                return raw.replace(Regex("\\s+"), " ").trim().take(500)
            }
            return null
        } catch (e: Exception) {
            android.util.Log.d("XHS_FETCH", "异常: ${e.javaClass.name} - ${e.message}")
            return null
        }
    }

    private fun getRecentAppUsageSummary(minutesBack: Long): String {
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = appOps.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), packageName
            )
            if (mode != android.app.AppOpsManager.MODE_ALLOWED) return ""

            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val endTime = System.currentTimeMillis()
            val startTime = endTime - minutesBack * 60 * 1000L
            val stats = usageStatsManager.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_BEST, startTime, endTime
            )
            if (stats.isNullOrEmpty()) return ""

            val excludePackages = setOf(
                packageName,
                "com.android.systemui", "com.android.launcher",
                "com.android.launcher2", "com.android.launcher3",
                "com.miui.home", "com.huawei.android.launcher",
                "com.vivo.launcher", "com.bbk.launcher2",
                "com.oppo.launcher", "com.android.settings",
                "com.android.phone", "android"
            )

            val appNameMap = mapOf(
                "com.zhihu.android" to "知乎",
                "com.sina.weibo" to "微博",
                "com.tencent.mm" to "微信",
                "com.tencent.mobileqq" to "QQ",
                "com.douyin.aweme" to "抖音",
                "com.ss.android.ugc.aweme" to "抖音",
                "com.bilibili.app.in" to "哔哩哔哩",
                "tv.danmaku.bili" to "哔哩哔哩",
                "com.xiaohongshu.android" to "小红书",
                "com.taobao.taobao" to "淘宝",
                "com.jingdong.app.mall" to "京东",
                "com.netease.cloudmusic" to "网易云音乐",
                "com.tencent.qqmusic" to "QQ音乐",
                "com.kugou.android" to "酷狗音乐",
                "com.tencent.games.pubgmhd" to "和平精英",
                "com.mihoyo.yuanshen" to "原神",
                "com.YoStarEN.Arknights" to "明日方舟",
                "com.netease.x19" to "第五人格",
                "com.ss.android.article.news" to "今日头条",
                "com.baidu.searchbox" to "百度",
                "com.youku.phone" to "优酷",
                "com.iqiyi.video" to "爱奇艺",
                "com.hunantv.imgo.activity" to "芒果TV",
                "com.android.chrome" to "Chrome浏览器",
                "com.UCMobile" to "UC浏览器"
            )

            val significantApps = stats
                .filter { it.packageName !in excludePackages }
                .filter { it.totalTimeInForeground > 60_000L }
                .sortedByDescending { it.totalTimeInForeground }
                .take(5)

            if (significantApps.isEmpty()) return ""

            val sb = StringBuilder()
            for (appStat in significantApps) {
                val appName = appNameMap[appStat.packageName]
                    ?: try {
                        packageManager.getApplicationLabel(
                            packageManager.getApplicationInfo(appStat.packageName, 0)
                        ).toString()
                    } catch (_: Exception) { continue }

                val mins = appStat.totalTimeInForeground / 60_000L
                val timeStr = when {
                    mins < 1 -> "不到1分钟"
                    mins < 60 -> "${mins}分钟"
                    else -> "${mins / 60}小时${mins % 60}分钟"
                }
                sb.append("・$appName $timeStr\n")
            }
            sb.toString().trim()
        } catch (_: Exception) { "" }
    }
}
