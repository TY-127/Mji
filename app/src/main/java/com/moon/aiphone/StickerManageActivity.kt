package com.moon.aiphone

import android.content.ContentValues
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class StickerManageActivity : AppCompatActivity() {

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val topBar = RelativeLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50))
            setBackgroundColor(Color.WHITE)
            elevation = 4f
        }
        val tvTitle = TextView(this).apply {
            text = "批量导入"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).also { it.addRule(RelativeLayout.CENTER_IN_PARENT) }
        }
        val btnCancel = TextView(this).apply {
            text = "取消"
            textSize = 15f
            setTextColor(Color.parseColor("#007AFF"))
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            ).also {
                it.addRule(RelativeLayout.ALIGN_PARENT_START)
                it.addRule(RelativeLayout.CENTER_VERTICAL)
            }
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { finish() }
        }
        topBar.addView(tvTitle)
        topBar.addView(btnCancel)
        root.addView(topBar)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        val tvMethod1 = TextView(this).apply {
            text = "方式一：文字输入"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(6) }
        }
        val tvHint = TextView(this).apply {
            text = "格式：表情包意思: 图片网址 (每行一个)"
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(12) }
        }
        val etBatch = EditText(this).apply {
            hint = "开心: https://example.com/happy.png\n生气: https://example.com/angry.png"
            textSize = 13f
            setTextColor(Color.BLACK)
            setHintTextColor(Color.parseColor("#BBBBBB"))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#F5F5F5"))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(180)
            ).also { it.bottomMargin = dp(16) }
            gravity = Gravity.TOP
        }
        val btnImportText = TextView(this).apply {
            text = "导入文字"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(8).toFloat()
            }
            setPadding(0, dp(14), 0, dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(24) }
            setOnClickListener {
                val text = etBatch.text.toString().trim()
                if (text.isEmpty()) {
                    Toast.makeText(this@StickerManageActivity, "请输入内容", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                importFromText(text)
            }
        }

        val divider = android.view.View(this).apply {
            setBackgroundColor(Color.parseColor("#EEEEEE"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).also { it.bottomMargin = dp(24) }
        }

        val tvMethod2 = TextView(this).apply {
            text = "方式二：文件导入"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(6) }
        }
        val tvFileHint = TextView(this).apply {
            text = "支持 json, txt 文件"
            textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(12) }
        }
        val btnPickFile = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(1, Color.parseColor("#DDDDDD"))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(0, dp(16), 0, dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            val icon = TextView(context).apply {
                text = "📄"
                textSize = 20f
                setPadding(0, 0, dp(8), 0)
            }
            val label = TextView(context).apply {
                text = "选择文件导入"
                textSize = 15f
                setTextColor(Color.parseColor("#333333"))
            }
            addView(icon)
            addView(label)
            setOnClickListener {
                pickFileLauncher.launch(arrayOf("text/*", "application/json"))
            }
        }

        content.addView(tvMethod1)
        content.addView(tvHint)
        content.addView(etBatch)
        content.addView(btnImportText)
        content.addView(divider)
        content.addView(tvMethod2)
        content.addView(tvFileHint)
        content.addView(btnPickFile)
        scrollView.addView(content)
        root.addView(scrollView)
        setContentView(root)
    }

    private val pickFileLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val text = contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader().use { it.readText() }
                } ?: return@registerForActivityResult

                importFromText(text)
            } catch (e: Exception) {
                Toast.makeText(this, "文件读取失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun importFromText(text: String) {
        var count = 0
        try {
            val db = DatabaseHelper(this).writableDatabase
            var packId = 0L

            db.rawQuery(
                "SELECT id FROM StickerPacks WHERE packName='默认' LIMIT 1",
                null
            ).use { packCursor ->
                if (packCursor.moveToFirst()) {
                    packId = packCursor.getLong(0)
                }
            }

            if (packId == 0L) {
                val cvPack = ContentValues().apply {
                    put("packName", "默认")
                    put("createdAt", System.currentTimeMillis())
                }
                packId = db.insert("StickerPacks", null, cvPack)
            }

            text.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach
                val colonIdx = trimmed.indexOfFirst { it == ':' || it == '：' }
                if (colonIdx > 0) {
                    val name = trimmed.substring(0, colonIdx).trim()
                    val url = trimmed.substring(colonIdx + 1).trim().trimStart('：', ':').trim()
                    if (name.isNotEmpty() && url.startsWith("http")) {
                        val cv = ContentValues().apply {
                            put("packId", packId)
                            put("name", name)
                            put("url", url)
                            put("createdAt", System.currentTimeMillis())
                        }
                        val exists = db.rawQuery(
                            "SELECT id FROM Stickers WHERE name=? AND url=? LIMIT 1",
                            arrayOf(name, url)
                        ).use { c ->
                            c.moveToFirst()
                        }

                        if (!exists) {
                            db.insert("Stickers", null, cv)
                            count++
                        }
                    }
                }
            }
            Toast.makeText(this, "成功导入 $count 个表情包", Toast.LENGTH_SHORT).show()
            if (count > 0) finish()
        } catch (e: Exception) {
            android.util.Log.e("StickerManage", e.stackTraceToString())
            Toast.makeText(this, "导入失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
