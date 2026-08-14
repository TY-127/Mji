package com.moon.aiphone

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class PhoneViewActivity : AppCompatActivity() {

    private var aiId = ""
    private var aiName = ""
    private lateinit var webView: WebView
    private lateinit var loadingLayout: LinearLayout
    private lateinit var tvLoadingText: TextView
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aiId = intent.getStringExtra("AI_ID") ?: ""
        aiName = intent.getStringExtra("AI_NAME") ?: ""

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val topBar = RelativeLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        val btnBack = TextView(this).apply {
            text = "‹ 退出"
            textSize = 14f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setTextColor(android.graphics.Color.parseColor("#00FF41"))
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            ).also {
                it.addRule(RelativeLayout.ALIGN_PARENT_START)
                it.addRule(RelativeLayout.CENTER_VERTICAL)
            }
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { finish() }
        }
        val tvTitle = TextView(this).apply {
            text = "📱 ${aiName}的手机"
            textSize = 13f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setTextColor(android.graphics.Color.parseColor("#00FF41"))
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).also { it.addRule(RelativeLayout.CENTER_IN_PARENT) }
        }
        topBar.addView(btnBack)
        topBar.addView(tvTitle)
        root.addView(topBar)

        loadingLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        tvLoadingText = TextView(this).apply {
            text = "> 正在入侵目标设备...\n> 读取个人数据中...\n> 请稍候..."
            textSize = 13f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setTextColor(android.graphics.Color.parseColor("#00FF41"))
            gravity = Gravity.CENTER
        }
        val progressBar = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(16) }
        }
        loadingLayout.addView(tvLoadingText)
        loadingLayout.addView(progressBar)
        root.addView(loadingLayout)

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            visibility = View.GONE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            webChromeClient = android.webkit.WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false
            }
        }
        root.addView(webView)
        setContentView(root)
        generatePhoneData()
    }

    private fun generatePhoneData() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pref = getSharedPreferences("AppConfig", MODE_PRIVATE)
                var apiUrl = pref.getString("apiUrl", "") ?: ""
                val apiKey = pref.getString("apiKey", "") ?: ""
                val modelName = pref.getString("modelName", "gemini-2.5-pro") ?: "gemini-2.5-pro"
                if (apiUrl.isEmpty() || apiKey.isEmpty()) return@launch
                while (apiUrl.endsWith("/")) apiUrl = apiUrl.dropLast(1)
                if (!apiUrl.endsWith("/chat/completions")) {
                    apiUrl = if (apiUrl.endsWith("/v1")) "$apiUrl/chat/completions" else "$apiUrl/v1/chat/completions"
                }

                val db = DatabaseHelper(this@PhoneViewActivity).readableDatabase
                var aiPersona = ""
                var cyberMemory = ""
                var myName = "用户"

                val pCursor = db.rawQuery(
                    """
    SELECT identityInfo
    FROM Contacts
    WHERE userId=?
    ORDER BY id DESC
    LIMIT 1
    """.trimIndent(),
                    arrayOf(aiId)
                )
                if (pCursor.moveToFirst()) aiPersona = pCursor.getString(0) ?: ""
                pCursor.close()

                val mCursor = db.rawQuery("SELECT memoryText FROM MemoryBank WHERE aiId=? ORDER BY insertTime DESC LIMIT 10", arrayOf(aiId))
                val memSb = StringBuilder()
                while (mCursor.moveToNext()) memSb.append(mCursor.getString(0)).append("\n")
                cyberMemory = memSb.toString().trim()
                mCursor.close()

                val profileCursor = db.rawQuery("SELECT myName FROM MyProfile LIMIT 1", null)
                if (profileCursor.moveToFirst()) myName = profileCursor.getString(0) ?: "用户"
                profileCursor.close()
                var myId = "my_id"
                try {
                    val idCursor = db.rawQuery("SELECT myId FROM MyProfile LIMIT 1", null)
                    if (idCursor.moveToFirst()) myId = idCursor.getString(0) ?: "my_id"
                    idCursor.close()
                } catch (_: Exception) {}
                // 读角色对用户的备注名
                try {
                    db.execSQL(
                        "ALTER TABLE Contacts ADD COLUMN userNicknameByAi TEXT DEFAULT ''"
                    )
                } catch (_: Exception) {}
                var userNickname = myName
                val nickCursor = db.rawQuery(
                    """
    SELECT userNicknameByAi
    FROM Contacts
    WHERE userId=?
    ORDER BY id DESC
    LIMIT 1
    """.trimIndent(),
                    arrayOf(aiId)
                )
                if (nickCursor.moveToFirst()) {
                    val nick = nickCursor.getString(0) ?: ""
                    if (nick.isNotEmpty()) userNickname = nick
                }
                nickCursor.close()

                // ── 读真实聊天记录，构建HTML ──────────────────────────────
                // 角色视角：isFromMe=1 表示用户发的（从app角度），在角色手机里是"对方发来的"→左边
                //           isFromMe=0 表示角色发的，在角色手机里是"我发的"→右边
                val realChatSb = StringBuilder()
                val realChatCursor = db.rawQuery(
                    "SELECT content, isFromMe, msgTime FROM ChatHistory WHERE aiId=? AND (groupId IS NULL OR groupId='') ORDER BY timestamp DESC LIMIT 5",
                    arrayOf(aiId)
                )
                val realMsgs = mutableListOf<Triple<String, Int, String>>()
                while (realChatCursor.moveToNext()) {
                    realMsgs.add(Triple(
                        realChatCursor.getString(0) ?: "",
                        realChatCursor.getInt(1),
                        realChatCursor.getString(2) ?: ""
                    ))
                }
                realChatCursor.close()
                // 倒序，让最新的在最下面
                for ((content, isFromMe, msgTime) in realMsgs.reversed()) {
                    val displayContent = content
                        .replace(Regex("（[^）]{0,100}）"), "")
                        .replace(Regex("\\[QUOTE\\][^\\n]*\\n?"), "")
                        .trim()
                    if (displayContent.isEmpty()) continue
                    val safeContent = displayContent
                        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    val safeNickname = userNickname.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    if (isFromMe == 1) {
                        // 用户发的 → 在角色手机里是左边（对方发来的）
                        realChatSb.append("""<div class="msg-left"><div class="sender-name">${safeNickname}</div><div class="bubble-left">${safeContent}</div></div>""")
                    } else {
                        // 角色发的 → 在角色手机里是右边（自己发的）
                        realChatSb.append("""<div class="msg-right"><div class="msg-time">${msgTime}</div><div class="bubble-right">${safeContent}</div></div>""")
                    }
                }
                val realChatHtml = if (realChatSb.isEmpty())
                    """<div style="text-align:center;color:#555;font-size:13px;padding:40px 20px">暂无聊天记录</div>"""
                else realChatSb.toString()

                // ── 读其他联系人聊天记录（5条），用于群聊和好友聊天 ──────────
                // 找其他角色
                val otherContacts = mutableListOf<Triple<String, String, String>>() // id, name, avatarUri
                val otherCursor = db.rawQuery(
                    "SELECT userId, realName FROM Contacts WHERE userId != ? AND userId != ? LIMIT 5", arrayOf(aiId, myId)
                )
                while (otherCursor.moveToNext()) {
                    otherContacts.add(Triple(otherCursor.getString(0) ?: "", otherCursor.getString(1) ?: "", ""))
                }
                otherCursor.close()

                // 找群聊
                val groupChats = mutableListOf<Pair<String, String>>() // groupId, groupName
                val groupCursor = db.rawQuery(
                    "SELECT groupId, groupName FROM GroupChats WHERE IFNULL(isDisbanded,0)=0 LIMIT 3", null
                )
                while (groupCursor.moveToNext()) {
                    groupChats.add(Pair(groupCursor.getString(0) ?: "", groupCursor.getString(1) ?: "群聊"))
                }
                groupCursor.close()

                // 构建给AI的聊天摘要（用于生成其他手机数据）
                val chatSummary = StringBuilder()
                for ((content, isFromMe, _) in realMsgs.reversed()) {
                    val role = if (isFromMe == 1) userNickname else aiName
                    chatSummary.append("$role: $content\n")
                }

                val nowTime = SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.CHINA).format(Date())
                val aiLang = pref.getString("aiLang_$aiId", "默认 (中文)") ?: "默认 (中文)"
                val langNote = if (aiLang == "默认 (中文)") {
                    "所有文字内容必须使用简体中文。"
                } else {
                    """所有非中文内容（包括聊天消息、备忘录内容、搜索历史、歌曲名、购物商品名、通话内容、健康数据标签等一切文字）必须在原文后面加上括号中文翻译，格式：原文（中文翻译）。例如：Good morning（早上好）、Lock the door（锁门）。不允许出现任何没有中文翻译的外文内容。"""
                }

                val scheduleBuilder = StringBuilder()
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                try {
                    val sCursor = db.rawQuery(
                        "SELECT startTime, endTime, eventDesc FROM Schedules WHERE aiId=? AND dateStr=?",
                        arrayOf(aiId, today)
                    )
                    while (sCursor.moveToNext()) {
                        scheduleBuilder.append("${sCursor.getString(0)}-${sCursor.getString(1)}: ${sCursor.getString(2)}\n")
                    }
                    sCursor.close()
                } catch (_: Exception) {}

                val prompt = """
你现在扮演 $aiName，人设：$aiPersona
【关于 $myName 的记忆】：$cyberMemory
【最近和 $myName 的聊天（最新5条）】：
${chatSummary.toString().take(800)}
【今日行程】：${if (scheduleBuilder.isEmpty()) "无" else scheduleBuilder.toString()}
【当前时间】：$nowTime
【语言要求】：$langNote

请以 $aiName 的角度生成他/她手机里的数据。
注意：
1. 群聊和好友私聊内容必须各至少5条消息，要有来有往，内容真实自然
2. 与 $myName 相关的私聊内容必须严格参考【最近和 $myName 的聊天】，只能引用或延伸真实聊天内容，绝对禁止编造 $myName 说过的话
3. 非中文内容必须在括号内附上中文翻译，格式：原文（中文翻译）
4. 备忘录第三条必须是关于 $myName 的隐秘想法或感情
5. 聊天记录里如果提到对方，统一用「$myName」称呼，禁止用「用户」这个词
严格按以下JSON格式输出，只输出JSON不要其他内容：
{
  "chat_group_name": "群聊名称",
  "chat_group_msgs": [
    {"sender": "群员A", "content": "消息内容", "isMe": false},
    {"sender": "$aiName", "content": "消息内容", "isMe": true},
    {"sender": "群员B", "content": "消息内容", "isMe": false},
    {"sender": "$aiName", "content": "消息内容", "isMe": true},
    {"sender": "群员A", "content": "消息内容", "isMe": false}
  ],
  "chat_friend1_name": "好友1名字",
  "chat_friend1_msgs": [
    {"sender": "好友1", "content": "内容", "isMe": false},
    {"sender": "$aiName", "content": "内容", "isMe": true},
    {"sender": "好友1", "content": "内容", "isMe": false},
    {"sender": "$aiName", "content": "内容", "isMe": true},
    {"sender": "好友1", "content": "内容", "isMe": false}
  ],
  "chat_friend2_name": "好友2名字",
  "chat_friend2_msgs": [
    {"sender": "好友2", "content": "内容", "isMe": false},
    {"sender": "$aiName", "content": "内容", "isMe": true},
    {"sender": "好友2", "content": "内容", "isMe": false},
    {"sender": "$aiName", "content": "内容", "isMe": true},
    {"sender": "好友2", "content": "内容", "isMe": false}
  ],
  "wallet_balance": "1234.56",
  "wallet_records": [
    {"amount": "+500.00", "desc": "名目", "time": "时间"},
    {"amount": "-88.00", "desc": "名目", "time": "时间"}
  ],
  "steps": 8432,
  "calories": 312,
  "memo1_title": "备忘标题",
  "memo1_body": "备忘内容",
  "memo2_title": "标题",
  "memo2_body": "内容",
  "memo3_title": "关于${userNickname}的秘密",
  "memo3_body": "对${userNickname}的真实感情或秘密想法，至少100字",
  "photos": [
    {"desc": "照片描述"},{"desc": "照片描述"},{"desc": "照片描述"},
    {"desc": "照片描述"},{"desc": "照片描述"},{"desc": "照片描述"}
  ],
  "secret_photos": [
    {"desc": "私密照片描述"},{"desc": "私密照片描述"},{"desc": "私密照片描述"}
  ],
  "call_records": [
    {"name": "联系人", "type": "拨出", "duration": "3分钟", "content": "通话内容"},
    {"name": "联系人", "type": "未接", "duration": "未接听", "content": "拒绝原因"}
  ],
  "search_history": ["搜索词1", "搜索词2", "搜索词3", "搜索词4", "搜索词5"],
  "music_playlist": "歌单名称",
  "music_songs": ["歌曲1 - 歌手", "歌曲2 - 歌手", "歌曲3 - 歌手"],
  "sleep_record": "昨晚几点睡到几点",
  "schedule_today": "${if (scheduleBuilder.isEmpty()) "根据角色人设生成今日行程" else scheduleBuilder.toString()}",
  "shop_recent": [
    {"name": "商品名称", "price": "88.00", "status": "已收货", "time": "3天前"},
    {"name": "商品名称", "price": "199.00", "status": "运输中", "time": "昨天"}
  ],
  "shop_cart": [
    {"name": "购物车商品", "price": "299.00", "note": "犹豫了好久"},
    {"name": "购物车商品", "price": "56.00", "note": ""}
  ]
}
""".trimIndent()

                val bodyJson = JSONObject().apply {
                    put("model", modelName)
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

                val response = Http.client.newBuilder()
                    .connectTimeout(90, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .build().newCall(request).execute()

                val body = response.body?.string() ?: return@launch
                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        tvLoadingText.text =
                            "> API错误\ncode=${response.code}"
                    }
                    return@launch
                }

                val rawContent = JSONObject(body)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    ?.trim()
                    ?: ""
                // optString returns "null" (string) when the JSON field is null
                val content = if (rawContent == "null") "" else rawContent

                if (content.isBlank()) {
                    withContext(Dispatchers.Main) {
                        tvLoadingText.text = "> AI返回空内容"
                    }
                    return@launch
                }
                val data = try {
                    parsePhoneJsonObject(content)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        tvLoadingText.text =
                            "> JSON解析失败\n${e.message}\n${content.take(180)}"
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    renderPhone(data, realChatHtml, userNickname)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvLoadingText.text = "> 入侵失败：${e.message}"
                }
            }
        }
    }

    private fun parsePhoneJsonObject(raw: String): JSONObject {
        val stripped = raw
            .replace("\uFEFF", "")
            .replace(Regex("```(?:json)?", RegexOption.IGNORE_CASE), "")
            .replace("```", "")
            .trim()
        val start = stripped.indexOf('{')
        if (start < 0) throw org.json.JSONException("No JSON object found")

        var depth = 0
        var inString = false
        var escape = false
        var end = -1
        for (i in start until stripped.length) {
            val ch = stripped[i]
            if (escape) {
                escape = false
                continue
            }
            when {
                ch == '\\' && inString -> escape = true
                ch == '"' -> inString = !inString
                !inString && ch == '{' -> depth++
                !inString && ch == '}' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        // 输出被截断时括号不闭合，取到结尾后交给 repairJson 补全，而不是直接失败
        val body = if (end >= 0) stripped.substring(start, end + 1) else stripped.substring(start)
        val cleaned = body
            .replace(Regex(",\\s*([}\\]])"), "\$1")
            .replace(Regex("[\\u0000-\\u001F&&[^\\n\\r\\t]]"), "")
        return try {
            JSONObject(cleaned)
        } catch (_: Exception) {
            JSONObject(repairJson(cleaned))
        }
    }

    // 尽力修复模型输出的非标准 JSON：字符串内未转义的换行/制表符、截断导致的未闭合引号与括号
    private fun repairJson(src: String): String {
        val sb = StringBuilder(src.length + 16)
        var inString = false
        var escape = false
        val closers = ArrayDeque<Char>()
        for (ch in src) {
            if (escape) { sb.append(ch); escape = false; continue }
            when {
                ch == '\\' && inString -> { sb.append(ch); escape = true }
                ch == '"' -> { inString = !inString; sb.append(ch) }
                inString && ch == '\n' -> sb.append("\\n")
                inString && ch == '\r' -> {}
                inString && ch == '\t' -> sb.append("\\t")
                else -> {
                    if (!inString) {
                        when (ch) {
                            '{' -> closers.addLast('}')
                            '[' -> closers.addLast(']')
                            '}', ']' -> if (closers.isNotEmpty() && closers.last() == ch) closers.removeLast()
                        }
                    }
                    sb.append(ch)
                }
            }
        }
        if (escape) sb.append('n')
        if (inString) sb.append('"')
        var s = sb.toString().trimEnd().trimEnd(',')
        if (s.endsWith(':')) s += " \"\""
        while (closers.isNotEmpty()) s += closers.removeLast()
        return s.replace(Regex(",\\s*([}\\]])"), "\$1")
    }

    private fun renderPhone(data: JSONObject, realChatHtml: String = "", userNickname: String = "用户") {
        val html = buildPhoneHtml(data, realChatHtml, userNickname)
        webView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
        loadingLayout.visibility = View.GONE
        webView.visibility = View.VISIBLE

        Thread {
            try {
                val db = DatabaseHelper(this).writableDatabase
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val steps = data.optInt("steps", 0)
                if (steps > 0) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS AiDailySteps (id INTEGER PRIMARY KEY AUTOINCREMENT, aiId TEXT, aiName TEXT, steps INTEGER, dateStr TEXT)")
                    db.execSQL("DELETE FROM AiDailySteps WHERE aiId=? AND dateStr=?", arrayOf(aiId, today))
                    db.execSQL("INSERT INTO AiDailySteps (aiId, aiName, steps, dateStr) VALUES (?,?,?,?)", arrayOf(aiId, aiName, steps, today))
                }
                db.execSQL("CREATE TABLE IF NOT EXISTS AiShopData (id INTEGER PRIMARY KEY AUTOINCREMENT, aiId TEXT, dateStr TEXT, shopRecent TEXT, shopCart TEXT)")
                db.execSQL("DELETE FROM AiShopData WHERE aiId=? AND dateStr=?", arrayOf(aiId, today))
                val shopRecent = data.optJSONArray("shop_recent")?.toString() ?: "[]"
                val shopCart = data.optJSONArray("shop_cart")?.toString() ?: "[]"
                db.execSQL("INSERT INTO AiShopData (aiId, dateStr, shopRecent, shopCart) VALUES (?,?,?,?)", arrayOf(aiId, today, shopRecent, shopCart))
            } catch (_: Exception) {}
        }.start()
    }

    private fun buildPhoneHtml(data: JSONObject, realChatHtml: String = "", userNickname: String = "用户"): String {
        val walletBalance = data.optString("wallet_balance", "0.00")
        val steps = data.optInt("steps", 0)
        val calories = data.optInt("calories", 0)
        val memo1Title = data.optString("memo1_title", "")
        val memo1Body = data.optString("memo1_body", "")
        val memo2Title = data.optString("memo2_title", "")
        val memo2Body = data.optString("memo2_body", "")
        val memo3Title = data.optString("memo3_title", "")
        val memo3Body = data.optString("memo3_body", "")
        val musicPlaylist = data.optString("music_playlist", "")
        val sleepRecord = data.optString("sleep_record", "")
        val myName = getSharedPreferences("AppConfig", MODE_PRIVATE).getString("myName", "用户") ?: "用户"
        val displayUserName = userNickname.ifEmpty { myName }
        val scheduleToday = data.optString("schedule_today", "")
            .replace("{{user}}", myName).replace("{{User}}", myName).replace("{{char}}", aiName)
        val searchHistory = data.optJSONArray("search_history")
        val musicSongs = data.optJSONArray("music_songs")
        val walletRecords = data.optJSONArray("wallet_records")
        val photos = data.optJSONArray("photos")
        val secretPhotos = data.optJSONArray("secret_photos")
        val callRecords = data.optJSONArray("call_records")
        val chatGroupName = data.optString("chat_group_name", "群聊")
        val chatGroupMsgs = data.optJSONArray("chat_group_msgs")
        val chatFriend1Name = data.optString("chat_friend1_name", "好友1")
        val chatFriend1Msgs = data.optJSONArray("chat_friend1_msgs")
        val chatFriend2Name = data.optString("chat_friend2_name", "好友2")
        val chatFriend2Msgs = data.optJSONArray("chat_friend2_msgs")
        val shopRecent = data.optJSONArray("shop_recent")
        val shopCart = data.optJSONArray("shop_cart")

        fun buildChatMsgs(msgs: JSONArray?): String {
            if (msgs == null) return ""
            val sb = StringBuilder()
            for (i in 0 until msgs.length()) {
                val msg = msgs.getJSONObject(i)
                val isMe = msg.optBoolean("isMe", false)
                val sender = msg.optString("sender", "")
                val content = msg.optString("content", "")
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                if (isMe) {
                    sb.append("""<div class="msg-right"><div class="bubble-right">${content}</div></div>""")
                } else {
                    sb.append("""<div class="msg-left"><div class="sender-name">${sender}</div><div class="bubble-left">${content}</div></div>""")
                }
            }
            return sb.toString()
        }

        fun buildWalletRecords(): String {
            if (walletRecords == null) return ""
            val sb = StringBuilder()
            for (i in 0 until walletRecords.length()) {
                val r = walletRecords.getJSONObject(i)
                val amount = r.optString("amount", "")
                val desc = r.optString("desc", "")
                val time = r.optString("time", "")
                val color = if (amount.startsWith("+")) "#07C160" else "#FF3B30"
                sb.append("""<div class="wallet-row"><div><div class="wallet-desc">${desc}</div><div class="wallet-time">${time}</div></div><div class="wallet-amount" style="color:${color}">${amount}</div></div>""")
            }
            return sb.toString()
        }

        fun buildPhotos(): String {
            if (photos == null) return ""
            val sb = StringBuilder()
            for (i in 0 until photos.length()) {
                val desc = photos.getJSONObject(i).optString("desc", "")
                sb.append("""<div class="photo-item" onclick="toggleDesc(this)"><div class="photo-dark"></div><div class="photo-desc" style="display:none">${desc}</div></div>""")
            }
            return sb.toString()
        }

        fun buildSecretPhotos(): String {
            if (secretPhotos == null) return ""
            val sb = StringBuilder()
            for (i in 0 until secretPhotos.length()) {
                val desc = secretPhotos.getJSONObject(i).optString("desc", "")
                sb.append("""<div class="photo-item" onclick="toggleDesc(this)"><div class="photo-dark" style="background:#1a0000"></div><div class="photo-desc" style="display:none">🔒 ${desc}</div></div>""")
            }
            return sb.toString()
        }

        fun buildCallRecords(): String {
            if (callRecords == null) return ""
            val sb = StringBuilder()
            for (i in 0 until callRecords.length()) {
                val r = callRecords.getJSONObject(i)
                val name = r.optString("name", "")
                val type = r.optString("type", "")
                val duration = r.optString("duration", "")
                val content = r.optString("content", "")
                val icon = when { type.contains("未接") -> "📵"; type.contains("拒绝") -> "🚫"; type.contains("拨出") -> "📞"; else -> "✅" }
                val color = when { type.contains("未接") -> "#FF3B30"; type.contains("拒绝") -> "#FF9500"; else -> "#07C160" }
                sb.append("""<div class="call-row" onclick="toggleCall(this)"><div style="display:flex;justify-content:space-between;align-items:center"><div><div class="call-name">${icon} ${name}</div><div class="call-type" style="color:${color}">${type} · ${duration}</div></div><div style="color:#888;font-size:12px">▼</div></div><div class="call-content" style="display:none;margin-top:8px;font-size:13px;color:#888;line-height:1.6">${content}</div></div>""")
            }
            return sb.toString()
        }

        fun buildSearchHistory(): String {
            if (searchHistory == null) return ""
            val sb = StringBuilder()
            for (i in 0 until searchHistory.length()) {
                sb.append("""<div class="search-item">🔍 ${searchHistory.getString(i)}</div>""")
            }
            return sb.toString()
        }

        fun buildMusicSongs(): String {
            if (musicSongs == null) return ""
            val sb = StringBuilder()
            for (i in 0 until musicSongs.length()) {
                sb.append("""<div class="music-item">🎵 ${musicSongs.getString(i)}</div>""")
            }
            return sb.toString()
        }

        fun buildShopRecent(): String {
            if (shopRecent == null) return "<div style='padding:14px 16px;color:#888;font-size:13px'>暂无购买记录</div>"
            val sb = StringBuilder()
            for (i in 0 until shopRecent.length()) {
                val r = shopRecent.getJSONObject(i)
                val name = r.optString("name", "")
                val price = r.optString("price", "")
                val status = r.optString("status", "")
                val time = r.optString("time", "")
                val statusColor = when { status.contains("收货") -> "#34C759"; status.contains("运输") || status.contains("快递") -> "#FF9500"; status.contains("退") -> "#FF3B30"; else -> "#888" }
                sb.append("""<div class="shop-item"><div class="shop-name">${name}</div><div class="shop-meta"><span style="color:${statusColor};font-size:12px">${status}</span><span style="color:#555;font-size:11px;margin-left:8px">${time}</span></div><div class="shop-price">${price}</div></div>""")
            }
            return sb.toString()
        }

        fun buildShopCart(): String {
            if (shopCart == null) return "<div style='padding:14px 16px;color:#888;font-size:13px'>购物车是空的</div>"
            val sb = StringBuilder()
            for (i in 0 until shopCart.length()) {
                val r = shopCart.getJSONObject(i)
                val name = r.optString("name", "")
                val price = r.optString("price", "")
                val note = r.optString("note", "")
                val noteHtml = if (note.isNotEmpty()) """<div style="font-size:12px;color:#888;margin-top:3px">${note}</div>""" else ""
                sb.append("""<div class="shop-item"><div class="shop-name">🛒 ${name}</div>${noteHtml}<div class="shop-price" style="color:#FF9500">${price}</div></div>""")
            }
            return sb.toString()
        }

        // 用 StringBuilder 拼接 HTML，完全避免 Kotlin 字符串插值干扰
        val html = StringBuilder()
        html.append("""<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<style>
* { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, sans-serif; }
body { background: #000; color: #fff; }
.section { margin: 12px; background: #1C1C1E; border-radius: 12px; overflow: hidden; }
.app-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; padding: 16px; }
.app-icon { display: flex; flex-direction: column; align-items: center; gap: 6px; cursor: pointer; -webkit-tap-highlight-color: rgba(255,255,255,0.1); }
.app-ic { width: 56px; height: 56px; border-radius: 14px; display: flex; align-items: center; justify-content: center; font-size: 26px; }
.app-nm { font-size: 11px; color: rgba(255,255,255,0.6); }
.chat-item { display: flex; align-items: center; gap: 12px; padding: 13px 16px; border-bottom: 1px solid rgba(255,255,255,0.05); cursor: pointer; }
.chat-av { width: 44px; height: 44px; border-radius: 8px; background: #333; display: flex; align-items: center; justify-content: center; font-size: 18px; flex-shrink: 0; }
.chat-nm { font-size: 15px; color: #fff; }
.chat-last { font-size: 12px; color: #888; margin-top: 2px; }
.screen { display: none; position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: #111; z-index: 100; overflow-y: auto; -webkit-overflow-scrolling: touch; padding-bottom: 30px; }
.screen-top { position: sticky; top: 0; background: rgba(17,17,17,0.97); padding: 12px 16px; display: flex; align-items: center; z-index: 101; }
.back-btn { color: #07C160; font-size: 18px; cursor: pointer; margin-right: 12px; padding: 4px 8px; }
.screen-title { font-size: 16px; font-weight: 600; }
.msgs { padding: 12px 14px; }
.msg-left { display: flex; flex-direction: column; align-items: flex-start; margin-bottom: 10px; }
.msg-right { display: flex; justify-content: flex-end; margin-bottom: 10px; flex-direction: column; align-items: flex-end; }
.sender-name { font-size: 11px; color: #888; margin-bottom: 3px; }
.msg-time { font-size: 11px; color: #888; margin-bottom: 3px; }
.bubble-left { background: #2a2a2e; color: rgba(255,255,255,0.85); padding: 9px 13px; border-radius: 16px 16px 16px 4px; font-size: 14px; line-height: 1.6; max-width: 75%; }
.bubble-right { background: #07C160; color: #fff; padding: 9px 13px; border-radius: 16px 16px 4px 16px; font-size: 14px; line-height: 1.6; max-width: 75%; }
.wallet-row { display: flex; justify-content: space-between; align-items: center; padding: 14px 16px; border-bottom: 1px solid rgba(255,255,255,0.05); }
.wallet-desc { font-size: 14px; color: #fff; }
.wallet-time { font-size: 11px; color: #888; margin-top: 2px; }
.wallet-amount { font-size: 16px; font-weight: 600; }
.stat-row { display: flex; justify-content: space-around; padding: 16px; }
.stat-item { text-align: center; }
.stat-val { font-size: 24px; font-weight: 700; color: #07C160; }
.stat-label { font-size: 12px; color: #888; margin-top: 4px; }
.memo-item { padding: 14px 16px; border-bottom: 1px solid rgba(255,255,255,0.05); cursor: pointer; }
.memo-title { font-size: 15px; color: #fff; font-weight: 500; }
.memo-preview { font-size: 12px; color: #888; margin-top: 3px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.memo-full { display: none; font-size: 13px; color: #aaa; line-height: 1.8; margin-top: 8px; }
.photo-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 2px; }
.photo-item { position: relative; aspect-ratio: 1; cursor: pointer; overflow: hidden; }
.photo-dark { width: 100%; height: 100%; background: #1a1a1a; }
.photo-desc { position: absolute; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.88); padding: 8px; font-size: 11px; color: #ccc; line-height: 1.5; overflow-y: auto; }
.call-row { padding: 14px 16px; border-bottom: 1px solid rgba(255,255,255,0.05); cursor: pointer; }
.call-name { font-size: 15px; color: #fff; }
.call-type { font-size: 12px; margin-top: 2px; }
.search-item { padding: 12px 16px; border-bottom: 1px solid rgba(255,255,255,0.05); font-size: 14px; color: #aaa; }
.music-item { padding: 12px 16px; border-bottom: 1px solid rgba(255,255,255,0.05); font-size: 14px; color: #aaa; }
.info-block { padding: 14px 16px; font-size: 14px; color: #aaa; line-height: 1.8; }
.balance-display { padding: 24px 16px; text-align: center; }
.balance-num { font-size: 36px; font-weight: 700; color: #fff; }
.balance-label { font-size: 12px; color: #888; margin-top: 4px; }
.shop-item { padding: 12px 16px; border-bottom: 1px solid rgba(255,255,255,0.05); position: relative; }
.shop-name { font-size: 14px; color: #fff; padding-right: 70px; }
.shop-meta { margin-top: 4px; }
.shop-price { position: absolute; right: 16px; top: 12px; font-size: 14px; color: #fff; font-weight: 600; }
</style>
</head>
<body>

<div id="home" style="padding-bottom:40px">
  <div style="padding:20px 16px 8px;text-align:center;color:#888;font-size:12px">📱 """)
        html.append(aiName)
        html.append(""" 的手机</div>
  <div class="section">
    <div class="app-grid">
      <div class="app-icon" onclick="show('chat-screen')"><div class="app-ic" style="background:#07C160">💬</div><div class="app-nm">消息</div></div>
      <div class="app-icon" onclick="show('wallet-screen')"><div class="app-ic" style="background:#FF9500">💰</div><div class="app-nm">钱包</div></div>
      <div class="app-icon" onclick="show('health-screen')"><div class="app-ic" style="background:#FF2D55">❤️</div><div class="app-nm">健康</div></div>
      <div class="app-icon" onclick="show('memo-screen')"><div class="app-ic" style="background:#FFD60A">📝</div><div class="app-nm">备忘录</div></div>
      <div class="app-icon" onclick="show('photo-screen')"><div class="app-ic" style="background:#5856D6">🖼️</div><div class="app-nm">相册</div></div>
      <div class="app-icon" onclick="show('call-screen')"><div class="app-ic" style="background:#34C759">📞</div><div class="app-nm">通话</div></div>
      <div class="app-icon" onclick="show('search-screen')"><div class="app-ic" style="background:#636366">🔍</div><div class="app-nm">搜索</div></div>
      <div class="app-icon" onclick="show('music-screen')"><div class="app-ic" style="background:#FF375F">🎵</div><div class="app-nm">音乐</div></div>
      <div class="app-icon" onclick="show('schedule-screen')"><div class="app-ic" style="background:#007AFF">📅</div><div class="app-nm">行程</div></div>
      <div class="app-icon" onclick="show('shop-screen')"><div class="app-ic" style="background:#FF6B00">🛍️</div><div class="app-nm">购物</div></div>
    </div>
  </div>
</div>

<div class="screen" id="chat-screen">
  <div class="screen-top"><span class="back-btn" onclick="hide('chat-screen')">‹</span><span class="screen-title">消息</span></div>
  <div class="chat-item" onclick="show('chat-group')"><div class="chat-av">👥</div><div><div class="chat-nm">""")
        html.append(chatGroupName)
        html.append("""</div><div class="chat-last">群聊</div></div></div>
  <div class="chat-item" onclick="show('chat-friend1')"><div class="chat-av">👤</div><div><div class="chat-nm">""")
        html.append(chatFriend1Name)
        html.append("""</div><div class="chat-last">私聊</div></div></div>
  <div class="chat-item" onclick="show('chat-friend2')"><div class="chat-av">👤</div><div><div class="chat-nm">""")
        html.append(chatFriend2Name)
        html.append("""</div><div class="chat-last">私聊</div></div></div>
  <div class="chat-item" style="background:rgba(7,193,96,0.05)" onclick="show('chat-real')"><div class="chat-av" style="background:#07C160">⭐</div><div><div class="chat-nm" style="color:#07C160">""")
        html.append(displayUserName)
        html.append(""" [置顶]</div><div class="chat-last">与""")
        html.append(aiName)
        html.append("""的对话</div></div></div>
</div>

<div class="screen" id="chat-group">
  <div class="screen-top"><span class="back-btn" onclick="hide('chat-group')">‹</span><span class="screen-title">""")
        html.append(chatGroupName)
        html.append("""</span></div>
  <div class="msgs">""")
        html.append(buildChatMsgs(chatGroupMsgs))
        html.append("""</div>
</div>

<div class="screen" id="chat-friend1">
  <div class="screen-top"><span class="back-btn" onclick="hide('chat-friend1')">‹</span><span class="screen-title">""")
        html.append(chatFriend1Name)
        html.append("""</span></div>
  <div class="msgs">""")
        html.append(buildChatMsgs(chatFriend1Msgs))
        html.append("""</div>
</div>

<div class="screen" id="chat-friend2">
  <div class="screen-top"><span class="back-btn" onclick="hide('chat-friend2')">‹</span><span class="screen-title">""")
        html.append(chatFriend2Name)
        html.append("""</span></div>
  <div class="msgs">""")
        html.append(buildChatMsgs(chatFriend2Msgs))
        html.append("""</div>
</div>

<div class="screen" id="chat-real">
  <div class="screen-top"><span class="back-btn" onclick="hide('chat-real')">‹</span><span class="screen-title">""")
        html.append(displayUserName)
        html.append("""</span></div>
  <div class="msgs">""")
        html.append(realChatHtml)
        html.append("""</div>
</div>

<div class="screen" id="wallet-screen">
  <div class="screen-top"><span class="back-btn" onclick="hide('wallet-screen')">‹</span><span class="screen-title">钱包</span></div>
  <div class="section" style="margin:12px"><div class="balance-display"><div class="balance-label">余额</div><div class="balance-num">¥""")
        html.append(walletBalance)
        html.append("""</div></div></div>
  <div style="padding:0 12px 8px;color:#888;font-size:12px">最近交易</div>
  <div class="section" style="margin:0 12px">""")
        html.append(buildWalletRecords())
        html.append("""</div>
</div>

<div class="screen" id="health-screen">
  <div class="screen-top"><span class="back-btn" onclick="hide('health-screen')">‹</span><span class="screen-title">健康数据</span></div>
  <div class="section" style="margin:12px"><div class="stat-row"><div class="stat-item"><div class="stat-val">""")
        html.append(steps)
        html.append("""</div><div class="stat-label">今日步数</div></div><div class="stat-item"><div class="stat-val">""")
        html.append(calories)
        html.append("""</div><div class="stat-label">消耗卡路里</div></div></div></div>
  <div class="section" style="margin:12px"><div style="padding:12px 16px;font-size:12px;color:#888">昨晚睡眠</div><div class="info-block">""")
        html.append(sleepRecord)
        html.append("""</div></div>
</div>

<div class="screen" id="memo-screen">
  <div class="screen-top"><span class="back-btn" onclick="hide('memo-screen')">‹</span><span class="screen-title">备忘录</span></div>
  <div class="section" style="margin:12px">
    <div class="memo-item" onclick="toggleMemo(this)"><div class="memo-title">""")
        html.append(memo1Title)
        html.append("""</div><div class="memo-preview">""")
        html.append(memo1Body)
        html.append("""</div><div class="memo-full">""")
        html.append(memo1Body)
        html.append("""</div></div>
    <div class="memo-item" onclick="toggleMemo(this)"><div class="memo-title">""")
        html.append(memo2Title)
        html.append("""</div><div class="memo-preview">""")
        html.append(memo2Body)
        html.append("""</div><div class="memo-full">""")
        html.append(memo2Body)
        html.append("""</div></div>
    <div class="memo-item" onclick="toggleMemo(this)" style="border-left:3px solid #FF3B30"><div class="memo-title" style="color:#FF3B30">🔒 """)
        html.append(memo3Title)
        html.append("""</div><div class="memo-preview">点击查看...</div><div class="memo-full">""")
        html.append(memo3Body)
        html.append("""</div></div>
  </div>
</div>

<div class="screen" id="photo-screen">
  <div class="screen-top"><span class="back-btn" onclick="hide('photo-screen')">‹</span><span class="screen-title">相册</span></div>
  <div style="padding:8px 12px;font-size:12px;color:#888">点击照片查看描述</div>
  <div class="photo-grid">""")
        html.append(buildPhotos())
        html.append("""</div>
  <div style="padding:14px 16px;font-size:13px;color:#FF3B30;font-weight:600">🔒 私密相册</div>
  <div class="photo-grid">""")
        html.append(buildSecretPhotos())
        html.append("""</div>
</div>

<div class="screen" id="call-screen">
  <div class="screen-top"><span class="back-btn" onclick="hide('call-screen')">‹</span><span class="screen-title">通话记录</span></div>
  <div class="section" style="margin:12px">""")
        html.append(buildCallRecords())
        html.append("""</div>
</div>

<div class="screen" id="search-screen">
  <div class="screen-top"><span class="back-btn" onclick="hide('search-screen')">‹</span><span class="screen-title">搜索历史</span></div>
  <div class="section" style="margin:12px">""")
        html.append(buildSearchHistory())
        html.append("""</div>
</div>

<div class="screen" id="music-screen">
  <div class="screen-top"><span class="back-btn" onclick="hide('music-screen')">‹</span><span class="screen-title">""")
        html.append(musicPlaylist)
        html.append("""</span></div>
  <div class="section" style="margin:12px">""")
        html.append(buildMusicSongs())
        html.append("""</div>
</div>

<div class="screen" id="schedule-screen">
  <div class="screen-top"><span class="back-btn" onclick="hide('schedule-screen')">‹</span><span class="screen-title">今日行程</span></div>
  <div class="section" style="margin:12px"><div class="info-block">""")
        html.append(scheduleToday)
        html.append("""</div></div>
</div>

<div class="screen" id="shop-screen">
  <div class="screen-top"><span class="back-btn" onclick="hide('shop-screen')">‹</span><span class="screen-title">购物</span></div>
  <div style="padding:8px 12px 4px;color:#888;font-size:12px">最近购买</div>
  <div class="section" style="margin:0 12px 12px">""")
        html.append(buildShopRecent())
        html.append("""</div>
  <div style="padding:8px 12px 4px;color:#888;font-size:12px">购物车</div>
  <div class="section" style="margin:0 12px">""")
        html.append(buildShopCart())
        html.append("""</div>
</div>

<script>
function show(id) { document.getElementById(id).style.display = 'block'; }
function hide(id) { document.getElementById(id).style.display = 'none'; }
function toggleMemo(el) {
  var full = el.querySelector('.memo-full');
  var preview = el.querySelector('.memo-preview');
  if (full.style.display === 'none' || full.style.display === '') { full.style.display = 'block'; preview.style.display = 'none'; }
  else { full.style.display = 'none'; preview.style.display = 'block'; }
}
function toggleDesc(el) { var desc = el.querySelector('.photo-desc'); desc.style.display = desc.style.display === 'none' ? 'block' : 'none'; }
function toggleCall(el) { var content = el.querySelector('.call-content'); content.style.display = content.style.display === 'none' ? 'block' : 'none'; }
</script>
</body>
</html>""")
        return html.toString()
    }
}
