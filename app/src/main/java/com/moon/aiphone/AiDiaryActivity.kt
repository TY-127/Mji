package com.moon.aiphone

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class AiDiaryActivity : AppCompatActivity() {

    private lateinit var aiId: String
    private lateinit var aiName: String
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        aiId = intent.getStringExtra("AI_ID") ?: ""
        aiName = intent.getStringExtra("AI_NAME") ?: ""
        try {
            val db = DatabaseHelper(this).writableDatabase
            db.execSQL("CREATE TABLE IF NOT EXISTS AiDiary (id INTEGER PRIMARY KEY AUTOINCREMENT, aiId TEXT, dateStr TEXT, weather TEXT DEFAULT '', location TEXT DEFAULT '', content TEXT, summaryForNext TEXT DEFAULT '', timestamp INTEGER DEFAULT 0)")
            try { db.execSQL("ALTER TABLE AiDiary ADD COLUMN summaryForNext TEXT DEFAULT ''") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE AiDiary ADD COLUMN weather TEXT DEFAULT ''") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE AiDiary ADD COLUMN location TEXT DEFAULT ''") } catch (_: Exception) {}
        } catch (_: Exception) {}
        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(AiDiaryBridge(), "Android")
        webView.webViewClient = object : WebViewClient() {}
        // 修复空白问题：加 WebChromeClient
        webView.webChromeClient = android.webkit.WebChromeClient()
        setContentView(webView)

        webView.loadDataWithBaseURL("file:///android_asset/", getAiDiaryHtml(), "text/html", "UTF-8", null)
    }

    inner class AiDiaryBridge {

        @android.webkit.JavascriptInterface
        fun getAiName(): String = aiName

        @android.webkit.JavascriptInterface
        fun getDiaries(): String {
            return try {
                val db = DatabaseHelper(this@AiDiaryActivity).readableDatabase
                val arr = JSONArray()
                val cur = db.rawQuery(
                    "SELECT id, dateStr, weather, location, content, timestamp FROM AiDiary WHERE aiId=? ORDER BY timestamp DESC",
                    arrayOf(aiId)
                )
                while (cur.moveToNext()) {
                    arr.put(JSONObject().apply {
                        put("id", cur.getInt(0))
                        put("date", cur.getString(1) ?: "")
                        put("weather", cur.getString(2) ?: "")
                        put("location", cur.getString(3) ?: "")
                        put("content", cur.getString(4) ?: "")
                    })
                }
                cur.close()
                arr.toString()
            } catch (e: Exception) {
                "[]"
            }
        }

        @android.webkit.JavascriptInterface
        fun deleteTodayDiary() {
            // 允许删除今天的日记，以便重新生成
            try {
                val today = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA).format(Date())
                val db = DatabaseHelper(this@AiDiaryActivity).writableDatabase
                db.delete("AiDiary", "aiId=? AND dateStr=?", arrayOf(aiId, today))
                runOnUiThread {
                    webView.evaluateJavascript("loadDiaries()", null)
                    webView.evaluateJavascript("resetGenBtn()", null)
                    webView.evaluateJavascript("showToast('已删除，可重新生成')", null)
                }
            } catch (e: Exception) {
                runOnUiThread { webView.evaluateJavascript("showToast('删除失败')", null) }
            }
        }

        @android.webkit.JavascriptInterface
        fun generateDiary() {
            android.util.Log.d("DIARY", "generateDiary被调用")
            Thread {
                try {
                    val sharedPref = getSharedPreferences("AppConfig", android.content.Context.MODE_PRIVATE)
                    val url = sharedPref.getString("apiUrl", "") ?: ""
                    val key = sharedPref.getString("apiKey", "") ?: ""
                    val model = sharedPref.getString("modelName", "") ?: ""
                    if
                            (url.isEmpty() || key.isEmpty()) {
                        runOnUiThread {
                            webView.evaluateJavascript("showToast('请先在设置里填写API地址和密钥')", null)
                            webView.evaluateJavascript("resetGenBtn()", null)
                        }
                        return@Thread
                    }
                    val finalModel = model.ifEmpty { "gpt-4o" }
                    var finalUrl = if (url.endsWith("/")) url.dropLast(1) else url
                    if (!finalUrl.endsWith("/chat/completions"))
                        finalUrl += if (finalUrl.contains("/v1")) "/chat/completions" else "/v1/chat/completions"

                    val helper = DatabaseHelper(this@AiDiaryActivity)
                    val db = helper.readableDatabase
                    val wdb = helper.writableDatabase
                    val today = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA).format(Date())

                    // 检查今天是否已生成且内容不为空
                    val checkCur = db.rawQuery("SELECT id, content FROM AiDiary WHERE aiId=? AND dateStr=?", arrayOf(aiId, today))
                    if (checkCur.moveToFirst()) {
                        val existingContent = checkCur.getString(1) ?: ""
                        checkCur.close()
                        if (existingContent.isNotEmpty()) {
                            runOnUiThread {
                                webView.evaluateJavascript("showTodayExistsDialog()", null)
                            }
                            return@Thread
                        } else {
                            // 内容为空说明上次生成失败了，删掉重新生成
                            wdb.delete("AiDiary", "aiId=? AND dateStr=?", arrayOf(aiId, today))
                        }
                    } else {
                        checkCur.close()
                    }

                    var persona = ""
                    var location = ""
                    db.query("Contacts", null, "userId=?", arrayOf(aiId), null, null, null).use { c ->
                        if (c.moveToFirst()) {
                            // ✅ 换成最安全的扩展函数，哪怕没有这个字段，也直接返回空字符串，绝不卡死！
                            persona = c.getSafeString("identityInfo")
                            if (persona.isBlank()) {
                                persona = "$aiName，一个普通人"
                            }
                        }
                    }
                    val locationMatch = Regex("(位于|在|驻扎|驻地|基地|城市)[：:：]?\\s*([\\u4e00-\\u9fa5A-Za-z·•\\s]{2,15})").find(persona)
                    location = locationMatch?.groupValues?.get(2)?.trim() ?: "未知"

                    val prevCur = db.rawQuery(
                        "SELECT summaryForNext FROM AiDiary WHERE aiId=? ORDER BY timestamp DESC LIMIT 1",
                        arrayOf(aiId)
                    )
                    val prevSummary = if (prevCur.moveToFirst()) prevCur.getString(0) ?: "" else ""
                    prevCur.close()
// 读取用户真实名字
                    var myRealName = "用户"
                    try {
                        db.rawQuery("SELECT myName FROM MyProfile", null).use { c ->
                            if (c.moveToFirst()) myRealName = c.getString(0)?.ifEmpty { "用户" } ?: "用户"
                        }
                    } catch (_: Exception) {}

                    val chatBuilder = StringBuilder()
                    db.rawQuery(
                        """
    SELECT content, isFromMe
    FROM (
        SELECT content, isFromMe, timestamp
        FROM ChatHistory
        WHERE aiId=?
          AND (groupId IS NULL OR groupId='')
        ORDER BY timestamp DESC
        LIMIT 20
    )
    ORDER BY timestamp ASC
    """.trimIndent(),
                        arrayOf(aiId)
                    ).use { c ->
                        while (c.moveToNext()) {
                            val role = if (c.getInt(1) == 1) myRealName else aiName
                            chatBuilder.append("$role：${c.getString(0)}\n")
                        }
                    }

                    val weather = getSharedPreferences("AppConfig", android.content.Context.MODE_PRIVATE)
                        .getString("cyberWeather", "") ?: ""
                    val weatherShort = Regex("(\\d+°C|晴|阴|多云|雨|雪|雷阵雨)").findAll(weather)
                        .map { it.value }.joinToString(" ").ifEmpty { "晴" }

                    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                    val dayFeeling = when (hour) {
                        in 0..5 -> "凌晨，城市很安静"
                        in 6..11 -> "上午，新的一天开始了"
                        in 12..13 -> "中午，有些慵懒"
                        in 14..17 -> "下午，时间过得慢"
                        in 18..20 -> "傍晚，余晖渐散"
                        else -> "夜晚，终于可以喘口气"
                    }

                    val prompt = """
你是 $aiName，请以第一人称写今天的私人日记。

【你的人设】：$persona
【今天日期】：$today（$dayFeeling）
【天气】：$weatherShort
【所在地】：$location
【前一天摘要】：${prevSummary.ifEmpty { "这是第一篇日记" }}
【今天与用户的聊天片段】：
$chatBuilder

【日记要求】：
0. 日记里只能出现真实存在的人名（${'$'}aiName、${'$'}myRealName），严禁编造或虚构任何其他人名
1. 用第一人称，真实私密的口吻，像真正在写日记
2. 内容包含：今天做了什么、对用户说的某句话或某件事的感受、内心真实想法、今天的情绪起伏
3. 如果聊天里有感动的话、重要的事、用户受伤了或者分享了喜好，要重点写进去
4. 300-500字，分段自然，不要标题不要序号
5. 结尾写一句今天最想说的话

【格式输出】：
【日记正文】日记内容
【次日摘要】50字以内的摘要供明天参考
                    """.trimIndent()

                    val body = JSONObject().apply {
                        put("model", finalModel)
                        put("messages", JSONArray().apply {
                            put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                        })
                    }.toString().toRequestBody("application/json".toMediaTypeOrNull())

                    val req = Request.Builder().url(finalUrl)
                        .addHeader("Authorization", "Bearer $key").post(body).build()
                    val resp = Http.client.newBuilder()
                        .connectTimeout(60, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .build().newCall(req).execute()

                    if (!resp.isSuccessful) {
                        runOnUiThread {
                            webView.evaluateJavascript("showToast('API错误: ${resp.code}')", null)
                            webView.evaluateJavascript("resetGenBtn()", null)
                        }
                        return@Thread
                    }

                    val raw = JSONObject(resp.body?.string() ?: return@Thread)
                        .getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim()

                    val diaryContent = Regex("【日记正文】(.*?)(?=【次日摘要】|$)", RegexOption.DOT_MATCHES_ALL)
                        .find(raw)?.groupValues?.get(1)?.trim() ?: raw
                    val summary = Regex("【次日摘要】(.*?)$", RegexOption.DOT_MATCHES_ALL)
                        .find(raw)?.groupValues?.get(1)?.trim() ?: ""

                    if (diaryContent.isEmpty()) {
                        runOnUiThread {
                            webView.evaluateJavascript("showToast('生成内容为空，请重试')", null)
                            webView.evaluateJavascript("resetGenBtn()", null)
                        }
                        return@Thread
                    }

                    val cv = android.content.ContentValues().apply {
                        put("aiId", aiId)
                        put("dateStr", today)
                        put("weather", weatherShort)
                        put("location", location)
                        put("content", diaryContent)
                        put("summaryForNext", summary)
                        put("timestamp", System.currentTimeMillis())
                    }
                    wdb.delete(
                        "AiDiary",
                        "aiId=? AND dateStr=?",
                        arrayOf(aiId, today)
                    )

                    wdb.insert(
                        "AiDiary",
                        null,
                        cv
                    )

                    // 同时写入记忆宫殿，让角色聊天时能感知到日记内容
                    if (summary.isNotEmpty()) {
                        val memCv = android.content.ContentValues().apply {
                            put("aiId", aiId)
                            put("memoryText", "【日记摘要 $today】$summary")
                            put("category", "timeline")
                            put("insertTime", System.currentTimeMillis())
                        }
                        wdb.insert("MemoryBank", null, memCv)
                    }

                    runOnUiThread {
                        webView.evaluateJavascript("loadDiaries()", null)
                        webView.evaluateJavascript("resetGenBtn()", null)
                        webView.evaluateJavascript("showToast('日记生成成功')", null)
                    }
                } catch (e: Exception) {
                    android.util.Log.d("DIARY", "异常: ${e.javaClass.name} - ${e.message}")
                    runOnUiThread {
                        webView.evaluateJavascript("showToast('生成失败，请重试')", null)
                        webView.evaluateJavascript("resetGenBtn()", null)
                    }
                }
            }.start()
        }

        @android.webkit.JavascriptInterface
        fun goBack() {
            runOnUiThread { finish() }
        }
    }

    private fun getAiDiaryHtml(): String = """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
* { margin:0; padding:0; box-sizing:border-box; }
body {
  background: #f0ebe3;
  font-family: 'Georgia', serif;
  min-height: 100vh;
  padding-bottom: 40px;
}
.header {
  background: #e8e0d0;
  padding: 52px 20px 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #d4c9b8;
  position: sticky;
  top: 0;
  z-index: 10;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.back-btn {
  font-size: 22px;
  color: #7a6a55;
  cursor: pointer;
  padding: 4px 8px;
}
.header-title {
  font-size: 18px;
  color: #4a3f32;
  font-weight: normal;
  letter-spacing: 1px;
}
.gen-btn {
  background: #7a6a55;
  color: white;
  border: none;
  padding: 8px 16px;
  border-radius: 20px;
  font-size: 13px;
  cursor: pointer;
  letter-spacing: 1px;
}
.gen-btn:disabled { background: #b0a090; }
.diary-list {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.diary-card {
  background: #fff;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 2px 12px rgba(0,0,0,0.07);
  cursor: pointer;
  transition: transform 0.15s;
}
.diary-card:active { transform: scale(0.98); }
.card-header {
  background: linear-gradient(135deg, #fdf8f0, #f5ede0);
  padding: 16px 20px 12px;
  border-bottom: 1px solid #ede3d5;
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}
.card-date { font-size: 16px; color: #4a3f32; letter-spacing: 1px; }
.card-meta { font-size: 11px; color: #9a8a78; margin-top: 4px; }
.card-preview {
  padding: 14px 20px;
  font-size: 14px;
  color: #6a5a4a;
  line-height: 1.8;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.empty-state { text-align: center; padding: 80px 20px; color: #9a8a78; }
.empty-icon { font-size: 48px; margin-bottom: 16px; }
.empty-text { font-size: 14px; line-height: 2; }
.toast {
  position: fixed;
  bottom: 40px;
  left: 50%;
  transform: translateX(-50%);
  background: rgba(74,63,50,0.85);
  color: white;
  padding: 10px 20px;
  border-radius: 20px;
  font-size: 13px;
  opacity: 0;
  transition: opacity 0.3s;
  pointer-events: none;
  white-space: nowrap;
  z-index: 999;
}
.dialog-mask {
  display: none;
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.4);
  z-index: 200;
  align-items: center;
  justify-content: center;
}
.dialog-mask.show { display: flex; }
.dialog-box {
  background: #fdf8f0;
  border-radius: 16px;
  padding: 24px 20px;
  width: 80%;
  max-width: 300px;
  text-align: center;
}
.dialog-title { font-size: 16px; color: #4a3f32; margin-bottom: 10px; }
.dialog-msg { font-size: 13px; color: #7a6a55; line-height: 1.8; margin-bottom: 20px; }
.dialog-btns { display: flex; gap: 10px; }
.dialog-btn {
  flex: 1; padding: 10px; border-radius: 20px; font-size: 14px;
  cursor: pointer; border: none;
}
.dialog-btn-cancel { background: #e8ddd0; color: #7a6a55; }
.dialog-btn-ok { background: #7a6a55; color: white; }
.detail-overlay {
  display: none;
  position: fixed;
  inset: 0;
  z-index: 100;
  background: #f5ede0;
  overflow-y: auto;
}
.parchment {
  margin: 20px 16px 40px;
  background: #fdf8f0;
  border-radius: 8px;
  padding: 28px 24px;
  box-shadow: 0 4px 20px rgba(0,0,0,0.1), inset 0 0 60px rgba(200,180,140,0.15);
  position: relative;
  line-height: 2;
}
.parchment::before {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: 8px;
  background: repeating-linear-gradient(transparent, transparent 31px, #e8ddd0 31px, #e8ddd0 32px);
  pointer-events: none;
}
.detail-header {
  background: #e8e0d0;
  padding: 52px 20px 16px;
  border-bottom: 1px solid #d4c9b8;
  display: flex;
  align-items: center;
  gap: 12px;
  position: sticky;
  top: 0;
}
.detail-date { font-size: 15px; color: #4a3f32; text-align: center; margin-bottom: 6px; position: relative; z-index: 1; letter-spacing: 2px; }
.detail-meta { font-size: 12px; color: #9a8a78; text-align: center; margin-bottom: 20px; position: relative; z-index: 1; }
.detail-divider { border: none; border-top: 1px solid #d4c9b8; margin: 16px 0; }
.detail-content { font-size: 15px; color: #4a3f32; line-height: 2.2; white-space: pre-wrap; position: relative; z-index: 1; }
</style>
</head>
<body>
<div class="header">
  <div class="header-left">
    <div class="back-btn" onclick="Android.goBack()">←</div>
    <div class="header-title" id="headerTitle">的日记</div>
  </div>
  <button class="gen-btn" id="genBtn" onclick="generateDiary()">✦ 今日日记</button>
</div>

<div class="diary-list" id="diaryList"></div>

<div class="detail-overlay" id="detailOverlay">
  <div class="detail-header">
    <div class="back-btn" onclick="closeDetail()">←</div>
    <div style="font-size:16px;color:#4a3f32;" id="detailTitle"></div>
  </div>
  <div class="parchment">
    <div class="detail-date" id="detailDate"></div>
    <div class="detail-meta" id="detailMeta"></div>
    <hr class="detail-divider">
    <div class="detail-content" id="detailContent"></div>
  </div>
</div>

<!-- 今天已生成的对话框 -->
<div class="dialog-mask" id="existsDialog">
  <div class="dialog-box">
    <div class="dialog-title">今天已有日记</div>
    <div class="dialog-msg">今天的日记已经生成了。<br>是否删除重新生成？</div>
    <div class="dialog-btns">
      <button class="dialog-btn dialog-btn-cancel" onclick="closeExistsDialog()">取消</button>
      <button class="dialog-btn dialog-btn-ok" onclick="confirmRegenerate()">重新生成</button>
    </div>
  </div>
</div>

<div class="toast" id="toast"></div>

<script>
var diaries = [];

function init() {
  var name = Android.getAiName();
  document.getElementById('headerTitle').textContent = name + ' 的日记';
  loadDiaries();
}

function loadDiaries() {
  try {
    diaries = JSON.parse(Android.getDiaries());
    var list = document.getElementById('diaryList');
    if (!diaries.length) {
      list.innerHTML = '<div class="empty-state"><div class="empty-icon">📔</div><div class="empty-text">还没有日记<br>点击右上角生成今天的日记</div></div>';
      return;
    }
    list.innerHTML = diaries.map(function(d, i) {
      return '<div class="diary-card" onclick="openDetail(' + i + ')">'
        + '<div class="card-header"><div>'
        + '<div class="card-date">' + d.date + '</div>'
        + '<div class="card-meta">☁ ' + d.weather + ' · 📍 ' + d.location + '</div>'
        + '</div></div>'
        + '<div class="card-preview">' + (d.content ? d.content.substring(0, 120) : '') + '…</div>'
        + '</div>';
    }).join('');
  } catch(e) {
    document.getElementById('diaryList').innerHTML = '<div class="empty-state"><div class="empty-icon">📔</div><div class="empty-text">加载失败，请返回重试</div></div>';
  }
}

function openDetail(i) {
  var d = diaries[i];
  document.getElementById('detailTitle').textContent = d.date;
  document.getElementById('detailDate').textContent = d.date;
  document.getElementById('detailMeta').textContent = '☁ ' + d.weather + '　📍 ' + d.location;
  document.getElementById('detailContent').textContent = d.content || '';
  document.getElementById('detailOverlay').style.display = 'block';
  document.getElementById('detailOverlay').scrollTop = 0;
}

function closeDetail() {
  document.getElementById('detailOverlay').style.display = 'none';
}

function generateDiary() {
  try {
    var btn = document.getElementById('genBtn');
    btn.disabled = true;
    btn.textContent = '生成中…';
    Android.generateDiary();
  } catch(e) {
    resetGenBtn();
    showToast('发生错误: ' + e.message);
  }
}

function resetGenBtn() {
  var btn = document.getElementById('genBtn');
  btn.disabled = false;
  btn.textContent = '✦ 今日日记';
}

function showTodayExistsDialog() {
  document.getElementById('existsDialog').classList.add('show');
  resetGenBtn();
}

function closeExistsDialog() {
  document.getElementById('existsDialog').classList.remove('show');
}

function confirmRegenerate() {
  closeExistsDialog();
  Android.deleteTodayDiary();
  setTimeout(function() {
    generateDiary();
  }, 300);
}

function showToast(msg) {
  var t = document.getElementById('toast');
  t.textContent = msg;
  t.style.opacity = '1';
  setTimeout(function() { t.style.opacity = '0'; }, 2500);
}

init();
</script>
</body>
</html>
    """.trimIndent()
}