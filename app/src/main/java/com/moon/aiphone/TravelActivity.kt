package com.moon.aiphone

import android.content.ContentValues
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

class TravelActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val dbHelper by lazy { DatabaseHelper(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        ensureTable()
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            isVerticalScrollBarEnabled = true
            webViewClient = WebViewClient()
            addJavascriptInterface(Bridge(), "TravelBridge")
        }
        setContentView(webView)
        webView.loadDataWithBaseURL(null, html(), "text/html", "UTF-8", null)
    }

    private fun ensureTable() {
        try {
            dbHelper.writableDatabase.execSQL(
                "CREATE TABLE IF NOT EXISTS UserTravelPlans (id INTEGER PRIMARY KEY AUTOINCREMENT, travelType TEXT, fromPlace TEXT, toPlace TEXT, tripNo TEXT, departTime TEXT, note TEXT, status TEXT DEFAULT 'active', createdAt INTEGER)"
            )
        } catch (_: Exception) {}
    }

    inner class Bridge {
        @JavascriptInterface fun list(): String {
            ensureTable()
            val arr = JSONArray()
            try {
                dbHelper.readableDatabase.rawQuery(
                    "SELECT id, travelType, fromPlace, toPlace, tripNo, departTime, note, status FROM UserTravelPlans ORDER BY status ASC, id DESC",
                    null
                ).use { c ->
                    while (c.moveToNext()) {
                        arr.put(JSONObject().apply {
                            put("id", c.getLong(0))
                            put("travelType", c.getString(1) ?: "")
                            put("fromPlace", c.getString(2) ?: "")
                            put("toPlace", c.getString(3) ?: "")
                            put("tripNo", c.getString(4) ?: "")
                            put("departTime", c.getString(5) ?: "")
                            put("note", c.getString(6) ?: "")
                            put("status", c.getString(7) ?: "active")
                        })
                    }
                }
            } catch (_: Exception) {}
            return arr.toString()
        }

        @JavascriptInterface fun save(travelType: String, fromPlace: String, toPlace: String, tripNo: String, departTime: String, note: String) {
            ensureTable()
            if (fromPlace.trim().isEmpty() || toPlace.trim().isEmpty() || tripNo.trim().isEmpty()) {
                toast("始发地、终点站和班次都要填")
                return
            }
            try {
                dbHelper.writableDatabase.insert("UserTravelPlans", null, ContentValues().apply {
                    put("travelType", travelType.ifBlank { "高铁" })
                    put("fromPlace", fromPlace.trim())
                    put("toPlace", toPlace.trim())
                    put("tripNo", tripNo.trim().uppercase())
                    put("departTime", departTime.trim())
                    put("note", note.trim())
                    put("status", "active")
                    put("createdAt", System.currentTimeMillis())
                })
                runOnUiThread {
                    Toast.makeText(this@TravelActivity, "已同步给 AI 角色", Toast.LENGTH_SHORT).show()
                    webView.evaluateJavascript("reload()", null)
                }
            } catch (_: Exception) {
                toast("保存失败")
            }
        }

        @JavascriptInterface fun finishPlan(id: Long) = updateStatus(id, "done")
        @JavascriptInterface fun reopenPlan(id: Long) = updateStatus(id, "active")
        @JavascriptInterface fun deletePlan(id: Long) {
            ensureTable()
            try {
                dbHelper.writableDatabase.delete("UserTravelPlans", "id=?", arrayOf(id.toString()))
                runOnUiThread { webView.evaluateJavascript("reload()", null) }
            } catch (_: Exception) {}
        }
        @JavascriptInterface fun goBack() { runOnUiThread { finish() } }
    }

    private fun updateStatus(id: Long, status: String) {
        ensureTable()
        try {
            dbHelper.writableDatabase.update("UserTravelPlans", ContentValues().apply { put("status", status) }, "id=?", arrayOf(id.toString()))
            runOnUiThread { webView.evaluateJavascript("reload()", null) }
        } catch (_: Exception) {}
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun html(): String = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<style>
*{box-sizing:border-box} body{margin:0;background:#eef2f6;color:#142033;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;min-height:100vh;overflow-y:auto;-webkit-overflow-scrolling:touch}
.top{position:sticky;top:0;background:rgba(238,242,246,.94);backdrop-filter:blur(10px);padding:48px 18px 14px;display:flex;align-items:center;gap:12px;border-bottom:1px solid #d7dde6;z-index:2}
.back{font-size:24px;color:#2f80ed;padding:2px 8px}.title{font-size:20px;font-weight:700;letter-spacing:.5px}.wrap{padding:16px 16px 36px}
.panel{background:#fff;border-radius:8px;padding:14px;box-shadow:0 8px 24px rgba(28,45,70,.08);border:1px solid #e2e7ef}
.seg{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:12px}.seg button{height:38px;border:1px solid #cfd8e5;background:#f8fafc;border-radius:6px;color:#34445c;font-size:14px}.seg button.on{background:#2f80ed;color:#fff;border-color:#2f80ed}
label{display:block;font-size:12px;color:#6b778c;margin:12px 0 5px}input,textarea{width:100%;border:1px solid #d7dde6;border-radius:6px;background:#fbfcfe;padding:11px 12px;font-size:15px;color:#142033;outline:none}textarea{min-height:72px;resize:vertical}
.row{display:grid;grid-template-columns:1fr 1fr;gap:10px}.save{width:100%;height:44px;border:0;border-radius:6px;background:#142033;color:white;font-size:15px;margin-top:14px}
.list{display:flex;flex-direction:column;gap:10px;margin-top:16px}.card{background:#fff;border:1px solid #e2e7ef;border-radius:8px;padding:13px;box-shadow:0 6px 18px rgba(28,45,70,.06)}.card.done{opacity:.55}.route{font-size:16px;font-weight:700}.meta{font-size:12px;color:#6b778c;margin-top:4px;line-height:1.6}.actions{display:flex;gap:8px;margin-top:10px}.actions button{flex:1;height:34px;border:1px solid #d7dde6;background:#f8fafc;border-radius:6px;color:#34445c}.empty{text-align:center;color:#8b97aa;padding:36px 0;font-size:14px}
</style></head><body>
<div class="top"><div class="back" onclick="TravelBridge.goBack()">←</div><div class="title">出行</div></div>
<div class="wrap">
<div class="panel">
<div class="seg"><button id="railBtn" class="on" onclick="setType('高铁')">高铁</button><button id="airBtn" onclick="setType('飞机')">飞机</button></div>
<div class="row"><div><label>始发地</label><input id="from" placeholder="如 上海虹桥"></div><div><label>终点站</label><input id="to" placeholder="如 北京南"></div></div>
<label id="noLabel">高铁号</label><input id="tripNo" placeholder="如 G12">
<label>出发时间</label><input id="depart" placeholder="如 7月6日 09:20">
<label>备注</label><textarea id="note" placeholder="座位、同行人、要带的东西…"></textarea>
<button class="save" onclick="save()">保存并同步</button>
</div>
<div class="list" id="list"></div>
</div>
<script>
var type='高铁';
function esc(s){return String(s||'').replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]})}
function setType(t){type=t;railBtn.className=t==='高铁'?'on':'';airBtn.className=t==='飞机'?'on':'';noLabel.textContent=t==='飞机'?'航班号':'高铁号';tripNo.placeholder=t==='飞机'?'如 MU5101':'如 G12'}
function save(){TravelBridge.save(type,from.value,to.value,tripNo.value,depart.value,note.value);from.value='';to.value='';tripNo.value='';depart.value='';note.value=''}
function reload(){var data=JSON.parse(TravelBridge.list());var box=document.getElementById('list');if(!data.length){box.innerHTML='<div class="empty">还没有出行计划</div>';return}box.innerHTML=data.map(function(x){var done=x.status==='done';return '<div class="card '+(done?'done':'')+'"><div class="route">'+esc(x.travelType)+' · '+esc(x.fromPlace)+' → '+esc(x.toPlace)+'</div><div class="meta">'+esc(x.tripNo)+' '+esc(x.departTime)+'<br>'+esc(x.note)+'</div><div class="actions"><button onclick="'+(done?'TravelBridge.reopenPlan':'TravelBridge.finishPlan')+'('+x.id+')">'+(done?'恢复':'完成')+'</button><button onclick="TravelBridge.deletePlan('+x.id+')">删除</button></div></div>'}).join('')}
reload();
</script></body></html>
    """.trimIndent()
}
