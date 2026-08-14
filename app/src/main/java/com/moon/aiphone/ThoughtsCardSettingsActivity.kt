package com.moon.aiphone

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * ThoughtsCardSettingsActivity — 心声卡片样式设置页
 *
 * 入口：心声卡片右上角⚙按钮
 * 功能：
 *   1. 粘贴自定义HTML模板
 *   2. 从文件导入 .html 文件
 *   3. 内置几款预设风格
 *   4. 清除恢复默认
 *
 * 模板占位符：
 *   {{name}}     AI名字
 *   {{avatar}}   AI头像 base64
 *   {{thoughts}} 心声内容
 */
class ThoughtsCardSettingsActivity : AppCompatActivity() {

    private lateinit var etTemplate: EditText
    private lateinit var tvFileName: TextView
    private lateinit var tvCurrentInfo: TextView

    private val pickHtmlFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val html = contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)?.readText() ?: return@registerForActivityResult
                val name = uri.lastPathSegment?.substringAfterLast("/") ?: "card.html"
                tvFileName.text = "已选择：$name"
                etTemplate.setText(html)
                Toast.makeText(this, "文件读取成功，点击「应用」生效", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "读取失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(buildUI())
        loadCurrentTemplate()
    }

    // ════════════════════════════════════════════════════════
    // UI
    // ════════════════════════════════════════════════════════
    private fun buildUI(): ScrollView {
        val scroll = ScrollView(this)
        val root = col().also { it.setPadding(dp(16), dp(16), dp(16), dp(48)) }

        // 标题栏
        root.addView(row().also { r ->
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, dp(4), 0, dp(16))
            r.addView(tv("←", 22f).also {
                it.setPadding(0, 0, dp(14), 0)
                it.setOnClickListener { finish() }
            })
            r.addView(tv("心声卡片样式", 20f, bold = true))
        })

        // 说明
        root.addView(card().also { c ->
            c.addView(tv("💡 说明", 15f, bold = true).also { it.setPadding(0, 0, 0, dp(6)) })
            c.addView(tv("""
导入一段 HTML 模板，心声卡片的样式完全由你控制。

模板里用以下占位符填入数据：
  {{name}}      AI 的名字
  {{avatar}}    AI 的头像（base64图片）
  {{thoughts}}  心声内容

必须保留两个按钮函数：
  onSettings()  → 打开此设置页
  onClose()     → 关闭卡片

最简示例：
  <div>{{name}} 的心声：{{thoughts}}</div>
  <button onclick="onClose()">关闭</button>
  <button onclick="onSettings()">⚙</button>
            """.trimIndent(), 12f, color = 0xFF888888.toInt()).also { it.setLineSpacing(0f, 1.45f) })
        })
        space(root)

        // 预设风格
        root.addView(card().also { c ->
            c.addView(tv("🎨 内置预设", 15f, bold = true).also { it.setPadding(0, 0, 0, dp(10)) })
            c.addView(row().also { r ->
                r.gravity = Gravity.CENTER_VERTICAL
                listOf(
                    "🌙 深夜" to { val h = presetDark(); etTemplate.setText(h); applyTemplate(h) },
                    "📓 备忘录" to { val h = presetMemo(); etTemplate.setText(h); applyTemplate(h) },
                    "🌸 粉" to { val h = presetPink(); etTemplate.setText(h); applyTemplate(h) }
                ).forEachIndexed { i, (label, action) ->
                    r.addView(btn(label, click = action).also {
                        it.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { lp ->
                            if (i < 2) lp.marginEnd = dp(8)
                        }
                    })
                }
            })
        })
        space(root)

        // 导入文件
        tvFileName = tv("未选择文件", 12f, color = 0xFF888888.toInt())
        root.addView(card().also { c ->
            c.addView(tv("方式一：导入 .html 文件", 15f, bold = true).also { it.setPadding(0, 0, 0, dp(10)) })
            c.addView(btn("📂  从文件管理器选择") {
                pickHtmlFile.launch(arrayOf("text/html", "text/plain", "*/*"))
            })
            c.addView(tvFileName.also { it.setPadding(0, dp(6), 0, 0) })
        })
        space(root)

        // 粘贴
        etTemplate = EditText(this).apply {
            hint = "在此粘贴 HTML 模板代码...\n\n记得保留 {{thoughts}}、onClose()、onSettings() 占位符"
            textSize = 12f; minLines = 8; gravity = Gravity.TOP
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = makeShape(0xFFF5F5F5.toInt(), dp(10).toFloat())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isVerticalScrollBarEnabled = true
            typeface = Typeface.MONOSPACE
        }
        root.addView(card().also { c ->
            c.addView(tv("方式二：直接粘贴 HTML 模板", 15f, bold = true).also { it.setPadding(0, 0, 0, dp(10)) })
            c.addView(etTemplate)
        })
        space(root)

        // 操作按钮
        root.addView(card().also { c ->
            c.addView(btn("✅  应用模板", bgColor = 0xFF07C160.toInt(), fgColor = 0xFFFFFFFF.toInt()) {
                applyTemplate(etTemplate.text.toString().trim())
            })
            c.addView(View(this).also { it.layoutParams = LinearLayout.LayoutParams(-1, dp(10)) })
            c.addView(btn("🗑️  恢复默认卡片", bgColor = 0xFFFF4D4D.toInt(), fgColor = 0xFFFFFFFF.toInt()) {
                clearTemplate()
            })
        })
        space(root)

        // 当前状态
        tvCurrentInfo = tv("", 12f, color = 0xFF888888.toInt())
        root.addView(card().also { c ->
            c.addView(tv("当前模板状态", 14f, bold = true).also { it.setPadding(0, 0, 0, dp(8)) })
            c.addView(tvCurrentInfo)
        })

        scroll.addView(root)
        return scroll
    }

    // ════════════════════════════════════════════════════════
    // 逻辑
    // ════════════════════════════════════════════════════════
    private fun applyTemplate(html: String) {
        if (html.isEmpty()) { Toast.makeText(this, "模板内容为空", Toast.LENGTH_SHORT).show(); return }
        if (!html.contains("{{thoughts}}")) {
            Toast.makeText(this, "模板必须包含 {{thoughts}} 占位符", Toast.LENGTH_LONG).show(); return
        }
        getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            .edit().putString("thoughtsCardTemplate", html).apply()
        updateInfo()
        Toast.makeText(this, "✅ 模板已保存，下次打开心声卡片即生效", Toast.LENGTH_LONG).show()
        setResult(Activity.RESULT_OK)
    }

    private fun clearTemplate() {
        getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            .edit().remove("thoughtsCardTemplate").apply()
        etTemplate.setText("")
        tvFileName.text = "未选择文件"
        updateInfo()
        Toast.makeText(this, "已恢复默认卡片样式", Toast.LENGTH_SHORT).show()
        setResult(Activity.RESULT_OK)
    }

    private fun loadCurrentTemplate() {
        val saved = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            .getString("thoughtsCardTemplate", "") ?: ""
        if (saved.isNotEmpty()) etTemplate.setText(saved)
        updateInfo()
    }

    private fun updateInfo() {
        val saved = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            .getString("thoughtsCardTemplate", "") ?: ""
        tvCurrentInfo.text = if (saved.isEmpty()) "（使用默认内置卡片）"
        else "自定义模板已启用（${saved.length} 字符）"
    }

    // ════════════════════════════════════════════════════════
    // 预设模板
    // ════════════════════════════════════════════════════════
    private fun presetDark() = """
<!DOCTYPE html><html><head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,"PingFang SC",sans-serif;background:transparent;display:flex;align-items:center;justify-content:center;min-height:100vh;padding:24px 16px}
.card{width:100%;max-width:400px;background:rgba(15,15,25,0.97);backdrop-filter:blur(24px);-webkit-backdrop-filter:blur(24px);border-radius:24px;border:1px solid rgba(255,255,255,0.07);box-shadow:0 20px 60px rgba(0,0,0,0.6);overflow:hidden;animation:up .3s cubic-bezier(.34,1.56,.64,1)}
@keyframes up{from{transform:scale(0.92) translateY(20px);opacity:0}to{transform:scale(1) translateY(0);opacity:1}}
.hd{display:flex;align-items:center;padding:16px;gap:10px;border-bottom:1px solid rgba(255,255,255,0.05)}
.av{width:42px;height:42px;border-radius:50%;object-fit:cover;border:2px solid rgba(255,255,255,0.15);background:#333;flex-shrink:0}
.nw{flex:1}.lb{font-size:10px;color:rgba(255,255,255,0.3);letter-spacing:1px;text-transform:uppercase}
.nm{font-size:14px;color:rgba(255,255,255,0.7);font-weight:600;margin-top:2px}
.ic{width:30px;height:30px;border-radius:50%;background:rgba(255,255,255,0.06);border:none;color:rgba(255,255,255,0.35);font-size:15px;cursor:pointer;display:flex;align-items:center;justify-content:center;flex-shrink:0;margin-left:4px}
.ic:active{background:rgba(255,255,255,0.12)}
.bd{padding:18px 16px 20px}.tx{font-size:15px;line-height:1.8;color:#e8e8f0;white-space:pre-wrap;word-break:break-word}
</style></head><body>
<div class="card">
  <div class="hd">
    <img class="av" src="{{avatar}}" onerror="this.style.background='#444'">
    <div class="nw"><div class="lb">内心独白</div><div class="nm">{{name}}</div></div>
    <button class="ic" onclick="onSettings()">⚙</button>
    <button class="ic" onclick="onClose()">✕</button>
  </div>
  <div class="bd"><div class="tx">{{thoughts}}</div></div>
</div>
<script>
function onClose()    { window.parent.postMessage('card:close',    '*'); }
function onSettings() { window.parent.postMessage('card:settings', '*'); }
</script></body></html>
    """.trimIndent()

    private fun presetMemo() = """
<!DOCTYPE html><html><head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,"PingFang SC",sans-serif;background:transparent;display:flex;align-items:center;justify-content:center;min-height:100vh;padding:24px 16px}
.card{width:100%;max-width:400px;background:#ffffff;border-radius:16px;box-shadow:0 10px 40px rgba(0,0,0,0.15);overflow:hidden;animation:up .3s cubic-bezier(.34,1.56,.64,1)}
@keyframes up{from{transform:scale(0.92) translateY(20px);opacity:0}to{transform:scale(1) translateY(0);opacity:1}}
.nav{display:flex;align-items:center;justify-content:space-between;padding:12px 16px;border-bottom:1px solid #f0f0f0}
.nav-left{color:#eab308;font-size:17px;font-weight:500}
.nav-right{display:flex;gap:6px}
.ic{width:32px;height:32px;border-radius:50%;background:#f5f5f5;border:none;color:#888;font-size:15px;cursor:pointer;display:flex;align-items:center;justify-content:center}
.ic:active{background:#eee}
.meta{padding:12px 16px 0;color:#8e8e93;font-size:12px;text-align:center}
.title{padding:6px 16px 0;font-size:22px;font-weight:bold;color:#1c1c1e;display:flex;align-items:center;gap:10px}
.title img{width:32px;height:32px;border-radius:50%;object-fit:cover;border:1px solid #eee}
.bd{padding:12px 16px 20px;font-size:15px;line-height:1.7;color:#1c1c1e;white-space:pre-wrap;word-break:break-word}
.sec-label{color:#eab308;font-size:13px;font-weight:bold;margin-bottom:4px}
</style></head><body>
<div class="card">
  <div class="nav">
    <span class="nav-left">‹ 备忘录</span>
    <div class="nav-right">
      <button class="ic" onclick="onSettings()">⚙</button>
      <button class="ic" onclick="onClose()">✕</button>
    </div>
  </div>
  <div class="meta">内心独白</div>
  <div class="title"><img src="{{avatar}}" onerror="this.style.background='#eee'">{{name}}</div>
  <div class="bd"><div class="sec-label">当前心声</div><div>{{thoughts}}</div></div>
</div>
<script>
function onClose()    { window.parent.postMessage('card:close',    '*'); }
function onSettings() { window.parent.postMessage('card:settings', '*'); }
</script></body></html>
    """.trimIndent()

    private fun presetPink() = """
<!DOCTYPE html><html><head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,"PingFang SC",sans-serif;background:transparent;display:flex;align-items:center;justify-content:center;min-height:100vh;padding:24px 16px}
.card{width:100%;max-width:400px;background:linear-gradient(145deg,#fff0f6,#fce7f3);border-radius:24px;border:1px solid rgba(236,72,153,0.15);box-shadow:0 16px 48px rgba(236,72,153,0.15);overflow:hidden;animation:up .3s cubic-bezier(.34,1.56,.64,1)}
@keyframes up{from{transform:scale(0.92) translateY(20px);opacity:0}to{transform:scale(1) translateY(0);opacity:1}}
.hd{display:flex;align-items:center;padding:16px;gap:10px;border-bottom:1px solid rgba(236,72,153,0.1)}
.av{width:42px;height:42px;border-radius:50%;object-fit:cover;border:2px solid rgba(236,72,153,0.3);background:#fce;flex-shrink:0}
.nw{flex:1}.lb{font-size:10px;color:rgba(190,24,93,0.5);letter-spacing:1px;text-transform:uppercase}
.nm{font-size:14px;color:#be185d;font-weight:600;margin-top:2px}
.ic{width:30px;height:30px;border-radius:50%;background:rgba(236,72,153,0.1);border:none;color:rgba(190,24,93,0.5);font-size:15px;cursor:pointer;display:flex;align-items:center;justify-content:center;flex-shrink:0;margin-left:4px}
.ic:active{background:rgba(236,72,153,0.2)}
.bd{padding:18px 16px 20px}.tx{font-size:15px;line-height:1.8;color:#831843;white-space:pre-wrap;word-break:break-word}
</style></head><body>
<div class="card">
  <div class="hd">
    <img class="av" src="{{avatar}}" onerror="this.style.background='#fce'">
    <div class="nw"><div class="lb">内心独白</div><div class="nm">{{name}}</div></div>
    <button class="ic" onclick="onSettings()">⚙</button>
    <button class="ic" onclick="onClose()">✕</button>
  </div>
  <div class="bd"><div class="tx">{{thoughts}}</div></div>
</div>
<script>
function onClose()    { window.parent.postMessage('card:close',    '*'); }
function onSettings() { window.parent.postMessage('card:settings', '*'); }
</script></body></html>
    """.trimIndent()

    // ════════════════════════════════════════════════════════
    // UI 工具
    // ════════════════════════════════════════════════════════
    private fun col() = LinearLayout(this).also { it.orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).also { it.orientation = LinearLayout.HORIZONTAL }
    private fun card() = col().also {
        it.setPadding(dp(16), dp(16), dp(16), dp(16))
        it.background = makeShape(0xFFFFFFFF.toInt(), dp(14).toFloat(), stroke = 0xFFEEEEEE.toInt())
        it.layoutParams = LinearLayout.LayoutParams(-1, -2)
    }
    private fun space(parent: LinearLayout) = parent.addView(
        View(this).also { it.layoutParams = LinearLayout.LayoutParams(-1, dp(12)) }
    )
    private fun tv(text: String, size: Float, bold: Boolean = false, color: Int = 0xFF333333.toInt()) =
        TextView(this).also {
            it.text = text; it.textSize = size; it.setTextColor(color)
            it.layoutParams = LinearLayout.LayoutParams(-1, -2)
            if (bold) it.setTypeface(null, Typeface.BOLD)
        }
    private fun btn(label: String, bgColor: Int = 0xFFF0F0F0.toInt(), fgColor: Int = 0xFF333333.toInt(), click: () -> Unit) =
        TextView(this).also {
            it.text = label; it.textSize = 14f; it.setTextColor(fgColor)
            it.gravity = Gravity.CENTER
            it.setPadding(dp(4), dp(12), dp(4), dp(12))
            it.background = makeShape(bgColor, dp(10).toFloat())
            it.layoutParams = LinearLayout.LayoutParams(-1, -2)
            it.setOnClickListener { click() }
        }
    private fun makeShape(color: Int, radius: Float, stroke: Int = 0) =
        GradientDrawable().also {
            it.setColor(color); it.cornerRadius = radius
            if (stroke != 0) it.setStroke(1, stroke)
        }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
}