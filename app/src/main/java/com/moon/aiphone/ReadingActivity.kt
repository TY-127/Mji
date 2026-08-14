package com.moon.aiphone

import android.content.ContentValues
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ReadingActivity : AppCompatActivity() {

    private lateinit var tvContent: TextView
    private lateinit var tvChunkTitle: TextView
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var tvLoading: TextView
    private lateinit var tvAfterthought: TextView  // 观后感卡片

    private var bookId: Long = 0
    private var filePath = ""
    private var totalChunks = 0
    private var currentChunk = 0
    private var contactId = ""
    private var contactName = ""
    private var bookTitle = ""

    data class ChunkResult(
        val comments: List<Pair<String, String>>,
        val afterthought: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookId = intent.getLongExtra("bookId", 0)
        bookTitle = intent.getStringExtra("bookTitle") ?: ""
        filePath = intent.getStringExtra("filePath") ?: ""
        totalChunks = intent.getIntExtra("totalChunks", 1)
        currentChunk = intent.getIntExtra("lastChunk", 0)
        contactId = intent.getStringExtra("contactId") ?: ""
        contactName = intent.getStringExtra("contactName") ?: ""

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF5F0E8.toInt())
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 60, 24, 16)
        }
        val btnBack = Button(this).apply {
            text = "←"
            setOnClickListener { finish() }
        }
        tvChunkTitle = TextView(this).apply {
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(16, 0, 0, 0)
        }
        topBar.addView(btnBack); topBar.addView(tvChunkTitle)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        tvContent = TextView(this).apply {
            textSize = 18f
            setLineSpacing(0f, 1.8f)
            setPadding(40, 30, 40, 30)
            setTextColor(0xFF333333.toInt())
            movementMethod = LinkMovementMethod.getInstance()
        }
        scrollView.addView(tvContent)

        tvLoading = TextView(this).apply {
            text = "角色正在阅读中…"
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 20, 0, 20)
            setTextColor(0xFF999999.toInt())
            visibility = View.GONE
        }

        // 观后感卡片，放在正文和翻页按钮之间
        tvAfterthought = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF5D4037.toInt())
            setBackgroundColor(0xFFFFF8E1.toInt())
            setPadding(40, 30, 40, 30)
            setLineSpacing(0f, 1.6f)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, 16, 0, 40)
        }
        btnPrev = Button(this).apply {
            text = "上一章"
            setOnClickListener { if (currentChunk > 0) { currentChunk--; loadChunk() } }
        }
        btnNext = Button(this).apply {
            text = "下一章"
            setOnClickListener { if (currentChunk < totalChunks - 1) { currentChunk++; loadChunk() } }
        }
        navBar.addView(btnPrev); navBar.addView(btnNext)

        root.addView(topBar)
        root.addView(scrollView)
        root.addView(tvLoading)
        root.addView(tvAfterthought)  // 观后感在loading下面、翻页按钮上面
        root.addView(navBar)
        setContentView(root)
        loadChunk()
    }

    private fun loadChunk() {
        lifecycleScope.launch(Dispatchers.IO) {
            val chunks = BookParser.getAllChunks(filePath)
            val chunk = chunks.getOrNull(currentChunk) ?: return@launch

            // 查缓存
            val db = DatabaseHelper(this@ReadingActivity).readableDatabase
            val cursor = db.rawQuery(
                "SELECT anchorText, comment FROM BookComments WHERE bookId=? AND contactId=? AND chunkIndex=?",
                arrayOf(bookId.toString(), contactId, currentChunk.toString())
            )
            val allCached = mutableListOf<Pair<String, String>>()
            while (cursor.moveToNext()) allCached.add(Pair(cursor.getString(0), cursor.getString(1)))
            cursor.close()

            withContext(Dispatchers.Main) {
                tvChunkTitle.text = "${chunk.title}（${currentChunk + 1}/${totalChunks}）"
                btnPrev.isEnabled = currentChunk > 0
                btnNext.isEnabled = currentChunk < totalChunks - 1
            }

            if (allCached.isNotEmpty()) {
                // 从缓存里分离观后感和批注
                val cachedAfterthought = allCached.find { it.first == "##AFTERTHOUGHT##" }?.second ?: ""
                val cachedComments = allCached.filter { it.first != "##AFTERTHOUGHT##" }
                withContext(Dispatchers.Main) {
                    renderWithBubbles(chunk.content, cachedComments, cachedAfterthought)
                }
            } else {
                withContext(Dispatchers.Main) { tvLoading.visibility = View.VISIBLE }
                val result = generateChunkResult(chunk.content)

                // 存批注进数据库
                val wdb = DatabaseHelper(this@ReadingActivity).writableDatabase
                result.comments.forEach { (anchor, comment) ->
                    val cv = ContentValues().apply {
                        put("bookId", bookId); put("contactId", contactId)
                        put("chunkIndex", currentChunk); put("anchorText", anchor)
                        put("comment", comment); put("createdAt", System.currentTimeMillis())
                    }
                    wdb.insert("BookComments", null, cv)
                }
                // 存观后感进数据库
                if (result.afterthought.isNotEmpty()) {
                    val cv = ContentValues().apply {
                        put("bookId", bookId); put("contactId", contactId)
                        put("chunkIndex", currentChunk); put("anchorText", "##AFTERTHOUGHT##")
                        put("comment", result.afterthought)
                        put("createdAt", System.currentTimeMillis())
                    }
                    wdb.insert("BookComments", null, cv)
                    // 写入角色记忆
                    val memKey = "$bookId-$contactId-$currentChunk"
                    val check = wdb.rawQuery(
                        "SELECT id FROM MemoryBank WHERE memoryText LIKE ? LIMIT 1",
                        arrayOf("%$memKey%")
                    )
                    if (!check.moveToFirst()) {
                        saveChunkMemory("$memKey ${chunk.title}", result.afterthought)
                    }
                    check.close()
                }

                withContext(Dispatchers.Main) {
                    tvLoading.visibility = View.GONE
                    renderWithBubbles(chunk.content, result.comments, result.afterthought)
                }
                saveProgress()
            }
        }
    }

    private fun renderWithBubbles(text: String, comments: List<Pair<String, String>>, afterthought: String = "") {
        val spannable = SpannableStringBuilder(text)
        comments.sortedByDescending { (anchor, _) ->
            text.indexOf(anchor).takeIf { it >= 0 } ?: 0
        }.forEach { (anchor, comment) ->
            val pos = text.indexOf(anchor)
            if (pos < 0) return@forEach
            val insertAt = minOf(pos + anchor.length, text.length)
            val bubble = " 💬"
            spannable.insert(insertAt, bubble)
            val start = insertAt
            val end = insertAt + bubble.length
            spannable.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) { showCommentDialog(anchor, comment) }
                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.color = 0xFFFF6B9D.toInt()
                    ds.isUnderlineText = false
                }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tvContent.text = spannable

        // 显示观后感卡片
        if (afterthought.isNotEmpty()) {
            tvAfterthought.text = "📖 $contactName 读完这段说：\n\n$afterthought"
            tvAfterthought.visibility = View.VISIBLE
        } else {
            tvAfterthought.visibility = View.GONE
        }
    }

    private fun showCommentDialog(anchor: String, comment: String) {
        val dialog = BottomSheetDialog(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 60)
            setBackgroundColor(0xFFFFFDF8.toInt())
        }
        val tvName = TextView(this).apply {
            text = "💬 $contactName 读到这里说："
            textSize = 13f
            setTextColor(0xFF999999.toInt())
        }
        val tvAnchor = TextView(this).apply {
            text = "「${anchor.take(30)}…」"
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            setPadding(20, 12, 20, 12)
            setBackgroundColor(0xFFF0EDE8.toInt())
        }
        val tvComment = TextView(this).apply {
            text = comment
            textSize = 17f
            setTextColor(0xFF333333.toInt())
            setPadding(0, 16, 0, 0)
            setLineSpacing(0f, 1.6f)
        }
        layout.addView(tvName); layout.addView(tvAnchor); layout.addView(tvComment)
        dialog.setContentView(layout)
        dialog.show()
    }

    private suspend fun generateChunkResult(chunkText: String): ChunkResult {
        return withContext(Dispatchers.IO) {
            try {
                val pref = getSharedPreferences("AppConfig", MODE_PRIVATE)
                val url = pref.getString("apiUrl", "") ?: ""
                val key = pref.getString("apiKey", "") ?: ""
                val model = pref.getString("modelName", "") ?: ""
                if (url.isEmpty() || key.isEmpty()) return@withContext ChunkResult(emptyList(), "")

                var finalUrl = if (url.endsWith("/")) url.dropLast(1) else url
                if (!finalUrl.endsWith("/chat/completions")) {
                    finalUrl += if (finalUrl.contains("/v1")) "/chat/completions" else "/v1/chat/completions"
                }

                val db = DatabaseHelper(this@ReadingActivity).readableDatabase
                val c = db.rawQuery("SELECT identityInfo FROM Contacts WHERE userId=?", arrayOf(contactId))
                var persona = ""
                if (c.moveToFirst()) persona = c.getString(0) ?: ""
                c.close()

                val prompt = """
你正在和用户一起阅读小说《$bookTitle》。
以下是当前阅读段落，请完成两件事：

【任务一】选3到5处有感触的句子发表批注
【任务二】用1到3句话说说读完这段的整体感受（观后感）

【你的角色设定】
$persona

【输出格式】只返回以下JSON，不要有任何其他文字：
{
  "comments": [
    {"anchor": "原文中存在的连续文字约15字", "comment": "批注内容"},
    {"anchor": "原文中存在的连续文字约15字", "comment": "批注内容"}
  ],
  "afterthought": "读完这段的整体感受，口语化，像发微信一样自然"
}

【要求】
- anchor必须是原文中真实存在的文字，不能自己编
- 批注和观后感都要符合角色性格，口语化自然

【小说内容】
${chunkText.take(6000)}
                """.trimIndent()

                val bodyJson = JSONObject().apply {
                    put("model", model)
                    put("temperature", 0.7)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                    })
                }

                val request = okhttp3.Request.Builder()
                    .url(finalUrl)
                    .addHeader("Authorization", "Bearer $key")
                    .post(bodyJson.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()

                val response = Http.client.newBuilder()
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
                    .newCall(request)
                    .execute()

                val body = response.body?.string() ?: return@withContext ChunkResult(emptyList(), "")
                if (!response.isSuccessful) return@withContext ChunkResult(emptyList(), "")

                val content = JSONObject(body)
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()
                    .removePrefix("```json").removePrefix("```")
                    .removeSuffix("```").trim()

                val jsonText =
                    Regex("\\{[\\s\\S]*\\}")
                        .find(content)
                        ?.value
                        ?: return@withContext ChunkResult(
                            emptyList(),
                            ""
                        )

                val json = JSONObject(jsonText)

                val comments = mutableListOf<Pair<String, String>>()
                try {
                    val arr = json.getJSONArray("comments")
                    for (i in 0 until arr.length()) {
                        try {
                            val obj = arr.getJSONObject(i)
                            val anchor = obj.optString("anchor", "")
                            val comment = obj.optString("comment", "")
                            if (anchor.isNotEmpty() && comment.isNotEmpty() && chunkText.contains(anchor)) {
                                comments.add(Pair(anchor, comment))
                            }
                        } catch (e: Exception) { continue }
                    }
                } catch (e: Exception) {}

                val afterthought = json.optString("afterthought", "")
                ChunkResult(comments, afterthought)

            } catch (e: Exception) {
                android.util.Log.e("ReadingActivity", "generateChunkResult error: ${e.message}")
                ChunkResult(emptyList(), "")
            }
        }
    }

    private fun saveChunkMemory(chunkTitle: String, afterthought: String) {
        if (afterthought.isEmpty()) return
        Thread {
            try {
                val memory = "和用户一起读《$bookTitle》$chunkTitle，读后感：${afterthought.take(50)}"
                val cv = ContentValues().apply {
                    put("aiId", contactId)
                    put("memoryText", memory)
                    put("category", "shared_event")
                    put("insertTime", System.currentTimeMillis())
                }
                DatabaseHelper(this@ReadingActivity).writableDatabase
                    .insert("MemoryBank", null, cv)
            } catch (e: Exception) {}
        }.start()
    }

    private fun saveProgress() {
        val cv = ContentValues().apply {
            put("lastReadChunkIndex", currentChunk)
            put("lastReadContactId", contactId)
        }
        DatabaseHelper(this).writableDatabase.update("BookShelf", cv, "id=?", arrayOf(bookId.toString()))
    }
}