package com.moon.aiphone

import android.content.ContentValues
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

data class ForumComment(
    val id: Long,
    val postId: Long,
    val authorId: String,
    val authorName: String,
    val content: String,
    val likeCount: Int,
    val timestamp: Long
)

class ForumPostDetailActivity : AppCompatActivity() {

    private var postId = -1L
    private val commentList = mutableListOf<ForumComment>()
    private lateinit var tvPostContent: TextView
    private lateinit var tvPostTitle: TextView
    private lateinit var tvAuthor: TextView
    private lateinit var tvLike: TextView
    private lateinit var etComment: EditText
    private lateinit var loadingBar: ProgressBar
    private var likeCount = 0
    private var isLiked = false

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        postId = intent.getLongExtra("POST_ID", -1L)
        if (postId == -1L) { finish(); return }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // 顶部栏
        val topBar = RelativeLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50))
            setBackgroundColor(Color.WHITE)
            elevation = 4f
        }
        val tvTitle = TextView(this).apply {
            text = "帖子详情"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).also { it.addRule(RelativeLayout.CENTER_IN_PARENT) }
        }
        val btnBack = TextView(this).apply {
            text = "‹"
            textSize = 32f
            setPadding(dp(16), 0, dp(16), dp(4))
            setTextColor(Color.BLACK)
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
        topBar.addView(btnBack)
        root.addView(topBar)

        // 滚动区域
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 帖子主体卡片
        val postCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(8) }
        }

        tvAuthor = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#999999"))
        }
        tvPostTitle = TextView(this).apply {
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111111"))
            setPadding(0, dp(10), 0, dp(10))
        }
        tvPostContent = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#333333"))
            setLineSpacing(6f, 1.2f)
        }
        tvLike = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#999999"))
            setPadding(0, dp(16), 0, 0)
            setOnClickListener {
                if (!isLiked) {
                    isLiked = true
                    likeCount++
                    text = "👍 $likeCount  已点赞"
                    setTextColor(Color.parseColor("#07C160"))
                    try {
                        val db = DatabaseHelper(this@ForumPostDetailActivity).writableDatabase
                        db.execSQL("UPDATE ForumPosts SET likeCount=? WHERE id=?", arrayOf(likeCount, postId))
                    } catch (e: Exception) {}
                }
            }
        }

        postCard.addView(tvAuthor)
        postCard.addView(tvPostTitle)
        postCard.addView(tvPostContent)
        postCard.addView(tvLike)
        scrollContent.addView(postCard)

        // 评论区标题
        val tvCommentTitle = TextView(this).apply {
            text = "评论"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#333333"))
            setPadding(dp(16), dp(12), dp(16), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        scrollContent.addView(tvCommentTitle)

        loadingBar = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }
        scrollContent.addView(loadingBar)

        // 评论列表（用LinearLayout代替RecyclerView，放在ScrollView里）
        val commentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            tag = "commentContainer"
        }
        scrollContent.addView(commentContainer)
        scrollView.addView(scrollContent)
        root.addView(scrollView)

        // 底部评论输入栏
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            )
            gravity = Gravity.CENTER_VERTICAL
            elevation = 8f
        }
        etComment = EditText(this).apply {
            hint = "说点什么..."
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#F5F5F5"))
                cornerRadius = dp(20).toFloat()
            }
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setSingleLine(true)
        }
        val btnSend = TextView(this).apply {
            text = "发送"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#07C160"))
                cornerRadius = dp(16).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(36)).also {
                it.marginStart = dp(8)
            }
            setOnClickListener { sendUserComment(commentContainer) }
        }
        bottomBar.addView(etComment)
        bottomBar.addView(btnSend)
        root.addView(bottomBar)

        setContentView(root)

        loadPostDetail(commentContainer)
    }
    private fun triggerAiReplyToComment(commentContainer: LinearLayout, userComment: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pref = getSharedPreferences("AppConfig", MODE_PRIVATE)
                var apiUrl = pref.getString("apiUrl", "") ?: ""
                val apiKey = pref.getString("apiKey", "") ?: ""
                val modelName =
                    pref.getString(
                        "modelName",
                        ""
                    )?.ifBlank { "gpt-4o" }
                        ?: "gpt-4o"
                if (apiUrl.isEmpty() || apiKey.isEmpty()) return@launch
                while (apiUrl.endsWith("/")) apiUrl = apiUrl.dropLast(1)
                if (!apiUrl.endsWith("/chat/completions")) {
                    apiUrl = if (apiUrl.endsWith("/v1")) "$apiUrl/chat/completions" else "$apiUrl/v1/chat/completions"
                }

                val db = DatabaseHelper(this@ForumPostDetailActivity).readableDatabase
                val postCursor = db.rawQuery("SELECT title, content FROM ForumPosts WHERE id=?", arrayOf(postId.toString()))
                var postTitle = ""; var postContent = ""
                if (postCursor.moveToFirst()) {
                    postTitle = postCursor.getString(0) ?: ""
                    postContent = postCursor.getString(1) ?: ""
                }
                postCursor.close()

                val replyCount = (1..2).random()
                val prompt = """
论坛帖子标题：$postTitle
帖子内容：$postContent
用户刚刚发了评论：「$userComment」

请以真实网友身份生成 $replyCount 条回复这条评论的评论。
网名要像真实网友（不能用真实姓名），口语化，有生活气息。
严格按JSON数组输出：
[{"authorName":"网名","authorId":"virtual_xxx","content":"评论内容","likeCount":数字}]
""".trimIndent()

                val bodyJson = JSONObject().apply {
                    put("model", modelName)
                    put("temperature", 0.9)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                    })
                }
                val request = Request.Builder().url(apiUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(bodyJson.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()
                val response = OkHttpClient.Builder()
                    .connectTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
                    .build().newCall(request).execute()
                val body = response.body?.string() ?: return@launch
                if (!response.isSuccessful) return@launch

                val cleanJson = JSONObject(body).getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").getString("content")
                    .trim().replace(Regex("```json|```"), "").trim()
                val jsonArray = JSONArray(cleanJson)
                val writeDb = DatabaseHelper(this@ForumPostDetailActivity).writableDatabase
                val now = System.currentTimeMillis()
                val newComments = mutableListOf<ForumComment>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val cv = ContentValues().apply {
                        put("postId", postId)
                        put("authorId", obj.optString("authorId", "virtual_anon"))
                        put("authorName", obj.optString("authorName", "匿名"))
                        put("content", obj.optString("content"))
                        put("likeCount", obj.optInt("likeCount", 1))
                        put("timestamp", now + i)
                    }
                    val newId = writeDb.insert("ForumComments", null, cv)
                    newComments.add(ForumComment(newId, postId, obj.optString("authorId"), obj.optString("authorName", "匿名"), obj.optString("content"), obj.optInt("likeCount", 1), now + i))
                }
                withContext(Dispatchers.Main) {

                    commentList.addAll(newComments)

                    renderComments(
                        commentContainer,
                        commentList
                    )
                }
            } catch (_: Exception) {}
        }
    }
    private fun loadPostDetail(commentContainer: LinearLayout) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = DatabaseHelper(this@ForumPostDetailActivity).readableDatabase
                val cursor = db.rawQuery(
                    "SELECT title, content, authorId, authorName, likeCount, timestamp FROM ForumPosts WHERE id=?",
                    arrayOf(postId.toString())
                )
                if (cursor.moveToFirst()) {
                    val title = cursor.getString(0) ?: ""
                    val content = cursor.getString(1) ?: ""
                    val authorName = cursor.getString(3) ?: ""
                    likeCount = cursor.getInt(4)
                    val ts = cursor.getLong(5)
                    val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
                    cursor.close()

                    withContext(Dispatchers.Main) {
                        tvAuthor.text = "🙂 $authorName  •  $time"
                        tvPostTitle.text = title
                        tvPostContent.text = content
                        tvLike.text = "👍 $likeCount  点个赞"
                    }
                } else {
                    cursor.close()
                }

                // 读评论
                val commentCursor = db.rawQuery(
                    "SELECT id, postId, authorId, authorName, content, likeCount, timestamp FROM ForumComments WHERE postId=? ORDER BY timestamp ASC",
                    arrayOf(postId.toString())
                )
                val comments = mutableListOf<ForumComment>()
                while (commentCursor.moveToNext()) {
                    comments.add(ForumComment(
                        id = commentCursor.getLong(0),
                        postId = commentCursor.getLong(1),
                        authorId = commentCursor.getString(2) ?: "",
                        authorName = commentCursor.getString(3) ?: "",
                        content = commentCursor.getString(4) ?: "",
                        likeCount = commentCursor.getInt(5),
                        timestamp = commentCursor.getLong(6)
                    ))
                }
                commentCursor.close()

                // 评论不足5条，AI生成
                if (comments.size < 5) {
                    val postCursor = db.rawQuery(
                        "SELECT title, content FROM ForumPosts WHERE id=?",
                        arrayOf(postId.toString())
                    )
                    if (postCursor.moveToFirst()) {
                        val title = postCursor.getString(0) ?: ""
                        val content = postCursor.getString(1) ?: ""
                        postCursor.close()
                        generateAiComments(title, content, 5 - comments.size, comments)
                    } else {
                        postCursor.close()
                    }
                }

                withContext(Dispatchers.Main) {
                    renderComments(commentContainer, comments)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ForumPostDetailActivity, "加载失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun generateAiComments(
        postTitle: String,
        postContent: String,
        count: Int,
        existingComments: MutableList<ForumComment>
    ) {
        try {
            val pref = getSharedPreferences("AppConfig", MODE_PRIVATE)
            var apiUrl = pref.getString("apiUrl", "") ?: ""
            val apiKey = pref.getString("apiKey", "") ?: ""
            val modelName = pref.getString("modelName", "gemini-2.5-pro") ?: "gemini-2.5-pro"
            if (apiUrl.isEmpty() || apiKey.isEmpty()) return
            while (apiUrl.endsWith("/")) apiUrl = apiUrl.dropLast(1)
            if (!apiUrl.endsWith("/chat/completions")) {
                apiUrl = if (apiUrl.endsWith("/v1")) "$apiUrl/chat/completions" else "$apiUrl/v1/chat/completions"
            }

            val db = DatabaseHelper(this@ForumPostDetailActivity).readableDatabase
            val contactsCursor =
                db.rawQuery(
                    """
        SELECT realName, identityInfo
        FROM Contacts
        WHERE userId IS NOT NULL
          AND TRIM(userId) <> ''
          AND id IN (
              SELECT MAX(id)
              FROM Contacts
              WHERE userId IS NOT NULL
                AND TRIM(userId) <> ''
              GROUP BY userId
          )
        """.trimIndent(),
                    null
                )
            val contacts = mutableListOf<Pair<String, String>>()
            while (contactsCursor.moveToNext()) {
                contacts.add(Pair(contactsCursor.getString(0) ?: "", contactsCursor.getString(1) ?: ""))
            }
            contactsCursor.close()

            val contactsInfo = contacts.joinToString("\n") { "- ${it.first}：${it.second.take(40)}" }

            val prompt = """
你正在模拟真实社区论坛的评论区。

以下是被评论的帖子：
标题：$postTitle
内容：$postContent

【已知角色（绝对保密，禁止在评论里直接使用这些真实名字）】：
$contactsInfo

请生成 $count 条真实感极强的评论。

【评论人规则】：
1. 评论人ID和网名必须像真实网友，例如：不想上班的打工人、路过的小透明、深夜emo中、摸鱼达人、just_a_lurker、夜猫子233 等风格
2. 严禁直接使用角色真实名字
3. 角色可以用马甲评论，但马甲ID完全看不出是谁
4. 约一半评论由路人发，一半由角色马甲发

【内容规则】：
1. 语气要有生活气息，口语化，可以有语气词、emoji、缩写
2. 评论长短不一，有的只有一两句，有的稍长
3. 评论角度多样：附和、反驳、调侃、共情、提问、分享经历等
4. 点赞数随机分布，大部分1-50，少数可以到200
5. 禁止任何角色配对、CP内容

严格按JSON数组格式输出，只输出JSON：
[
  {
    "authorName": "真实感网名",
    "authorId": "virtual_xxx",
    "content": "评论内容",
    "likeCount": 数字
  }
]
""".trimIndent()

            val bodyJson = JSONObject().apply {
                put("model", modelName)
                put("temperature", 0.9)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }

            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(bodyJson.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            val response = OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build().newCall(request).execute()

            val body = response.body?.string() ?: return
            if (!response.isSuccessful) return

            val content = JSONObject(body).getJSONArray("choices")
                .getJSONObject(0).getJSONObject("message").getString("content").trim()
            val cleanJson =
                content.replace(
                    Regex("```json|```"),
                    ""
                ).trim()

            val start = cleanJson.indexOf('[')
            val end = cleanJson.lastIndexOf(']')

            if (start == -1 || end == -1) {
                return
            }

            val jsonArray =
                JSONArray(
                    cleanJson.substring(
                        start,
                        end + 1
                    )
                )

            val writeDb = DatabaseHelper(this@ForumPostDetailActivity).writableDatabase
            val now = System.currentTimeMillis()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val cv = ContentValues().apply {
                    put("postId", postId)
                    put("authorId", obj.optString("authorId", "virtual_anon"))
                    put("authorName", obj.optString("authorName", "匿名"))
                    put("content", obj.optString("content"))
                    put("likeCount", obj.optInt("likeCount", 1))
                    put("timestamp", now - (0..3600000L).random())
                }
                val newId = writeDb.insert("ForumComments", null, cv)
                existingComments.add(ForumComment(
                    id = newId,
                    postId = postId,
                    authorId = obj.optString("authorId"),
                    authorName = obj.optString("authorName", "匿名"),
                    content = obj.optString("content"),
                    likeCount = obj.optInt("likeCount", 1),
                    timestamp = now
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun renderComments(container: LinearLayout, comments: List<ForumComment>) {
        container.removeAllViews()
        comments.forEach { comment ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = dp(1) }
            }
            val tvName = TextView(this).apply {
                text = "🙂 ${comment.authorName}"
                textSize = 13f
                setTextColor(Color.parseColor("#07C160"))
            }
            val tvContent = TextView(this).apply {
                text = comment.content
                textSize = 14f
                setTextColor(Color.parseColor("#333333"))
                setPadding(0, dp(6), 0, dp(6))
                setLineSpacing(4f, 1.2f)
            }
            val tvTime = TextView(this).apply {
                val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(comment.timestamp))
                text = "👍 ${comment.likeCount}  •  $time"
                textSize = 11f
                setTextColor(Color.parseColor("#AAAAAA"))
            }
            card.addView(tvName)
            card.addView(tvContent)
            card.addView(tvTime)
            container.addView(card)
        }
    }

    private fun sendUserComment(commentContainer: LinearLayout) {
        val text = etComment.text.toString().trim()
        if (text.isEmpty()) return

        try {
            val pref = getSharedPreferences("AppConfig", MODE_PRIVATE)
            val myName = pref.getString("myName", "我") ?: "我"
            val myId = pref.getString("myId", "me") ?: "me"
            val now = System.currentTimeMillis()

            val db = DatabaseHelper(this).writableDatabase
            val cv = ContentValues().apply {
                put("postId", postId)
                put("authorId", myId)
                put("authorName", myName)
                put("content", text)
                put("likeCount", 0)
                put("timestamp", now)
            }
            db.insert("ForumComments", null, cv)

            val newComment = ForumComment(
                id = now,
                postId = postId,
                authorId = myId,
                authorName = myName,
                content = text,
                likeCount = 0,
                timestamp = now
            )
            commentList.add(newComment)

            renderComments(
                commentContainer,
                commentList
            )
            etComment.setText("")
            triggerAiReplyToComment(commentContainer, text)
            Toast.makeText(this, "评论成功", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "评论失败", Toast.LENGTH_SHORT).show()
        }
    }
}
