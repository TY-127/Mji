package com.moon.aiphone

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

data class TimelineItem(
    val time: String,
    val event: String,
    val transEvent: String,
    val mood: String = "",
    val accident: String = ""
)

class ScheduleDetailActivity : AppCompatActivity() {
    private val timelineList = mutableListOf<TimelineItem>()
    private var aiId = ""
    private var aiName = ""
    private var aiPersona = ""
    private var todayStr = ""
    private lateinit var webView: WebView
    private lateinit var tvStatus: TextView
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            renderWebView()
            refreshHandler.postDelayed(this, 60000L) // 每分钟刷新一次，更新划线状态
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        aiId = intent.getStringExtra("aiId") ?: ""
        aiName = intent.getStringExtra("aiName") ?: "未知"
        aiPersona = intent.getStringExtra("aiPersona") ?: ""
        val aiAvatar = intent.getStringExtra("aiAvatar") ?: ""
        todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        // 建表加字段
        try {
            val db = DatabaseHelper(this).writableDatabase
            db.execSQL("ALTER TABLE Schedules ADD COLUMN dateStr TEXT")
        } catch (_: Exception) {}
        try {
            val db = DatabaseHelper(this).writableDatabase
            db.execSQL("ALTER TABLE Schedules ADD COLUMN translatedText TEXT")
        } catch (_: Exception) {}
        try {
            val db = DatabaseHelper(this).writableDatabase
            db.execSQL("ALTER TABLE Schedules ADD COLUMN mood TEXT DEFAULT ''")
        } catch (_: Exception) {}
        try {
            val db = DatabaseHelper(this).writableDatabase
            db.execSQL("ALTER TABLE Schedules ADD COLUMN accident TEXT DEFAULT ''")
        } catch (_: Exception) {}

