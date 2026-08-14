package com.moon.aiphone

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/** 为单个角色保存“门”页面的 CSS/HTML 美化。 */
class DoorThemeSettingsActivity : AppCompatActivity() {
    private var aiId = ""
    private lateinit var editor: EditText
    private lateinit var fileLabel: TextView
    private lateinit var statusLabel: TextView

    private val pickThemeFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            editor.setText(text)
            fileLabel.text = "已读取：${uri.lastPathSegment?.substringAfterLast('/') ?: "theme"}"
            statusLabel.text = describe(text)
        } catch (e: Exception) {
            Toast.makeText(this, "读取失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        aiId = intent.getStringExtra("AI_ID").orEmpty()
        setContentView(buildUi())
        val saved = DoorThemeManager.load(this, aiId).raw
        editor.setText(saved)
        statusLabel.text = if (saved.isBlank()) "当前使用默认美化" else describe(saved)
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#F4F1EA")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(32))
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@DoorThemeSettingsActivity).apply {
                text = "←"; textSize = 24f; setTextColor(Color.parseColor("#2E2A35"))
                setPadding(0, dp(8), dp(16), dp(12)); setOnClickListener { finish() }
            })
            addView(TextView(this@DoorThemeSettingsActivity).apply {
                text = "门 · 自定义美化"; textSize = 20f; setTextColor(Color.parseColor("#2E2A35"))
                setTypeface(null, Typeface.BOLD)
            })
        })

        root.addView(card().apply {
            addView(label("使用方式"))
            addView(body("可直接粘贴 CSS，也可导入 .css / .html 文件。门页面是原生界面，因此通过 --door-* 变量改变颜色、圆角、字号和间距；HTML 的 <style> 会参与解析，<body> 会作为不可点击的装饰背景。\n\n"))
        })
        spacer(root)

        fileLabel = body("未选择文件")
        root.addView(card().apply {
            addView(label("导入文件"))
            addView(button("选择 CSS / HTML 文件") {
                pickThemeFile.launch(arrayOf("text/css", "text/html", "text/plain", "*/*"))
            })
            addView(fileLabel)
            addView(button("载入原创示例 · 暮色档案") {
                val sample = assets.open("door_theme_twilight_archive.html").bufferedReader(Charsets.UTF_8).readText()
                editor.setText(sample)
                fileLabel.text = "已载入内置原创示例"
                statusLabel.text = describe(sample)
            }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10) })
            addView(button("载入原创示例 · 羊皮档案袋") {
                val sample = assets.open("door_theme_parchment_dossier.html").bufferedReader(Charsets.UTF_8).readText()
                editor.setText(sample)
                fileLabel.text = "已载入内置原创示例 · 羊皮档案袋"
                statusLabel.text = describe(sample)
            }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10) })
        })
        spacer(root)

        editor = EditText(this).apply {
            hint = ":root {\n  --door-screen-bg: #f4f1ea;\n  --door-card-bg: #fffdf8;\n  --door-accent: #76536d;\n}"
            textSize = 12f; typeface = Typeface.MONOSPACE; gravity = Gravity.TOP
            minLines = 15; setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextColor(Color.parseColor("#2F2A33")); setHintTextColor(Color.parseColor("#A0979F"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            background = rounded(Color.WHITE, 12f, Color.parseColor("#DED4C9"))
        }
        root.addView(card().apply { addView(label("CSS / HTML 代码")); addView(editor) })
        spacer(root)

        statusLabel = body("")
        root.addView(card().apply {
            addView(label("状态")); addView(statusLabel)
            addView(button("应用美化", Color.parseColor("#76536D"), Color.WHITE) {
                val raw = editor.text.toString().trim()
                if (raw.isBlank()) {
                    Toast.makeText(this@DoorThemeSettingsActivity, "请输入 CSS 或 HTML", Toast.LENGTH_SHORT).show()
                    return@button
                }
                DoorThemeManager.save(this@DoorThemeSettingsActivity, aiId, raw)
                statusLabel.text = describe(raw)
                setResult(Activity.RESULT_OK, Intent().putExtra("door_theme_updated", true))
                Toast.makeText(this@DoorThemeSettingsActivity, "已应用，返回后立即刷新", Toast.LENGTH_SHORT).show()
            })
            addView(button("清除美化，恢复默认", Color.parseColor("#EEE8E2"), Color.parseColor("#6B5F68")) {
                DoorThemeManager.clear(this@DoorThemeSettingsActivity, aiId)
                editor.setText(""); fileLabel.text = "未选择文件"; statusLabel.text = "当前使用默认美化"
                setResult(Activity.RESULT_OK, Intent().putExtra("door_theme_updated", true))
            }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10) })
        })

        root.addView(body("支持变量：screen-bg、header-bg、header-text、story-bg、card-bg、user-card-bg、text、user-text、muted-text、border、accent、input-area-bg、input-bg、input-text、input-hint、send-bg、send-text、regen-bg、option-bg、option-text、option-border、card-border-width、card-radius、avatar-size、avatar-radius、text-size、item-horizontal-padding、item-vertical-gap、line-height。变量均需加 --door- 前缀。卡片默认不画外框；如确实需要，可单独设置 card-border-width。" ).apply {
            setPadding(dp(4), dp(16), dp(4), 0)
        })

        scroll.addView(root)
        return scroll
    }

    private fun describe(raw: String): String {
        val theme = DoorThemeManager.parse(raw)
        val variableCount = Regex("--door-[a-z0-9-]+\\s*:", RegexOption.IGNORE_CASE).findAll(raw).count()
        val html = if (theme.backgroundHtml.isBlank()) "无 HTML 背景" else "含 HTML 装饰背景"
        return "已识别 $variableCount 个门主题变量 · $html"
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(Color.parseColor("#FFFDF8"), 16f, Color.parseColor("#DED4C9"))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text; textSize = 15f; setTextColor(Color.parseColor("#2F2A33")); setTypeface(null, Typeface.BOLD)
        setPadding(0, 0, 0, dp(8))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text; textSize = 13f; setTextColor(Color.parseColor("#756B73")); setLineSpacing(0f, 1.35f)
    }

    private fun button(text: String, bg: Int = Color.parseColor("#EEE8E2"), fg: Int = Color.parseColor("#4B4149"), click: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 14f; setTextColor(fg); gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12))
        background = rounded(bg, 10f); layoutParams = LinearLayout.LayoutParams(-1, -2); setOnClickListener { click() }
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null) = android.graphics.drawable.GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radiusDp).toFloat(); if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun spacer(parent: LinearLayout) = parent.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(12)) })
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float) = (value * resources.displayMetrics.density).toInt()
}
