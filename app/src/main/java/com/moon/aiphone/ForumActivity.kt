package com.moon.aiphone

import android.content.Intent
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
import androidx.lifecycle.lifecycleScope

data class ForumPost(
    val id: Long,
    val boardId: String,
    val title: String,
    val content: String,
    val authorId: String,
    val authorName: String,
    val isAlias: Boolean,
    val likeCount: Int,
    val timestamp: Long
)

class ForumActivity : AppCompatActivity() {

    private val boards = listOf("近期热门", "日常吐槽区", "八卦闲聊区", "情报交流区", "袒露心声区")
    private val boardIds = listOf("hot", "daily", "gossip", "info", "heart")
    private var currentBoard = "hot"
    private val postList = mutableListOf<ForumPost>()
    private lateinit var adapter: ForumPostAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingBar: ProgressBar
    private val tabViews = mutableListOf<TextView>()

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            text = "论坛"
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
        val btnRefresh = TextView(this).apply {
            text = "🔄"
            textSize = 20f
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            ).also {
                it.addRule(RelativeLayout.ALIGN_PARENT_END)
                it.addRule(RelativeLayout.CENTER_VERTICAL)
            }
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                // 清空当前板块帖子，强制重新生成
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val db = DatabaseHelper(this@ForumActivity).writableDatabase
                        if (currentBoard == "hot") {
                            db.delete("ForumPosts", null, null)
                        } else {
                            db.delete(
                                "ForumPosts",
                                "boardId=?",
                                arrayOf(currentBoard)
                            )
                        }
                    } catch (e: Exception) {}
                    withContext(Dispatchers.Main) {
                        loadPosts()
                    }
                }
            }
        }
        topBar.addView(tvTitle)
        topBar.addView(btnBack)
        topBar.addView(btnRefresh)
        root.addView(topBar)

        // 板块tab
        val tabScroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
            setBackgroundColor(Color.WHITE)
            isHorizontalScrollBarEnabled = false
        }
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), 0)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        boards.forEachIndexed { index, name ->
            val tab = TextView(this).apply {
                text = name
                textSize = 14f
                setPadding(dp(16), 0, dp(16), 0)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                setOnClickListener {
                    currentBoard = boardIds[index]
                    updateTabs()
                    loadPosts()
                }
            }
            tabViews.add(tab)
            tabRow.addView(tab)
        }
        tabScroll.addView(tabRow)
        root.addView(tabScroll)

        // 加载条
        loadingBar = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }
        root.addView(loadingBar)

        // 帖子列表
        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ForumActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        adapter = ForumPostAdapter(postList) { post ->
            val intent = Intent(this, ForumPostDetailActivity::class.java)
            intent.putExtra("POST_ID", post.id)
            startActivity(intent)
        }
        recyclerView.adapter = adapter
        root.addView(recyclerView)

        // 右下角发帖按钮（悬浮）
        val fab = TextView(this).apply {
            text = "✏️"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#07C160"))
                cornerRadius = dp(28).toFloat()
            }
            elevation = 8f
            setOnClickListener {
                startActivity(Intent(this@ForumActivity, ForumPostEditorActivity::class.java))
            }
        }
        val fabParams = android.widget.FrameLayout.LayoutParams(dp(56), dp(56)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(0, 0, dp(24), dp(24))
        }
        val frame = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        frame.addView(root)
        frame.addView(fab, fabParams)
        setContentView(frame)

        updateTabs()
        loadPosts()
    }

    override fun onResume() {
        super.onResume()
        loadPosts()
    }

    private fun updateTabs() {
        tabViews.forEachIndexed { index, tv ->
            if (boardIds[index] == currentBoard) {
                tv.setTextColor(Color.parseColor("#07C160"))
                tv.setTypeface(null, Typeface.BOLD)
            } else {
                tv.setTextColor(Color.parseColor("#555555"))
                tv.setTypeface(null, Typeface.NORMAL)
            }
        }
    }

    private fun loadPosts() {
        postList.clear()
        adapter.notifyDataSetChanged()
        loadingBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = DatabaseHelper(this@ForumActivity).readableDatabase

                // 读数据库已有帖子
                val query = if (currentBoard == "hot") {
                    "SELECT id, boardId, title, content, authorId, authorName, isAlias, likeCount, timestamp FROM ForumPosts ORDER BY likeCount DESC, timestamp DESC LIMIT 20"
                } else {
                    "SELECT id, boardId, title, content, authorId, authorName, isAlias, likeCount, timestamp FROM ForumPosts WHERE boardId=? ORDER BY timestamp DESC LIMIT 20"
                }
                val cursor = if (currentBoard == "hot") {
                    db.rawQuery(query, null)
                } else {
                    db.rawQuery(query, arrayOf(currentBoard))
                }

                val existingPosts = mutableListOf<ForumPost>()
                while (cursor.moveToNext()) {
                    existingPosts.add(
                        ForumPost(
                            id = cursor.getLong(0),
                            boardId = cursor.getString(1) ?: "",
                            title = cursor.getString(2) ?: "",
                            content = cursor.getString(3) ?: "",
                            authorId = cursor.getString(4) ?: "",
                            authorName = cursor.getString(5) ?: "",
                            isAlias = cursor.getInt(6) == 1,
                            likeCount = cursor.getInt(7),
                            timestamp = cursor.getLong(8)
                        )
                    )
                }
                cursor.close()

                // 如果帖子不足5条，AI生成补充
                if (existingPosts.size < 5) {
                    generateAiPosts(currentBoard, 5 - existingPosts.size)
                    // 重新读取
                    val cursor2 = if (currentBoard == "hot") {
                        db.rawQuery("SELECT id, boardId, title, content, authorId, authorName, isAlias, likeCount, timestamp FROM ForumPosts ORDER BY likeCount DESC, timestamp DESC LIMIT 20", null)
                    } else {
                        db.rawQuery("SELECT id, boardId, title, content, authorId, authorName, isAlias, likeCount, timestamp FROM ForumPosts WHERE boardId=? ORDER BY timestamp DESC LIMIT 20", arrayOf(currentBoard))
                    }
                    while (cursor2.moveToNext()) {
                        existingPosts.add(
                            ForumPost(
                                id = cursor2.getLong(0),
                                boardId = cursor2.getString(1) ?: "",
                                title = cursor2.getString(2) ?: "",
                                content = cursor2.getString(3) ?: "",
                                authorId = cursor2.getString(4) ?: "",
                                authorName = cursor2.getString(5) ?: "",
                                isAlias = cursor2.getInt(6) == 1,
                                likeCount = cursor2.getInt(7),
                                timestamp = cursor2.getLong(8)
                            )
                        )
                    }
                    cursor2.close()
                }

                withContext(Dispatchers.Main) {
                    postList.addAll(existingPosts.distinctBy { it.id })
                    adapter.notifyDataSetChanged()
                    loadingBar.visibility = View.GONE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadingBar.visibility = View.GONE
                    Toast.makeText(this@ForumActivity, "加载失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun generateAiPosts(boardId: String, count: Int) {
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
            if (apiUrl.isEmpty() || apiKey.isEmpty()) return
            while (apiUrl.endsWith("/")) apiUrl = apiUrl.dropLast(1)
            if (!apiUrl.endsWith("/chat/completions")) {
                apiUrl = if (apiUrl.endsWith("/v1")) "$apiUrl/chat/completions" else "$apiUrl/v1/chat/completions"
            }

            val db = DatabaseHelper(this@ForumActivity).readableDatabase
            val contactsCursor = db.rawQuery(
                """
    SELECT userId, realName, identityInfo
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
            val contacts = mutableListOf<Triple<String, String, String>>()
            while (contactsCursor.moveToNext()) {
                contacts.add(Triple(
                    contactsCursor.getString(0) ?: "",
                    contactsCursor.getString(1) ?: "",
                    contactsCursor.getString(2) ?: ""
                ))
            }
            contactsCursor.close()

            val boardName = boards[boardIds.indexOf(boardId)]
            val contactsInfo = contacts.joinToString("\n") { "- ${it.second}：${it.third.take(50)}" }
            val nowTime = SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.CHINA).format(Date())

            val prompt = """
你正在模拟一个真实社区论坛的【$boardName】板块。
【当前时间】：$nowTime
【已知角色（绝对保密，禁止在帖子里直接使用这些真实名字）】：
$contactsInfo

请生成 $count 条真实感极强的论坛帖子。

【发帖人规则】：
1. 每个发帖人必须有一个像真实网友的ID，例如：萌萌的小饼干、深夜码字人、just_passing_by、柠檬味的风、摸鱼专家2077、夜猫子不睡觉 等风格
2. 严禁直接使用角色真实名字作为ID或网名
3. 角色可以用马甲发帖，但马甲ID必须完全看不出是谁
4. 约一半帖子由路人网友发，一半由角色马甲发
5. isAlias为true表示角色马甲，false表示路人

【内容规则】：
1. 内容符合【$boardName】的板块氛围
2. 语气要有生活气息，像真实网友：可以有错别字、缩写、emoji、口语化表达
3. 标题简短接地气，正文80-150字，不要太工整
4. 内容要多样：可以是吐槽、分享、求助、炫耀、感慨、碎碎念等
5. 点赞数随机分布，大部分10-100，少数可以到500
6. 时间戳要有差异，不要都是同一时间发的

【绝对禁止】：
- 禁止出现角色的真实名字
- 禁止任何角色配对、CP、恋爱暗示内容
- 禁止内容太过完整通顺，真实网帖是有点随性的

严格按以下JSON格式输出，只输出JSON数组：
[
  {
    "title": "帖子标题",
    "content": "帖子正文",
    "authorId": "virtual_xxx",
    "authorName": "真实感网名",
    "isAlias": true或false,
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

            val response = Http.client.newBuilder()
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

            if (start == -1 || end == -1 || end <= start) {
                return
            }

            val jsonArray =
                JSONArray(
                    cleanJson.substring(start, end + 1)
                )
            val writeDb = DatabaseHelper(this@ForumActivity).writableDatabase
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val cv = android.content.ContentValues().apply {
                    put("boardId", boardId)
                    put("title", obj.optString("title"))
                    put("content", obj.optString("content"))
                    put("authorId", obj.optString("authorId"))
                    put("authorName", obj.optString("authorName"))
                    put("isAlias", if (obj.optBoolean("isAlias")) 1 else 0)
                    put("likeCount", obj.optInt("likeCount", 10))
                    put("timestamp", System.currentTimeMillis() - (0..86400000L).random())
                }
                writeDb.insert("ForumPosts", null, cv)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

class ForumPostAdapter(
    private val list: MutableList<ForumPost>,
    private val onClick: (ForumPost) -> Unit
) : RecyclerView.Adapter<ForumPostAdapter.VH>() {

    private fun dp(context: android.content.Context, n: Int) =
        (n * context.resources.displayMetrics.density).toInt()

    inner class VH(val root: LinearLayout) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(ctx, 8) }
        }
        return VH(card)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val post = list[position]
        val ctx = holder.root.context
        holder.root.removeAllViews()

        val tvAuthor = TextView(ctx).apply {
            text = "🙂 ${post.authorName}"
            textSize = 12f
            setTextColor(Color.parseColor("#999999"))
        }
        val tvTitle = TextView(ctx).apply {
            text = post.title
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111111"))
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 4))
        }
        val tvContent = TextView(ctx).apply {
            text = post.content
            textSize = 13f
            setTextColor(Color.parseColor("#555555"))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val tvBottom = TextView(ctx).apply {
            val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(post.timestamp))
            text = "👍 ${post.likeCount}  •  $time"
            textSize = 11f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, dp(ctx, 8), 0, 0)
        }

        holder.root.addView(tvAuthor)
        holder.root.addView(tvTitle)
        holder.root.addView(tvContent)
        holder.root.addView(tvBottom)
        holder.root.setOnClickListener { onClick(post) }
    }

    override fun getItemCount() = list.size
}