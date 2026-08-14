package com.moon.aiphone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * ChatThemeSettingsActivity
 * 入口：CharacterSettingsActivity 里加一个「聊天主题」按钮跳转到这里
 * 功能：
 *   1. 粘贴 CSS 代码直接应用
 *   2. 从文件管理器导入 .css 文件
 *   3. 预览当前已应用的 CSS（显示前300字）
 *   4. 清除主题（恢复默认）
 */
class ChatThemeSettingsActivity : AppCompatActivity() {

    private var aiId = ""
    private lateinit var etCssPaste: EditText
    private lateinit var tvCurrentCss: TextView
    private lateinit var tvFileName: TextView

    private val pickCssFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val cssText = contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.readText() ?: return@registerForActivityResult

                // 显示文件名
                val fileName = uri.lastPathSegment?.substringAfterLast("/") ?: "theme.css"
                tvFileName.text = "已选择：$fileName"

                // 填入粘贴框预览
                etCssPaste.setText(cssText)

                Toast.makeText(this, "文件已读取，点击「应用」生效", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "读取文件失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        aiId = intent.getStringExtra("AI_ID") ?: ""

        // ── 动态构建UI（避免依赖layout文件）─────────────────────
        val root = buildUI()
        setContentView(root)

        loadCurrentCSS()
    }

    private fun buildUI(): ScrollView {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        // ── 标题栏 ────────────────────────────────────────────
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(16))

            addView(TextView(this@ChatThemeSettingsActivity).apply {
                text = "←"
                textSize = 22f
                setPadding(0, 0, dp(12), 0)
                setOnClickListener { finish() }
            })
            addView(TextView(this@ChatThemeSettingsActivity).apply {
                text = "聊天主题 CSS"
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
        })

        // ── 说明 ─────────────────────────────────────────────
        container.addView(buildCard {
            addView(TextView(this@ChatThemeSettingsActivity).apply {
                text = "💡 使用说明"
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, dp(6))
            })
            addView(TextView(this@ChatThemeSettingsActivity).apply {
                text = "将 CSS 代码粘贴到下方输入框，或从文件管理器导入 .css 文件。\n" +
                        "CSS 通过修改 CSS 变量（:root）和类名来美化聊天界面。\n\n" +
                        "支持的核心变量：\n" +
                        "  --bubble-me-bg / --bubble-them-bg  气泡背景\n" +
                        "  --bubble-me-color / --bubble-them-color  文字颜色\n" +
                        "  --bubble-me-radius / --bubble-them-radius  圆角\n" +
                        "  --header-bg / --input-area-bg  顶栏/底栏背景\n" +
                        "  --chat-bg  整体背景色\n" +
                        "  完整变量列表见 chat_template.html 的 :root 注释"
                textSize = 13f
                setTextColor(0xFF888888.toInt())
                setLineSpacing(0f, 1.4f)
            })
        })

        spacer(container, dp(12))

        // ── 导入文件按钮 ──────────────────────────────────────
        tvFileName = TextView(this).apply {
            text = "未选择文件"
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            setPadding(0, dp(6), 0, 0)
        }

        container.addView(buildCard {
            addView(TextView(this@ChatThemeSettingsActivity).apply {
                text = "方式一：从文件管理器导入 .css 文件"
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, dp(10))
            })
            addView(buildButton("📂  选择 .css 文件") {
                pickCssFile.launch(arrayOf("text/css", "text/plain", "*/*"))
            })
            addView(tvFileName)
        })

        spacer(container, dp(12))

        // ── 粘贴输入框 ────────────────────────────────────────
        etCssPaste = EditText(this).apply {
            hint = "在此粘贴 CSS 代码...\n\n例如：\n:root {\n  --bubble-me-bg: #FF6B9D;\n  --bubble-them-bg: rgba(255,255,255,0.2);\n}"
            textSize = 13f
            minLines = 8
            gravity = android.view.Gravity.TOP
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFF5F5F5.toInt())
                cornerRadius = dp(10).toFloat()
            }
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isVerticalScrollBarEnabled = true
        }

        container.addView(buildCard {
            addView(TextView(this@ChatThemeSettingsActivity).apply {
                text = "方式二：直接粘贴 CSS 代码"
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, dp(10))
            })
            addView(etCssPaste)
        })

        spacer(container, dp(12))

        // ── 操作按钮 ──────────────────────────────────────────
        container.addView(buildCard {
            // 应用按钮
            addView(buildButton("✅  应用主题", bgColor = 0xFF07C160.toInt(), textColor = 0xFFFFFFFF.toInt()) {
                applyCSS(etCssPaste.text.toString().trim())
            })
            spacer(this, dp(10))
            // 清除按钮
            addView(buildButton("🗑️  清除主题（恢复默认）", bgColor = 0xFFFF4D4D.toInt(), textColor = 0xFFFFFFFF.toInt()) {
                clearCSS()
            })
        })

        spacer(container, dp(16))

        // ── 当前已应用的CSS预览 ───────────────────────────────
        tvCurrentCss = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
        }

        container.addView(buildCard {
            addView(TextView(this@ChatThemeSettingsActivity).apply {
                text = "当前已应用的主题（前300字）"
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, dp(8))
            })
            addView(tvCurrentCss)
        })

        scroll.addView(container)
        return scroll
    }

    // ════════════════════════════════════════════════════════════
    // 逻辑
    // ════════════════════════════════════════════════════════════

    private fun applyCSS(css: String) {
        if (css.isEmpty()) {
            Toast.makeText(this, "CSS 内容为空", Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            .edit().putString("customCSS_$aiId", css).apply()
        tvCurrentCss.text = if (css.length > 300) css.take(300) + "\n..." else css
        Toast.makeText(this, "✅ 主题已保存，重新进入聊天即可生效", Toast.LENGTH_LONG).show()
        // 如果聊天界面还在，立即通知它刷新
        setResult(Activity.RESULT_OK, Intent().putExtra("css_updated", true))
    }

    private fun clearCSS() {
        getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            .edit().remove("customCSS_$aiId").apply()
        etCssPaste.setText("")
        tvCurrentCss.text = "（无）"
        tvFileName.text = "未选择文件"
        Toast.makeText(this, "已清除主题，重新进入聊天即可恢复默认", Toast.LENGTH_SHORT).show()
        setResult(Activity.RESULT_OK, Intent().putExtra("css_updated", true))
    }

    private fun loadCurrentCSS() {
        val css = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            .getString("customCSS_$aiId", "") ?: ""
        if (css.isNotEmpty()) {
            etCssPaste.setText(css)
            tvCurrentCss.text = if (css.length > 300) css.take(300) + "\n..." else css
        } else {
            tvCurrentCss.text = "（无，使用默认主题）"
        }
    }

    // ════════════════════════════════════════════════════════════
    // UI 工具函数
    // ════════════════════════════════════════════════════════════

    private fun buildCard(block: LinearLayout.() -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = dp(14).toFloat()
                setStroke(1, 0xFFEEEEEE.toInt())
            }
            block()
        }
    }

    private fun buildButton(
        text: String,
        bgColor: Int = 0xFFF0F0F0.toInt(),
        textColor: Int = 0xFF333333.toInt(),
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            this.setTextColor(textColor)
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(13), 0, dp(13))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setOnClickListener { onClick() }
        }
    }

    private fun spacer(parent: LinearLayout, height: Int) {
        parent.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, height)
        })
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
}