package com.moon.aiphone

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.*
import java.util.Locale
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class ChatAdapter(
    private val msgList: MutableList<Message>,
    private val myAvatarPath: String,
    private var aiAvatarPath: String,
    private val aiId: String = "",
    private val isBlockedByUser: Boolean = false,   // 用户拉黑了角色
    private val isBlockedByAi: Boolean = false      // 角色拉黑了用户
) : RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

    private var currentPlayingPosition: Int = -1
    private var ttsManager: TTSManager? = null
    // 引用回调：长按消息选择引用时通知ChatActivity
    var onQuoteMessage: ((Message) -> Unit)? = null
    private var isBlockedByUserVar: Boolean = isBlockedByUser
    private var cachedAiAvatar: android.graphics.Bitmap? = null
    private var cachedMyAvatar: android.graphics.Bitmap? = null
    private var isBlockedByAiVar: Boolean = isBlockedByAi
    private fun chatRowWhere(msg: Message): Pair<String, Array<String>> {
        return when {
            msg.dbId > 0 -> "id=?" to arrayOf(msg.dbId.toString())
            aiId.isNotEmpty() -> "timestamp=? AND aiId=?" to arrayOf(msg.timestamp.toString(), aiId)
            else -> "timestamp=?" to arrayOf(msg.timestamp.toString())
        }
    }

    private fun deleteChatMessage(db: android.database.sqlite.SQLiteDatabase, msg: Message) {
        val where = chatRowWhere(msg)
        db.delete("ChatHistory", where.first, where.second)
    }

    private fun updateChatMessage(db: android.database.sqlite.SQLiteDatabase, msg: Message, values: android.content.ContentValues) {
        val where = chatRowWhere(msg)
        db.update("ChatHistory", values, where.first, where.second)
    }

    private fun updateChatTranslation(db: android.database.sqlite.SQLiteDatabase, msg: Message, translated: String) {
        updateChatMessage(db, msg, android.content.ContentValues().apply { put("translatedText", translated) })
    }
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSystemMsg: TextView = view.findViewById(R.id.tvSystemMsg)
        val layoutLeft: RelativeLayout = view.findViewById(R.id.layoutLeft)
        val layoutRight: RelativeLayout = view.findViewById(R.id.layoutRight)
        val ivAvatarLeft: ImageView = view.findViewById(R.id.ivAvatarLeft)
        val ivAvatarRight: ImageView = view.findViewById(R.id.ivAvatarRight)
        val tvMsgLeft: TextView = view.findViewById(R.id.tvMsgLeft)
        val tvMsgRight: TextView = view.findViewById(R.id.tvMsgRight)
        val tvTimeLeft: TextView = view.findViewById(R.id.tvTimeLeft)
        val tvTimeRight: TextView = view.findViewById(R.id.tvTimeRight)
        val tvReadStatus: TextView = view.findViewById(R.id.tvReadStatus)
        val layoutVoiceLeft: LinearLayout = view.findViewById(R.id.layoutVoiceLeft)
        val tvVoiceDurationLeft: TextView = view.findViewById(R.id.tvVoiceDurationLeft)
        val tvVoiceToText: TextView = view.findViewById(R.id.tvVoiceToText)
        val btnPlayVoiceIcon: TextView = view.findViewById(R.id.btnPlayVoiceIcon)
        val tvTranslatedTextLeft: TextView = view.findViewById(R.id.tvTranslatedTextLeft)
        val ivImageLeft: ImageView = view.findViewById(R.id.ivImageLeft)
        val ivImageRight: ImageView = view.findViewById(R.id.ivImageRight)
        // 引用View
        val layoutQuoteLeft: LinearLayout = view.findViewById(R.id.layoutQuoteLeft)
        val tvQuoteNameLeft: TextView = view.findViewById(R.id.tvQuoteNameLeft)
        val tvQuoteContentLeft: TextView = view.findViewById(R.id.tvQuoteContentLeft)
        val layoutQuoteRight: LinearLayout = view.findViewById(R.id.layoutQuoteRight)
        val tvQuoteNameRight: TextView = view.findViewById(R.id.tvQuoteNameRight)
        val tvQuoteContentRight: TextView = view.findViewById(R.id.tvQuoteContentRight)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat, parent, false)
        if (ttsManager == null) {
            ttsManager = TTSManager(parent.context.applicationContext)
        }
        return ViewHolder(view)
    }

    private fun callTranslateApi(
        context: android.content.Context,
        content: String,
        onSuccess: (String) -> Unit,
        onFail: () -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pref = context.getSharedPreferences("AppConfig", android.content.Context.MODE_PRIVATE)
                var apiUrl = pref.getString("apiUrl", "") ?: ""
                val apiKey = pref.getString("apiKey", "") ?: ""
                val transModel = pref.getString("transModel_$aiId", "") ?: ""
                val modelName = if (transModel.isNotEmpty()) transModel else (pref.getString("modelName", "gemini-2.5-pro") ?: "gemini-2.5-pro")
                while (apiUrl.endsWith("/")) apiUrl = apiUrl.dropLast(1)
                if (!apiUrl.endsWith("/chat/completions")) {
                    apiUrl = if (apiUrl.endsWith("/v1")) "$apiUrl/chat/completions" else "$apiUrl/v1/chat/completions"
                }
                val bodyJson = org.json.JSONObject().apply {
                    put("model", modelName)
                    put("temperature", 0.3)
                    put("messages", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("role", "user")
                            put("content", "请将以下内容翻译成简体中文，只输出翻译结果，不要任何解释：\n${content.take(3500)}")
                        })
                    })
                }
                val request = okhttp3.Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(bodyJson.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()
                val response = Http.client.newBuilder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build().newCall(request).execute()
                val body = response.use { if (it.isSuccessful) it.body?.string() else null }
                if (body.isNullOrBlank()) {
                    withContext(Dispatchers.Main) { onFail() }
                    return@launch
                }
                val translated = org.json.JSONObject(body).getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").getString("content").trim()
                withContext(Dispatchers.Main) {
                    if (translated.isBlank()) onFail() else onSuccess(translated)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onFail() }
            }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val msg = msgList[position]
        val msgTime = msg.timestamp
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis
        val yesterdayStart = todayStart - 86400000L

        val currentTime = when {
            msgTime >= todayStart -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msgTime))
            msgTime >= yesterdayStart -> "昨天 " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msgTime))
            else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(msgTime))
        }

        val isLastInGroup =
            position == msgList.size - 1 || msgList[position + 1].isFromMe != msg.isFromMe || msgList[position + 1].isSystem

        holder.itemView.setOnLongClickListener {
            // 撤回消息和系统消息不显示引用选项
            val isRecalled = msg.imageDesc?.startsWith("[RECALLED]") == true
                    || msg.content.endsWith("撤回了一条消息")
                    || msg.content == "（撤回了一条消息）"
            val options = if (isRecalled || msg.isSystem) {
                arrayOf("🗑️ 删除 (物理火化)", "🧠 删除+清除记忆")
            } else {
                arrayOf("💬 引用回复", "🗑️ 删除 (物理火化)", "👻 撤回 (让他看见)", "🧠 删除+清除记忆")
            }
            AlertDialog.Builder(holder.itemView.context)
                .setItems(options) { _, which ->
                    if (isRecalled || msg.isSystem) {
                        // 撤回/系统消息菜单：["删除", "删除+清除记忆"]
                        when (which) {
                            0 -> {
                                try {
                                    val db = DatabaseHelper(holder.itemView.context).writableDatabase
                                    deleteChatMessage(db, msg)
                                    msgList.removeAt(position)
                                    notifyItemRemoved(position)
                                    notifyItemRangeChanged(position, msgList.size)
                                } catch (e: Exception) {}
                            }
                            1 -> {
                                try {
                                    val db = DatabaseHelper(holder.itemView.context).writableDatabase
                                    deleteChatMessage(db, msg)
                                    // 只删包含这条消息内容关键词的记忆，不清空全部
                                    // 关键词太短（如"嗯""哈哈"）会 LIKE 命中该角色几乎所有记忆，导致记忆被清空
                                    if (aiId.isNotEmpty()) {
                                        val keyword = msg.content.trim().take(30)
                                        if (keyword.length >= 15) {
                                            db.delete("MemoryBank", "aiId=? AND memoryText LIKE ?", arrayOf(aiId, "%$keyword%"))
                                        }
                                    }
                                    msgList.removeAt(position)
                                    notifyItemRemoved(position)
                                    notifyItemRangeChanged(position, msgList.size)
                                    android.widget.Toast.makeText(holder.itemView.context, "消息和相关记忆已清除", android.widget.Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {}
                            }
                        }
                    } else {
                        // 普通消息菜单：["引用回复", "删除", "撤回", "删除+清除记忆"]
                        when (which) {
                            0 -> onQuoteMessage?.invoke(msg)
                            1 -> {
                                try {
                                    val db = DatabaseHelper(holder.itemView.context).writableDatabase
                                    deleteChatMessage(db, msg)
                                    msgList.removeAt(position)
                                    notifyItemRemoved(position)
                                    notifyItemRangeChanged(position, msgList.size)
                                } catch (e: Exception) {}
                            }
                            2 -> {
                                try {
                                    if (msg.isFromMe == false) {
                                        android.widget.Toast.makeText(holder.itemView.context, "不能撤回别人的消息！", android.widget.Toast.LENGTH_SHORT).show()
                                        return@setItems
                                    }
                                    val db = DatabaseHelper(holder.itemView.context).writableDatabase
                                    val values = android.content.ContentValues()
                                    values.put("content", "（撤回了一条消息）")
                                    updateChatMessage(db, msg, values)
                                    msg.content = "（撤回了一条消息）"
                                    msg.imageDesc = ""
                                    notifyItemChanged(position)
                                } catch (e: Exception) {}
                            }
                            3 -> {
                                try {
                                    val db = DatabaseHelper(holder.itemView.context).writableDatabase
                                    deleteChatMessage(db, msg)
                                    // 只删包含这条消息内容关键词的记忆，不清空全部
                                    // 关键词太短（如"嗯""哈哈"）会 LIKE 命中该角色几乎所有记忆，导致记忆被清空
                                    if (aiId.isNotEmpty()) {
                                        val keyword = msg.content.trim().take(30)
                                        if (keyword.length >= 15) {
                                            db.delete("MemoryBank", "aiId=? AND memoryText LIKE ?", arrayOf(aiId, "%$keyword%"))
                                        }
                                    }
                                    msgList.removeAt(position)
                                    notifyItemRemoved(position)
                                    notifyItemRangeChanged(position, msgList.size)
                                    android.widget.Toast.makeText(holder.itemView.context, "消息和相关记忆已清除", android.widget.Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {}
                            }
                        }
                    }
                }
                .show()
            true
        }

        if (msg.isSystem) {
            holder.tvSystemMsg.visibility = View.VISIBLE
            holder.layoutLeft.visibility = View.GONE
            holder.layoutRight.visibility = View.GONE
            holder.tvSystemMsg.text = msg.content
        } else {
            holder.tvSystemMsg.visibility = View.GONE
            if (msg.isFromMe) {
                holder.layoutRight.visibility = View.VISIBLE
                holder.layoutLeft.visibility = View.GONE
                if (!msg.imageDesc.isNullOrEmpty()) {
                    holder.tvMsgRight.visibility = View.GONE
                    holder.ivImageRight.visibility = View.VISIBLE
                    try {
                        val imageDesc = msg.imageDesc ?: ""
                        if (imageDesc.startsWith("http")) {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val url = java.net.URL(imageDesc)
                                    val bitmap = android.graphics.BitmapFactory.decodeStream(url.openStream())
                                    withContext(Dispatchers.Main) { holder.ivImageRight.setImageBitmap(bitmap) }
                                } catch (e: Exception) {}
                            }
                        } else {
                            if (imageDesc.startsWith("[REAL_IMG]")) {
                                val path = imageDesc.replace("[REAL_IMG]", "")
                                val bitmap = android.graphics.BitmapFactory.decodeFile(path)
                                holder.ivImageRight.setImageBitmap(bitmap)
                            } else {
                                val uri = android.net.Uri.parse(imageDesc)
                                val bmp = holder.itemView.context.contentResolver.openInputStream(uri)?.use { stream ->
                                    android.graphics.BitmapFactory.decodeStream(stream)
                                }
                                holder.ivImageRight.setImageBitmap(bmp)
                            }
                        }
                    } catch (e: Exception) {
                        holder.ivImageRight.setBackgroundColor(Color.DKGRAY)
                    }
                    // 长按保存图片
                    holder.ivImageRight.setOnLongClickListener {
                        val imageDesc = msg.imageDesc ?: ""
                        if (imageDesc.isNotEmpty()) {
                            saveImageToGallery(holder.itemView.context, imageDesc)
                        }
                        true
                    }
                } else {
                    holder.tvMsgRight.visibility = View.VISIBLE
                    holder.ivImageRight.visibility = View.GONE
                    holder.tvMsgRight.text = msg.content
                }
                if (isLastInGroup) {
                    holder.tvTimeRight.visibility = View.VISIBLE
                    holder.tvReadStatus.visibility = View.VISIBLE
                    holder.tvTimeRight.text = currentTime
                    if (isBlockedByAiVar) {
                        // 角色拉黑了用户，用户发的消息显示红色感叹号
                        holder.tvReadStatus.text = "!"
                        holder.tvReadStatus.setTextColor(Color.RED)
                        holder.tvReadStatus.textSize = 18f
                    } else {
                        holder.tvReadStatus.text = if (msg.isRead) "已读" else "已发送"
                        holder.tvReadStatus.setTextColor(Color.parseColor("#888888"))
                        holder.tvReadStatus.textSize = 12f
                    }
                } else {
                    holder.tvTimeRight.visibility = View.GONE
                    holder.tvReadStatus.visibility = View.GONE
                }
                if (myAvatarPath.isNotEmpty()) {
                    try {
                        if (cachedMyAvatar == null) {
                            cachedMyAvatar =
                                if (myAvatarPath.startsWith("/")) {
                                    android.graphics.BitmapFactory.decodeFile(myAvatarPath)
                                } else {
                                    holder.itemView.context.contentResolver
                                        .openInputStream(android.net.Uri.parse(myAvatarPath))
                                        ?.use {
                                            android.graphics.BitmapFactory.decodeStream(it)
                                        }
                                }
                        }
                        cachedMyAvatar?.let { holder.ivAvatarRight.setImageBitmap(it) }
                    } catch (_: Exception) {}
                }
                // 渲染引用气泡（右侧用户消息）
                val quoteRefRight = msg.innerThoughts.let {
                    if (it.startsWith("[QUOTE]")) it.removePrefix("[QUOTE]") else null
                }
                if (quoteRefRight != null) {
                    val parts = quoteRefRight.split("|", limit = 2)
                    holder.layoutQuoteRight.visibility = View.VISIBLE
                    holder.tvQuoteNameRight.text = parts.getOrNull(0) ?: ""
                    holder.tvQuoteContentRight.text = (parts.getOrNull(1) ?: "").substringBefore("\n").trim()
                } else {
                    holder.layoutQuoteRight.visibility = View.GONE
                }
            } else {
                holder.layoutLeft.visibility = View.VISIBLE
                holder.layoutRight.visibility = View.GONE
                if (isLastInGroup) {
                    holder.tvTimeLeft.visibility = View.VISIBLE
                    holder.tvTimeLeft.text = currentTime
                    if (isBlockedByUserVar) {
                        // 用户拉黑了角色，角色发的消息显示红色感叹号
                        val ctx = holder.itemView.context
                        // 检查是否已经加过感叹号，避免重复添加
                        val existingBang = holder.layoutLeft.findViewWithTag<TextView>("tv_bang_left")
                        if (existingBang == null) {
                            val tvBang = TextView(ctx).apply {
                                tag = "tv_bang_left"
                                text = "!"
                                textSize = 18f
                                setTextColor(Color.RED)
                                setTypeface(null, android.graphics.Typeface.BOLD)
                                layoutParams = RelativeLayout.LayoutParams(
                                    RelativeLayout.LayoutParams.WRAP_CONTENT,
                                    RelativeLayout.LayoutParams.WRAP_CONTENT
                                ).also {
                                    it.addRule(RelativeLayout.ALIGN_PARENT_END)
                                    it.addRule(RelativeLayout.CENTER_VERTICAL)
                                }
                            }
                            holder.layoutLeft.addView(tvBang)
                        }
                    } else {
                        holder.layoutLeft.findViewWithTag<TextView>("tv_bang_left")?.let {
                            holder.layoutLeft.removeView(it)
                        }
                    }
                } else {
                    holder.tvTimeLeft.visibility = View.GONE
                    holder.layoutLeft.findViewWithTag<TextView>("tv_bang_left")?.let {
                        holder.layoutLeft.removeView(it)
                    }
                }
                if (aiAvatarPath.isNotEmpty()) {
                    try {
                        if (cachedAiAvatar == null) {
                            cachedAiAvatar =
                                if (aiAvatarPath.startsWith("/")) {
                                    android.graphics.BitmapFactory.decodeFile(aiAvatarPath)
                                } else {
                                    holder.itemView.context.contentResolver
                                        .openInputStream(android.net.Uri.parse(aiAvatarPath))
                                        ?.use {
                                            android.graphics.BitmapFactory.decodeStream(it)
                                        }
                                }
                        }
                        cachedAiAvatar?.let { holder.ivAvatarLeft.setImageBitmap(it) }
                    } catch (_: Exception) {}
                }

                // 窃听心声
                holder.ivAvatarLeft.setOnClickListener {
                    try {
                        val db = DatabaseHelper(holder.itemView.context).readableDatabase
                        var innerVoice = "意识流信号微弱，无法解析……"
                        try {
                            val cursor = db.rawQuery(
                                "SELECT innerThoughts FROM ChatHistory WHERE timestamp=? AND isFromMe=0",
                                arrayOf(msg.timestamp.toString())
                            )
                            if (cursor.moveToFirst()) {
                                val thoughts = cursor.getString(0)
                                if (!thoughts.isNullOrEmpty()) {
                                    val cleaned = if (thoughts.startsWith("[QUOTE]")) {
                                        thoughts.substringAfter("\n").trim()
                                    } else {
                                        thoughts
                                    }
                                    if (cleaned.isNotEmpty()) innerVoice = cleaned
                                }
                            }
                            cursor.close()
                        } catch (e: Exception) {}

                        // 读取角色信息
                        var persona = ""
                        var avatarUri = ""
                        var location = ""
                        var aiNameLocal = ""
                        try {
                            // ✅ 新增安全防御：先用 SELECT * 兜底，通过 getColumnIndex 智能判断字段是否存在
                            db.rawQuery("SELECT * FROM Contacts WHERE userId=?", arrayOf(aiId)).use { c ->
                                if (c.moveToFirst()) {
                                    // identityInfo
                                    val idxIdentity = c.getColumnIndex("identityInfo")
                                    if (idxIdentity != -1) persona = c.getString(idxIdentity) ?: ""

                                    // avatarUri
                                    val idxAvatar = c.getColumnIndex("avatarUri")
                                    if (idxAvatar != -1) avatarUri = c.getString(idxAvatar) ?: ""

                                    // realName (有些老用户没有这个字段，找不到就用系统传进来的 aiName)
                                    val idxRealName = c.getColumnIndex("realName")
                                    var aiName = "" // 👈 确保在使用前，在最外层作用域声明它
                                    aiNameLocal = if (idxRealName != -1) c.getString(idxRealName) ?: aiName else aiName
                                }
                            }
                        } catch (_: Exception) {}

                        // 从人设里提取工作单位/组织
                        val orgMatch = Regex("(所属|单位|组织|团队|公司|学校|队伍|部队|学院|小队|战队)[：:：]?\\s*([^，。\\n]{2,20})").find(persona)
                        var org = orgMatch?.groupValues?.get(2)?.trim() ?: ""
                        if (org.isEmpty()) {
                            // 备用：直接取人设第一句话里的专有名词
                            val firstLine = persona.lines().firstOrNull { it.isNotEmpty() } ?: ""
                            org = firstLine.take(20).trim()
                        }
                        if (org.isEmpty()) org = "未知组织"
// 去掉开头多余的"的"字
                        if (org.startsWith("的")) org = org.removePrefix("的").trim()

                        // 从日程里读今日位置
                        try {
                            val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                            val now = java.util.Calendar.getInstance()
                            val nowHour = now.get(java.util.Calendar.HOUR_OF_DAY)
                            val nowMin = now.get(java.util.Calendar.MINUTE)
                            val nowTotal = nowHour * 60 + nowMin
                            db.rawQuery(
                                "SELECT startTime, eventDesc FROM Schedules WHERE aiId=? AND dateStr=? ORDER BY startTime ASC",
                                arrayOf(aiId, todayStr)
                            ).use { c ->
                                var lastDesc = ""
                                while (c.moveToNext()) {
                                    val t = c.getString(0) ?: ""
                                    val desc = c.getString(1) ?: ""
                                    val parts = t.split(":")
                                    if (parts.size == 2) {
                                        val h = parts[0].toIntOrNull() ?: 0
                                        val m = parts[1].toIntOrNull() ?: 0
                                        if (h * 60 + m <= nowTotal) lastDesc = desc
                                    }
                                }
                                if (lastDesc.isNotEmpty()) location = lastDesc.take(40)
                            }
                        } catch (_: Exception) {}
                        if (location.isEmpty()) location = "位置信号屏蔽中"

                        // 优先从标签里提取动作和穿着
                        // 🔴 找到这两段处理 actionText 和 outfitText 的旧代码：
// val actionText = Regex("【动作】\\s*(.+?)...
// val outfitText = Regex("\\[OUTFIT\\](.+?)...
// -----------------------------------------------------------------
// ✅ 全部替换为以下这一段【智能自适应解析】代码：

// 1. 优先提取旧有的【动作】标签，如果没有，则进入智能语义分析
                        val actionText = Regex("【动作】\\s*(.+?)\\s*(?=【|$)", RegexOption.DOT_MATCHES_ALL)
                            .find(innerVoice)?.groupValues?.get(1)?.trim()
                            ?: Regex("\\[ACTION\\](.+?)(?=\\[|$)", RegexOption.DOT_MATCHES_ALL)
                                .find(innerVoice)?.groupValues?.get(1)?.trim()
                            ?: run {
                                // 新格式：动作和心理融合在了一起，我们用动作动词智能抓取一个生动的短句
                                val sentences = innerVoice.split("。", "，", "！", "？", "\n")
                                val actionKw = listOf("走", "拿", "转身", "坐", "站", "看", "拉", "推", "靠", "躺", "跑", "回", "起", "接", "递", "摸", "按", "敲", "抱", "拧", "缩", "紧", "点")
                                sentences.firstOrNull { s -> actionKw.any { s.contains(it) } && s.length in 4..25 }?.trim() ?: "常规待机中"
                            }

// 2. 优先提取旧有的【穿着】标签，如果没有，则从内心或人设中智能摘取衣服关键词
                        val outfitText = Regex("【穿着】\\s*(.+?)\\s*(?=【|$)", RegexOption.DOT_MATCHES_ALL)
                            .find(innerVoice)?.groupValues?.get(1)?.trim()
                            ?: Regex("\\[OUTFIT\\](.+?)(?=\\[|$)", RegexOption.DOT_MATCHES_ALL)
                                .find(innerVoice)?.groupValues?.get(1)?.trim()
                            ?: run {
                                val sentences = (innerVoice + persona).split("。", "，", "！", "？", "\n")
                                val outfitKw = listOf("穿", "戴", "衬衫", "外套", "夹克", "T恤", "裤子", "裙子", "西装", "制服", "运动服", "毛衣", "卫衣", "校服", "军装")
                                sentences.firstOrNull { s -> outfitKw.any { s.contains(it) } && s.length in 3..25 }?.trim() ?: "日常便服（未采集到特征）"
                            }

// 3. 洗出纯心声：移除可能残留在新旧文本里的废弃系统标签
                        val pureInnerVoice = innerVoice
                            .replace(Regex("【动作】.*?(\\s*(?=【)|$)"), "")
                            .replace(Regex("【穿着】.*?(\\s*(?=【)|$)"), "")
                            .replace(Regex("【语气】.*?(\\s*(?=【)|$)"), "")
                            .replace(Regex("\\[ACTION\\].*?(\\s*(?=\\[)|$)"), "")
                            .replace(Regex("\\[OUTFIT\\].*?(\\s*(?=\\[)|$)"), "")
                            .trim()
                            .ifEmpty { "意识流信号微弱，无法解析……" }

                        val nowStr = java.text.SimpleDateFormat("yyyy/MM/dd  HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                        val bpm = when {
                            actionText.any { it in "跑步训练作战冲刺搏击" } -> (140..185).random()
                            actionText.any { it in "走路移动巡逻" } -> (90..110).random()
                            actionText.any { it in "睡觉躺休息" } -> (55..70).random()
                            else -> (72..95).random()
                        }

                        // 把base64头像编码
                        var avatarBase64 = ""
                        try {
                            if (avatarUri.isNotEmpty()) {
                                val uri = android.net.Uri.parse(avatarUri)
                                val bytes = holder.itemView.context.contentResolver.openInputStream(uri)?.readBytes()
                                if (bytes != null) {
                                    avatarBase64 = "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                }
                            }
                        } catch (_: Exception) {}

                        val avatarHtml = try {
                            var finalAvatarUri = if (avatarUri.isNotEmpty()) avatarUri else aiAvatarPath

                            if (finalAvatarUri.startsWith("[REAL_IMG]")) {
                                finalAvatarUri = finalAvatarUri.removePrefix("[REAL_IMG]").trim()
                            }

                            val bytes: ByteArray? = when {
                                finalAvatarUri.isBlank() -> null

                                finalAvatarUri.startsWith("/") -> {
                                    val f = java.io.File(finalAvatarUri)
                                    if (f.exists()) f.readBytes() else null
                                }

                                finalAvatarUri.startsWith("file://") -> {
                                    val path = android.net.Uri.parse(finalAvatarUri).path ?: ""
                                    val f = java.io.File(path)
                                    if (f.exists()) f.readBytes() else null
                                }

                                finalAvatarUri.startsWith("content://") -> {
                                    holder.itemView.context.contentResolver
                                        .openInputStream(android.net.Uri.parse(finalAvatarUri))
                                        ?.use { input -> input.readBytes() }
                                }

                                else -> null
                            }

                            if (bytes != null) {
                                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                "<img src=\"data:image/jpeg;base64,$base64\" style=\"width:100%;height:100%;object-fit:cover;display:block;\">"
                            } else {
                                "<div style=\"width:100%;height:100%;display:flex;align-items:center;justify-content:center;font-size:36px;color:#1688ff;\">👤</div>"
                            }
                        } catch (_: Exception) {
                            "<div style=\"width:100%;height:100%;display:flex;align-items:center;justify-content:center;font-size:36px;color:#1688ff;\">👤</div>"
                        }
                        // 先处理 LOCATION
                        var safeLocation = location.trim()
                            .replace("当前位置：", "")
                            .replace("LOCATION:", "")
                            .replace("Location:", "")
                            .replace("📍", "")
                            .trim()

                        safeLocation = safeLocation
                            .substringBefore("，")
                            .substringBefore(",")
                            .substringBefore("。")
                            .substringBefore(".")
                            .substringBefore("\n")
                            .trim()

                        val bannedLocations = setOf(
                            "安全屋", "基地", "房间", "宿舍", "某处", "秘密地点", "当前位置", "未知地点", "unknown"
                        )

                        if (safeLocation.isBlank() || bannedLocations.contains(safeLocation)) {
                            safeLocation = listOf(
                                "厨房","窗边","阳台","走廊","浴室门口","训练场","车里","办公室",
                                "会议室","便利店门口","停车场","天台"
                            ).random()
                        }

                        val html = "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0,maximum-scale=1.0\"><style>*{margin:0;padding:0;box-sizing:border-box;}body{background:#0a0f0a;font-family:monospace,sans-serif;color:#b8d4b8;padding:16px;}.card{border:1px solid #2a4a2a;background:linear-gradient(160deg,#0d140d,#0f1a0f);border-radius:4px;overflow:hidden;position:relative;}.header{background:linear-gradient(90deg,#0d2a0d,#1a3a1a);border-bottom:1px solid #2a4a2a;padding:10px 14px 8px;}.header-top{font-size:9px;color:#4a7a4a;letter-spacing:3px;text-transform:uppercase;margin-bottom:4px;}.header-title{font-size:18px;font-weight:900;color:#00cc44;letter-spacing:2px;}.live-row{display:flex;align-items:center;gap:8px;background:#0f1f0f;border-bottom:1px solid #1a3a1a;padding:8px 14px;}.live-badge{background:#cc2200;color:#fff;font-size:10px;font-weight:900;padding:2px 8px;border-radius:2px;letter-spacing:2px;}.live-text{font-size:12px;color:#7aaa7a;flex:1;}.profile-row{display:flex;gap:14px;padding:14px;border-bottom:1px solid #1a3a1a;align-items:flex-start;}.avatar-box{width:72px;height:72px;flex-shrink:0;border:1px solid #2a5a2a;overflow:hidden;}.info-col{flex:1;}.role-label{font-size:9px;color:#4a7a4a;letter-spacing:2px;margin-bottom:2px;}.name{font-size:17px;color:#00ee55;font-weight:900;letter-spacing:1px;margin-bottom:8px;}.loc-label{font-size:9px;color:#4a7a4a;letter-spacing:2px;margin-bottom:3px;}.loc-time{font-size:11px;color:#7aaa7a;margin-bottom:2px;}.loc-place{font-size:12px;color:#aaddaa;line-height:1.35;word-break:break-word;overflow-wrap:break-word;}.telemetry{padding:12px 14px;}.tele-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;}.tele-label{font-size:9px;color:#4a7a4a;letter-spacing:3px;}.bpm{font-size:13px;color:#00cc44;font-weight:900;}.consciousness{background:#080e08;border:1px solid #1a3a1a;border-left:3px solid #00cc44;padding:10px 12px;font-size:13px;color:#cceecc;line-height:1.7;margin-bottom:10px;}.bottom-row{display:flex;gap:8px;}.action-box{flex:1;background:#080e08;border:1px solid #1a3a1a;border-left:3px solid #4a7a00;padding:8px 10px;}.action-label{font-size:9px;color:#4a7a4a;letter-spacing:2px;margin-bottom:3px;}.action-val{font-size:12px;color:#aadd44;}.corner{position:absolute;width:12px;height:12px;border-color:#00cc44;}.corner-tl{top:0;left:0;border-top:2px solid;border-left:2px solid;}.corner-tr{top:0;right:0;border-top:2px solid;border-right:2px solid;}.corner-bl{bottom:0;left:0;border-bottom:2px solid;border-left:2px solid;}.corner-br{bottom:0;right:0;border-bottom:2px solid;border-right:2px solid;}</style></head><body><div class=\"card\"><div class=\"corner corner-tl\"></div><div class=\"corner corner-tr\"></div><div class=\"corner corner-bl\"></div><div class=\"corner corner-br\"></div><div class=\"header\"><div class=\"header-top\">" + org + " · NEURAL ACCESS SYSTEM</div><div class=\"header-title\">&#9646; MIND INTERCEPT</div></div><div class=\"live-row\"><div class=\"live-badge\">LIVE</div><div class=\"live-text\">实时意识流截取中 · 数据加密传输</div></div><div class=\"profile-row\"><div class=\"avatar-box\">" + avatarHtml + "</div><div class=\"info-col\"><div class=\"role-label\">AGENT / 档案主体</div><div class=\"name\">" + aiNameLocal + "</div><div class=\"loc-label\">LOCATION / 定点</div><div class=\"loc-time\">" + nowStr + " &#128205;</div><div class=\"loc-place\">" + safeLocation + "</div></div></div><div class=\"telemetry\"><div class=\"tele-header\"><div class=\"tele-label\">TELEMETRY / 大脑神经监控</div><div class=\"bpm\">" + bpm + " BPM &#8593;</div></div><div class=\"consciousness\">当前意识海：「" + pureInnerVoice + "」</div><div class=\"bottom-row\"><div class=\"action-box\"><div class=\"action-label\">ACTION / 当前动作</div><div class=\"action-val\">&#9654; " + actionText + "</div></div><div class=\"action-box\" style=\"border-left:3px solid #007acc;\"><div class=\"action-label\">OUTFIT / 当前着装</div><div class=\"action-val\" style=\"color:#44aadd;\">&#9672; " + outfitText + "</div></div></div></div></div></body></html>"

                        val webView = android.webkit.WebView(holder.itemView.context).apply {
                            settings.javaScriptEnabled = false
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            setBackgroundColor(android.graphics.Color.parseColor("#0a0f0a"))
                        }
                        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)

                        val dialog = AlertDialog.Builder(holder.itemView.context)
                            .setView(webView)
                            .create()
                        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.parseColor("#0a0f0a")))
                        dialog.show()

                        // 弹窗宽度设为屏幕85%
                        val dm = holder.itemView.context.resources.displayMetrics
                        dialog.window?.setLayout((dm.widthPixels * 0.88).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)

                    } catch (e: Exception) {
                        android.widget.Toast.makeText(holder.itemView.context, "窃听失败！", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
// 渲染引用气泡（左侧AI消息）
                val quoteRef = msg.innerThoughts.let {
                    if (it.startsWith("[QUOTE]")) it.removePrefix("[QUOTE]") else null
                }
                if (quoteRef != null) {
                    val parts = quoteRef.split("|", limit = 2)
                    val quoteName = parts.getOrNull(0) ?: ""
                    val quoteText = (parts.getOrNull(1) ?: "").substringBefore("\n").trim()
                    holder.layoutQuoteLeft.visibility = View.VISIBLE
                    holder.tvQuoteNameLeft.text = quoteName
                    holder.tvQuoteContentLeft.text = quoteText
                } else {
                    holder.layoutQuoteLeft.visibility = View.GONE
                }
                val hasTranslation = !msg.translatedText.isNullOrEmpty() && msg.translatedText != msg.content
                if (msg.imageDesc?.startsWith("[MUSIC_CARD]") == true) {
                    holder.tvMsgLeft.visibility = View.GONE
                    holder.layoutVoiceLeft.visibility = View.GONE
                    holder.ivImageLeft.visibility = View.GONE
                    holder.tvVoiceToText.visibility = View.GONE
                    holder.tvTranslatedTextLeft.visibility = View.GONE

                    try {
                        val cardJson = msg.imageDesc!!.removePrefix("[MUSIC_CARD]")
                        val card = org.json.JSONObject(cardJson)
                        val songId = card.optString("songId")
                        val songName = card.optString("songName")
                        val artist = card.optString("artist")
                        val coverBase64 = card.optString("coverBase64")
                        val coverUrl = card.optString("coverUrl")
                        val context = holder.itemView.context

                        val cardView = android.widget.LinearLayout(context).apply {
                            orientation = android.widget.LinearLayout.HORIZONTAL
                            setPadding(24, 20, 24, 20)
                            background = android.graphics.drawable.GradientDrawable().apply {
                                setColor(android.graphics.Color.parseColor("#1C1C1E"))
                                cornerRadius = 20f
                            }
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            tag = "music_card_view"
                        }

                        val ivCover = android.widget.ImageView(context).apply {
                            layoutParams = android.widget.LinearLayout.LayoutParams(120, 120).also { it.marginEnd = 20 }
                            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            background = android.graphics.drawable.GradientDrawable().apply {
                                setColor(android.graphics.Color.parseColor("#333333"))
                                cornerRadius = 12f
                            }
                            clipToOutline = true
                            outlineProvider = object : android.view.ViewOutlineProvider() {
                                override fun getOutline(v: android.view.View, outline: android.graphics.Outline) {
                                    outline.setRoundRect(0, 0, v.width, v.height, 12f)
                                }
                            }
                        }
                        // 优先用内存缓存的base64，没有就用URL加载
                        val cachedBase64 = MusicCardCache.get(songId)
                        val base64ToUse = if (cachedBase64.isNotEmpty()) cachedBase64 else coverBase64
                        if (base64ToUse.isNotEmpty()) {
                            try {
                                val b64 = base64ToUse.substringAfter("base64,")
                                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                ivCover.setImageBitmap(bmp)
                            } catch (_: Exception) {}
                        } else if (coverUrl.isNotEmpty()) {
                            // 内存没有就从URL重新加载封面
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val conn = java.net.URL("$coverUrl?param=200y200").openConnection() as java.net.HttpURLConnection
                                    conn.setRequestProperty("Referer", "https://music.163.com/")
                                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                                    val bmp = android.graphics.BitmapFactory.decodeStream(conn.inputStream)
                                    withContext(Dispatchers.Main) { ivCover.setImageBitmap(bmp) }
                                } catch (_: Exception) {}
                            }
                        }

                        val textCol = android.widget.LinearLayout(context).apply {
                            orientation = android.widget.LinearLayout.VERTICAL
                            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            gravity = android.view.Gravity.CENTER_VERTICAL
                        }
                        val tvNote = android.widget.TextView(context).apply {
                            text = "🎵 为你点了一首歌"
                            textSize = 10f
                            setTextColor(android.graphics.Color.parseColor("#888888"))
                            setPadding(0, 0, 0, 6)
                        }
                        val tvSongName = android.widget.TextView(context).apply {
                            text = songName
                            textSize = 14f
                            setTextColor(android.graphics.Color.WHITE)
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        }
                        val tvArtistName = android.widget.TextView(context).apply {
                            text = artist
                            textSize = 12f
                            setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setPadding(0, 4, 0, 12)
                        }
                        val btnPlay = android.widget.TextView(context).apply {
                            text = "▶  播放"
                            textSize = 13f
                            setTextColor(android.graphics.Color.WHITE)
                            setPadding(28, 10, 28, 10)
                            background = android.graphics.drawable.GradientDrawable().apply {
                                setColor(android.graphics.Color.parseColor("#CC0000"))
                                cornerRadius = 20f
                            }
                        }

                        textCol.addView(tvNote)
                        textCol.addView(tvSongName)
                        textCol.addView(tvArtistName)
                        textCol.addView(btnPlay)
                        cardView.addView(ivCover)
                        cardView.addView(textCol)

                        val layoutLeft = holder.layoutLeft
// 每次先清除旧卡片，再插入新的，防止复用时乱跑
                        layoutLeft.findViewWithTag<android.view.View>("music_card_view")?.let {
                            layoutLeft.removeView(it)
                        }
                        val cardParams = android.widget.RelativeLayout.LayoutParams(
                            android.widget.RelativeLayout.LayoutParams.MATCH_PARENT,
                            android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            addRule(android.widget.RelativeLayout.BELOW, R.id.tvMsgLeft)
                            marginStart = 8; marginEnd = 8; topMargin = 4
                        }
                        layoutLeft.addView(cardView, cardParams)

                        btnPlay.setOnClickListener {
                            val pref = context.getSharedPreferences("AppConfig", android.content.Context.MODE_PRIVATE)
                            pref.edit()
                                .putString("currentSong", songName)
                                .putString("currentSongArtist", artist)
                                .putString("currentSongCover", coverBase64)
                                .apply()
                            val intent = android.content.Intent(context, MusicActivity::class.java).apply {
                                putExtra("song_id", songId)
                                putExtra("song_name", songName)
                            }
                            context.startActivity(intent)
                        }
                    } catch (_: Exception) {
                        holder.tvMsgLeft.visibility = View.VISIBLE
                        holder.tvMsgLeft.text = "🎵 音乐卡片加载失败"
                    }
                } else if (msg.imageDesc?.startsWith("[RECALLED]") == true) {
                    // 撤回消息
                    holder.tvMsgLeft.visibility = View.VISIBLE
                    holder.layoutVoiceLeft.visibility = View.GONE
                    holder.ivImageLeft.visibility = View.GONE
                    holder.tvVoiceToText.visibility = View.GONE
                    holder.tvTranslatedTextLeft.visibility = View.GONE

                    holder.tvMsgLeft.text = msg.content
                    holder.tvMsgLeft.setTextColor(android.graphics.Color.parseColor("#999999"))
                    holder.tvMsgLeft.setTypeface(null, android.graphics.Typeface.ITALIC)
                    holder.tvMsgLeft.background = null

                    holder.tvMsgLeft.setOnClickListener {
                        val recalled = msg.imageDesc!!.removePrefix("[RECALLED]")
                        val layout = android.widget.LinearLayout(holder.itemView.context).apply {
                            orientation = android.widget.LinearLayout.VERTICAL
                            setPadding(60, 60, 60, 60)
                            setBackgroundColor(android.graphics.Color.parseColor("#E6111111"))
                        }
                        val tvTitle = android.widget.TextView(holder.itemView.context).apply {
                            text = "👀 偷看撤回"
                            setTextColor(android.graphics.Color.parseColor("#888888"))
                            textSize = 12f
                            setPadding(0, 0, 0, 20)
                        }
                        val tvContent = android.widget.TextView(holder.itemView.context).apply {
                            text = recalled
                            setTextColor(android.graphics.Color.WHITE)
                            textSize = 16f
                        }
                        layout.addView(tvTitle)
                        layout.addView(tvContent)
                        val dialog = AlertDialog.Builder(holder.itemView.context)
                            .setView(layout)
                            .create()
                        dialog.window?.setBackgroundDrawable(
                            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                        )
                        dialog.show()
                    }
                    holder.tvMsgLeft.setOnLongClickListener {
                        AlertDialog.Builder(holder.itemView.context)
                            .setItems(arrayOf("🗑️ 删除 (物理火化)", "🧠 删除+清除记忆")) { _, which ->
                                when (which) {
                                    0 -> try {
                                        DatabaseHelper(holder.itemView.context).writableDatabase
                                            .let { deleteChatMessage(it, msg) }
                                        val idx = msgList.indexOf(msg)
                                        if (idx != -1) { msgList.removeAt(idx); notifyItemRemoved(idx); notifyItemRangeChanged(idx, msgList.size) }
                                    } catch (e: Exception) {}
                                    1 -> try {
                                        val db = DatabaseHelper(holder.itemView.context).writableDatabase
                                        deleteChatMessage(db, msg)
                                        // 关键词太短会 LIKE 命中该角色几乎所有记忆，导致记忆被清空
                                        val keyword = msg.content.trim().take(30)
                                        if (aiId.isNotEmpty() && keyword.length >= 15) {
                                            db.delete("MemoryBank", "aiId=? AND memoryText LIKE ?", arrayOf(aiId, "%$keyword%"))
                                        }
                                        val idx = msgList.indexOf(msg)
                                        if (idx != -1) { msgList.removeAt(idx); notifyItemRemoved(idx); notifyItemRangeChanged(idx, msgList.size) }
                                        android.widget.Toast.makeText(holder.itemView.context, "消息和记忆已清除", android.widget.Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {}
                                }
                            }.show()
                        true
                    }
                } else if (!msg.imageDesc.isNullOrEmpty()) {
                    // 图片消息
                    holder.tvMsgLeft.visibility = View.GONE
                    holder.layoutVoiceLeft.visibility = View.GONE
                    holder.ivImageLeft.visibility = View.VISIBLE
                    holder.tvVoiceToText.visibility = View.GONE
                    holder.tvTranslatedTextLeft.visibility = View.GONE
                    try {
                        val imageDesc = msg.imageDesc ?: ""
                        if (imageDesc.startsWith("http")) {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val url = java.net.URL(imageDesc)
                                    val bitmap = android.graphics.BitmapFactory.decodeStream(url.openStream())
                                    withContext(Dispatchers.Main) { holder.ivImageLeft.setImageBitmap(bitmap) }
                                } catch (e: Exception) {}
                            }
                        } else {
                            if (imageDesc.startsWith("[REAL_IMG]")) {
                                val path = imageDesc.replace("[REAL_IMG]", "")
                                val bitmap = android.graphics.BitmapFactory.decodeFile(path)
                                holder.ivImageLeft.setImageBitmap(bitmap)
                            } else {
                                val uri = android.net.Uri.parse(imageDesc)
                                val bmp = holder.itemView.context.contentResolver.openInputStream(uri)?.use { stream ->
                                    android.graphics.BitmapFactory.decodeStream(stream)
                                }
                                holder.ivImageLeft.setImageBitmap(bmp)
                            }
                        }
                    } catch (e: Exception) {
                        holder.ivImageLeft.setBackgroundColor(Color.DKGRAY)
                    }
                    // 长按保存或删除图片
                    holder.ivImageLeft.setOnLongClickListener {
                        val imageDesc = msg.imageDesc ?: ""
                        AlertDialog.Builder(holder.itemView.context)
                            .setItems(arrayOf("💾 保存到相册", "🗑️ 删除这条消息")) { _, which ->
                                when (which) {
                                    0 -> if (imageDesc.isNotEmpty()) saveImageToGallery(holder.itemView.context, imageDesc)
                                    1 -> {
                                        try {
                                            DatabaseHelper(holder.itemView.context).writableDatabase
                                                .let { deleteChatMessage(it, msg) }
                                            val idx = msgList.indexOf(msg)
                                            if (idx != -1) {
                                                msgList.removeAt(idx)
                                                notifyItemRemoved(idx)
                                            }
                                        } catch (e: Exception) {}
                                    }
                                }
                            }.show()
                        true
                    }
                } else if (msg.isVoice) {
                    // 语音消息
                    holder.tvMsgLeft.visibility = View.GONE
                    holder.ivImageLeft.visibility = View.GONE
                    holder.layoutVoiceLeft.visibility = View.VISIBLE
                    holder.tvVoiceDurationLeft.text = "${msg.voiceDuration}\""
                    holder.btnPlayVoiceIcon.text = if (currentPlayingPosition == position) "⏸️" else "▶️"

                    holder.layoutVoiceLeft.setOnClickListener {
                        if (currentPlayingPosition == position) {
                            currentPlayingPosition = -1
                            notifyItemChanged(position)
                        } else {
                            if (currentPlayingPosition != -1) {
                                val oldPos = currentPlayingPosition
                                currentPlayingPosition = -1
                                notifyItemChanged(oldPos)
                            }
                            currentPlayingPosition = position
                            notifyItemChanged(position)
                            try {
                                val context = holder.itemView.context
                                val currentAiId = (context as? android.app.Activity)?.intent?.getStringExtra("AI_ID") ?: ""
                                val charPref = context.getSharedPreferences("AppConfig", android.content.Context.MODE_PRIVATE)
                                val voiceId = charPref.getString("voiceId_$currentAiId", "") ?: ""
                                if (voiceId.isEmpty()) {
                                    android.widget.Toast.makeText(context, "voiceId为空", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    val aiLang = charPref.getString("aiLang_$currentAiId", "默认 (中文)") ?: "默认 (中文)"
                                    val ttsText = if (aiLang != "默认 (中文)" && !aiLang.contains("中")) {
                                        // 非中文角色：去掉中文字符再送TTS
                                        msg.content.filter { c -> c.code !in 0x4E00..0x9FFF && c.code !in 0x3000..0x303F && c.code !in 0xFF00..0xFFEF && c !in "，。！？、；："}.trim()
                                    } else {
                                        // 中文角色：直接用原文
                                        msg.content
                                    }
                                    ttsManager?.speak(ttsText, voiceId)

                                    ttsManager?.onTtsStart = {
                                        context.sendBroadcast(android.content.Intent("MUSIC_PAUSE"))
                                    }
                                    ttsManager?.onTtsEnd = {
                                        context.sendBroadcast(android.content.Intent("MUSIC_RESUME"))
                                    }
                                    android.widget.Toast.makeText(context, "正在播放", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(holder.itemView.context, "播放失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    holder.tvMsgLeft.setOnLongClickListener {
                        AlertDialog.Builder(holder.itemView.context)
                            .setItems(arrayOf("🗑️ 删除这条消息")) { _, _ ->
                                try {
                                    DatabaseHelper(holder.itemView.context).writableDatabase
                                        .let { deleteChatMessage(it, msg) }
                                    val idx = msgList.indexOf(msg)
                                    if (idx != -1) {
                                        msgList.removeAt(idx)
                                        notifyItemRemoved(idx)
                                    }
                                } catch (e: Exception) {}
                            }.show()
                        true
                    }
                    holder.layoutVoiceLeft.setOnLongClickListener {
                        val options = arrayOf("🗑️ 删除 (物理火化)", "🧠 删除+清除记忆")
                        AlertDialog.Builder(holder.itemView.context)
                            .setItems(options) { _, which ->
                                if (which == 0) {
                                    try {
                                        val db = DatabaseHelper(holder.itemView.context).writableDatabase
                                        deleteChatMessage(db, msg)
                                        msgList.removeAt(position)
                                        notifyItemRemoved(position)
                                        notifyItemRangeChanged(position, msgList.size)
                                    } catch (e: Exception) {}
                                } else {
                                    try {
                                        val db = DatabaseHelper(holder.itemView.context).writableDatabase
                                        deleteChatMessage(db, msg)
                                        // 关键词太短会 LIKE 命中该角色几乎所有记忆，导致记忆被清空
                                        val keyword = msg.content.trim().take(30)
                                        if (aiId.isNotEmpty() && keyword.length >= 15) {
                                            db.delete("MemoryBank", "aiId=? AND memoryText LIKE ?", arrayOf(aiId, "%$keyword%"))
                                        }
                                        msgList.removeAt(position)
                                        notifyItemRemoved(position)
                                        notifyItemRangeChanged(position, msgList.size)
                                        android.widget.Toast.makeText(holder.itemView.context, "消息和记忆已清除", android.widget.Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {}
                                }
                            }
                            .show()
                        true
                    }

                    // 语音翻译按钮
                    holder.tvVoiceToText.visibility = View.VISIBLE
                    holder.tvVoiceToText.isClickable = true
                    val translationTag = "${msg.dbId}:${msg.timestamp}"
                    holder.tvVoiceToText.tag = translationTag
                    holder.tvTranslatedTextLeft.visibility = View.GONE
                    holder.tvVoiceToText.text = if (!msg.translatedText.isNullOrEmpty()) "查看转写文字" else "转写文字"
                    holder.tvTranslatedTextLeft.text = msg.translatedText ?: ""

                    holder.tvVoiceToText.setOnClickListener {
                        if (holder.tvTranslatedTextLeft.visibility == View.VISIBLE) {
                            holder.tvTranslatedTextLeft.visibility = View.GONE
                            holder.tvVoiceToText.text = if (!msg.translatedText.isNullOrEmpty()) "查看翻译" else "翻译"
                            return@setOnClickListener
                        }
                        if (!msg.translatedText.isNullOrEmpty()) {
                            holder.tvTranslatedTextLeft.visibility = View.VISIBLE
                            holder.tvVoiceToText.text = "收起翻译"
                        } else {
                            // 无翻译时调用API翻译
                            holder.tvVoiceToText.text = "翻译中..."
                            holder.tvVoiceToText.isClickable = false
                            callTranslateApi(
                                context = holder.itemView.context,
                                content = msg.content,
                                onSuccess = success@{ translated ->
                                    try {
                                        val db = DatabaseHelper(holder.itemView.context).writableDatabase
                                        updateChatTranslation(db, msg, translated)
                                    } catch (_: Exception) {}
                                    msg.translatedText = translated
                                    if (holder.tvVoiceToText.tag != translationTag) return@success
                                    holder.tvTranslatedTextLeft.text = translated
                                    holder.tvTranslatedTextLeft.visibility = View.VISIBLE
                                    holder.tvVoiceToText.text = "收起翻译"
                                    holder.tvVoiceToText.isClickable = true
                                },
                                onFail = fail@{
                                    if (holder.tvVoiceToText.tag != translationTag) return@fail
                                    holder.tvVoiceToText.text = "翻译失败"
                                    holder.tvVoiceToText.isClickable = true
                                }
                            )
                        }
                    }
// 没有翻译就隐藏按钮
                    // 语音消息始终显示按钮，translatedText为空时点击才调用转写


                } else {
                    // 纯文本消息
                    holder.tvMsgLeft.visibility = View.VISIBLE
                    holder.layoutVoiceLeft.visibility = View.GONE
                    holder.ivImageLeft.visibility = View.GONE
                    holder.tvVoiceToText.visibility = View.VISIBLE
                    holder.tvTranslatedTextLeft.visibility = View.GONE
                    holder.tvMsgLeft.text = msg.content
                    // 确保普通消息恢复正常样式（防止复用时继承撤回样式）
                    holder.tvMsgLeft.setTextColor(android.graphics.Color.parseColor("#111111"))
                    holder.tvMsgLeft.setTypeface(null, android.graphics.Typeface.NORMAL)
                    holder.tvMsgLeft.setBackgroundResource(R.drawable.bg_chat_left)

                    holder.tvVoiceToText.visibility = View.VISIBLE
                    holder.tvVoiceToText.isClickable = true
                    val translationTag = "${msg.dbId}:${msg.timestamp}"
                    holder.tvVoiceToText.tag = translationTag
                    holder.tvTranslatedTextLeft.text = msg.translatedText ?: ""
                    holder.tvTranslatedTextLeft.visibility = View.GONE
                    holder.tvVoiceToText.text = if (hasTranslation) "查看翻译" else "翻译"

                    holder.tvVoiceToText.setOnClickListener {
                        if (holder.tvTranslatedTextLeft.visibility == View.VISIBLE) {
                            holder.tvTranslatedTextLeft.visibility = View.GONE
                            holder.tvVoiceToText.text = if (hasTranslation) "查看翻译" else "翻译"
                            return@setOnClickListener
                        }
                        if (!msg.translatedText.isNullOrEmpty()) {
                            holder.tvTranslatedTextLeft.visibility = View.VISIBLE
                            holder.tvVoiceToText.text = "收起翻译"
                        } else {
                            holder.tvVoiceToText.text = "翻译中..."
                            holder.tvVoiceToText.isClickable = false
                            callTranslateApi(
                                context = holder.itemView.context,
                                content = msg.content,
                                onSuccess = success@{ translated ->
                                    try {
                                        val db = DatabaseHelper(holder.itemView.context).writableDatabase
                                        updateChatTranslation(db, msg, translated)
                                    } catch (_: Exception) {}
                                    msg.translatedText = translated
                                    if (holder.tvVoiceToText.tag != translationTag) return@success
                                    holder.tvTranslatedTextLeft.text = translated
                                    holder.tvTranslatedTextLeft.visibility = View.VISIBLE
                                    holder.tvVoiceToText.text = "收起翻译"
                                    holder.tvVoiceToText.isClickable = true
                                },
                                onFail = fail@{
                                    if (holder.tvVoiceToText.tag != translationTag) return@fail
                                    holder.tvVoiceToText.text = "翻译失败"
                                    holder.tvVoiceToText.isClickable = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun getItemCount() = msgList.size
    fun updateBlockStatus(blockedByUser: Boolean, blockedByAi: Boolean) {
        isBlockedByUserVar = blockedByUser
        isBlockedByAiVar = blockedByAi
        notifyDataSetChanged()
    }
    fun updateAiAvatar(newPath: String) {
        aiAvatarPath = newPath
        cachedAiAvatar = null
        notifyDataSetChanged()
    }
    // 保存图片到相册工具函数
    private fun saveImageToGallery(context: android.content.Context, imageDesc: String) {
        Thread {
            try {
                val bitmap: android.graphics.Bitmap? = when {
                    imageDesc.startsWith("[REAL_IMG]") -> {
                        val path = imageDesc.replace("[REAL_IMG]", "")
                        android.graphics.BitmapFactory.decodeFile(path)
                    }
                    imageDesc.startsWith("http") -> {
                        val url = java.net.URL(imageDesc)
                        android.graphics.BitmapFactory.decodeStream(url.openStream())
                    }
                    else -> {
                        val uri = android.net.Uri.parse(imageDesc)
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            android.graphics.BitmapFactory.decodeStream(stream)
                        }
                    }
                }
                if (bitmap == null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(context, "图片加载失败", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                val fileName = "M叽_${System.currentTimeMillis()}.jpg"
                val savedUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/M叽")
                    }
                    val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    uri?.let { u ->
                        context.contentResolver.openOutputStream(u)?.use { out ->
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                        }
                    }
                    uri
                } else {
                    val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "M叽")
                    if (!dir.exists()) dir.mkdirs()
                    val file = java.io.File(dir, fileName)
                    java.io.FileOutputStream(file).use { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    android.net.Uri.fromFile(file)
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (savedUri != null) {
                        android.widget.Toast.makeText(context, "✓ 已保存到相册", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        android.widget.Toast.makeText(context, "保存失败", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, "保存失败：${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}
