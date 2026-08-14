package com.moon.aiphone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class AppThemeSettingsActivity : AppCompatActivity() {

    private lateinit var etCssPaste: EditText
    private lateinit var tvCurrentInfo: TextView
    private lateinit var tvFileName: TextView

    private val pickCssFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val css = contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)?.readText() ?: return@registerForActivityResult
                val name = uri.lastPathSegment?.substringAfterLast("/") ?: "theme.css"
                tvFileName.text = "已选择：$name"
                etCssPaste.setText(css)
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
        loadCurrentState()
    }

    // ════════════════════════════════════════════════════════
    // UI 构建
    // ════════════════════════════════════════════════════════
    private fun buildUI(): ScrollView {
        val scroll = ScrollView(this)
        val root = makeCol().also { it.setPadding(dp(16), dp(16), dp(16), dp(48)) }

        // 标题栏
        root.addView(makeRow().also { row ->
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(4), 0, dp(16))
            row.addView(makeTv("←", 22f).also {
                it.setPadding(0, 0, dp(14), 0)
                it.setOnClickListener { finish() }
            })
            row.addView(makeTv("APP 全局主题", 20f, bold = true))
        })

        // 说明
        root.addView(makeCard().also { card ->
            card.addView(makeTv("💡 说明", 15f, bold = true).also { it.setPadding(0, 0, 0, dp(6)) })
            card.addView(makeTv("""
导入一个 CSS 文件，全APP跟着变：消息列表、通讯录、导航栏、个人页、聊天气泡。

CSS 通过 :root { } 里的变量控制样式，例如：

  --bubble-me-bg: #FF6B9D;
  --bubble-them-bg: rgba(255,255,255,0.3);
  --bottom-nav-bg: rgba(30,30,50,0.85);
  --msg-list-item-bg: rgba(255,255,255,0.15);
  --app-bg: #1a1a2e;
  --header-bg: rgba(0,0,0,0.4);
  --msg-list-name-color: #FFFFFF;

完整变量列表见 ThemeManager.kt 的 defaults。
像「融雪」这类主题CSS可以直接导入。
            """.trimIndent(), 12f, color = 0xFF888888.toInt()).also { it.setLineSpacing(0f, 1.45f) })
        })
        root.addView(spacerView())

        // 示例主题
        root.addView(makeCard().also { card ->
            card.addView(makeTv("🎨 快速体验示例主题", 15f, bold = true).also { it.setPadding(0, 0, 0, dp(10)) })
            card.addView(makeRow().also { row ->
                row.gravity = Gravity.CENTER_VERTICAL
                row.addView(makeBtn("🌙 深夜蓝") { etCssPaste.setText(sampleDarkBlue()) }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
                })
                row.addView(makeBtn("🌸 樱花粉") { etCssPaste.setText(sampleSakura()) }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
                })
                row.addView(makeBtn("🧊 极简白") { etCssPaste.setText(sampleMinimal()) }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            })
        })
        root.addView(spacerView())

        // 导入文件
        tvFileName = makeTv("未选择文件", 12f, color = 0xFF888888.toInt())
        root.addView(makeCard().also { card ->
            card.addView(makeTv("方式一：导入 .css 文件", 15f, bold = true).also { it.setPadding(0, 0, 0, dp(10)) })
            card.addView(makeBtn("📂  从文件管理器选择") {
                pickCssFile.launch(arrayOf("text/css", "text/plain", "*/*"))
            })
            card.addView(tvFileName.also { it.setPadding(0, dp(6), 0, 0) })
        })
        root.addView(spacerView())

        // 粘贴输入
        etCssPaste = EditText(this).apply {
            hint = "在此粘贴 CSS 代码...\n\n:root {\n  --bubble-me-bg: #07C160;\n  --app-bg: #F7F7F7;\n}"
            textSize = 13f
            minLines = 8
            gravity = Gravity.TOP
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = makeShape(0xFFF5F5F5.toInt(), dp(10).toFloat())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isVerticalScrollBarEnabled = true
            typeface = Typeface.MONOSPACE
        }
        root.addView(makeCard().also { card ->
            card.addView(makeTv("方式二：直接粘贴 CSS 代码", 15f, bold = true).also { it.setPadding(0, 0, 0, dp(10)) })
            card.addView(etCssPaste)
        })
        root.addView(spacerView())

        // 操作按钮
        root.addView(makeCard().also { card ->
            card.addView(makeBtn("✅  应用主题", bgColor = 0xFF07C160.toInt(), fgColor = 0xFFFFFFFF.toInt()) {
                applyTheme(etCssPaste.text.toString().trim())
            })
            card.addView(View(this).also {
                it.layoutParams = LinearLayout.LayoutParams(-1, dp(10))
            })
            card.addView(makeBtn("🗑️  清除主题（恢复默认）", bgColor = 0xFFFF4D4D.toInt(), fgColor = 0xFFFFFFFF.toInt()) {
                clearTheme()
            })
        })
        root.addView(spacerView())

        // 当前状态
        tvCurrentInfo = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.4f)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        root.addView(makeCard().also { card ->
            card.addView(makeTv("已应用的主题变量（摘要）", 14f, bold = true).also { it.setPadding(0, 0, 0, dp(8)) })
            card.addView(tvCurrentInfo)
        })

        scroll.addView(root)
        return scroll
    }

    // ════════════════════════════════════════════════════════
    // 逻辑
    // ════════════════════════════════════════════════════════
    private fun applyTheme(css: String) {
        if (css.isEmpty()) { Toast.makeText(this, "CSS 内容为空", Toast.LENGTH_SHORT).show(); return }
        // ThemeManager.saveAndApply 内部已经保存到 globalThemeCSS，不需要再另存一份
        ThemeManager.saveAndApply(this, css)
        updateCurrentInfo()
        Toast.makeText(this, "✅ 主题已应用，重新进入聊天即可看到效果", Toast.LENGTH_LONG).show()
        setResult(Activity.RESULT_OK)
    }

    private fun clearTheme() {
        ThemeManager.clear(this)
        // ThemeManager.clear 内部已经删除 globalThemeCSS，这里只需清 UI
        etCssPaste.setText("")
        tvFileName.text = "未选择文件"
        updateCurrentInfo()
        Toast.makeText(this, "已清除主题，重新进入聊天恢复默认", Toast.LENGTH_SHORT).show()
        setResult(Activity.RESULT_OK)
    }

    private fun loadCurrentState() {
        val saved = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            .getString("globalThemeCSS", "") ?: ""
        if (saved.isNotEmpty()) etCssPaste.setText(saved)
        updateCurrentInfo()
    }

    private fun updateCurrentInfo() {
        if (!ThemeManager.hasTheme()) { tvCurrentInfo.text = "（当前使用默认主题）"; return }
        val keys = listOf(
            "--app-bg", "--bubble-me-bg", "--bubble-them-bg",
            "--bottom-nav-bg", "--header-bg",
            "--msg-list-item-bg", "--msg-list-name-color",
            "--bottom-nav-icon-color", "--bottom-nav-selected-color"
        )
        tvCurrentInfo.text = keys.joinToString("\n") { k -> "$k:\n  ${ThemeManager.get(k)}" }
    }

    // ════════════════════════════════════════════════════════
    // 示例主题
    // ════════════════════════════════════════════════════════
    private fun sampleDarkBlue() = """
:root {
  --app-bg: #0d0d1a;
  --header-bg: rgba(15,15,35,0.92);
  --header-title-color: #e8eaf6;
  --header-icon-color: #9fa8da;
  --bottom-nav-bg: rgba(10,10,28,0.95);
  --bottom-nav-icon-color: #7986cb;
  --bottom-nav-selected-color: #80cbc4;
  --msg-list-item-bg: rgba(255,255,255,0.05);
  --msg-list-item-radius: 10;
  --msg-list-name-color: #e8eaf6;
  --msg-list-preview-color: #7986cb;
  --msg-list-time-color: #5c6bc0;
  --msg-list-divider-color: rgba(255,255,255,0.04);
  --contact-item-bg: rgba(255,255,255,0.04);
  --contact-name-color: #e8eaf6;
  --bubble-me-bg: #3949ab;
  --bubble-me-color: #ffffff;
  --bubble-them-bg: rgba(255,255,255,0.08);
  --bubble-them-color: #e8eaf6;
  --chat-bg: #0d0d1a;
  --input-area-bg: rgba(10,10,22,0.95);
  --input-box-bg: rgba(255,255,255,0.08);
  --input-box-color: #e8eaf6;
  --send-btn-bg: #3949ab;
  --timestamp-color: rgba(121,134,203,0.7);
}""".trimIndent()

    private fun sampleSakura() = """
:root {
  --app-bg: #fff5f8;
  --header-bg: rgba(255,240,245,0.92);
  --header-title-color: #880e4f;
  --header-icon-color: #c2185b;
  --bottom-nav-bg: rgba(255,240,248,0.95);
  --bottom-nav-icon-color: #e91e8c;
  --bottom-nav-selected-color: #c2185b;
  --msg-list-item-bg: rgba(255,255,255,0.7);
  --msg-list-item-radius: 12;
  --msg-list-name-color: #880e4f;
  --msg-list-preview-color: #c2185b;
  --msg-list-time-color: #f48fb1;
  --msg-list-divider-color: rgba(194,24,91,0.08);
  --contact-item-bg: rgba(255,255,255,0.7);
  --contact-name-color: #880e4f;
  --bubble-me-bg: #e91e8c;
  --bubble-me-color: #ffffff;
  --bubble-them-bg: rgba(255,255,255,0.85);
  --bubble-them-color: #880e4f;
  --chat-bg: #fff0f5;
  --send-btn-bg: #e91e8c;
  --input-area-bg: rgba(255,240,248,0.95);
  --input-box-bg: rgba(255,255,255,0.8);
  --timestamp-color: rgba(194,24,91,0.5);
}""".trimIndent()

    private fun sampleMinimal() = """
:root {
  --app-bg: #ffffff;
  --header-bg: #ffffff;
  --header-title-color: #000000;
  --header-icon-color: #333333;
  --bottom-nav-bg: #ffffff;
  --bottom-nav-icon-color: #888888;
  --bottom-nav-selected-color: #000000;
  --msg-list-item-bg: #ffffff;
  --msg-list-item-radius: 0;
  --msg-list-name-color: #000000;
  --msg-list-preview-color: #aaaaaa;
  --msg-list-time-color: #cccccc;
  --msg-list-divider-color: #f5f5f5;
  --contact-item-bg: #ffffff;
  --contact-name-color: #000000;
  --bubble-me-bg: #000000;
  --bubble-me-color: #ffffff;
  --bubble-them-bg: #f5f5f5;
  --bubble-them-color: #000000;
  --chat-bg: #fafafa;
  --send-btn-bg: #000000;
  --input-area-bg: #ffffff;
  --input-box-bg: #f5f5f5;
  --timestamp-color: rgba(0,0,0,0.3);
}""".trimIndent()

    // ════════════════════════════════════════════════════════
    // UI 工具（改名避免冲突）
    // ════════════════════════════════════════════════════════
    private fun makeCol() = LinearLayout(this).also { it.orientation = LinearLayout.VERTICAL }
    private fun makeRow() = LinearLayout(this).also { it.orientation = LinearLayout.HORIZONTAL }

    private fun makeCard() = makeCol().also { col ->
        col.setPadding(dp(16), dp(16), dp(16), dp(16))
        col.background = makeShape(0xFFFFFFFF.toInt(), dp(14).toFloat(), strokeColor = 0xFFEEEEEE.toInt())
        col.layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun spacerView() = View(this).also {
        it.layoutParams = LinearLayout.LayoutParams(-1, dp(12))
    }

    private fun makeTv(
        text: String, size: Float,
        bold: Boolean = false,
        color: Int = 0xFF333333.toInt()
    ) = TextView(this).also {
        it.text = text; it.textSize = size; it.setTextColor(color)
        it.layoutParams = LinearLayout.LayoutParams(-1, -2)
        if (bold) it.setTypeface(null, Typeface.BOLD)
    }

    private fun makeBtn(
        label: String,
        bgColor: Int = 0xFFF0F0F0.toInt(),
        fgColor: Int = 0xFF333333.toInt(),
        click: () -> Unit
    ) = TextView(this).also {
        it.text = label; it.textSize = 15f; it.setTextColor(fgColor)
        it.gravity = Gravity.CENTER
        it.setPadding(0, dp(13), 0, dp(13))
        it.background = makeShape(bgColor, dp(10).toFloat())
        it.layoutParams = LinearLayout.LayoutParams(-1, -2)
        it.setOnClickListener { click() }
    }

    private fun makeShape(color: Int, radius: Float, strokeColor: Int = 0) =
        GradientDrawable().also {
            it.setColor(color); it.cornerRadius = radius
            if (strokeColor != 0) it.setStroke(1, strokeColor)
        }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
}