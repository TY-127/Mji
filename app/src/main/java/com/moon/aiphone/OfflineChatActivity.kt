package com.moon.aiphone

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.WebView
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class OfflineMsg(var id: Long, var content: String, val isFromMe: Boolean, var isThinking: Boolean = false)

/** 一个行动选项：label＝类别(如"选项3·恋爱")，text＝填入输入框的实际内容 */
data class AkoOption(val label: String, val text: String)

class OfflineChatActivity : AppCompatActivity() {
    private var aiId = ""
    private var aiName = ""
    private var aiAvatarUri: String? = null
    private var myAvatarUri: String? = null
    private var myName = "我"
    private val msgList = mutableListOf<OfflineMsg>()
    private lateinit var adapter: OfflineAdapter
    private var isAiRunning = false
    private lateinit var sendBtn: Button
    private lateinit var regenBtn: Button

    // 见面设置参数（由 OfflineSetupActivity 传入）
    private var meetLocation = ""
    private var meetTime = ""
    private var meetMood = ""
    private var meetBackground = ""
    private var meetMask = ""        // 用户本次扮演的身份（面具）
    private var meetPerson = "第二人称"  // 人称
    private var meetStyle = ""       // 文风（已解析为完整指令文本，空＝默认）
    private var meetSkit = false     // 是否开启盲盒小剧场
    private var meetAko = false      // 是否开启自动行动选项
    private var offlineSessionId = ""
    private val EDIT_REQ = 1001
    private val EXPORT_REQ = 1002
    private var pendingExportText: String? = null
    private var inputEt: EditText? = null
    private var doorTheme = DoorTheme()
    private val THEME_REQ = 1003

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aiId = intent.getStringExtra("AI_ID") ?: ""
        aiName = intent.getStringExtra("AI_NAME") ?: ""

        // 读取设置页传来的参数
        meetLocation = intent.getStringExtra("MEET_LOCATION") ?: ""
        meetTime = intent.getStringExtra("MEET_TIME") ?: ""
        meetMood = intent.getStringExtra("MEET_MOOD") ?: ""
        meetBackground = intent.getStringExtra("MEET_BACKGROUND") ?: ""
        meetMask = intent.getStringExtra("MEET_MASK") ?: ""
        meetPerson = intent.getStringExtra("MEET_PERSON") ?: "第二人称"
        meetStyle = intent.getStringExtra("MEET_STYLE") ?: ""
        meetSkit = intent.getBooleanExtra("MEET_SKIT", false)
        meetAko = intent.getBooleanExtra("MEET_AKO", false)
        offlineSessionId = intent.getStringExtra("OFFLINE_SESSION_ID")
            ?: savedInstanceState?.getString("OFFLINE_SESSION_ID")
            ?: getOrCreateActiveSessionId()
        getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            .edit().putString("offlineActiveSession_$aiId", offlineSessionId).apply()

        try {
            val db = DatabaseHelper(this).writableDatabase
            var cur = db.rawQuery("SELECT avatarUri FROM Contacts WHERE userId=?", arrayOf(aiId))
            if (cur.moveToFirst()) aiAvatarUri = cur.getString(0)
            cur.close()

            cur = db.rawQuery("SELECT myAvatarUri, myName FROM MyProfile LIMIT 1", null)
            if (cur.moveToFirst()) {
                myAvatarUri = cur.getString(0)
                val dbName = cur.getString(1)
                if (!dbName.isNullOrEmpty()) myName = dbName
            }
            cur.close()

            db.execSQL("CREATE TABLE IF NOT EXISTS OfflineChatHistory (id INTEGER PRIMARY KEY AUTOINCREMENT, aiId TEXT, content TEXT, isFromMe INTEGER, timestamp INTEGER)")
            ensureOfflineSessionColumn(db)
            // 旧版本没有 sessionId 列，老消息的 sessionId 为空，升级后会"消失"（其实还在库里）。
            // 把该角色的无会话老消息收编进当前会话，幂等：收编一次后不再有空 sessionId 的行
            try {
                db.execSQL(
                    "UPDATE OfflineChatHistory SET sessionId=? WHERE aiId=? AND IFNULL(sessionId,'')=''",
                    arrayOf(offlineSessionId, aiId)
                )
            } catch (_: Exception) {}

            val curMsg = db.rawQuery("SELECT id, content, isFromMe FROM OfflineChatHistory WHERE aiId=? AND IFNULL(sessionId,'')=? ORDER BY timestamp ASC", arrayOf(aiId, offlineSessionId))
            while (curMsg.moveToNext()) {
                msgList.add(OfflineMsg(curMsg.getLong(0), curMsg.getString(1), curMsg.getInt(2) == 1))
            }
            curMsg.close()
        } catch (e: Exception) {}

        supportActionBar?.hide()

