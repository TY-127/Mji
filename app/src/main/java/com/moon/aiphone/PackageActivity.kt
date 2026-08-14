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

class PackageActivity : AppCompatActivity() {
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
            addJavascriptInterface(Bridge(), "PackageBridge")
        }
        setContentView(webView)
        webView.loadDataWithBaseURL(null, html(), "text/html", "UTF-8", null)
    }

    private fun ensureTable() {
        try {
            dbHelper.writableDatabase.execSQL(
                "CREATE TABLE IF NOT EXISTS UserPackages (id INTEGER PRIMARY KEY AUTOINCREMENT, trackingNo TEXT, carrier TEXT, itemName TEXT, status TEXT DEFAULT 'active', note TEXT, createdAt INTEGER)"
            )
        } catch (_: Exception) {}
    }

    private fun guessCarrier(no: String): String {
        val s = no.trim().uppercase()
        return when {
            s.startsWith("SF") || Regex("^\\d{12}$").matches(s) -> "顺丰/或通用快递"
            s.startsWith("JD") || s.startsWith("JDX") -> "京东物流"
            s.startsWith("YT") || s.length in 15..18 -> "圆通/或通用快递"
            s.startsWith("ZTO") -> "中通"
            s.startsWith("YTO") -> "圆通"
            s.startsWith("STO") -> "申通"
            s.startsWith("EMS") || s.startsWith("EA") || s.startsWith("EV") -> "邮政/EMS"
            s.startsWith("JT") -> "极兔"
            else -> ""
        }
    }

    inner class Bridge {
        @JavascriptInterface fun guess(no: String): String = guessCarrier(no)
        @JavascriptInterface fun list(): String {
            ensureTable()
            val arr = JSONArray()
            try {
                dbHelper.readableDatabase.rawQuery(
                    "SELECT id, trackingNo, carrier, itemName, status, note FROM UserPackages ORDER BY status ASC, id DESC",
                    null
                ).use { c ->
                    while (c.moveToNext()) {
                        arr.put(JSONObject().apply {
                            put("id", c.getLong(0))
                            put("trackingNo", c.getString(1) ?: "")
                            put("carrier", c.getString(2) ?: "")
                            put("itemName", c.getString(3) ?: "")
                            put("status", c.getString(4) ?: "active")
                            put("note", c.getString(5) ?: "")
                        })
                    }
                }
            } catch (_: Exception) {}
            return arr.toString()
        }

        @JavascriptInterface fun save(trackingNo: String, carrier: String, itemName: String, note: String) {
            ensureTable()
            val no = trackingNo.trim().uppercase()
            if (no.isEmpty()) {
                toast("物流号要填")
                return
            }
            val finalCarrier = carrier.trim().ifEmpty { guessCarrier(no) }
            try {
                dbHelper.writableDatabase.insert("UserPackages", null, ContentValues().apply {
                    put("trackingNo", no)
                    put("carrier", finalCarrier)
                    put("itemName", itemName.trim())
                    put("note", note.trim())
                    put("status", "active")
                    put("createdAt", System.currentTimeMillis())
                })
                runOnUiThread {
                    Toast.makeText(this@PackageActivity, "已同步给 AI 角色", Toast.LENGTH_SHORT).show()
                    webView.evaluateJavascript("reload()", null)
                }
            } catch (_: Exception) {
                toast("保存失败")
            }
        }

        @JavascriptInterface fun picked(id: Long) = updateStatus(id, "picked")
        @JavascriptInterface fun reopen(id: Long) = updateStatus(id, "active")
        @JavascriptInterface fun deletePackage(id: Long) {
            ensureTable()
            try {
                dbHelper.writableDatabase.delete("UserPackages", "id=?", arrayOf(id.toString()))
                runOnUiThread { webView.evaluateJavascript("reload()", null) }
            } catch (_: Exception) {}
        }
        @JavascriptInterface fun goBack() { runOnUiThread { finish() } }
    }

    private fun updateStatus(id: Long, status: String) {
        ensureTable()
        try {
            dbHelper.writableDatabase.update("UserPackages", ContentValues().apply { put("status", status) }, "id=?", arrayOf(id.toString()))
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
*{box-sizing:border-box}body{margin:0;background:#f3efe8;color:#251a10;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;min-height:100vh;overflow-y:auto;-webkit-overflow-scrolling:touch}
.top{position:sticky;top:0;background:rgba(243,239,232,.94);backdrop-filter:blur(10px);padding:48px 18px 14px;display:flex;align-items:center;gap:12px;border-bottom:1px solid #ded4c4;z-index:2}.back{font-size:24px;color:#9b672c;padding:2px 8px}.title{font-size:20px;font-weight:700}
.wrap{padding:16px 16px 36px}.panel,.card{background:#fffaf2;border:1px solid #e2d4bd;border-radius:8px;box-shadow:0 8px 22px rgba(80,54,25,.08)}.panel{padding:14px}label{display:block;font-size:12px;color:#8b7358;margin:12px 0 5px}input,textarea{width:100%;border:1px solid #d8c8af;border-radius:6px;background:#fffdf8;padding:11px 12px;font-size:15px;color:#251a10;outline:none}textarea{min-height:70px;resize:vertical}.hint{font-size:12px;color:#9b672c;margin-top:6px}.save{width:100%;height:44px;border:0;border-radius:6px;background:#9b672c;color:white;font-size:15px;margin-top:14px}
.list{display:flex;flex-direction:column;gap:10px;margin-top:16px}.card{padding:13px}.card.picked{opacity:.55}.name{font-size:16px;font-weight:700}.meta{font-size:12px;color:#806b55;margin-top:5px;line-height:1.65;word-break:break-all}.actions{display:flex;gap:8px;margin-top:10px}.actions button{flex:1;height:34px;border:1px solid #d8c8af;background:#fffdf8;border-radius:6px;color:#5d4328}.empty{text-align:center;color:#9a8975;padding:36px 0;font-size:14px}
</style></head><body>
<div class="top"><div class="back" onclick="PackageBridge.goBack()">←</div><div class="title">包裹</div></div>
<div class="wrap">
<div class="panel">
<label>物流号</label><input id="trackingNo" placeholder="粘贴快递单号" oninput="autoGuess()"><div class="hint" id="guessHint">会尝试按单号特征识别快递；不准时可手动改。</div>
<label>快递公司</label><input id="carrier" placeholder="如 顺丰 / 京东 / 中通">
<label>是什么东西</label><input id="itemName" placeholder="如 书、衣服、谷子、日用品">
<label>备注</label><textarea id="note" placeholder="取件码、放驿站、易碎、别忘了验货…"></textarea>
<button class="save" onclick="save()">保存并同步</button>
</div>
<div class="list" id="list"></div>
</div>
<script>
function esc(s){return String(s||'').replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]})}
function autoGuess(){var g=PackageBridge.guess(trackingNo.value);if(g&&!carrier.value){carrier.value=g}guessHint.textContent=g?'猜测：'+g+'。不准可以手动修改。':'暂时没识别出来，可以自己填快递公司。'}
function save(){PackageBridge.save(trackingNo.value,carrier.value,itemName.value,note.value);trackingNo.value='';carrier.value='';itemName.value='';note.value='';autoGuess()}
function reload(){var data=JSON.parse(PackageBridge.list());var box=document.getElementById('list');if(!data.length){box.innerHTML='<div class="empty">还没有包裹</div>';return}box.innerHTML=data.map(function(x){var picked=x.status==='picked';return '<div class="card '+(picked?'picked':'')+'"><div class="name">'+esc(x.itemName||'未命名包裹')+'</div><div class="meta">'+esc(x.carrier||'未填写快递')+' · '+esc(x.trackingNo)+'<br>'+esc(x.note)+'</div><div class="actions"><button onclick="'+(picked?'PackageBridge.reopen':'PackageBridge.picked')+'('+x.id+')">'+(picked?'恢复':'已取件')+'</button><button onclick="PackageBridge.deletePackage('+x.id+')">删除</button></div></div>'}).join('')}
reload();
</script></body></html>
    """.trimIndent()
}
