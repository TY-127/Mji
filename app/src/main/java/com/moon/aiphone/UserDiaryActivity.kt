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

class UserDiaryActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(UserDiaryBridge(), "Android")
        webView.setOnLongClickListener { true }
        webView.isLongClickable = false
        webView.webViewClient = object : WebViewClient() {}
        webView.webChromeClient = android.webkit.WebChromeClient()
        setContentView(webView)
        webView.loadDataWithBaseURL(null, getUserDiaryHtml(), "text/html", "UTF-8", null)
    }

    inner class UserDiaryBridge {

        @android.webkit.JavascriptInterface
        fun goBack() { runOnUiThread { finish() } }

        @android.webkit.JavascriptInterface
        fun getContacts(): String {
            return try {
                val arr = JSONArray()

                DatabaseHelper(this@UserDiaryActivity)
                    .readableDatabase
                    .rawQuery(
                        """
                SELECT userId, realName
                FROM Contacts
                WHERE userId IS NOT NULL
                AND TRIM(userId) <> ''
                AND id IN (
                    SELECT MAX(id)
                    FROM Contacts
                    GROUP BY userId
                )
                ORDER BY id DESC
                """.trimIndent(),
                        null
                    ).use { cur ->

                        while (cur.moveToNext()) {
                            arr.put(
                                JSONObject().apply {
                                    put("id", cur.getString(0) ?: "")
                                    put("name", cur.getString(1) ?: "")
                                }
                            )
                        }
                    }

                arr.toString()
            } catch (e: Exception) {
                "[]"
            }
        }

        @android.webkit.JavascriptInterface
        fun getDiaries(): String {
            return try {
                val db = DatabaseHelper(this@UserDiaryActivity).readableDatabase
                val sb = StringBuilder("[")
                val cur = db.rawQuery(
                    "SELECT id, dateStr, weather, content, visibleAiIds, aiAnnotations, timestamp FROM UserDiary ORDER BY timestamp DESC",
                    null
                )
                var first = true
                while (cur.moveToNext()) {
                    if (!first) sb.append(",")
                    val content = (cur.getString(3) ?: "").replace("\"", "\\\"").replace("\n", "\\n")
                    val annotations = (cur.getString(5) ?: "").replace("\"", "\\\"").replace("\n", "\\n")
                    sb.append("{\"id\":${cur.getInt(0)},\"date\":\"${cur.getString(1)}\",\"weather\":\"${cur.getString(2)}\",\"content\":\"$content\",\"visibleAiIds\":\"${cur.getString(4)}\",\"annotations\":\"$annotations\"}")
                    first = false
                }
                cur.close()
                sb.append("]")
                sb.toString()
            } catch (e: Exception) { "[]" }
        }

        @android.webkit.JavascriptInterface
        fun deleteDiary(id: Int) {
            try {
                val db = DatabaseHelper(this@UserDiaryActivity).writableDatabase
                db.execSQL("DELETE FROM UserDiary WHERE id=?", arrayOf(id))
                runOnUiThread { webView.evaluateJavascript("loadDiaries()", null) }
            } catch (e: Exception) {}
        }

        @android.webkit.JavascriptInterface
        fun saveDiary(content: String, weather: String, visibleAiIds: String) {
            Thread {
                try {
                    android.util.Log.d("DIARY", "saveDiary开始, visibleAiIds=$visibleAiIds")
                    val today = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA).format(Date())
                    val db = DatabaseHelper(this@UserDiaryActivity).writableDatabase
                    val cv = android.content.ContentValues().apply {
                        put("dateStr", today)
                        put("weather", weather)
                        put("content", content)
                        put("visibleAiIds", visibleAiIds)
                        put("aiAnnotations", "")
                        put("timestamp", System.currentTimeMillis())
                    }
                    val rowId = db.insert("UserDiary", null, cv)
                    android.util.Log.d("DIARY", "日记已保存, rowId=$rowId")

                    val aiIds = visibleAiIds.split(",").filter { it.isNotBlank() }
                    if (aiIds.isEmpty()) {
                        android.util.Log.d("DIARY", "没有选角色，直接返回")
                        runOnUiThread { webView.evaluateJavascript("onSaveSuccess($rowId)", null) }
                        return@Thread
                    }

                    val sharedPref = getSharedPreferences("AppConfig", android.content.Context.MODE_PRIVATE)
                    var apiUrl = (sharedPref.getString("apiUrl", "") ?: "").trimEnd('/')
                    val apiKey = sharedPref.getString("apiKey", "") ?: ""
                    val model = sharedPref.getString(
                        "modelName",
                        ""
                    )?.ifBlank { "gpt-4o" } ?: "gpt-4o"

                    if (!apiUrl.endsWith("/chat/completions")) {
                        apiUrl += if (apiUrl.contains("/v1")) "/chat/completions" else "/v1/chat/completions"
                    }
                    android.util.Log.d("DIARY", "apiUrl=$apiUrl, model=$model")

                    val annotationsAll = StringBuilder()
                    val rdb = DatabaseHelper(this@UserDiaryActivity).readableDatabase

                    for (aiId in aiIds) {
                        android.util.Log.d("DIARY", "处理角色: $aiId")
                        var aiName = ""
                        var persona = ""
                        rdb.rawQuery("SELECT realName, identityInfo FROM Contacts WHERE userId=?", arrayOf(aiId)).use { c ->
                            if (c.moveToFirst()) {
                                aiName = c.getString(0) ?: ""
                                persona = c.getString(1) ?: ""
                            }
                        }
                        if (aiName.isEmpty()) {
                            android.util.Log.d("DIARY", "角色名为空，跳过")
                            continue
                        }

                        val prompt = """
你是 $aiName，你刚刚读完了用户写给你看的日记。

【你的人设】：$persona
【用户日记内容】：
$content

【任务】：
1. 从日记里找出1-2句最触动你、最想回应的话，用"——"引用原文（15字以内的片段）
2. 对每句引用写一句你真实的内心批注（30字以内，符合你的性格）
3. 最后写一句整体的回应（50字以内）

【格式】：
「引用原文片段」——你的批注
「引用原文片段」——你的批注
✦ 整体回应

只输出以上格式内容，不要多余的话。
                        """.trimIndent()

                        val body = JSONObject().apply {
                            put("model", model)
                            put("messages", JSONArray().apply {
                                put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                            })
                        }.toString().toRequestBody("application/json".toMediaTypeOrNull())

                        val req = Request.Builder().url(apiUrl)
                            .addHeader("Authorization", "Bearer $apiKey").post(body).build()
                        val resp = Http.client.newBuilder()
                            .connectTimeout(60, TimeUnit.SECONDS)
                            .readTimeout(60, TimeUnit.SECONDS)
                            .build().newCall(req).execute()
                        if (!resp.isSuccessful) {
                            android.util.Log.d(
                                "DIARY",
                                "API失败:${resp.code}"
                            )
                            continue
                        }

                        val raw = JSONObject(
                            resp.body?.string() ?: continue
                        )
                            .getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").getString("content").trim()

                        android.util.Log.d("DIARY", "$aiName 批注: ${raw.take(100)}")

                        if (annotationsAll.isNotEmpty()) annotationsAll.append("\n\n")
                        annotationsAll.append("【$aiName】\n$raw")
                    }

                    db.execSQL("UPDATE UserDiary SET aiAnnotations=? WHERE id=?",
                        arrayOf(annotationsAll.toString(), rowId))
                    android.util.Log.d("DIARY", "批注已写入数据库")

                    runOnUiThread { webView.evaluateJavascript("onSaveSuccess($rowId)", null) }
                } catch (e: Exception) {
                    android.util.Log.d("DIARY", "异常: ${e.javaClass.name} - ${e.message}")
                    runOnUiThread {
                        webView.evaluateJavascript("onSaveSuccess(-1)", null)
                        webView.evaluateJavascript("showToast('日记已保存，批注生成失败')", null)
                    }
                }
            }.start()
        }
    }

    private fun getUserDiaryHtml(): String = """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
* { margin:0; padding:0; box-sizing:border-box; }
html, body { min-height:100%; overflow-y:auto; -webkit-overflow-scrolling:touch; }
body { background:#f0ebe3; font-family:'Georgia',serif; min-height:100vh; padding-bottom:40px; touch-action:pan-y; }
.header {
  background:#e8e0d0; padding:52px 20px 16px;
  display:flex; align-items:center; justify-content:space-between;
  border-bottom:1px solid #d4c9b8; position:sticky; top:0; z-index:10;
}
.header-left { display:flex; align-items:center; gap:12px; }
.back-btn { font-size:22px; color:#7a6a55; cursor:pointer; padding:4px 8px; }
.header-title { font-size:18px; color:#4a3f32; font-weight:normal; letter-spacing:1px; }
.write-btn {
  background:#7a6a55; color:white; border:none;
  padding:8px 16px; border-radius:20px; font-size:13px;
  cursor:pointer; letter-spacing:1px;
}
.diary-list { padding:16px; display:flex; flex-direction:column; gap:16px; }
.diary-card {
  background:#fff; border-radius:12px; overflow:hidden;
  box-shadow:0 2px 12px rgba(0,0,0,0.07); cursor:pointer;
  transition:transform 0.1s;
}
.diary-card:active { transform:scale(0.98); }
.card-header {
  background:linear-gradient(135deg,#fdf8f0,#f5ede0);
  padding:14px 20px 10px; border-bottom:1px solid #ede3d5;
}
.card-date { font-size:15px; color:#4a3f32; letter-spacing:1px; }
.card-meta { font-size:11px; color:#9a8a78; margin-top:3px; }
.card-preview {
  padding:12px 20px; font-size:13px; color:#6a5a4a; line-height:1.8;
  display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden;
}
.card-annotation {
  padding:10px 20px 14px; font-size:12px; color:#8a7a6a;
  border-top:1px dashed #e0d5c5; font-style:italic; line-height:1.8;
  display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden;
}
.empty-state { text-align:center; padding:80px 20px; color:#9a8a78; }
.empty-icon { font-size:48px; margin-bottom:16px; }
.write-overlay {
  display:none; position:fixed; inset:0; z-index:100;
  background:#f0ebe3; overflow-y:scroll; -webkit-overflow-scrolling:touch; touch-action:pan-y;
}
.write-header {
  background:#e8e0d0; padding:52px 20px 16px;
  display:flex; align-items:center; justify-content:space-between;
  border-bottom:1px solid #d4c9b8; position:sticky; top:0;
}
.submit-btn {
  background:#7a6a55; color:white; border:none;
  padding:8px 18px; border-radius:20px; font-size:13px; cursor:pointer;
}
.submit-btn:disabled { background:#b0a090; }
.write-body { padding:16px; }
.weather-row { display:flex; gap:8px; margin-bottom:16px; flex-wrap:wrap; }
.weather-chip {
  padding:6px 14px; border-radius:20px; font-size:13px;
  background:#fff; color:#7a6a55; border:1px solid #d4c9b8; cursor:pointer;
}
.weather-chip.active { background:#7a6a55; color:#fff; border-color:#7a6a55; }
.ai-select-section { margin-bottom:16px; }
.section-label { font-size:12px; color:#9a8a78; letter-spacing:1px; margin-bottom:8px; }
.ai-chips { display:flex; gap:8px; flex-wrap:wrap; }
.ai-chip {
  padding:6px 14px; border-radius:20px; font-size:13px;
  background:#fff; color:#7a6a55; border:1px solid #d4c9b8; cursor:pointer;
}
.ai-chip.active { background:#7a6a55; color:#fff; border-color:#7a6a55; }
.parchment-write {
  background:#fdf8f0; border-radius:8px; padding:20px;
  box-shadow:0 4px 20px rgba(0,0,0,0.08);
  position:relative;
}
.parchment-write::before {
  content:''; position:absolute; inset:0; border-radius:8px;
  background:repeating-linear-gradient(transparent,transparent 31px,#e8ddd0 31px,#e8ddd0 32px);
  pointer-events:none;
}
textarea {
  width:100%; min-height:300px; border:none; outline:none;
  background:transparent; font-family:'Georgia',serif; font-size:15px;
  color:#4a3f32; line-height:2; resize:none; position:relative; z-index:1;
}
.detail-overlay {
  display:none; position:fixed; inset:0; z-index:200;
  background:#f5ede0; overflow-y:scroll; -webkit-overflow-scrolling:touch; touch-action:pan-y;
}
.detail-header {
  background:#e8e0d0; padding:52px 20px 16px;
  display:flex; align-items:center; gap:12px;
  border-bottom:1px solid #d4c9b8; position:sticky; top:0;
}
.parchment-detail {
  margin:20px 16px; background:#fdf8f0; border-radius:8px;
  padding:28px 24px; box-shadow:0 4px 20px rgba(0,0,0,0.1);
  position:relative; line-height:2;
}
.parchment-detail::before {
  content:''; position:absolute; inset:0; border-radius:8px;
  background:repeating-linear-gradient(transparent,transparent 31px,#e8ddd0 31px,#e8ddd0 32px);
  pointer-events:none;
}
.detail-date { font-size:15px; color:#4a3f32; text-align:center; margin-bottom:4px; letter-spacing:2px; position:relative; z-index:1; }
.detail-meta { font-size:12px; color:#9a8a78; text-align:center; margin-bottom:20px; position:relative; z-index:1; }
.detail-divider { border:none; border-top:1px solid #d4c9b8; margin:16px 0; }
.detail-content { font-size:15px; color:#4a3f32; line-height:2.2; white-space:pre-wrap; position:relative; z-index:1; }
.annotation-section {
  margin:0 16px 40px; background:#fff; border-radius:8px;
  padding:20px; box-shadow:0 2px 8px rgba(0,0,0,0.06);
}
.annotation-title { font-size:12px; color:#9a8a78; letter-spacing:2px; margin-bottom:12px; }
.annotation-text { font-size:14px; color:#5a4a3a; line-height:2.2; white-space:pre-wrap; font-style:italic; }
.loading-mask {
  display:none; position:fixed; inset:0; z-index:300;
  background:rgba(240,235,227,0.85); flex-direction:column;
  align-items:center; justify-content:center;
}
.loading-mask.show { display:flex; }
.loading-text { font-size:14px; color:#7a6a55; margin-top:16px; letter-spacing:1px; }
.spinner {
  width:32px; height:32px; border:3px solid #d4c9b8;
  border-top-color:#7a6a55; border-radius:50%;
  animation:spin 0.8s linear infinite;
}
@keyframes spin { to { transform:rotate(360deg); } }
.toast {
  position:fixed; bottom:40px; left:50%; transform:translateX(-50%);
  background:rgba(74,63,50,0.85); color:white; padding:10px 20px;
  border-radius:20px; font-size:13px; opacity:0; transition:opacity 0.3s;
  pointer-events:none; white-space:nowrap; z-index:999;
}
</style>
</head>
<body>

<div class="header">
  <div class="header-left">
    <div class="back-btn" onclick="Android.goBack()">←</div>
    <div class="header-title">我的日记</div>
  </div>
  <button class="write-btn" onclick="openWrite()">✦ 写日记</button>
</div>

<div class="diary-list" id="diaryList"><div class="empty-state"><div class="empty-icon">📝</div><div>还没有日记</div></div></div>

<div class="write-overlay" id="writeOverlay">
  <div class="write-header">
    <div class="header-left">
      <div class="back-btn" onclick="closeWrite()">←</div>
      <div style="font-size:16px;color:#4a3f32;">写日记</div>
    </div>
    <button class="submit-btn" id="submitBtn" onclick="submitDiary()">发布</button>
  </div>
  <div class="write-body">
    <div class="section-label">今天天气</div>
    <div class="weather-row" id="weatherRow">
      <div class="weather-chip active" onclick="selectWeather(this,'晴')">☀️ 晴</div>
      <div class="weather-chip" onclick="selectWeather(this,'多云')">⛅ 多云</div>
      <div class="weather-chip" onclick="selectWeather(this,'阴')">☁️ 阴</div>
      <div class="weather-chip" onclick="selectWeather(this,'雨')">🌧️ 雨</div>
      <div class="weather-chip" onclick="selectWeather(this,'雪')">❄️ 雪</div>
    </div>
    <div class="ai-select-section">
      <div class="section-label">让谁看到</div>
      <div class="ai-chips" id="aiChips">加载中...</div>
    </div>
    <div class="parchment-write">
      <textarea id="diaryInput" placeholder="今天发生了什么…"></textarea>
    </div>
  </div>
</div>

<div class="detail-overlay" id="detailOverlay">
  <div class="detail-header">
    <div class="back-btn" onclick="closeDetail()">←</div>
    <div style="font-size:15px;color:#4a3f32;" id="detailTitle"></div>
  </div>
  <div class="parchment-detail">
    <div class="detail-date" id="detailDate"></div>
    <div class="detail-meta" id="detailMeta"></div>
    <hr class="detail-divider">
    <div class="detail-content" id="detailContent"></div>
  </div>
  <div class="annotation-section" id="annotationSection" style="display:none">
    <div class="annotation-title">— 批注 —</div>
    <div class="annotation-text" id="annotationText"></div>
  </div>
</div>

<div class="loading-mask" id="loadingMask">
  <div class="spinner"></div>
  <div class="loading-text">正在生成批注…</div>
</div>

<div class="toast" id="toast"></div>

<script>
var diaries = [];
var contacts = [];
var selectedWeather = '晴';
var selectedAiIds = [];
var longPressTimer = null;

function init() {
  loadDiaries();
  contacts = JSON.parse(Android.getContacts());
  var chips = document.getElementById('aiChips');
  if (!contacts.length) {
    chips.innerHTML = '<div style="color:#9a8a78;font-size:13px;">暂无角色</div>';
    return;
  }
  chips.innerHTML = contacts.map(function(c) {
    return '<div class="ai-chip" data-id="' + c.id + '" onclick="toggleAi(this,\'' + c.id + '\')">' + c.name + '</div>';
  }).join('');
}

function loadDiaries() {
  try {
    diaries = JSON.parse(Android.getDiaries());
    var list = document.getElementById('diaryList');
    if (!diaries.length) {
      list.innerHTML = '<div class="empty-state"><div class="empty-icon">📝</div><div>还没有日记<br>点击右上角开始写</div></div>';
      return;
    }
    list.innerHTML = diaries.map(function(d, i) {
      var annotationPreview = d.annotations ? '<div class="card-annotation">✦ ' + d.annotations.substring(0,60) + '…</div>' : '';
      return '<div class="diary-card"'
        + ' onclick="openDetail(' + i + ')"'
        + ' ontouchstart="startLongPress(' + i + ')"'
        + ' ontouchend="cancelLongPress()"'
        + ' ontouchmove="cancelLongPress()">'
        + '<div class="card-header">'
        + '<div class="card-date">' + d.date + '</div>'
        + '<div class="card-meta">☁ ' + d.weather + '</div>'
        + '</div>'
        + '<div class="card-preview">' + d.content.substring(0,80) + '…</div>'
        + annotationPreview
        + '</div>';
    }).join('');
  } catch(e) {}
}

function startLongPress(i) {
  longPressTimer = setTimeout(function() {
    longPressTimer = null;
    if (confirm('删除这篇日记？')) {
      Android.deleteDiary(diaries[i].id);
    }
  }, 600);
}

function cancelLongPress() {
  if (longPressTimer) {
    clearTimeout(longPressTimer);
    longPressTimer = null;
  }
}

function selectWeather(el, val) {
  selectedWeather = val;
  document.querySelectorAll('.weather-chip').forEach(function(c) { c.classList.remove('active'); });
  el.classList.add('active');
}

function toggleAi(el, id) {
  var idx = selectedAiIds.indexOf(id);
  if (idx >= 0) {
    selectedAiIds.splice(idx, 1);
    el.classList.remove('active');
  } else {
    selectedAiIds.push(id);
    el.classList.add('active');
  }
}

function openWrite() {
  document.getElementById('writeOverlay').style.display = 'block';
  document.getElementById('diaryInput').value = '';
  selectedAiIds = [];
  document.querySelectorAll('.ai-chip').forEach(function(c) { c.classList.remove('active'); });
}

function closeWrite() {
  document.getElementById('writeOverlay').style.display = 'none';
}

function submitDiary() {
  var content = document.getElementById('diaryInput').value.trim();
  if (!content) { showToast('请写点什么再发布'); return; }
  document.getElementById('submitBtn').disabled = true;
  document.getElementById('loadingMask').classList.add('show');
  Android.saveDiary(content, selectedWeather, selectedAiIds.join(','));
}

function onSaveSuccess(rowId) {
  document.getElementById('loadingMask').classList.remove('show');
  document.getElementById('submitBtn').disabled = false;
  closeWrite();
  loadDiaries();
  showToast('保存成功');
}

function openDetail(i) {
  if (longPressTimer) return;
  var d = diaries[i];
  document.getElementById('detailTitle').textContent = d.date;
  document.getElementById('detailDate').textContent = d.date;
  document.getElementById('detailMeta').textContent = '☁ ' + d.weather;
  document.getElementById('detailContent').textContent = d.content;
  var annotSection = document.getElementById('annotationSection');
  var annotText = document.getElementById('annotationText');
  if (d.annotations && d.annotations.length > 0) {
    annotText.textContent = d.annotations;
    annotSection.style.display = 'block';
  } else {
    annotSection.style.display = 'none';
  }
  document.getElementById('detailOverlay').style.display = 'block';
  document.getElementById('detailOverlay').scrollTop = 0;
}

function closeDetail() {
  document.getElementById('detailOverlay').style.display = 'none';
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