        // 构建全代码UI
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#F2F2F6"))
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -1)
        }

        // 顶栏
        val topBar = android.widget.RelativeLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, dp(50))
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        val btnBack = TextView(this).apply {
            text = "‹"; textSize = 32f
            setPadding(dp(16), 0, dp(16), dp(4))
            setTextColor(android.graphics.Color.parseColor("#111111"))
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.RelativeLayout.LayoutParams(-2, -1).also {
                it.addRule(android.widget.RelativeLayout.ALIGN_PARENT_START)
                it.addRule(android.widget.RelativeLayout.CENTER_VERTICAL)
            }
            setOnClickListener { finish() }
        }

        // 头像+名字行
        val nameRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.RelativeLayout.LayoutParams(-2, -2).also {
                it.addRule(android.widget.RelativeLayout.CENTER_IN_PARENT)
            }
        }
        val ivAvatar = ImageView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(28), dp(28)).also { it.marginEnd = dp(8) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            setBackgroundColor(android.graphics.Color.LTGRAY)
        }
        if (aiAvatar.isNotEmpty()) {
            try {
                val bitmap = when {
                    aiAvatar.startsWith("/") -> {
                        android.graphics.BitmapFactory.decodeFile(aiAvatar)
                    }
                    aiAvatar.startsWith("file://") -> {
                        android.graphics.BitmapFactory.decodeFile(aiAvatar.removePrefix("file://"))
                    }
                    else -> {
                        contentResolver.openInputStream(Uri.parse(aiAvatar))
                            ?.use { android.graphics.BitmapFactory.decodeStream(it) }
                    }
                }

                if (bitmap != null) {
                    ivAvatar.setImageBitmap(bitmap)
                } else {
                    ivAvatar.setImageResource(android.R.drawable.sym_def_app_icon)
                }
            } catch (_: Exception) {
                ivAvatar.setImageResource(android.R.drawable.sym_def_app_icon)
            }
        }
        val tvName = TextView(this).apply {
            text = aiName; textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#111111"))
        }
        nameRow.addView(ivAvatar); nameRow.addView(tvName)

        val btnReset = TextView(this).apply {
            text = "重排"; textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#FF3B30"))
            setPadding(0, 0, dp(16), 0)
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.RelativeLayout.LayoutParams(-2, -1).also {
                it.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END)
                it.addRule(android.widget.RelativeLayout.CENTER_VERTICAL)
            }
            setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this@ScheduleDetailActivity)
                    .setTitle("重置行程")
                    .setMessage("清空今日行程并重新生成？")
                    .setPositiveButton("确定") { _, _ ->
                        try {
                            DatabaseHelper(this@ScheduleDetailActivity).writableDatabase
                                .delete("Schedules", "aiId=? AND dateStr=?", arrayOf(aiId, todayStr))
                        } catch (_: Exception) {}
                        checkAndLoad()
                    }
                    .setNegativeButton("取消", null).show()
            }
        }
        topBar.addView(btnBack); topBar.addView(nameRow); topBar.addView(btnReset)
        root.addView(topBar)

        // 状态栏
        tvStatus = TextView(this).apply {
            text = ""; textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#888888"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2)
        }
        root.addView(tvStatus)

        // WebView
        webView = WebView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, 0, 1f)
            settings.javaScriptEnabled = true
            settings.loadWithOverviewMode = false
            settings.useWideViewPort = false
            settings.setSupportZoom(false)
            settings.displayZoomControls = false
            setBackgroundColor(android.graphics.Color.parseColor("#F2F2F6"))
        }
        root.addView(webView)

        setContentView(root)
        checkAndLoad()
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun checkAndLoad() {
        timelineList.clear()
        try {
            val db = DatabaseHelper(this).readableDatabase
            val cur = db.rawQuery(
                "SELECT startTime, eventDesc, translatedText, mood, accident FROM Schedules WHERE aiId=? AND dateStr=? ORDER BY startTime ASC",
                arrayOf(aiId, todayStr)
            )
            while (cur.moveToNext()) {
                timelineList.add(TimelineItem(
                    time = cur.getString(0) ?: "",
                    event = cur.getString(1) ?: "",
                    transEvent = try { cur.getString(2) ?: "" } catch (_: Exception) { "" },
                    mood = try { cur.getString(3) ?: "" } catch (_: Exception) { "" },
                    accident = try { cur.getString(4) ?: "" } catch (_: Exception) { "" }
                ))
            }
            cur.close()
        } catch (_: Exception) {}

        if (timelineList.isNotEmpty()) {
            tvStatus.text = "已生成今日行程"
            renderWebView()
        } else {
            tvStatus.text = "正在生成今日行程..."
            generateSchedule()
        }
    }

    private fun renderWebView() {
        val nowMinutes = run {
            val cal = Calendar.getInstance()
            cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        }

        // 取第一条的 accident 作为今日小意外
        val accidentText = timelineList.firstOrNull { it.accident.isNotEmpty() }?.accident ?: ""

        val accidentParts = accidentText.split("\n", limit = 2)
        val accidentMain2 = accidentParts[0].trim()
        val accidentTrans2 = if (accidentParts.size > 1) accidentParts[1].trim() else ""
        val accidentHtml = if (accidentText.isNotEmpty()) {
            """
            <div class="accident-card">
                <div class="accident-icon">⚡ 意外</div>
                <div class="accident-text">$accidentMain2</div>
                ${if (accidentTrans2.isNotEmpty()) "<div class='accident-trans'>$accidentTrans2</div>" else ""}
            </div>
            """.trimIndent()
        } else ""

        val itemsHtml = StringBuilder()
        timelineList.forEachIndexed { i, item ->
            val itemMinutes = try {
                val parts = item.time.split(":")
                parts[0].toInt() * 60 + parts[1].toInt()
            } catch (_: Exception) { 9999 }

            // 判断是否已结束：下一条已开始 或 当前时间超过此节点1小时
            val isDone = if (i < timelineList.size - 1) {
                val nextMinutes = try {
                    val parts = timelineList[i + 1].time.split(":")
                    parts[0].toInt() * 60 + parts[1].toInt()
                } catch (_: Exception) { 9999 }
                nowMinutes >= nextMinutes
            } else {
                nowMinutes >= itemMinutes + 60
            }

            val doneClass = if (isDone) "done" else ""
            val dotClass = if (isDone) "dot-done" else if (nowMinutes in itemMinutes until itemMinutes + 60) "dot-active" else "dot-normal"

            val moodHtml = if (item.mood.isNotEmpty()) {
                "<div class='mood'>💭 ${item.mood}</div>"
            } else ""

            val eventTitle = item.event
            val transEventHtml = if (item.transEvent.isNotEmpty()) "<div class='event-trans'>${item.transEvent}</div>" else ""
            itemsHtml.append("""
                <div class="timeline-item $doneClass">
                    <div class="time-col">
                        <div class="time-text">${item.time}</div>
                        <div class="dot $dotClass"></div>
                        ${if (i < timelineList.size - 1) "<div class='line'></div>" else ""}
                    </div>
                    <div class="content-col">
                      <div class="event-title">$eventTitle</div>
                      $transEventHtml
                      $moodHtml
                    </div>
                </div>
            """.trimIndent())
        }

        val todayLabel = SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date())

        val html = """
<!DOCTYPE html><html><head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0,maximum-scale=1.0,user-scalable=no">
<style>
*{margin:0;padding:0;box-sizing:border-box;font-family:-apple-system,sans-serif}
html,body{width:100%;max-width:100%;overflow-x:hidden}
body{background:#F2F2F6;padding:12px 16px 40px}
.date-label{font-size:12px;color:#888;margin-bottom:10px}
.accident-card{background:#2d2d1a;border-left:3px solid #FFD60A;border-radius:10px;padding:12px 14px;margin-bottom:16px}
.accident-icon{font-size:11px;color:#FFD60A;font-weight:bold;margin-bottom:4px}
.accident-text{font-size:13px;color:#ffe;line-height:1.6}
.accident-trans{font-size:12px;color:rgba(255,255,220,0.7);line-height:1.5;margin-top:4px;font-style:italic}
.timeline-item{display:flex;gap:12px;margin-bottom:0}
.time-col{display:flex;flex-direction:column;align-items:center;width:48px;flex-shrink:0}
.time-text{font-size:12px;color:#888;margin-bottom:6px;white-space:nowrap}
.dot{width:10px;height:10px;border-radius:50%;flex-shrink:0}
.dot-normal{background:#D0D0D0;border:2px solid #bbb}
.dot-active{background:#fff;border:3px solid #34C759;box-shadow:0 0 0 3px rgba(52,199,89,0.2)}
.dot-done{background:#34C759;border:2px solid #34C759}
.line{width:2px;flex:1;background:#E0E0E0;min-height:40px;margin-top:4px}
.content-col{flex:1;background:#fff;border-radius:12px;padding:12px 14px;margin-bottom:10px}
.event-title{font-size:15px;color:#111;font-weight:500;line-height:1.5}
.event-trans{font-size:12px;color:#777;margin-top:5px;line-height:1.5}
.mood{font-size:12px;color:#888;margin-top:6px;line-height:1.5}
.done .event-title{text-decoration:line-through;color:#bbb}
.done .content-col{background:#f7f7f7}
.done .mood{color:#ccc}
.done .time-text{color:#ccc}
</style></head><body>
<div class="date-label">$todayLabel · 已生成今日行程</div>
$accidentHtml
$itemsHtml
</body></html>
        """.trimIndent()

        runOnUiThread {
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }
    }

    private fun generateSchedule() {
        val sharedPref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val url = sharedPref.getString("apiUrl", "") ?: ""
        // 从 MyProfile 表读用户名
        var myName = "用户"
        var myIdentity = ""
        var myGender = ""
        try {
            DatabaseHelper(this).readableDatabase
                .rawQuery("SELECT myName, identity, gender FROM MyProfile LIMIT 1", null)
                .use { c ->
                    if (c.moveToFirst()) {
                        myName = c.getString(0) ?: "用户"
                        myIdentity = c.getString(1) ?: ""
                        myGender = c.getString(2) ?: ""
                    }
                }
        } catch (_: Exception) {}

// 从数据库重新读一次 aiPersona，防止 intent 传值丢失
        if (aiPersona.isEmpty()) {
            try {
                DatabaseHelper(this).readableDatabase
                    .rawQuery("SELECT identityInfo FROM Contacts WHERE userId=?", arrayOf(aiId))
                    .use { c -> if (c.moveToFirst()) aiPersona = c.getString(0) ?: "" }
            } catch (_: Exception) {}
        }
        val key = sharedPref.getString("apiKey", "") ?: ""
        val model = sharedPref.getString("modelName", "") ?: ""
        if (url.isEmpty() || key.isEmpty()) {
            tvStatus.text = "❌ 未配置API"
            return
        }

        var finalUrl = if (url.endsWith("/")) url.dropLast(1) else url
        if (!finalUrl.endsWith("/chat/completions")) {
            finalUrl += if (finalUrl.contains("/v1")) "/chat/completions" else "/v1/chat/completions"
        }

        val cal = Calendar.getInstance()
        cal.add(Calendar.DATE, -1)
        val yesterdayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
        var yesterdaySummary = ""
        try {
            val db = DatabaseHelper(this).readableDatabase
            val cur = db.rawQuery("SELECT startTime, eventDesc FROM Schedules WHERE aiId=? AND dateStr=? ORDER BY startTime ASC", arrayOf(aiId, yesterdayStr))
            while (cur.moveToNext()) yesterdaySummary += "【${cur.getString(0)}】${cur.getString(1)}\n"
            cur.close()
        } catch (_: Exception) {}

        val aiLang = sharedPref.getString("aiLang_$aiId", "默认 (中文)") ?: "默认 (中文)"
        val isChinese = aiLang == "默认 (中文)"
        val langNote = if (aiLang != "默认 (中文)") "行程描述和心情均必须用${aiLang}，每个字段末尾单独加【翻译】中文翻译，格式：内容【翻译】中文" else ""
        val memNote = if (yesterdaySummary.isNotEmpty()) "\n【昨日行程参考】：\n$yesterdaySummary\n请保持连贯性。" else ""

        val prompt = """
你是 $aiName，人设：$aiPersona。
请生成【${aiName}自己】今天的完整行程（5到7个时间节点）。这是${aiName}的个人日常，不是用户的行程。$langNote$memNote
每条行程必须包含：时间、行程描述、此刻心情（一句话，${aiName}第一人称内心独白）。
另外生成1条今天遇到的小意外（真实感，不夸张）。
【用户信息参考（仅用于涉及用户的互动描写，不要把用户的职业/身份当成${aiName}的）】
用户名：「$myName」${if (myGender.isNotEmpty()) "，性别：$myGender" else ""}${if (myIdentity.isNotEmpty()) "，用户身份：$myIdentity" else ""}
如果行程中提到与用户互动，必须直接用「$myName」称呼，禁止用「用户」「你」等代称。
严格按以下JSON格式输出，只输出JSON不要其他内容：
{
  "accident": "今天遇到的小意外描述",
  "schedule": [
    {"time": "05:30", "event": "行程描述", "mood": "心情独白"},
    {"time": "08:00", "event": "行程描述", "mood": "心情独白"}
  ]
}
        """.trimIndent()


        Thread {
            try {
                val body = JSONObject().apply {
                    put("model", model)
                    put("temperature", 0.6)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                    })
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())

                val req = Request.Builder().url(finalUrl).addHeader("Authorization", "Bearer $key").post(body).build()
                val client = Http.client.newBuilder().connectTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
                val resp = client.newCall(req).execute()
                val result = resp.body?.string() ?: return@Thread
                val raw = JSONObject(result).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
                val clean = raw
                    .replace("```json", "")
                    .replace("```", "")
                    .trim()
                val data = JSONObject(clean)
                val rawAccident = data.optString("accident", "")
                var accidentMain = rawAccident
                var accidentTrans = ""

                val accTransTag = Regex("【翻译】(.+)$").find(rawAccident)
                if (accTransTag != null) {
                    accidentTrans = accTransTag.groupValues[1].trim()
                    accidentMain = rawAccident.substringBefore("【翻译】").trim()
                }
                val scheduleArr = data.optJSONArray("schedule") ?: return@Thread

                val db = DatabaseHelper(this).writableDatabase
                db.delete(
                    "Schedules",
                    "aiId=? AND dateStr=?",
                    arrayOf(aiId, todayStr)
                )

                for (i in 0 until scheduleArr.length()) {
                    val s = scheduleArr.getJSONObject(i)
                    val t = s.optString("time", "")
                    val evt = s.optString("event", "")
                    val mood = s.optString("mood", "")
                    if (t.isEmpty() || evt.isEmpty()) continue

                    // 翻译处理
                    var evtMain = evt; var evtTrans = ""
                    val evtTransTag = Regex("【翻译】(.+)$").find(evt)
                    if (evtTransTag != null) {
                        evtTrans = evtTransTag.groupValues[1].trim()
                        evtMain = evt.substringBefore("【翻译】").trim()
                    } else if (evt.contains("|翻译:")) {
                        evtMain = evt.substringBefore("|翻译:").trim()
                        evtTrans = evt.substringAfter("|翻译:").trim()
                    }
                    val moodClean = mood.substringBefore("【翻译】").trim()

                    val cv = ContentValues().apply {
                        put("aiId", aiId)
                        put("startTime", t)
                        put("endTime", "")
                        put("eventDesc", evtMain)
                        put("translatedText", evtTrans)
                        put("mood", moodClean)
                        put(
                            "accident",
                            if (i == 0) {
                                if (accidentTrans.isNotEmpty()) "$accidentMain\n${accidentTrans}" else accidentMain
                            } else ""
                        ) // 只在第一条存意外
                        put("dateStr", todayStr)

                    }
                    db.insert("Schedules", null, cv)
                }

                runOnUiThread {
                    tvStatus.text = "已生成今日行程"
                    checkAndLoad()
                }
            } catch (_: Exception) {
                runOnUiThread { tvStatus.text = "❌ 生成失败，长按可重排" }
            }
        }.start()
    }
}