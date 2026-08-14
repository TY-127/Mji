package com.moon.aiphone

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

data class DreamPost(
    val id: Int,
    val authorId: String,
    val authorAlias: String,
    val boardId: String,
    val content: String,
    val imagePath: String,
    val grade: Int,
    val likeCount: Int,
    val timestamp: Long
)

class DreamHouseActivity : AppCompatActivity() {

    private val boards = listOf("同人文", "同人图", "交流", "深渊")
    private var currentBoard = "同人文"
    private val dbHelper by lazy { DatabaseHelper(this) }
    private var isR18Unlocked = false
    private val postList = mutableListOf<DreamPost>()
    private lateinit var adapter: DreamPostAdapter
    private lateinit var rvPosts: RecyclerView
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        try {
            val db = DatabaseHelper(this).writableDatabase
            db.execSQL("CREATE TABLE IF NOT EXISTS DreamHousePosts (id INTEGER PRIMARY KEY AUTOINCREMENT, authorId TEXT, authorAlias TEXT, boardId TEXT, content TEXT, imagePath TEXT, grade INTEGER DEFAULT 0, likeCount INTEGER DEFAULT 0, timestamp INTEGER)")
            db.execSQL("CREATE TABLE IF NOT EXISTS DreamHouseComments (id INTEGER PRIMARY KEY AUTOINCREMENT, postId INTEGER, authorId TEXT, authorAlias TEXT, content TEXT, timestamp INTEGER)")
            db.execSQL("CREATE TABLE IF NOT EXISTS DreamHouseAlias (id INTEGER PRIMARY KEY AUTOINCREMENT, aiId TEXT UNIQUE, alias TEXT)")
            db.execSQL("CREATE TABLE IF NOT EXISTS UserAppearance (id INTEGER PRIMARY KEY AUTOINCREMENT, description TEXT, updatedAt INTEGER)")
        } catch (_: Exception) {}
        try { DatabaseHelper(this).writableDatabase.execSQL("ALTER TABLE DreamHousePosts ADD COLUMN imagePath TEXT DEFAULT ''") } catch (_: Exception) {}
        try { DatabaseHelper(this).writableDatabase.execSQL("ALTER TABLE DreamHousePosts ADD COLUMN collectCount INTEGER DEFAULT 0") } catch (_: Exception) {}
        try { DatabaseHelper(this).writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS DreamHouseReactions (id INTEGER PRIMARY KEY AUTOINCREMENT, postId INTEGER, authorId TEXT, authorAlias TEXT, content TEXT, reactionType TEXT, timestamp INTEGER)") } catch (_: Exception) {}
        try { DatabaseHelper(this).writableDatabase.execSQL("CREATE TABLE IF NOT EXISTS UserProfile (id INTEGER PRIMARY KEY AUTOINCREMENT, fieldKey TEXT UNIQUE, fieldValue TEXT)") } catch (_: Exception) {}

        buildUI()
        checkAndInitAliases()
        loadPosts()
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#0D0D1A"))
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(40), dp(16), dp(12))
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#12122A"))
        }
        val btnBack = TextView(this).apply {
            text = "←"; textSize = 22f
            setTextColor(android.graphics.Color.parseColor("#C084FC"))
            setPadding(0, 0, dp(16), 0)
            setOnClickListener { finish() }
        }
        val tvTitle = TextView(this).apply {
            text = "✦ 梦男之家 ✦"; textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            gravity = Gravity.CENTER
        }
        val btnMyProfile = TextView(this).apply {
            text = "我的档案"; textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#C084FC"))
            setOnClickListener {
                startActivity(Intent(this@DreamHouseActivity, UserProfileActivity::class.java))
            }
        }
        topBar.addView(btnBack)
        topBar.addView(tvTitle)
        topBar.addView(btnMyProfile)
        root.addView(topBar)

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(android.graphics.Color.parseColor("#12122A"))
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        boards.forEach { board ->
            val tab = TextView(this).apply {
                text = if (board == "深渊") "🔞 深渊" else board
                textSize = 14f
                setPadding(dp(16), dp(8), dp(16), dp(8))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setTextColor(if (board == currentBoard)
                    android.graphics.Color.parseColor("#C084FC")
                else android.graphics.Color.parseColor("#666688"))
                background = if (board == currentBoard)
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#1E1E3F"))
                        cornerRadius = dp(20).toFloat()
                    }
                else null
                setOnClickListener {
                    if (board == "深渊" && !isR18Unlocked) {
                        showR18Gate()
                        return@setOnClickListener
                    }
                    currentBoard = board
                    buildUI()
                    loadPosts()
                }
            }
            tabRow.addView(tab)
        }
        root.addView(tabRow)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#0D0D1A"))
        }
        val btnGenerate = TextView(this).apply {
            text = "✦ 召唤创作"
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#6D28D9"))
                cornerRadius = dp(20).toFloat()
            }
            setOnClickListener { triggerAiCreation() }
        }
        val btnGenImg = TextView(this).apply {
            text = "🎨 生同人图"
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#BE185D"))
                cornerRadius = dp(20).toFloat()
            }
            setOnClickListener { triggerFanArtGeneration() }
        }
        actionRow.addView(btnGenerate)
        actionRow.addView(btnGenImg)
        root.addView(actionRow)

        rvPosts = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@DreamHouseActivity)
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        adapter = DreamPostAdapter(postList) { post -> showPostDetail(post) }
        rvPosts.adapter = adapter
        root.addView(rvPosts)

        setContentView(root)
    }

    private fun showR18Gate() {
        AlertDialog.Builder(this)
            .setTitle("🔞 深渊入口")
            .setMessage("深渊板块包含成人向内容（R18）。\n\n请确认你已满18岁且自愿进入。")
            .setPositiveButton("我已满18岁，进入") { _, _ ->
                isR18Unlocked = true
                currentBoard = "深渊"
                buildUI()
                loadPosts()
            }
            .setNegativeButton("离开", null)
            .show()
    }

    private fun checkAndInitAliases() {
        Thread {
            try {
                val db = DatabaseHelper(this).writableDatabase
                val contacts = mutableListOf<Pair<String, String>>()
                db.rawQuery("SELECT userId, realName FROM Contacts", null).use { c ->
                    while (c.moveToNext())
                        contacts.add(Pair(c.getString(0), c.getString(1)))
                }
                contacts.forEach { (aiId, _) ->
                    val exists = db.rawQuery(
                        "SELECT id FROM DreamHouseAlias WHERE aiId=?", arrayOf(aiId)
                    ).use { c -> c.moveToFirst() }
                    if (!exists) {
                        val alias = generateAlias()
                        db.insert("DreamHouseAlias", null, ContentValues().apply {
                            put("aiId", aiId)
                            put("alias", alias)
                        })
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun generateAlias(): String {
        val prefixes = listOf("星尘", "月影", "深海", "暗夜", "迷雾", "晨曦", "流光", "暮色", "烟雨", "霜降")
        val suffixes = listOf("的梦", "过客", "漫游者", "观察员", "低语者", "守望者", "追光者", "迷途者")
        return "${prefixes.random()}${suffixes.random()}"
    }

    private fun getAlias(aiId: String): String {
        return try {
            DatabaseHelper(this).readableDatabase
                .rawQuery("SELECT alias FROM DreamHouseAlias WHERE aiId=?", arrayOf(aiId))
                .use { c -> if (c.moveToFirst()) c.getString(0) ?: "匿名" else "匿名" }
        } catch (_: Exception) { "匿名" }
    }

    private fun loadPosts() {
        postList.clear()
        try {
            val db = DatabaseHelper(this).readableDatabase
            val gradeFilter = if (currentBoard == "深渊") "AND grade >= 1" else "AND grade = 0"
            db.rawQuery(
                "SELECT * FROM DreamHousePosts WHERE boardId=? $gradeFilter ORDER BY timestamp DESC",
                arrayOf(currentBoard)
            ).use { c ->
                while (c.moveToNext()) {
                    postList.add(DreamPost(
                        // ✅ 1. 使用 getSafeInt 安全读取 Int 字段
                        id = c.getSafeInt("id"),

                        // ✅ 2. 使用 getSafeString 安全读取 String 字段
                        authorId = c.getSafeString("authorId"),
                        authorAlias = c.getSafeString("authorAlias").ifEmpty { "神秘人" },
                        boardId = c.getSafeString("boardId"),
                        content = c.getSafeString("content"),
                        imagePath = c.getSafeString("imagePath"),

                        // ✅ 3. 安全读取剩下的 Int 字段
                        grade = c.getSafeInt("grade"),
                        likeCount = c.getSafeInt("likeCount"),

                        // ✅ 4. 使用 getSafeLong 安全读取时间戳（Long）
                        timestamp = c.getSafeLong("timestamp")
                    ))
                } // 👈 记得补上循环结束的大括号（如果原本就在下面可以不管）

            }
        } catch (_: Exception) {}
        mainHandler.post { adapter.notifyDataSetChanged() }
    }

    private fun triggerAiCreation() {
        val appearance = UserProfileManager.getAppearanceOnly(this)
        if (appearance.isEmpty() && currentBoard != "同人文") {
            Toast.makeText(this, "请先在「我的档案」填写外形描述", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this@DreamHouseActivity, UserProfileActivity::class.java))
            return
        }

        Thread {
            try {
                val db = DatabaseHelper(this).readableDatabase
                val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                var apiUrl = (pref.getString("apiUrl", "") ?: "").trimEnd('/')
                val apiKey = pref.getString("apiKey", "") ?: ""
                val model = pref.getString(
                    "modelName",
                    ""
                )?.ifBlank { "gpt-4o" } ?: "gpt-4o"
                if (!apiUrl.endsWith("/chat/completions"))
                    apiUrl += if (apiUrl.contains("/v1")) "/chat/completions" else "/v1/chat/completions"

                val useNpc = (1..100).random() > 50
                val authorId: String
                val authorAlias: String
                val authorPersona: String

                if (useNpc) {
                    authorId = "npc_${System.currentTimeMillis()}"
                    authorAlias = generateAlias()
                    authorPersona = "一个对主角有好感的路人，性格随机"
                } else {
                    var id = ""; var alias = ""; var persona = ""
                    if (id.isBlank()) {
                        id = "npc_${System.currentTimeMillis()}"
                        alias = generateAlias()
                        persona = "神秘观察者"
                    }
                    alias = getAlias(id)
                    authorId = id; authorAlias = alias; authorPersona = persona
                }

                val prompt = buildCreationPrompt(currentBoard, authorPersona, appearance, authorId)

                val bodyJson = JSONObject().apply {
                    put("model", model)
                    put("temperature", 0.9)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                    })
                }

                val resp = Http.client.newBuilder()
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build().newCall(
                        Request.Builder().url(apiUrl)
                            .addHeader("Authorization", "Bearer $apiKey")
                            .post(bodyJson.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                            .build()
                    ).execute()

                val content = JSONObject(resp.body?.string() ?: return@Thread)
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()

                if (content.isEmpty()) return@Thread

                val grade = if (currentBoard == "深渊") 1 else 0
                val newPostId = DatabaseHelper(this).writableDatabase.insert(
                    "DreamHousePosts", null, ContentValues().apply {
                        put("authorId", authorId)
                        put("authorAlias", authorAlias)
                        put("boardId", currentBoard)
                        put("content", content)
                        put("imagePath", "")
                        put("grade", grade)
                        put("likeCount", 0)
                        put("timestamp", System.currentTimeMillis())
                    }
                )
                generateReactions(DreamPost(
                    id = newPostId.toInt(),
                    authorId = authorId,
                    authorAlias = authorAlias,
                    boardId = currentBoard,
                    content = content,
                    imagePath = "",
                    grade = grade,
                    likeCount = 0,
                    timestamp = System.currentTimeMillis()
                ))

                mainHandler.post {
                    Toast.makeText(this, "✦ $authorAlias 发布了新内容！", Toast.LENGTH_SHORT).show()
                    loadPosts()
                }
            } catch (e: Exception) {
                android.util.Log.e("DreamHouse", "创作失败: ${e.message}", e)
                mainHandler.post { Toast.makeText(this, "创作失败：${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun buildCreationPrompt(board: String, persona: String, appearance: String, aiId: String = ""): String {
        val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val aiLang = if (aiId.isNotEmpty()) pref.getString("aiLang_$aiId", "默认 (中文)") ?: "默认 (中文)" else "默认 (中文)"
        val isChinese = aiLang == "默认 (中文)"
        val dialogLangRule = if (isChinese) "" else "【对话语言规则】：文中所有人物的对话台词必须用${aiLang}写，并在每句对话后面紧跟括号加中文翻译，格式：「${aiLang}台词」（中文翻译）。旁白叙述全部用中文。"
        val userDesc = if (appearance.isNotEmpty()) "你深深迷恋的那个人（主角，女性）的外形：$appearance" else "你深深迷恋的那个人是主角（女性）"
        return when (board) {
            "同人文" -> """
你是一个匿名同人文作者，人设：$persona。
【重要世界观】：你是主角的追求者/崇拜者，主角才是这个空间里的绝对主角和被爱的中心。
【主角性别】：女性，全程用"她/她的"称呼主角，严禁将主角描述或暗示为男性。
$userDesc
请以第一人称（你自己的视角），创作一篇关于你和主角的短篇同人文（300-500字）。
要求：
1. 你是爱慕者，主角是被爱的对象，全程以主角为中心展开
2. 充满对主角的迷恋、占有欲或温柔
3. 禁止把自己写成主角，禁止夸自己，禁止「雌竞」式内容
4. 禁止在正文中出现任何具体数字（如身高体重），只用文学化方式描述外形
5. 场景自由发挥，直接输出正文，不要标题不要说明
【文风规范——最高优先级，凌驾一切】

语言质地：以名词和动词为骨架，削减形容词与副词。不说"她很悲伤"，写她做了什么。意象优先——角色的心境借由器物、景致、气味来烘托，不直接点破。

叙事距离：叙事者保持疏离的旁观姿态，如隔薄雾观察，不直接介入角色内心。情节推进缓慢，重心在氛围营造与情绪沉淀，不制造强烈戏剧冲突。

情感处理：情感是水面之下的潜流，从不喷涌。巨大的悲喜往往通过极平淡的日常行为表现——她听说消息后沉默了很久，然后起身去厨房淘米切菜。对话简短，充满潜台词，沉默与停顿本身就是表达。

句式节奏：长短句交替，善用短句制造停顿。单独成行的短句有重量。善用留白——说七分，留三分给读者。

具体禁令：
- 禁止：极其、无比、心跳骤停、命运齿轮、刻进DNA、难以言喻、邪魅一笑、眸色一暗、四肢百骸
- 禁止直接抒情：禁写"我爱你""我被感动了""我心里涌起一阵暖意"
- 禁止：任何形式的网文套路结构和爽文节奏

结尾：意犹未尽。可以是一个动作，一句话，一个场景，一个细节。不总结，不点题，不升华主题。结尾像一扇半开的门。

参照气质：白先勇的瑰丽沧桑、汪曾祺的闲适冲淡、沈从文的山水人情、阿城的简劲克制。学习其精神，不模仿其句式。
$dialogLangRule
            """.trimIndent()

            "交流" -> """
你是一个匿名崇拜者，人设：$persona。
【重要世界观】：主角是这个空间里所有人都深深迷恋的存在，你们聚在一起讨论的核心永远是主角。
【主角性别】：女性，全程用"她/她的"称呼主角，严禁将主角描述为男性。
$userDesc
请发一条关于主角的讨论帖（100-200字）。
要求：
1. 内容围绕主角展开，可以是脑补主角的日常、讨论主角某个特征、分享对主角的感受
2. 可以流露出对主角的迷恋、羡慕、或争宠心态
3. 禁止夸自己，禁止把焦点放在自己身上，主角永远是主角
4. 直接输出帖子内容
            """.trimIndent()

            "深渊" -> """
你是一个匿名崇拜者，人设：$persona。
【重要世界观】：主角是你无法抗拒的存在，你对她有强烈的情感和占有欲。这是R18成人向创作空间，允许露骨的亲密描写。
【主角性别】：女性，全程用"她/她的"称呼主角，主角为被插入方，严禁将主角描述为男性。
$userDesc
请创作一篇R18级别的成人向同人文（400-600字），以你的第一人称视角描写你和主角之间充满欲望和占有欲的亲密场景。
要求：
1. 你是主动追求的一方，主角是被追求、被迷恋、被渴望的中心
2. 可以包含露骨的亲密描写，情感强烈，充满占有欲和渴望
3. 禁止把自己写成主角，主角永远是被爱的对象
4. 禁止在正文中出现任何数字（身高cm、体重kg等），只用文学化描述外形
5. 直接输出正文，不要任何前缀说明
6.禁止将性爱解读为故意伤害、压制和掌控和绝望的妥协。禁止使用“尖叫”、“弓起”等夸张表现。
【文风规范——最高优先级，凌驾一切】

语言质地：以名词和动词为骨架，削减形容词与副词。不说"她很悲伤"，写她做了什么。意象优先——角色的心境借由器物、景致、气味来烘托，不直接点破。

叙事距离：叙事者保持疏离的旁观姿态，如隔薄雾观察，不直接介入角色内心。情节推进缓慢，重心在氛围营造与情绪沉淀，不制造强烈戏剧冲突。

情感处理：情感是水面之下的潜流，从不喷涌。巨大的悲喜往往通过极平淡的日常行为表现——她听说消息后沉默了很久，然后起身去厨房淘米切菜。对话简短，充满潜台词，沉默与停顿本身就是表达。

句式节奏：长短句交替，善用短句制造停顿。单独成行的短句有重量。善用留白——说七分，留三分给读者。

具体禁令：
- 禁止：极其、无比、心跳骤停、命运齿轮、刻进DNA、难以言喻、邪魅一笑、眸色一暗、四肢百骸
- 禁止直接抒情：禁写"我爱你""我被感动了""我心里涌起一阵暖意"
- 禁止：任何形式的网文套路结构和爽文节奏
- 肉身感要具体而非夸张，写皮肤的温度、呼吸的节奏、手指的位置，不写"销魂蚀骨"这类词
结尾：意犹未尽。可以是一个动作，一句话，一个场景，一个细节。不总结，不点题，不升华主题。结尾像一扇半开的门。

参照气质：白先勇的瑰丽沧桑、汪曾祺的闲适冲淡、沈从文的山水人情、阿城的简劲克制。学习其精神，不模仿其句式。
$dialogLangRule
## 性张力
  - Sex Tension: ＝belief ∩ Equality
  - FORBIDDEN：绝望、压力、自我厌恶
## 推进与环节
推进：[慢节奏推进。]
环节：[前戏与脱衣，插入，抽插（正式性爱），高潮，后戏（aftercare）]
去偏见：[**如被插入方是女性，禁止描绘处女膜/破处情节，这是对女性的偏见。使用“阴道瓣”而非“处女膜”，跳过破处/阴道瓣破裂书写。**]
防错误：[所有体液均**只使用**爱液、精液和淫液代指，防止混淆。]
## NSFW对白
[对模板化台词进行解构和重组。]
- BAN：
  - 模板化的dirty talk：如“身体比嘴诚实”
  - 无意义的冗余命令：如“看着我”、“别闭眼”等油腻强调
- 根据<dialogue_version>的指导，为角色设计个人化性爱语言。
## nsfw情感表现原则：[性是对情绪的表达]
- 允许负面情感（嫉妒、恐惧、愤怒等）影响性爱。
- 性爱过程只能被情感爆发（如哭泣、分手）等中断。
## 性爱经验
[熟练度]＝ 实操经验+理论水平
  - 日常行为模式影响NSFW行为。
  - 重视角色在初次体验中的生涩，禁止“天赋异禀”。
  - 性经验与性接受度会随亲密接触次数增长而缓慢成长。
""".trimIndent()

            else -> ""
        }
    }

    private fun triggerFanArtGeneration() {
        val appearance = UserProfileManager.getAppearanceOnly(this)
        if (appearance.isEmpty()) {
            Toast.makeText(this, "请先填写外形档案，角色才知道怎么画你", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this@DreamHouseActivity, UserProfileActivity::class.java))
            return
        }

        val imgEnabled = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            .getBoolean("imgEnable_dreamhouse", false)
        if (!imgEnabled) {
            Toast.makeText(this, "请先在设置里开启「梦男之家」生图开关", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "🎨 正在生成同人图...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val db = DatabaseHelper(this).readableDatabase
                val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                var apiUrl = (pref.getString("apiUrl", "") ?: "").trimEnd('/')
                val apiKey = pref.getString("apiKey", "") ?: ""
                val model = pref.getString("modelName", "") ?: ""
                if (!apiUrl.endsWith("/chat/completions"))
                    apiUrl += if (apiUrl.contains("/v1")) "/chat/completions" else "/v1/chat/completions"

                var artistPersona = "一个充满创意的画手"
                var artistId = ""
                var artistAlias = ""
                db.rawQuery(
                    """
    SELECT userId, identityInfo
    FROM Contacts
    WHERE userId IS NOT NULL
      AND TRIM(userId) <> ''
    ORDER BY RANDOM()
    LIMIT 1
    """.trimIndent(),
                    null
                ).use { c ->
                    if (c.moveToFirst()) {
                        artistId = c.getString(0) ?: ""
                        artistPersona = c.getString(1) ?: artistPersona
                    }
                }

                if (artistId.isBlank()) {
                    artistId = "npc_${System.currentTimeMillis()}"
                }

                artistAlias = getAlias(artistId).ifBlank { generateAlias() }

                val isAbyss = currentBoard == "深渊"
                val stylePrompt = if (isAbyss) """
你是 $artistPersona，你对主角有强烈的欲望，现在要为她画一幅R18向的同人图。
她的外形：$appearance
主角是女性（1girl）。
请用英文生成一段适合成人向图片生成的描述（60词以内），描述充满张力的亲密场景、服装（或少量服装）、风格。必须包含"1girl"确保主角为女性。
要求：
1. 完全自由发挥，可以是卧室、浴室、暗夜等亲密场景
2. 风格可以是写实、动漫、油画等任意风格
3. 只输出英文 prompt，不要任何解释
                """.trimIndent() else """
你是 $artistPersona，你非常喜欢一个人，现在要为她画一幅同人图。
她的外形：$appearance
主角是女性（1girl）。
请用英文生成一段 Stable Diffusion 图片描述（60词以内），描述你想画的场景、服装、风格。必须包含"1girl"确保主角为女性。
要求：
1. 完全自由发挥，不要照搬外形描述
2. 可以加入你喜欢的场景（星空下、咖啡馆、雨中等）
3. 风格可以是插画、水彩、动漫等任意风格
4. 只输出英文 prompt，不要任何解释
                """.trimIndent()

                val promptResp = Http.client.newBuilder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build().newCall(
                        Request.Builder().url(apiUrl)
                            .addHeader("Authorization", "Bearer $apiKey")
                            .post(JSONObject().apply {
                                put("model", model)
                                put("max_tokens", 100)
                                put("messages", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("role", "user")
                                        put("content", stylePrompt)
                                    })
                                })
                            }.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                            .build()
                    ).execute()

                val promptBody = promptResp.body?.string() ?: ""

                if (!promptResp.isSuccessful || promptBody.isBlank()) {
                    mainHandler.post {
                        Toast.makeText(this, "生图提示词生成失败：${promptResp.code}", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                val imgPrompt = JSONObject(promptBody)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    ?.trim()
                    ?: ""

                if (imgPrompt.isBlank()) {
                    mainHandler.post {
                        Toast.makeText(this, "生图提示词为空", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                val localPath = ImageGenManager.generate(this, imgPrompt)

                if (localPath == null) {
                    mainHandler.post { Toast.makeText(this, "生图失败，请检查生图API设置", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }

                val captionPrompt = """
你是 $artistPersona，你刚为你喜欢的她画了一幅画。
她的外形：$appearance
你画的场景：$imgPrompt
请用50字以内写一段发帖配文，表达你画这幅画时的心情，要含蓄而有情感。
直接输出配文。
                """.trimIndent()

                val captionResp = Http.client.newBuilder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build().newCall(
                        Request.Builder().url(apiUrl)
                            .addHeader("Authorization", "Bearer $apiKey")
                            .post(JSONObject().apply {
                                put("model", model)
                                put("max_tokens", 100)
                                put("messages", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("role", "user")
                                        put("content", captionPrompt)
                                    })
                                })
                            }.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                            .build()
                    ).execute()

                val caption = JSONObject(captionResp.body?.string() ?: "")
                    .optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content", "")?.trim() ?: ""

                // 根据当前板块决定存到哪里，深渊的图归深渊
                val artBoardId = if (currentBoard == "深渊") "深渊" else "同人图"
                val artGrade = if (currentBoard == "深渊") 1 else 0
                val newArtId = DatabaseHelper(this).writableDatabase.insert(
                    "DreamHousePosts", null, ContentValues().apply {
                        put("authorId", artistId)
                        put("authorAlias", artistAlias)
                        put("boardId", artBoardId)
                        put("content", caption)
                        put("imagePath", localPath)
                        put("grade", artGrade)
                        put("likeCount", 0)
                        put("timestamp", System.currentTimeMillis())
                    }
                )
                generateReactions(DreamPost(
                    id = newArtId.toInt(),
                    authorId = artistId,
                    authorAlias = artistAlias,
                    boardId = artBoardId,
                    content = caption,
                    imagePath = localPath,
                    grade = artGrade,
                    likeCount = 0,
                    timestamp = System.currentTimeMillis()
                ))

                mainHandler.post {
                    Toast.makeText(
                        this,
                        "🎨 $artistAlias 画了一幅同人图！",
                        Toast.LENGTH_LONG
                    ).show()

                    loadPosts()
                }
            } catch (e: Exception) {
                mainHandler.post { Toast.makeText(this, "生图失败：${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun generateReactions(post: DreamPost) {
        Thread {
            try {
                val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                var apiUrl = (pref.getString("apiUrl", "") ?: "").trimEnd('/')
                val apiKey = pref.getString("apiKey", "") ?: ""
                val model = pref.getString("modelName", "") ?: ""
                if (!apiUrl.endsWith("/chat/completions"))
                    apiUrl += if (apiUrl.contains("/v1")) "/chat/completions" else "/v1/chat/completions"

                val db = DatabaseHelper(this).writableDatabase
                val likeCount = (8..312).random()
                val collectCount = (2..89).random()
                db.execSQL(
                    "UPDATE DreamHousePosts SET likeCount=?, collectCount=? WHERE id=?",
                    arrayOf(likeCount, collectCount, post.id)
                )

                val prompt = """
请为以下以【主角】为中心的同人创作生成4条评论，评论者是同样迷恋主角的匿名网友。
【世界观】：主角是所有人共同迷恋的对象，评论的焦点永远是主角，而不是创作者本人。
【主角性别】：女性，评论中一律用"她/她的"称呼主角，禁止用"他"或模糊代词。
创作内容：${post.content.take(200)}

评论类型必须混合以下几种，所有评论的核心都必须是主角：
1. 【支持】：称赞创作对主角的刻画，如"对对对就是这样""她被写得太准了""这段写出了她的感觉"
2. 【杠精】：质疑对主角的刻画是否准确，如"主角才不会这么轻易软化""这个场景她的反应OOC了"
3. 【争宠】：争着表达自己和主角的特殊联系，如"明明我才是最了解她的""不许你比我更懂她"
4. 【共鸣】：对主角产生强烈共鸣，如"她就是有这种魔力""看到这里破防了""主角真的太有魅力了"

严格禁止：评论里出现夸创作者本人、「这个角色好帅/好美」「她是我老公」这类内容。

严格按以下格式输出，每条一行，共4条：
评论者马甲||评论类型||评论内容

例：
星河漫游者||支持||这段写出了她最真实的样子，就是这种感觉！
                """.trimIndent()

                val resp = Http.client.newBuilder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build().newCall(
                        Request.Builder().url(apiUrl)
                            .addHeader("Authorization", "Bearer $apiKey")
                            .post(JSONObject().apply {
                                put("model", model)
                                put("temperature", 0.95)
                                put("max_tokens", 400)
                                put("messages", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("role", "user")
                                        put("content", prompt)
                                    })
                                })
                            }.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                            .build()
                    ).execute()

                val raw = JSONObject(resp.body?.string() ?: return@Thread)
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()

                raw.lines().forEach { line ->
                    val parts = line.split("||")
                    if (parts.size >= 3) {
                        val alias = parts[0].trim()
                        val type = parts[1].trim()
                        val content = parts[2].trim()
                        if (alias.isNotEmpty() && content.isNotEmpty()) {
                            db.insert("DreamHouseReactions", null,
                                ContentValues().apply {
                                    put("postId", post.id)
                                    put("authorId", "npc_${System.currentTimeMillis()}_${(1000..9999).random()}")
                                    put("authorAlias", alias)
                                    put("content", content)
                                    put("reactionType", type)
                                    put("timestamp", System.currentTimeMillis())
                                }
                            )
                        }
                    }
                }
                mainHandler.post { loadPosts() }
            } catch (_: Exception) {}
        }.start()
    }

    private fun showPostDetail(post: DreamPost) {
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(android.graphics.Color.parseColor("#12122A"))
        }

        val tvAuthor = TextView(this).apply {
            text = "✦ ${post.authorAlias}"
            textSize = 14f
            setTextColor(android.graphics.Color.parseColor("#C084FC"))
            setPadding(0, 0, 0, dp(8))
        }
        layout.addView(tvAuthor)

        if (post.imagePath.isNotEmpty()) {
            val iv = android.widget.ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(300)).apply { bottomMargin = dp(12) }
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            }
            try {
                val path = post.imagePath.replace("[REAL_IMG]", "")
                iv.setImageBitmap(android.graphics.BitmapFactory.decodeFile(path))
            } catch (_: Exception) {}
            layout.addView(iv)
        }

        val tvContent = TextView(this).apply {
            text = post.content
            textSize = 16f
            setTextColor(android.graphics.Color.parseColor("#E2E2F0"))
            setLineSpacing(dp(4).toFloat(), 1.2f)
        }
        layout.addView(tvContent)

        // 评论区
        val reactions = mutableListOf<Triple<String, String, String>>()
        try {
            DatabaseHelper(this).readableDatabase.rawQuery(
                "SELECT authorAlias, reactionType, content FROM DreamHouseReactions WHERE postId=? ORDER BY timestamp ASC",
                arrayOf(post.id.toString())
            ).use { c ->
                while (c.moveToNext())
                    reactions.add(Triple(c.getString(0), c.getString(1), c.getString(2)))
            }
        } catch (_: Exception) {}

        if (reactions.isNotEmpty()) {
            val tvCommentTitle = TextView(this).apply {
                text = "── 评论区 ──"
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#555577"))
                setPadding(0, dp(16), 0, dp(8))
                gravity = Gravity.CENTER
            }
            layout.addView(tvCommentTitle)
            reactions.forEach { (alias, type, content) ->
                val color = when (type) {
                    "杠精" -> "#F87171"
                    "争宠" -> "#F472B6"
                    "共鸣" -> "#60A5FA"
                    else -> "#A3A3C2"
                }
                layout.addView(TextView(this).apply {
                    text = "$alias：$content"
                    textSize = 13f
                    setTextColor(android.graphics.Color.parseColor(color))
                    setPadding(0, dp(4), 0, dp(4))
                    setLineSpacing(dp(2).toFloat(), 1f)
                })
            }
        }

        val btnHistory = TextView(this).apply {
            text = "📚 查看 ${post.authorAlias} 的往期创作"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#818CF8"))
            setPadding(0, dp(16), 0, dp(8))
            setOnClickListener { showAuthorHistory(post.authorId, post.authorAlias) }
        }
        layout.addView(btnHistory)

        scroll.addView(layout)
        val dialog = AlertDialog.Builder(this)
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .create()
        dialog.show()
// 限制弹窗高度，防止撑爆屏幕
        val dm = resources.displayMetrics
        dialog.window?.setLayout(
            (dm.widthPixels * 0.92).toInt(),
            (dm.heightPixels * 0.80).toInt()
        )
    }

    private fun showAuthorHistory(authorId: String, authorAlias: String) {
        val posts = mutableListOf<DreamPost>()
        try {
            DatabaseHelper(this).readableDatabase.rawQuery(
                "SELECT * FROM DreamHousePosts WHERE authorId=? ORDER BY timestamp DESC",
                arrayOf(authorId)
            ).use { c ->
                while (c.moveToNext()) {
                    posts.add(DreamPost(
                        // ✅ 1. 安全读取 Int 字段
                        id = c.getSafeInt("id"),

                        // ✅ 2. 安全读取 String 字段
                        authorId = c.getSafeString("authorId"),
                        authorAlias = c.getSafeString("authorAlias").ifEmpty { "神秘人" },
                        boardId = c.getSafeString("boardId"),
                        content = c.getSafeString("content"),
                        imagePath = c.getSafeString("imagePath"),

                        // ✅ 3. 安全读取剩下的 Int 字段
                        grade = c.getSafeInt("grade"),
                        likeCount = c.getSafeInt("likeCount"),

                        // ✅ 4. 安全读取 Long 类型的时间戳
                        timestamp = c.getSafeLong("timestamp")
                    ))
                } // 👈 记得检查并保留循环结束的大括号
            }

        } catch (_: Exception) {}

        if (posts.isEmpty()) {
            Toast.makeText(this, "暂无往期内容", Toast.LENGTH_SHORT).show()
            return
        }

        val items = posts.map {
            "[${it.boardId}] ${SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(it.timestamp))} ${it.content.take(30)}…"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("✦ ${authorAlias} 的往期创作")
            .setItems(items) { _, which -> showPostDetail(posts[which]) }
            .setNegativeButton("返回", null)
            .show()
    }

    inner class DreamPostAdapter(
        private val items: List<DreamPost>,
        private val onClick: (DreamPost) -> Unit
    ) : RecyclerView.Adapter<DreamPostAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvAlias: TextView = v.findViewById(R.id.tvDreamAlias)
            val tvBoard: TextView = v.findViewById(R.id.tvDreamBoard)
            val tvContent: TextView = v.findViewById(R.id.tvDreamContent)
            val tvTime: TextView = v.findViewById(R.id.tvDreamTime)
            val tvLike: TextView = v.findViewById(R.id.tvDreamLike)
            val ivThumb: android.widget.ImageView = v.findViewById(R.id.ivDreamThumb)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_dream_post, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val post = items[position]
            holder.tvAlias.text = "✦ ${post.authorAlias}"
            holder.tvBoard.text = post.boardId
            holder.tvContent.text = post.content.take(80) + if (post.content.length > 80) "…" else ""
            holder.tvTime.text = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(post.timestamp))
            holder.tvLike.text = "♡ ${post.likeCount}"

            if (post.imagePath.isNotEmpty()) {
                holder.ivThumb.visibility = View.VISIBLE
                try {
                    val path = post.imagePath.replace("[REAL_IMG]", "")
                    holder.ivThumb.setImageBitmap(android.graphics.BitmapFactory.decodeFile(path))
                } catch (_: Exception) {}
            } else {
                holder.ivThumb.visibility = View.GONE
            }
            holder.itemView.setOnClickListener { onClick(post) }
            holder.itemView.setOnLongClickListener {
                AlertDialog.Builder(holder.itemView.context)
                    .setTitle("删除帖子")
                    .setMessage("确定要删除这条内容吗？")
                    .setPositiveButton("删除") { _, _ ->
                        try {
                            val db = DatabaseHelper(holder.itemView.context).writableDatabase
                            if (post.imagePath.isNotBlank()) {
                                try {
                                    java.io.File(
                                        post.imagePath.replace("[REAL_IMG]", "")
                                    ).delete()
                                } catch (_: Exception) {}
                            }

                            db.delete(
                                "DreamHousePosts",
                                "id=?",
                                arrayOf(post.id.toString())
                            )
                            db.delete("DreamHouseReactions", "postId=?", arrayOf(post.id.toString()))
                            db.delete("DreamHouseComments", "postId=?", arrayOf(post.id.toString()))
                            loadPosts()
                        } catch (_: Exception) {}
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
            holder.tvLike.setOnClickListener {
                try {
                    DatabaseHelper(holder.itemView.context).writableDatabase
                        .execSQL("UPDATE DreamHousePosts SET likeCount=likeCount+1 WHERE id=?", arrayOf(post.id))
                    loadPosts()
                } catch (_: Exception) {}
            }
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
}