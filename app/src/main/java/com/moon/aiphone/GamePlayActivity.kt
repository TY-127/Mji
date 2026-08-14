package com.moon.aiphone

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import android.content.Intent
class GamePlayActivity : AppCompatActivity() {

    private var aiId = ""
    private var aiName = ""
    private var aiPersona = ""
    private lateinit var tvBubble: TextView
    private lateinit var ivAvatar: android.widget.ImageView
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private lateinit var webView: WebView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val gameFile = intent.getStringExtra("GAME_FILE") ?: return
        val gameName = intent.getStringExtra("GAME_NAME") ?: "游戏"
        aiId = intent.getStringExtra("AI_ID") ?: ""
        aiName = intent.getStringExtra("AI_NAME") ?: ""

        try {
            val db = DatabaseHelper(this).readableDatabase
            val cur = db.rawQuery(
                """
    SELECT identityInfo
    FROM Contacts
    WHERE userId=?
    ORDER BY id DESC
    LIMIT 1
    """.trimIndent(),
                arrayOf(aiId)
            )
            if (cur.moveToFirst()) aiPersona = cur.getString(0) ?: ""
            cur.close()
        } catch (_: Exception) {}

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1a1a2e"))
            layoutParams = LinearLayout.LayoutParams(-1, -1)
        }

        // 顶栏
        val topBar = RelativeLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(52))
            setBackgroundColor(Color.parseColor("#12122a"))
        }
        val btnBack = TextView(this).apply {
            text = "‹"; textSize = 32f
            setTextColor(Color.WHITE)
            setPadding(dp(16), 0, dp(16), dp(4))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RelativeLayout.LayoutParams(-2, -1).also {
                it.addRule(RelativeLayout.ALIGN_PARENT_START)
                it.addRule(RelativeLayout.CENTER_VERTICAL)
            }
            setOnClickListener { finish() }
        }
        val tvTitle = TextView(this).apply {
            text = gameName; textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = RelativeLayout.LayoutParams(-2, -2).also {
                it.addRule(RelativeLayout.CENTER_IN_PARENT)
            }
        }
        topBar.addView(btnBack); topBar.addView(tvTitle)
        root.addView(topBar)

        // AI 互动区
        val aiBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(Color.parseColor("#12122a"))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        ivAvatar = android.widget.ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).also { it.marginEnd = dp(10) }
            setBackgroundColor(Color.DKGRAY)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        }
        try {
            val db = DatabaseHelper(this).readableDatabase
            val cur = db.rawQuery("SELECT avatarUri FROM Contacts WHERE userId=?", arrayOf(aiId))
            if (cur.moveToFirst()) {
                val uriStr = cur.getString(0) ?: ""
                if (uriStr.isNotEmpty()) {
                    val bmp =
                        if (uriStr.startsWith("/")) {
                            android.graphics.BitmapFactory.decodeFile(uriStr)
                        } else {
                            contentResolver.openInputStream(
                                android.net.Uri.parse(uriStr)
                            )?.use {
                                android.graphics.BitmapFactory.decodeStream(it)
                            }
                        }
                    if (bmp != null) ivAvatar.setImageBitmap(bmp)
                }
            }
            cur.close()
        } catch (_: Exception) {}

        tvBubble = TextView(this).apply {
            text = "💭 等待游戏开始…"
            textSize = 13f
            setTextColor(Color.parseColor("#DDDDDD"))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#2a2a4a"))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        aiBar.addView(ivAvatar)
        aiBar.addView(tvBubble)
        root.addView(aiBar)

        // WebView 游戏区
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccessFromFileURLs = true
            settings.allowUniversalAccessFromFileURLs = true
            settings.allowFileAccess = true
            setBackgroundColor(Color.parseColor("#1a1a2e"))
            addJavascriptInterface(GameBridge(), "Android")
        }
        root.addView(webView)
        setContentView(root)
        webView.loadUrl("file:///android_asset/$gameFile")
    }

    private fun showBubble(text: String) {
        runOnUiThread { tvBubble.text = text }
    }

    private fun callAiForEvent(eventType: String, eventData: String) {
        val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        var apiUrl = pref.getString("apiUrl", "") ?: ""
        val apiKey = pref.getString("apiKey", "") ?: ""
        val model =
            pref.getString(
                "modelName",
                ""
            )?.ifBlank { "gpt-4o" }
                ?: "gpt-4o"
        if (apiUrl.isEmpty() || apiKey.isEmpty()) return

        while (apiUrl.endsWith("/")) apiUrl = apiUrl.dropLast(1)
        if (!apiUrl.endsWith("/chat/completions")) {
            apiUrl += if (apiUrl.contains("/v1")) "/chat/completions" else "/v1/chat/completions"
        }

        val eventDesc = when (eventType) {
            "start" -> "游戏刚开始了，说一句开场话激励或挑衅用户"
            "struggling" -> {
                val obj = try { JSONObject(eventData) } catch (_: Exception) { JSONObject() }
                val tries = obj.optInt("tries")
                "用户已经猜了 $tries 次还没猜对，适当嘲讽或鼓励一下"
            }
            "almost" -> {
                val obj = try { JSONObject(eventData) } catch (_: Exception) { JSONObject() }
                val remaining = obj.optInt("remaining")
                "答案范围已经缩小到只剩 $remaining 个数字了，加油或挑衅用户冲刺"
            }
            "win" -> {
                val obj = try { JSONObject(eventData) } catch (_: Exception) { JSONObject() }
                val tries = obj.optInt("tries")
                "用户猜对了！一共猜了 $tries 次，给出结束评价"
            }
            else -> "游戏发生了事件：$eventType，简单回应一下"
        }

        val aiLang = pref.getString("aiLang_$aiId", "默认 (中文)") ?: "默认 (中文)"
        val isChinese = aiLang == "默认 (中文)"
        val langRule = "必须用简体中文回应，不得使用其他语言"


        val prompt = """
你是 $aiName，人设：$aiPersona。
你现在和用户一起玩游戏，当前事件：$eventDesc。
用你的口吻发表一句简短的实时评论（10-25字），可以加油、吐槽、惊讶、挑衅，像真人陪玩一样自然。
$langRule，只输出评论内容，不要任何标签或解释。
        """.trimIndent()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().apply {
                    put("model", model)
                    put("max_tokens", 80)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                    })
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())

                val resp = Http.client.newBuilder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
                    .newCall(Request.Builder().url(apiUrl).addHeader("Authorization", "Bearer $apiKey").post(body).build())
                    .execute()

                if (!resp.isSuccessful) {
                    return@launch
                }

                val result =
                    resp.body?.string()
                        ?: return@launch

                val reply = JSONObject(result)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
                showBubble("💬 $reply")
            } catch (_: Exception) {}
        }
    }

    inner class GameBridge {
        @JavascriptInterface
        fun onGameEvent(type: String, data: String) {
            when (type) {
                "game_end_summary" -> {
                    // 把游戏总结存入聊天记录
                    try {
                        val obj = JSONObject(data)
                        val summary = obj.optString("summary", "")
                        val zone = obj.optString("zone", "")
                        if (summary.isNotEmpty()) {
                            val context = JSONObject().apply {
                                put("type", "game_summary")
                                put("content", "【真心话大冒险·${zone}总结】$summary")
                            }
                            // 通知主 Activity 写入聊天
                            val intent = Intent("GAME_SUMMARY").apply {
                                putExtra("aiId", aiId)
                                putExtra("summary", "【真心话大冒险·${zone}】$summary")
                            }
                            sendBroadcast(intent)
                        }
                    } catch (_: Exception) {}
                }
                "exit" -> runOnUiThread { finish() }
                else -> callAiForEvent(type, data)
            }
        }

        @JavascriptInterface
        fun callAI(prompt: String, callbackId: String) {
            val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            var apiUrl = pref.getString("apiUrl", "") ?: ""
            val apiKey = pref.getString("apiKey", "") ?: ""
            val model =
                pref.getString(
                    "modelName",
                    ""
                )?.ifBlank { "gpt-4o" }
                    ?: "gpt-4o"
            if (apiUrl.isEmpty() || apiKey.isEmpty()) {
                runOnUiThread {
                    webView.evaluateJavascript("window.__aiCallback('$callbackId', '', 'API未配置')", null)
                }
                return
            }
            while (apiUrl.endsWith("/")) apiUrl = apiUrl.dropLast(1)
            if (!apiUrl.endsWith("/chat/completions")) {
                apiUrl += if (apiUrl.contains("/v1")) "/chat/completions" else "/v1/chat/completions"
            }
            val finalUrl = apiUrl
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val body = JSONObject().apply {
                        put("model", model)
                        put("max_tokens", 1000)
                        put("messages", JSONArray().apply {
                            put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                        })
                    }.toString().toRequestBody("application/json".toMediaTypeOrNull())
                    val resp = Http.client.newBuilder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .build()
                        .newCall(Request.Builder().url(finalUrl).addHeader("Authorization", "Bearer $apiKey").post(body).build())
                        .execute()
                    if (!resp.isSuccessful) {
                        throw RuntimeException("AI请求失败:${resp.code}")
                    }

                    val result =
                        resp.body?.string()
                            ?: ""

                    val text = JSONObject(result).getJSONArray("choices")
                        .getJSONObject(0).getJSONObject("message").getString("content").trim()
                    val escaped = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript("window.__aiCallback('$callbackId', '$escaped', null)", null)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript("window.__aiCallback('$callbackId', '', '${e.message}')", null)
                    }
                }
            }
        }

        @JavascriptInterface
        fun getAiName(): String = aiName

        @JavascriptInterface
        fun getAiPersona(): String = aiPersona

        @JavascriptInterface
        fun getUserName(): String {
            return try {
                val db = DatabaseHelper(this@GamePlayActivity).readableDatabase
                val cur = db.rawQuery("SELECT myName FROM MyProfile LIMIT 1", null)
                val name = if (cur.moveToFirst()) cur.getString(0) ?: "你" else "你"
                cur.close()
                name
            } catch (_: Exception) { "你" }
        }

        @JavascriptInterface
        fun getAiAvatar(): String {
            return try {
                val db = DatabaseHelper(this@GamePlayActivity).readableDatabase
                val cur = db.rawQuery("SELECT avatarUri FROM Contacts WHERE userId=?", arrayOf(aiId))
                val uri = if (cur.moveToFirst()) cur.getString(0) ?: "" else ""
                cur.close()
                uri
            } catch (_: Exception) { "" }
        }
        @JavascriptInterface
        fun getAiLang(): String {
            val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            return pref.getString("aiLang_$aiId", "默认 (中文)") ?: "默认 (中文)"
        }
    }override fun onDestroy() {
        try {
            webView.removeJavascriptInterface("Android")
            webView.stopLoading()
            webView.clearHistory()
            webView.destroy()
        } catch (_: Exception) {}

        super.onDestroy()
    }
}