        // 设置已在上一页完成，直接构建聊天界面
        buildUI()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("OFFLINE_SESSION_ID", offlineSessionId)
    }

    private fun ensureOfflineSessionColumn(db: android.database.sqlite.SQLiteDatabase) {
        try { db.execSQL("ALTER TABLE OfflineChatHistory ADD COLUMN sessionId TEXT DEFAULT ''") } catch (_: Exception) {}
        try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_offline_chat_session ON OfflineChatHistory(aiId, sessionId, timestamp)") } catch (_: Exception) {}
    }

    private fun getOrCreateActiveSessionId(): String {
        val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val key = "offlineActiveSession_$aiId"
        pref.getString(key, "")?.takeIf { it.isNotBlank() }?.let { return it }
        val created = "door_${aiId}_${System.currentTimeMillis()}"
        pref.edit().putString(key, created).apply()
        return created
    }

    private fun offlineTimeoutSeconds(): Long {
        // 下限 300 秒（5 分钟）：慢模型出字慢，太短会没输出完就被掐断。
        // coerceIn 会把设备上残留的旧小值（比如以前存的 60/120）也自动抬到 5 分钟。
        return getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            .getInt("offlineTimeoutSeconds", 300)
            .coerceIn(300, 1800)
            .toLong()
    }

    /** 弹窗自定义等待回复的超时秒数（慢模型可调大） */
    private fun showTimeoutDialog() {
        val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val et = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(pref.getInt("offlineTimeoutSeconds", 300).toString())
            hint = "秒（300-1800，即 5-30 分钟）"
        }
        AlertDialog.Builder(this)
            .setTitle("回复等待超时")
            .setMessage("模型出字慢时可以调大，超过该时长仍未收到回复才会判定失败。")
            .setView(et)
            .setPositiveButton("保存") { _, _ ->
                val v = et.text.toString().toIntOrNull()
                if (v == null) {
                    Toast.makeText(this, "请输入数字", Toast.LENGTH_SHORT).show()
                } else {
                    val clamped = v.coerceIn(300, 1800)
                    pref.edit().putInt("offlineTimeoutSeconds", clamped).apply()
                    Toast.makeText(this, "已设置为 $clamped 秒", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun buildUI() {
        doorTheme = DoorThemeManager.load(this, aiId)
        val screen = FrameLayout(this).apply {
            setBackgroundColor(doorTheme.screenBg)
        }

        if (doorTheme.backgroundHtml.isNotBlank()) {
            screen.addView(WebView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = false
                settings.domStorageEnabled = false
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                loadDataWithBaseURL(
                    null,
                    DoorThemeManager.backgroundDocument(doorTheme),
                    "text/html",
                    "UTF-8",
                    null
                )
            })
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (doorTheme.backgroundHtml.isBlank()) doorTheme.screenBg else Color.TRANSPARENT)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(doorTheme.headerBg)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            gravity = Gravity.CENTER_VERTICAL
            elevation = 8f
        }
        val backBtn = TextView(this).apply {
            text = "←"
            setTextColor(doorTheme.headerText)
            textSize = 22f
            setPadding(0, 0, 20, 0)
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "推开了 $aiName 的门"
            setTextColor(doorTheme.headerText)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val menuBtn = TextView(this).apply {
            text = "≡"
            setTextColor(doorTheme.headerText)
            textSize = 28f
            setPadding(20, 0, 0, 0)
            setOnClickListener { showMenu() }
        }
        topBar.addView(backBtn)
        topBar.addView(title)
        topBar.addView(menuBtn)
        root.addView(topBar)

        val rv = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutManager = LinearLayoutManager(this@OfflineChatActivity)
            setBackgroundColor(doorTheme.storyBg)
            clipToPadding = false
            setPadding(0, dp(6), 0, dp(6))
        }
        adapter = OfflineAdapter()
        rv.adapter = adapter
        root.addView(rv)

        val inputArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            setBackgroundColor(doorTheme.inputAreaBg)
            gravity = Gravity.CENTER_VERTICAL
            elevation = 10f
        }
        val et = EditText(this).apply {
            hint = "输入描写或对话…"
            setHintTextColor(doorTheme.inputHint)
            setTextColor(doorTheme.inputText)
            textSize = 14f
            setSingleLine(true)
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(doorTheme.inputBg)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), doorTheme.border)
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                marginStart = dp(7)
                marginEnd = dp(7)
            }
        }
        inputEt = et
        sendBtn = Button(this).apply {
            text = "发送"
            setTextColor(doorTheme.sendText)
            textSize = 13f
            setPadding(dp(12), 0, dp(12), 0)
            minimumHeight = 0
            minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(40))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(doorTheme.sendBg)
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener {
                if (isAiRunning) return@setOnClickListener
                val txt = et.text.toString()
                if (txt.isNotEmpty()) {
                    val ts = System.currentTimeMillis()
                    try {
                        val db = DatabaseHelper(this@OfflineChatActivity).writableDatabase
                        ensureOfflineSessionColumn(db)
                        val cvMe = ContentValues().apply {
                            put("aiId", aiId); put("content", txt); put("isFromMe", 1); put("timestamp", ts)
                            put("sessionId", offlineSessionId)
                        }
                        val myNewId = db.insert("OfflineChatHistory", null, cvMe)
                        msgList.add(OfflineMsg(myNewId, txt, true))
                        val aiMsgObj = OfflineMsg(-1, "...", false, true)
                        msgList.add(aiMsgObj)
                        adapter.notifyDataSetChanged()
                        rv.scrollToPosition(msgList.size - 1)
                        et.setText("")
                        callAi(txt, aiMsgObj, rv)
                    } catch (e: Exception) {}
                }
            }
        }
        regenBtn = Button(this).apply {
            text = "▶"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 0, 0, 0)
            minimumHeight = 0; minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(doorTheme.regenBg); cornerRadius = dp(12).toFloat()
            }
            setOnClickListener {
                if (isAiRunning) return@setOnClickListener
                val lastMyMsg = msgList.lastOrNull { it.isFromMe } ?: return@setOnClickListener
                val aiMsgObj = OfflineMsg(-1, "...", false, true)
                msgList.add(aiMsgObj)
                adapter.notifyDataSetChanged()
                rv.scrollToPosition(msgList.size - 1)
                callAi(lastMyMsg.content, aiMsgObj, rv)
            }
        }
        inputArea.addView(regenBtn)
        inputArea.addView(et)
        inputArea.addView(sendBtn)
        root.addView(inputArea)
        rv.post { if (msgList.isNotEmpty()) rv.scrollToPosition(msgList.size - 1) }
        screen.addView(root)
        setContentView(screen)
    }

    private fun showMenu() {
        val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val currentLang = pref.getString("offlineLang", "中文") ?: "中文"
        val langLabel = if (currentLang == "中文") "🌐 语言：中文（点击切换英文）" else "🌐 Language: English (tap to switch to 中文)"

        AlertDialog.Builder(this)
            .setTitle("见面选项")
            .setItems(arrayOf(
                "⚙️ 修改见面设置",
                "🎨 线下见面美化",
                "📝 结束本次见面，写入记忆",
                "🗑️ 清除本次对话记录",
                "📤 导出全部聊天记录（txt）",
                langLabel,
                "⏱ 回复等待超时（当前 ${pref.getInt("offlineTimeoutSeconds", 300)} 秒）",
                "🔄 重新开始（开新会话，旧对话保留）",
                "↩ 返回"
            )) { _, which ->
                when (which) {
                    0 -> launchEditSettings()
                    1 -> launchThemeSettings()
                    2 -> confirmEndMeeting()
                    3 -> confirmClearHistory()
                    4 -> exportHistory()
                    5 -> {
                        val newLang = if (currentLang == "中文") "英文" else "中文"
                        pref.edit().putString("offlineLang", newLang).apply()
                        val tip = if (newLang == "中文") "已切换为中文" else "Switched to English"
                        Toast.makeText(this, tip, Toast.LENGTH_SHORT).show()
                    }
                    6 -> showTimeoutDialog()
                    7 -> confirmRestartSession()
                    8 -> { /* 关闭菜单 */ }
                }
            }.show()
    }

    /** 导出当前角色的全部线下聊天记录为 txt（用系统保存对话框，无需权限） */
    private fun exportHistory() {
        try {
            val db = DatabaseHelper(this).readableDatabase
            val c = db.rawQuery(
                "SELECT COUNT(*) FROM OfflineChatHistory WHERE aiId=? AND IFNULL(sessionId,'')=?",
                arrayOf(aiId, offlineSessionId)
            )
            val count = if (c.moveToFirst()) c.getInt(0) else 0
            c.close()
            if (count == 0) {
                Toast.makeText(this, "暂无聊天记录可导出", Toast.LENGTH_SHORT).show()
                return
            }
        } catch (e: Exception) {
            Toast.makeText(this, "读取记录失败：${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("导出格式")
            .setItems(arrayOf("干净阅读版（去掉HTML与选项标记）", "原始完整版")) { _, w ->
                doExport(clean = (w == 0))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doExport(clean: Boolean) {
        try {
            val db = DatabaseHelper(this).readableDatabase
            val cur = db.rawQuery(
                "SELECT content, isFromMe, timestamp FROM OfflineChatHistory WHERE aiId=? AND IFNULL(sessionId,'')=? ORDER BY timestamp ASC",
                arrayOf(aiId, offlineSessionId)
            )
            val tf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            val sb = StringBuilder()
            sb.append("线下见面聊天记录\n")
            sb.append("角色：$aiName\n")
            sb.append("导出时间：${tf.format(java.util.Date())}\n")
            sb.append("==============================\n\n")
            while (cur.moveToNext()) {
                val rawContent = cur.getString(0) ?: ""
                val isMe = cur.getInt(1) == 1
                val ts = cur.getLong(2)
                val role = if (isMe) myName else aiName
                // 只清洗 AI 的消息，用户自己输入的原样保留
                val content = if (clean && !isMe) cleanForExport(rawContent) else rawContent
                sb.append("[${tf.format(java.util.Date(ts))}] $role：\n")
                sb.append(content)
                sb.append("\n\n")
            }
            cur.close()
            pendingExportText = sb.toString()

            val tag = if (clean) "阅读版" else "完整版"
            val dateStr = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.getDefault())
                .format(java.util.Date())
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "线下见面_${aiName}_${tag}_$dateStr.txt")
            }
            startActivityForResult(intent, EXPORT_REQ)
        } catch (e: Exception) {
            Toast.makeText(this, "读取记录失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 把一条 AI 消息清洗成纯阅读文本：去掉小剧场HTML、行动选项、HTML标签与实体 */
    private fun cleanForExport(raw: String): String {
        var s = raw
        // 小剧场代码块（```html ... ```）-> 标记
        s = s.replace(Regex("```[a-zA-Z]*\\s*[\\s\\S]*?```"), "〔小剧场〕")
        // 行动选项 <details>...</details>
        s = s.replace(Regex("(?is)<details>[\\s\\S]*?</details>"), "")
        // <br> -> 换行
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        // 残留的 HTML 标签
        s = s.replace(Regex("<[^>]+>"), "")
        // 没被 details 包裹的裸选项行
        s = s.replace(Regex("【\\s*选项[^】]*】[^\\n]*"), "")
        s = s.replace(Regex("行动选项[:：]?"), "")
        // 常见 HTML 实体
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        // 合并多余空行
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    /** 带着当前设置进入设置页（编辑模式），保存后通过 onActivityResult 就地更新 */
    private fun launchEditSettings() {
        val i = Intent(this, OfflineSetupActivity::class.java).apply {
            putExtra("EDIT_MODE", true)
            putExtra("AI_ID", aiId)
            putExtra("AI_NAME", aiName)
            putExtra("MEET_LOCATION", meetLocation)
            putExtra("MEET_TIME", meetTime)
            putExtra("MEET_MOOD", meetMood)
            putExtra("MEET_BACKGROUND", meetBackground)
            putExtra("MEET_MASK", meetMask)
            putExtra("MEET_PERSON", meetPerson)
            putExtra("MEET_STYLE", meetStyle)
            putExtra("MEET_SKIT", meetSkit)
            putExtra("MEET_AKO", meetAko)
        }
        startActivityForResult(i, EDIT_REQ)
    }

    private fun launchThemeSettings() {
        startActivityForResult(
            Intent(this, DoorThemeSettingsActivity::class.java).putExtra("AI_ID", aiId),
            THEME_REQ
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == EDIT_REQ && resultCode == RESULT_OK && data != null) {
            meetLocation = data.getStringExtra("MEET_LOCATION") ?: meetLocation
            meetTime = data.getStringExtra("MEET_TIME") ?: meetTime
            meetMood = data.getStringExtra("MEET_MOOD") ?: meetMood
            meetBackground = data.getStringExtra("MEET_BACKGROUND") ?: meetBackground
            meetMask = data.getStringExtra("MEET_MASK") ?: meetMask
            meetPerson = data.getStringExtra("MEET_PERSON") ?: meetPerson
            meetStyle = data.getStringExtra("MEET_STYLE") ?: meetStyle
            meetSkit = data.getBooleanExtra("MEET_SKIT", meetSkit)
            meetAko = data.getBooleanExtra("MEET_AKO", meetAko)
            Toast.makeText(this, "见面设置已更新，下一条生效", Toast.LENGTH_SHORT).show()
        }
        if (requestCode == THEME_REQ && resultCode == RESULT_OK) {
            buildUI()
            Toast.makeText(this, "门美化已刷新", Toast.LENGTH_SHORT).show()
        }
        if (requestCode == EXPORT_REQ && resultCode == RESULT_OK && data?.data != null) {
            try {
                contentResolver.openOutputStream(data.data!!)?.use { os ->
                    os.write((pendingExportText ?: "").toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this, "聊天记录已导出 ✓", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
            }
            pendingExportText = null
        }
    }

    private fun confirmEndMeeting() {
        AlertDialog.Builder(this)
            .setTitle("结束本次见面")
            .setMessage("AI 会把这次见面的内容压缩成记忆，存入长期记忆中，线上聊天时也能记住。确定吗？")
            .setPositiveButton("确定") { _, _ -> summarizeAndSaveMemory() }
            .setNegativeButton("再聊一会", null)
            .show()
    }

    /** 开一个全新会话：旧对话保留在库中（相当于存档点），界面从零开始 */
    private fun confirmRestartSession() {
        AlertDialog.Builder(this)
            .setTitle("重新开始")
            .setMessage("将开启一个全新的会话，从头开始剧情。\n当前对话会保留在库中，不会被删除。")
            .setPositiveButton("重新开始") { _, _ ->
                val created = "door_${aiId}_${System.currentTimeMillis()}"
                getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                    .edit().putString("offlineActiveSession_$aiId", created).apply()
                offlineSessionId = created
                msgList.clear()
                adapter.notifyDataSetChanged()
                Toast.makeText(this, "新会话已开启 ✓", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle("清除记录")
            .setMessage("只清除本次对话记录，不影响长期记忆。")
            .setPositiveButton("清除") { _, _ ->
                try {
                    DatabaseHelper(this).writableDatabase.execSQL(
                        "DELETE FROM OfflineChatHistory WHERE aiId=? AND IFNULL(sessionId,'')=?",
                        arrayOf(aiId, offlineSessionId)
                    )
                    msgList.clear()
                    adapter.notifyDataSetChanged()
                    Toast.makeText(this, "对话记录已清除", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {}
            }
            .setNegativeButton("取消", null).show()
    }

    private fun summarizeAndSaveMemory() {
        if (msgList.isEmpty()) { Toast.makeText(this, "没有对话内容可以总结", Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(this, "正在压缩记忆，请稍候…", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val sharedPref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                val url = sharedPref.getString("apiUrl", "") ?: return@Thread
                val key = sharedPref.getString("apiKey", "") ?: return@Thread
                val model = sharedPref.getString(
                    "modelName",
                    ""
                )?.ifBlank { "gpt-4o" } ?: "gpt-4o"
                var finalUrl = if (url.endsWith("/")) url.dropLast(1) else url
                if (!finalUrl.endsWith("/chat/completions")) finalUrl += if (finalUrl.contains("/v1")) "/chat/completions" else "/v1/chat/completions"

                // 构造对话记录文本
                val dialogText = StringBuilder()
                msgList.filter { !it.isThinking }.forEach { msg ->
                    val role = if (msg.isFromMe) myName else aiName
                    dialogText.append("${role}：${msg.content}\n")
                }

                // 构造见面背景信息
                val contextInfo = buildString {
                    if (meetLocation.isNotEmpty()) append("地点：$meetLocation。")
                    if (meetTime.isNotEmpty()) append("时间：$meetTime。")
                    if (meetMood.isNotEmpty()) append("氛围：$meetMood。")
                    if (meetMask.isNotEmpty()) append("${myName}本次扮演：$meetMask。")
                    if (meetBackground.isNotEmpty()) append("背景：$meetBackground。")
                }

                val summaryPrompt = """
                    以下是 $myName 和 $aiName 的一次线下见面记录。
                    见面情况：$contextInfo
                    
                    对话内容：
                    $dialogText
                    
                    请你以 $aiName 的第一人称视角，把这次见面压缩成一段100-200字的记忆摘要。
                    要求：
                    1. 记录重要的情感变化、发生的事、说过的关键话
                    2. 语气像是 $aiName 在回忆这段经历
                    3. 只输出记忆摘要文本，不要任何前缀或解释
                """.trimIndent()

                val jsonBody = JSONObject().apply {
                    put("model", model)
                    put("temperature", 0.6)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "user"); put("content", summaryPrompt) })
                    })
                }
                val request = Request.Builder().url(finalUrl).addHeader("Authorization", "Bearer $key")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull())).build()
                val timeout = offlineTimeoutSeconds()
                val response = Http.client.newBuilder().connectTimeout(60, TimeUnit.SECONDS).readTimeout(timeout, TimeUnit.SECONDS).build().newCall(request).execute()
                val body = response.body?.string()

                if (!body.isNullOrEmpty()) {
                    val jsonObj = JSONObject(body)
                    if (jsonObj.has("choices")) {
                        val summary = jsonObj.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()

                        // 写入 MemoryBank，每次见面单独插入一条新记忆，不合并
                        val db = DatabaseHelper(this).writableDatabase
                        val dateStr = java.text.SimpleDateFormat("MM月dd日", java.util.Locale.getDefault()).format(java.util.Date())
                        val cv = ContentValues().apply {
                            put("aiId", aiId)
                            put("memoryText", "【线下见面 $dateStr】$summary")
                            put("category", "shared_event")
                            put("insertTime", System.currentTimeMillis())
                        }
                        db.insert("MemoryBank", null, cv)

                        runOnUiThread {
                            AlertDialog.Builder(this)
                                .setTitle("记忆已写入 ✓")
                                .setMessage("本次见面已压缩为记忆：\n\n$summary")
                                .setPositiveButton("好的") { _, _ ->
                                    getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                                        .edit().remove("offlineActiveSession_$aiId").apply()
                                    finish()
                                }
                                .show()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "压缩失败：${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    inner class OfflineAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val itemRoot = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(dp(12), dp(doorTheme.itemVerticalDp / 2), dp(12), dp(doorTheme.itemVerticalDp / 2))
                }
                setPadding(
                    dp(doorTheme.itemHorizontalDp),
                    dp(doorTheme.itemVerticalDp),
                    dp(doorTheme.itemHorizontalDp),
                    dp(doorTheme.itemVerticalDp)
                )
            }
            val avatar = ImageView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(doorTheme.avatarSizeDp), dp(doorTheme.avatarSizeDp)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(doorTheme.avatarRadiusDp).toFloat())
                    }
                }
                clipToOutline = true; tag = "avatar"
            }
            val floorTv = TextView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(6) }
                setTextColor(doorTheme.mutedText); setTypeface(null, Typeface.BOLD); textSize = 12f; tag = "floor"
            }
            val nameTv = TextView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
                setTextColor(doorTheme.accent); setTypeface(null, Typeface.BOLD); textSize = 16f; tag = "name"
            }
            // 正文与小剧场可混排：用一个纵向容器，按段落动态填 TextView / WebView
            val contentContainer = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 10 }
                tag = "content_container"
            }
            itemRoot.addView(avatar); itemRoot.addView(floorTv); itemRoot.addView(nameTv)
            itemRoot.addView(contentContainer)
            return object : RecyclerView.ViewHolder(itemRoot) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = msgList[position]
            val root = holder.itemView as LinearLayout
            val avatar = root.findViewWithTag<ImageView>("avatar")
            val floorTv = root.findViewWithTag<TextView>("floor")
            val nameTv = root.findViewWithTag<TextView>("name")
            val container = root.findViewWithTag<LinearLayout>("content_container")

            floorTv.text = "#${position + 1}"
            nameTv.text = if (item.isFromMe) myName else aiName
            // 楼层不使用整块卡片底板或外框；头像、名字、楼层号与留白就是分隔标志。
            root.setBackgroundColor(Color.TRANSPARENT)

            // 先回收旧的 WebView，避免内存泄漏，再按段落重建
            for (i in 0 until container.childCount) {
                (container.getChildAt(i) as? WebView)?.destroy()
            }
            container.removeAllViews()
            val ctx = container.context

            if (item.isThinking) {
                container.addView(makeTextSegment(ctx, "正在思考中...", doorTheme.mutedText))
            } else {
                // AI 消息：先把"行动选项"剥离出来，正文/小剧场正常渲染，选项做成可点按钮
                var bodyText = item.content
                var options: List<AkoOption> = emptyList()
                if (!item.isFromMe) {
                    val parsed = extractOptions(item.content)
                    bodyText = parsed.first
                    options = parsed.second
                }
                // 把内容拆成「正文 / 小剧场HTML」多段，按顺序混排渲染
                parseSegments(bodyText).forEach { (isHtml, seg) ->
                    if (isHtml) {
                        val web = WebView(ctx).apply {
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 }
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            setBackgroundColor(Color.TRANSPARENT)
                            isVerticalScrollBarEnabled = false
                            loadDataWithBaseURL(null, wrapHtml(seg), "text/html", "UTF-8", null)
                            // 长按转发给整条 item，保留删除菜单
                            setOnLongClickListener { root.performLongClick(); true }
                        }
                        container.addView(web)
                    } else {
                        container.addView(makeTextSegment(ctx, seg, if (item.isFromMe) doorTheme.userText else doorTheme.text))
                    }
                }
                // 行动选项按钮（点击填入输入框，不直接发送）
                if (options.isNotEmpty()) {
                    container.addView(makeOptionsHeader(ctx))
                    options.forEach { opt -> container.addView(makeOptionButton(ctx, opt)) }
                }
            }

            val uriStr = if (item.isFromMe) myAvatarUri else aiAvatarUri
            if (!uriStr.isNullOrEmpty()) { avatar.setImageURI(Uri.parse(uriStr)); avatar.setBackgroundColor(Color.TRANSPARENT) }
            else { avatar.setImageDrawable(null); avatar.setBackgroundColor(Color.parseColor("#CCCCCC")) }

            root.setOnLongClickListener {
                AlertDialog.Builder(root.context).setTitle("删除跑团记录").setMessage("要删掉这条记录吗？")
                    .setPositiveButton("仅删此条") { _, _ ->
                        try {
                            DatabaseHelper(root.context).writableDatabase
                                .execSQL("DELETE FROM OfflineChatHistory WHERE id=?", arrayOf(item.id))
                            val currentPos = holder.adapterPosition
                            if (currentPos == RecyclerView.NO_POSITION) return@setPositiveButton

                            msgList.removeAt(currentPos); notifyDataSetChanged()
                        } catch (e: Exception) {}
                    }
                    .setNeutralButton("删除+清记忆") { _, _ ->
                        try {
                            val db = DatabaseHelper(root.context).writableDatabase
                            db.execSQL("DELETE FROM OfflineChatHistory WHERE id=?", arrayOf(item.id))
                            // 关键词太短会 LIKE 命中该角色几乎所有记忆，导致记忆被清空
                            val keyword = item.content.trim().take(30)
                            if (aiId.isNotEmpty() && keyword.length >= 15) {
                                db.delete("MemoryBank", "aiId=? AND memoryText LIKE ?", arrayOf(aiId, "%$keyword%"))
                            }
                            msgList.removeAt(position); notifyDataSetChanged()
                            Toast.makeText(root.context, "消息和相关记忆已清除", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {}
                    }
                    .setNegativeButton("点错了", null).show()
                true
            }
        }

        override fun getItemCount() = msgList.size
    }

    // ---------- HTML 小剧场渲染辅助 ----------

    private fun cleanAiAnswerForBubble(raw: String): String {
        var text = raw.trim()
        text = text.replace(Regex("【内心】[\\s\\S]*?(?=【台词】|$)"), "").trim()

        val dialogMatch = Regex("【台词】([\\s\\S]*?)(?=【(?:翻译|译|选项[^】]*)】|$)").find(text)
        if (dialogMatch != null) {
            text = dialogMatch.groupValues[1].trim()
        }

        val transMatch = Regex("【(?:翻译|译)】[：:]?\\s*([\\s\\S]*?)(?=【选项[^】]*】|$)").find(raw)
        val translation = transMatch?.groupValues?.get(1)?.trim().orEmpty()
        val options = Regex("【\\s*选项[^】]*】[\\s\\S]*$").find(raw)?.value?.trim().orEmpty()

        text = text
            .replace(Regex("【(?:翻译|译)】[\\s\\S]*$"), "")
            .replace(Regex("【[^】]*】"), "")
            .trim()

        return buildString {
            if (text.isNotEmpty()) append(text)
            if (translation.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("译：").append(translation)
            }
            if (options.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append(options)
            }
        }.ifBlank { raw.trim() }
    }
    /** 一段消息可能是「正文 + 小剧场HTML + 正文…」的混排，按代码块切分并标注每段是否为 HTML */
    private fun parseSegments(content: String): List<Pair<Boolean, String>> {
        val segments = mutableListOf<Pair<Boolean, String>>()
        val regex = Regex("```(?:html)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        var last = 0
        for (m in regex.findAll(content)) {
            val before = content.substring(last, m.range.first)
            if (before.isNotBlank()) segments.add(false to before.trim())
            val code = m.groupValues[1].trim()
            if (code.isNotBlank()) segments.add(looksLikeHtml(code) to code)
            last = m.range.last + 1
        }
        if (last < content.length) {
            val tail = content.substring(last)
            if (tail.isNotBlank()) {
                val isHtml = segments.isEmpty() && looksLikeHtml(tail)
                segments.add(isHtml to tail.trim())
            }
        }
        if (segments.isEmpty()) {
            segments.add(looksLikeHtml(content) to content.trim())
        }
        return segments
    }

    private fun looksLikeHtml(s: String): Boolean {
        val lower = s.lowercase()
        return lower.contains("<!doctype") || lower.contains("<html") || lower.contains("<body") ||
                lower.contains("<style") || lower.contains("<svg") ||
                (lower.contains("<div") && lower.contains("</div>")) ||
                (lower.contains("<table") && lower.contains("</table>")) ||
                (lower.contains("<ul") && lower.contains("</ul>")) ||
                (lower.contains("<p") && lower.contains("</p>") && lower.contains("style="))
    }

    private fun makeTextSegment(ctx: Context, text: String, color: Int): TextView = TextView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 12 }
        setTextColor(color); textSize = doorTheme.contentTextSp; setLineSpacing(0f, doorTheme.lineHeight)
        this.text = text
    }

    // ---------- 行动选项 解析与按钮 ----------

    /** 从 AI 输出里剥离"行动选项"块：返回(去掉选项后的正文, 选项列表) */
    private fun extractOptions(content: String): Pair<String, List<AkoOption>> {
        // 三类写法都识别：【选项1·日常】xxx ／ 选项1：xxx（无括号）／ 1. xxx
        // 标签后允许换行（[\s:：]*），兼容模型把选项内容写在下一行的情况
        val optRegex = Regex(
            "(?:[【\\[]\\s*((?:选项|Option)\\s*\\d*[^】\\]\\n]*)[】\\]]|^\\s*((?:选项|Option)\\s*\\d+[^\\S\\n]*[·・]?[^\\n:：<]{0,12}|\\d+[.．、]\\s*[^\\n:：]*))[\\s:：]*([^\\n<]+)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        )
        val detailsRegex = Regex("<details>[\\s\\S]*?(?:</details>|$)", RegexOption.IGNORE_CASE)
        val det = detailsRegex.find(content)
        val isOptionsDetails = det != null &&
                (det.value.contains("行动选项") || optRegex.containsMatchIn(det.value))
        val scope = if (isOptionsDetails) det!!.value else content

        val options = mutableListOf<AkoOption>()
        val plainScope = scope
            .replace("**", "")  // 去掉 markdown 加粗，防止 **【选项1】** 影响匹配
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</?(details|summary)>"), "\n")
        for (m in optRegex.findAll(plainScope)) {
            val label = m.groupValues[1].ifBlank { m.groupValues[2] }.trim()
            val text = m.groupValues[3].trim().trim('，', ',', '。', '）', ')', ' ')
            if (text.isNotEmpty() && !text.contains("<summary", ignoreCase = true)) {
                options.add(AkoOption(label.ifBlank { "选项${options.size + 1}" }, text))
            }
        }
        if (options.isEmpty()) return content.trim() to emptyList()

        var cleaned = content
        if (isOptionsDetails) {
            cleaned = content.replace(det!!.value, "")
        } else {
            for (m in optRegex.findAll(plainScope)) cleaned = cleaned.replace(m.value, "")
            // 匹配是在去掉 **/<br> 的文本上做的，原文里的选项行可能替换不掉，按行再清一遍
            cleaned = cleaned.replace(
                Regex("(?im)^[^\\S\\n]*\\*{0,2}[【\\[][^】\\]\\n]*(?:选项|Option)[^】\\]\\n]*[】\\]][^\\n]*$"), ""
            )
            cleaned = cleaned.replace(Regex("行动选项[:：]?"), "")
            cleaned = cleaned.replace(Regex("(?i)</?summary>"), "")
            cleaned = cleaned.replace(Regex("(?i)</?details>"), "")
            cleaned = cleaned.replace(Regex("(?i)<br\\s*/?>"), "\n")
        }
        return cleaned.trim() to options
    }

    private fun makeOptionsHeader(ctx: Context): TextView = TextView(ctx).apply {
        text = "行动选项（点一下填入输入框，可修改后再发送）"
        textSize = 12f
        setTextColor(doorTheme.mutedText)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 24 }
    }

    private fun makeOptionButton(ctx: Context, opt: AkoOption): TextView = TextView(ctx).apply {
        text = "【${opt.label}】${opt.text}"
        textSize = 14f
        setTextColor(doorTheme.optionText)
        setPadding(28, 22, 28, 22)
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(doorTheme.optionBg)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), doorTheme.optionBorder)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 14 }
        setOnClickListener {
            inputEt?.let { field ->
                field.setText(opt.text)
                field.setSelection(opt.text.length)
                field.requestFocus()
            }
        }
    }

    /** 如果是片段则补全 head/viewport，保证自适应宽度；完整文档则原样加载 */
    private fun wrapHtml(html: String): String {
        if (html.lowercase().contains("<html")) return html
        return """<!DOCTYPE html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>html,body{margin:0;padding:0;background:transparent;}img{max-width:100%;height:auto;}*{box-sizing:border-box;}</style>
            </head><body>$html</body></html>""".trimIndent()
    }

    private fun callAi(inputText: String, aiMsgObj: OfflineMsg, rv: RecyclerView) {
        val ts = System.currentTimeMillis()
        isAiRunning = true; sendBtn.alpha = 0.5f; regenBtn.alpha = 0.5f
        Thread {
            try {
                val sharedPref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                val url = sharedPref.getString("apiUrl", "") ?: return@Thread
                val key = sharedPref.getString("apiKey", "") ?: return@Thread
                val model = sharedPref.getString(
                    "modelName",
                    ""
                )?.ifBlank { "gpt-4o" } ?: "gpt-4o"
                var finalUrl = if (url.endsWith("/")) url.dropLast(1) else url
                if (!finalUrl.endsWith("/chat/completions")) finalUrl += if (finalUrl.contains("/v1")) "/chat/completions" else "/v1/chat/completions"
                val aiLang = sharedPref.getString("aiLang_$aiId", "默认 (中文)") ?: "默认 (中文)"
                val offlineLang = sharedPref.getString("offlineLang", "中文") ?: "中文"
                var aiPersona = ""; var cyberMemory = ""
                val dbRead = DatabaseHelper(this@OfflineChatActivity).readableDatabase
                val curP = dbRead.rawQuery("SELECT identityInfo FROM Contacts WHERE userId=?", arrayOf(aiId))
                if (curP.moveToFirst() && !curP.isNull(0)) aiPersona = curP.getString(0); curP.close()
                try {
                    val curM = dbRead.rawQuery(
                        "SELECT memoryText FROM MemoryBank WHERE aiId=? ORDER BY insertTime DESC LIMIT 15",
                        arrayOf(aiId)
                    )
                    val sb = StringBuilder()
                    while (curM.moveToNext()) {
                        sb.append(curM.getString(0)).append("\n")
                    }
                    cyberMemory = sb.toString().trim()
                    curM.close()
                } catch (e: Exception) {}
                var presetOffline = ""
                try {
                    dbRead.rawQuery("SELECT presetOffline FROM Contacts WHERE userId=?", arrayOf(aiId))
                        .use { c -> if (c.moveToFirst()) presetOffline = c.getString(0) ?: "" }
                } catch (_: Exception) {}

                val promptBuilder = StringBuilder()
                promptBuilder.append("系统设定：这是线下角色扮演。你现在的身份是 $aiName。\n")
                if (presetOffline.isNotEmpty()) {
                    promptBuilder.insert(0, "$presetOffline\n\n")
                }

                // 本次见面场景
                if (meetLocation.isNotEmpty() || meetTime.isNotEmpty() || meetMood.isNotEmpty()) {
                    promptBuilder.append("【本次见面场景】")
                    if (meetLocation.isNotEmpty()) promptBuilder.append("地点：$meetLocation。")
                    if (meetTime.isNotEmpty()) promptBuilder.append("时间：$meetTime。")
                    if (meetMood.isNotEmpty()) promptBuilder.append("氛围：$meetMood。")
                    promptBuilder.append("\n")
                }
                if (meetBackground.isNotEmpty()) promptBuilder.append("【前情背景】$meetBackground\n")

                // 面具：用户本次扮演的身份（留空则用默认档案身份，不额外说明）
                if (meetMask.isNotEmpty()) {
                    promptBuilder.append("【对方的面具】$myName 这次扮演的身份是：$meetMask。请始终把对方当成这个身份来对待与互动。\n")
                }

                // 人称
                when (meetPerson) {
                    "第一人称" -> promptBuilder.append("【人称】以 $myName 的第一人称视角（用“我”指代 $myName）来叙写。\n")
                    "第三人称" -> promptBuilder.append("【人称】用第三人称（用 $myName 的名字或“TA”）来指代 $myName。\n")
                    else -> promptBuilder.append("【人称】用第二人称（称呼 $myName 为“你”）来叙写。\n")
                }

                // 语言
                when {
                    offlineLang == "英文" -> {
                        promptBuilder.append("【Language Instruction】You must write EVERYTHING in English only. All narration, inner thoughts, actions, dialogue — 100% English. No Chinese characters at all.\n")
                    }
                    aiLang != "默认 (中文)" -> {
                        promptBuilder.append("【绝对强制语言指令】\n")
                        promptBuilder.append("你输出的所有内心活动、动作描写、神态、环境叙述，必须100%使用简体中文。\n")
                        promptBuilder.append("你开口说出的台词（对话部分）必须100%使用${aiLang}，绝不能混入汉字。\n")
                        promptBuilder.append("每一段台词后面，必须紧跟一行中文翻译，格式如下：\n")
                        promptBuilder.append("\"${aiLang}台词内容\"\n【译】对应中文翻译\n")
                    }
                    else -> {
                        promptBuilder.append("【语言指令】全程使用简体中文输出。\n")
                    }
                }

                // 文风
                if (meetStyle.isNotEmpty()) {
                    promptBuilder.append("【文本风格要求】\n$meetStyle\n")
                }

                // 盲盒小剧场
                if (meetSkit) {
                    promptBuilder.append(SKIT_PROMPT)
                    promptBuilder.append("\n")
                }

                // 自动行动选项
                if (meetAko) {
                    promptBuilder.append("【行动选项】\n")
                    promptBuilder.append("你扮演的角色是 $aiName。下面要生成的行动选项，代表的是【$myName（用户本人）】接下来可以采取的行动或要说的话，**不是 $aiName 的行动**。\n")
                    promptBuilder.append("硬性要求：\n")
                    promptBuilder.append("1. 每个选项的主语都必须是 $myName 自己；选项里提到 $aiName 时，用 $aiName 的名字或“他/她”来称呼，绝对不要写成“你”。\n")
                    promptBuilder.append("2. 用 $myName 的第一人称口吻书写（例如“我……”）或可直接发送的祈使口吻，因为 $myName 会把选中的选项当成自己的输入直接发出去。\n")
                    promptBuilder.append("3. 正确示例：『我伸手替他理了理刚才弄乱的衣领』；错误示例（这是 $aiName 的行动，禁止出现）：『他替你理了理衣领』。\n")
                    promptBuilder.append("在本回合正文（及可能的小剧场）全部输出完之后，于回复最末尾生成 6 个 $myName 的行动选项，每个用一句话概括。严格使用如下格式，用 <details> 包裹，每个选项末尾加 <br>：\n")
                    promptBuilder.append("<details>\n<summary>行动选项</summary>\n")
                    promptBuilder.append("【选项1·日常】$myName 的一句话日常向行动/发言<br>\n")
                    promptBuilder.append("【选项2·剧情】$myName 推动主线剧情的一句话行动/发言<br>\n")
                    promptBuilder.append("【选项3·恋爱】$myName 增进恋爱好感的一句话行动/发言<br>\n")
                    promptBuilder.append("【选项4·色情】$myName 色情/挑逗向的一句话行动/发言<br>\n")
                    promptBuilder.append("【选项5·自由】$myName 自由发挥的一句话行动/发言<br>\n")
                    promptBuilder.append("【选项6·自由】$myName 自由发挥的一句话行动/发言<br>\n")
                    promptBuilder.append("</details>\n")
                }

                if (aiPersona.isNotEmpty()) promptBuilder.append("你的身份设定：$aiPersona\n")
                if (cyberMemory.isNotEmpty()) promptBuilder.append("你的核心记忆：$cyberMemory\n")
                promptBuilder.append("以下是我们之间的历史互动上下文：\n")
                val curContext = dbRead.rawQuery(
                    "SELECT content, isFromMe FROM (SELECT content, isFromMe, timestamp FROM OfflineChatHistory WHERE aiId=? AND IFNULL(sessionId,'')=? ORDER BY timestamp DESC LIMIT 30) AS t ORDER BY t.timestamp ASC",
                    arrayOf(aiId, offlineSessionId)
                )
                while (curContext.moveToNext()) {
                    val role = if (curContext.getInt(1) == 1) myName else aiName
                    promptBuilder.append("${role}：${curContext.getString(0)}\n")
                }
                curContext.close()
                promptBuilder.append("\n")
                promptBuilder.append("${myName}：$inputText\n")
                val jsonBody = JSONObject().apply {
                    put("model", model); put("temperature", 0.7)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "user"); put("content", promptBuilder.toString()) })
                    })
                }
                val request = Request.Builder().url(finalUrl).addHeader("Authorization", "Bearer $key")
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull())).build()
                val timeout = offlineTimeoutSeconds()
                val response = Http.client.newBuilder().connectTimeout(60, TimeUnit.SECONDS).readTimeout(timeout, TimeUnit.SECONDS).build().newCall(request).execute()
                val body = response.body?.string()
                var finalAnswer = "（接口请求失败或未解析到数据）"
                if (!body.isNullOrEmpty()) {
                    val jsonObj = JSONObject(body)
                    if (jsonObj.has("choices")) finalAnswer = jsonObj.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                }
                val cleanedFinalAnswer = cleanAiAnswerForBubble(finalAnswer)
                val writeDb = DatabaseHelper(this@OfflineChatActivity).writableDatabase
                ensureOfflineSessionColumn(writeDb)
                runOnUiThread {
                    val insertAiCv = ContentValues().apply {
                        put("aiId", aiId); put("content", cleanedFinalAnswer); put("isFromMe", 0); put("timestamp", ts + 1)
                        put("sessionId", offlineSessionId)
                    }
                    // 把插入后的真实 rowid 写回消息对象，否则长按删除时 id 还是 -1，删不掉库里这条
                    aiMsgObj.id = writeDb.insert("OfflineChatHistory", null, insertAiCv)
                    aiMsgObj.content = cleanedFinalAnswer; aiMsgObj.isThinking = false
                    adapter.notifyDataSetChanged(); rv.scrollToPosition(msgList.size - 1)
                    isAiRunning = false; sendBtn.alpha = 1f; regenBtn.alpha = 1f
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    aiMsgObj.content = "（网络异常：${e.message}）"; aiMsgObj.isThinking = false
                    adapter.notifyDataSetChanged()
                    isAiRunning = false; sendBtn.alpha = 1f; regenBtn.alpha = 1f
                }
            }
        }.start()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float) = (value * resources.displayMetrics.density).toInt()

    companion object {
        // 盲盒小剧场 prompt（开启时注入）
        const val SKIT_PROMPT = """
【盲盒小剧场】
你每一回合都要在叙事中穿插一个"小剧场"模块（默认每次都出现；只有当场景实在完全无法自然嵌入任何道具/界面时，才可破例跳过）。
小剧场必须用 HTML 渲染输出：把这段 HTML 放进 ```html 代码块 里单独成段，核心语言为简体中文，尽量富有创意与拟真细节。
内容库（从中随机选取一个，但不局限于此）：
1. 社交平台 / app 模拟类（手机页面需把手机壳也完整生成，可不同配色与装饰）：
   - 中国场景：微信聊天/朋友圈、QQ聊天/空间、微博、小红书笔记、豆瓣、知乎、B站弹幕播放器、网易云评论、淘宝商品页、抖音短视频/直播、晋江/天涯/A岛论坛
   - 欧美场景：Twitter、Facebook、Instagram帖子与Story、Reddit、Discord、YouTube、Spotify播放器与歌词、Tumblr、Telegram、Google搜索结果页、iMessage
   - 日韩场景：LINE聊天、KakaoTalk、TheQoo、Pann、2channel
   - 情侣场景：情侣空间、一起听歌、情侣秘密聊天室、倒数日纪念日、想做的事清单
2. 电子设备模拟类：Apple Watch通知、AirPods连接页、传呼机、导航终端、任务面板、黑客终端、AI助手界面、AirDrop、iPod、随身听、CCD相机、CD/唱片、老式按键机、Switch、小霸王、3DS、PSP、街机、拓麻歌子、电视
3. 纸质与书写类：手写信封、便签/便利贴、问卷、报告、答题卡、留言纸条、旧报纸、明信片、手账页、拍立得相框、任务书、旧档案卡、日记页、涂鸦、塔罗牌、情书、规则怪谈
4. 交互游戏类：猜拳、抽奖、转盘、盲盒、打牌、下棋、扭蛋（可用内嵌 JS 做简单交互）
5. 特殊风格：模拟恐怖、乱码、WindowsXP怀旧窗口、古风、魔法元素、礼物、动漫周边、SCP收容记录、Steam游戏库/评价
要求：HTML 自带内联样式即可（style 标签或 inline style），宽度自适应手机屏幕。
【重要输出顺序，三部分缺一不可】
1. 先正常输出本回合的正文叙事（动作、神态、心理、对白），严格遵守上文的文风与人称要求，正文不可省略。
2. 紧接着，把本回合的小剧场作为"附加道具"追加到正文之后，HTML 必须单独放进一个 ```html 代码块里。默认每回合都要有，不要因为还要生成行动选项就省略它。
3. 若本回合后面还要生成"行动选项"，行动选项放在小剧场 HTML 之后、作为整段回复的最后部分。
（即顺序为：正文 → ```html 小剧场 → 行动选项 details。）
"""
    }
}
