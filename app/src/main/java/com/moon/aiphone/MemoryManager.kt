package com.moon.aiphone

import android.content.ContentValues
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

object MemoryManager {

    // 防止同一个角色的记忆总结/召回同时跑太多网络请求，旧角色聊天多时最容易被这里拖到“网络连接错误”。
    private val summarizingAiIds = ConcurrentHashMap.newKeySet<String>()
    private const val SUMMARY_MIN_NEW_MSG_COUNT = 30
    private const val SUMMARY_MIN_INTERVAL_MS = 10 * 60 * 1000L
    private const val SUMMARY_MAX_CHAT_LINES = 40
    // 向量记忆启用后，本地余弦计算很便宜，可以扫更多历史记忆，让长期记忆更持久
    private const val RECALL_MAX_ROWS = 200
    // timeline / key_event 记忆的保留条数（放宽，让记忆长久）
    private const val MEMORY_KEEP_PER_CATEGORY = 300

    /**
     * 宠物小屋的事件直接成为共同角色的长期记忆。
     * 不依赖聊天总结触发，确保领养、互动和角色离线照料都不会被漏掉。
     */
    fun recordPetMemory(context: Context, aiId: String, memoryText: String, isBond: Boolean = false) {
        if (aiId.isBlank() || memoryText.isBlank()) return
        try {
            val db = DatabaseHelper(context.applicationContext).writableDatabase
            ensureMemorySchema(db)
            val category = if (isBond) "pet_bond" else "pet_event"
            insertMemory(context.applicationContext, db, aiId, memoryText.take(500), category)
            val keep = if (isBond) 30 else 150
            db.execSQL(
                "DELETE FROM MemoryBank WHERE id IN (SELECT id FROM MemoryBank WHERE aiId=? AND category=? ORDER BY insertTime DESC LIMIT -1 OFFSET ?)",
                arrayOf<Any>(aiId, category, keep)
            )
        } catch (_: Exception) {}
    }

    private fun ensureMemorySchema(db: android.database.sqlite.SQLiteDatabase) {
        try { db.execSQL("ALTER TABLE MemoryBank ADD COLUMN embedding TEXT DEFAULT ''") } catch (_: Exception) {}
        try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_ai_category_time ON MemoryBank(aiId, category, insertTime)") } catch (_: Exception) {}
        try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_chat_ai_group_time ON ChatHistory(aiId, groupId, timestamp)") } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════
    // Embedding 工具
    // ══════════════════════════════════════════════

    // 向量记忆专用配置（和聊天 API 完全独立，可以聊天用 Gemini、向量用 OpenAI）
    private data class EmbConfig(val enabled: Boolean, val url: String, val key: String, val model: String)

    private fun embConfig(context: Context): EmbConfig {
        val sp = context.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        return EmbConfig(
            enabled = sp.getBoolean("embEnable", false),
            url = (sp.getString("embApiUrl", "") ?: "").trim(),
            key = (sp.getString("embApiKey", "") ?: "").trim(),
            model = (sp.getString("embModel", "text-embedding-3-small") ?: "text-embedding-3-small")
                .trim().ifEmpty { "text-embedding-3-small" }
        )
    }

    // 配置完整可用（启用 + 有链接 + 有 key）
    private fun EmbConfig.usable(): Boolean = enabled && url.isNotEmpty() && key.isNotEmpty()

    // 调 OpenAI 兼容 embedding API，返回 FloatArray，失败返回 null
    private fun fetchEmbedding(text: String, cfg: EmbConfig): FloatArray? {
        if (!cfg.usable() || text.isBlank()) return null
        return try {
            // 构建 embedding endpoint：去掉 /chat/completions，补 /v1，末尾接 /embeddings
            var base = if (cfg.url.endsWith("/")) cfg.url.dropLast(1) else cfg.url
            base = base.replace(Regex("/chat/completions$"), "")
            if (!base.contains("/v1")) base += "/v1"
            val embUrl = "$base/embeddings"

            val body = JSONObject().apply {
                put("model", cfg.model)
                put("input", text.take(1000))
            }.toString().toRequestBody("application/json".toMediaTypeOrNull())

            val req = Request.Builder()
                .url(embUrl)
                .addHeader("Authorization", "Bearer ${cfg.key}")
                .post(body)
                .build()

            val resp = Http.client.newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build().newCall(req).execute()

            if (!resp.isSuccessful) { resp.close(); return null }
            val arr = JSONObject(resp.body?.string() ?: return null)
                .getJSONArray("data")
                .getJSONObject(0)
                .getJSONArray("embedding")
            FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
        } catch (_: Exception) { null }
    }

    // 统一写入记忆：启用向量记忆时，写入当下就算好 embedding 存库（长期不用重算）
    // computeEmbedding=true 时才现算并存向量。默认 false：向量储存只跟随「30条消息总结」这一个节奏，
    // 关键事件 / AI生活等零散写入不各自触发 embedding，省钱且可控。
    private fun insertMemory(
        context: Context,
        db: android.database.sqlite.SQLiteDatabase,
        aiId: String,
        memoryText: String,
        category: String,
        computeEmbedding: Boolean = false
    ) {
        val cfg = embConfig(context)
        val embStr = if (computeEmbedding && cfg.usable()) {
            fetchEmbedding(memoryText, cfg)?.let { floatArrayToString(it) } ?: ""
        } else ""
        val cv = ContentValues().apply {
            put("aiId", aiId)
            put("memoryText", memoryText)
            put("insertTime", System.currentTimeMillis())
            put("category", category)
            put("embedding", embStr)
        }
        db.insert("MemoryBank", null, cv)
    }

    // 余弦相似度
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }

    // FloatArray 序列化为逗号字符串存 SQLite
    private fun floatArrayToString(arr: FloatArray): String = arr.joinToString(",")
    private fun stringToFloatArray(s: String): FloatArray? {
        if (s.isEmpty()) return null
        return try { s.split(",").map { it.toFloat() }.toFloatArray() } catch (_: Exception) { null }
    }

    // ══════════════════════════════════════════════
    // 第一层：30轮触发总结（时间线记忆）
    // ══════════════════════════════════════════════
    fun checkAndSummarizeMemory(context: Context, aiId: String, aiName: String) {
        if (aiId.isEmpty()) return
        if (!summarizingAiIds.add(aiId)) return
        Thread {
            try {
                val db = DatabaseHelper(context).writableDatabase
                ensureMemorySchema(db)
                val sharedPref = context.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                val lastCountKey = "memoryLastCount_$aiId"
                val lastCount = sharedPref.getInt(lastCountKey, 0)

                val totalCursor = db.rawQuery(
                    "SELECT COUNT(*) FROM ChatHistory WHERE aiId=? AND IFNULL(groupId,'')=''",
                    arrayOf(aiId)
                )
                val totalCount = if (totalCursor.moveToFirst()) totalCursor.getInt(0) else 0
                totalCursor.close()

                val newMsgCount = totalCount - lastCount
                val lastRunAt = sharedPref.getLong("memoryLastRunAt_$aiId", 0L)
                val nowMs = System.currentTimeMillis()
                if (lastCount > 0 && newMsgCount < SUMMARY_MIN_NEW_MSG_COUNT) return@Thread
                if (nowMs - lastRunAt < SUMMARY_MIN_INTERVAL_MS) return@Thread
                sharedPref.edit().putLong("memoryLastRunAt_$aiId", nowMs).apply()

                val lastSummarizedTime = sharedPref.getLong("memoryLastTime_$aiId", 0L)
                val msgs = mutableListOf<String>()
                val msgCursor = db.rawQuery(
                    """
                    SELECT content, isFromMe FROM (
                        SELECT content, isFromMe, timestamp FROM ChatHistory
                        WHERE aiId=? AND IFNULL(groupId,'')='' AND timestamp > ?
                        ORDER BY timestamp DESC LIMIT $SUMMARY_MAX_CHAT_LINES
                    ) ORDER BY timestamp ASC
                    """.trimIndent(),
                    arrayOf(aiId, lastSummarizedTime.toString())
                )
                while (msgCursor.moveToNext()) {
                    val content = (msgCursor.getString(0) ?: "").take(220)
                    if (content.isBlank()) continue
                    val sender = if (msgCursor.getInt(1) == 1) "我" else aiName
                    msgs.add("$sender: $content")
                }
                msgCursor.close()

                val groupMsgs = mutableListOf<String>()
                val groupCursor = db.rawQuery(
                    "SELECT content, isFromMe FROM ChatHistory WHERE aiId=? AND groupId IS NOT NULL AND groupId!='' ORDER BY timestamp DESC LIMIT 10",
                    arrayOf(aiId)
                )
                while (groupCursor.moveToNext()) {
                    val sender = if (groupCursor.getInt(1) == 1) "我" else aiName
                    groupMsgs.add(0, "[群聊]$sender: ${(groupCursor.getString(0) ?: "").take(160)}")
                }
                groupCursor.close()

                val momentMsgs = mutableListOf<String>()
                val momentCursor = db.rawQuery(
                    "SELECT content FROM Moments WHERE aiId=? ORDER BY timestamp DESC LIMIT 5",
                    arrayOf(aiId)
                )
                while (momentCursor.moveToNext()) {
                    momentMsgs.add("[朋友圈]$aiName 发了动态: ${(momentCursor.getString(0) ?: "").take(160)}")
                }
                momentCursor.close()

                val allContent = msgs + groupMsgs + momentMsgs
                if (allContent.isEmpty()) return@Thread
                val chatLog = allContent.joinToString("\n").takeLast(3600)

                val url = sharedPref.getString("apiUrl", "") ?: return@Thread
                val key = sharedPref.getString("apiKey", "") ?: return@Thread
                val model = sharedPref.getString("modelName", "") ?: return@Thread
                val finalUrl = buildUrl(url)

                // 去重：同一天 timeline 最多6条
                val todayStr = java.text.SimpleDateFormat("MM月dd日", java.util.Locale.CHINA).format(java.util.Date())
                val existCount = db.rawQuery(
                    "SELECT COUNT(*) FROM MemoryBank WHERE aiId=? AND category='timeline' AND memoryText LIKE ?",
                    arrayOf(aiId, "%$todayStr%")
                ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                if (existCount >= 6) return@Thread

                var myRealName = "对方"
                try {
                    db.rawQuery("SELECT myName FROM MyProfile LIMIT 1", null).use { c ->
                        if (c.moveToFirst()) myRealName = c.getString(0)?.ifEmpty { "对方" } ?: "对方"
                    }
                } catch (_: Exception) {}

                val prompt = """
请将以下对话总结为「$aiName」的长期记忆片段。
要求：
1. 保留关键情节、$myRealName 的喜好、重要承诺、情绪变化
2. 用第三人称简洁描述，100字以内，称呼对方时用「$myRealName」而不是「用户」
3. 开头标注时间：$todayStr

对话内容：
$chatLog
                """.trimIndent()

                val summary = callApi(finalUrl, key, model, prompt)
                if (summary.isNullOrBlank()) {
                    // 总结失败时不要反复重试拖垮下一轮正常聊天；下次累积足够新消息再总结。
                    sharedPref.edit().putInt(lastCountKey, totalCount).apply()
                    return@Thread
                }
                val memoryText = "[时间线]${summary.take(300)}"

                // 只有这里（30条消息触发的总结）才现算并储存向量
                insertMemory(context, db, aiId, memoryText, "timeline", computeEmbedding = true)

                // 清理旧 timeline，保留最近 MEMORY_KEEP_PER_CATEGORY 条
                db.execSQL("""
DELETE FROM MemoryBank WHERE id IN (
    SELECT id FROM MemoryBank WHERE aiId='$aiId' AND category='timeline'
    ORDER BY insertTime DESC LIMIT -1 OFFSET $MEMORY_KEEP_PER_CATEGORY
)
                """.trimIndent())

                sharedPref.edit()
                    .putInt(lastCountKey, totalCount)
                    .putLong("memoryLastTime_$aiId", System.currentTimeMillis())
                    .apply()

            } catch (_: Exception) {
            } finally {
                summarizingAiIds.remove(aiId)
            }
        }.start()
    }

    // ══════════════════════════════════════════════
    // 第二层：关键事件检测
    // ══════════════════════════════════════════════
    fun checkKeyEvent(context: Context, aiId: String, aiName: String, userMsg: String, aiReply: String) {
        Thread {
            try {
                val keyEventWords = listOf(
                    "喜欢你", "爱你", "表白", "在一起", "想你",
                    "生日", "纪念日", "约定", "答应我", "记住",
                    "对不起", "吵架", "误会", "和好",
                    "第一次", "第一个", "从来没有", "永远",
                    "秘密", "只告诉你", "不能告诉别人",
                    "i like you", "i love you", "promise", "never forget",
                    "birthday", "anniversary"
                )
                val combined = userMsg + aiReply
                val isKeyEvent = keyEventWords.any { combined.contains(it, ignoreCase = true) }
                if (!isKeyEvent) return@Thread

                val db = DatabaseHelper(context).writableDatabase
                ensureMemorySchema(db)
                val sharedPref = context.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                val url = sharedPref.getString("apiUrl", "") ?: return@Thread
                val key = sharedPref.getString("apiKey", "") ?: return@Thread
                val model = sharedPref.getString("modelName", "") ?: return@Thread
                val finalUrl = buildUrl(url)

                val dateStr = java.text.SimpleDateFormat("MM月dd日", java.util.Locale.CHINA).format(java.util.Date())
                val prompt = """
以下是一段对话中的关键时刻，请用一句话（30字以内）提炼为核心记忆片段。
格式：[$dateStr] + 一句话描述
只输出这一句话，不要其他内容。

对话：
我：${userMsg.take(300)}
${aiName}：${aiReply.take(300)}
                """.trimIndent()

                val memory = callApi(finalUrl, key, model, prompt) ?: return@Thread

                insertMemory(context, db, aiId, "[关键]$memory", "key_event")

                // 保留最近 MEMORY_KEEP_PER_CATEGORY 条 key_event
                db.execSQL("""
DELETE FROM MemoryBank WHERE id IN (
    SELECT id FROM MemoryBank WHERE aiId='$aiId' AND category='key_event'
    ORDER BY insertTime DESC LIMIT -1 OFFSET $MEMORY_KEEP_PER_CATEGORY
)
                """.trimIndent())
            } catch (_: Exception) {}
        }.start()
    }

    // ══════════════════════════════════════════════
    // 第三层：ai_life 事件
    // ══════════════════════════════════════════════
    fun checkAiLifeEvent(context: Context, aiId: String, aiName: String, aiMsg: String) {
        if (aiId.isEmpty() || aiMsg.isEmpty()) return
        val lifeKeywords = listOf("今天", "刚才", "刚刚", "任务", "回来", "去了", "遇到", "发生", "看到", "听说", "完成", "结束", "出发", "到了")
        val hasLifeEvent = lifeKeywords.any { aiMsg.contains(it) }
        if (!hasLifeEvent || aiMsg.length < 10) return

        Thread {
            try {
                val db = DatabaseHelper(context).writableDatabase
                ensureMemorySchema(db)
                val dateStr = java.text.SimpleDateFormat("MM月dd日", java.util.Locale.CHINA).format(java.util.Date())
                val existCount = db.rawQuery(
                    "SELECT COUNT(*) FROM MemoryBank WHERE aiId=? AND category='ai_life' AND memoryText LIKE ?",
                    arrayOf(aiId, "%$dateStr%")
                ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
                if (existCount >= 3) return@Thread

                insertMemory(context, db, aiId, "[$dateStr] $aiName 说：${aiMsg.take(80)}", "ai_life")
            } catch (_: Exception) {}
        }.start()
    }

    // ══════════════════════════════════════════════
    // 核心：向量召回
    // ══════════════════════════════════════════════
    fun recallRelevantMemory(context: Context, aiId: String, currentMsg: String): String {
        return try {
            val db = DatabaseHelper(context).writableDatabase
            ensureMemorySchema(db)
            val cfg = embConfig(context)

            data class MemoryRow(val id: Long, val text: String, val category: String, var embStr: String)
            val rows = mutableListOf<MemoryRow>()
            db.rawQuery(
                "SELECT id, memoryText, category, IFNULL(embedding,'') FROM MemoryBank WHERE aiId=? ORDER BY insertTime DESC LIMIT $RECALL_MAX_ROWS",
                arrayOf(aiId)
            ).use { c ->
                while (c.moveToNext()) {
                    rows.add(MemoryRow(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "misc", c.getString(3) ?: ""))
                }
            }
            if (rows.isEmpty()) return ""

            // key_event 和手动记忆永远全量保留（这些通常条数少）
            val alwaysInclude = setOf("key_event", "user_info", "shared_event", "future_plan", "pet_bond")
            val fixed = rows.filter { it.category in alwaysInclude }.take(6).map { it.text.take(180) }

            val candidates = rows.filter { it.category !in alwaysInclude }
            if (candidates.isEmpty()) return fixed.distinct().joinToString("\n").take(900)

            // 关键词本地打分：无向量配置时的主力，也是向量失败时的兜底
            val keywords = currentMsg
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .split(" ")
                .filter { it.length >= 2 }
                .take(8)
            fun localScore(text: String): Int = keywords.sumOf { kw ->
                if (text.contains(kw, ignoreCase = true)) 3 else 0
            } + if (currentMsg.length >= 8 && text.contains(currentMsg.take(8), ignoreCase = true)) 5 else 0

            val queryVec = if (cfg.usable() && currentMsg.isNotBlank()) fetchEmbedding(currentMsg.take(1000), cfg) else null

            val scored: List<String> = if (queryVec != null) {
                // 召回不再零散补算 embedding。已有向量的（总结记忆）走余弦，没向量的用关键词分兜底
                // （缩放到很小，优先让有向量的语义命中排前面）
                candidates.map { row ->
                    val vec = stringToFloatArray(row.embStr)
                    val score = if (vec != null) cosineSimilarity(queryVec, vec)
                                else localScore(row.text).toFloat() / 100f
                    Pair(row.text, score)
                }.sortedByDescending { it.second }.take(5).map { it.first.take(240) }
            } else {
                // 没启用向量记忆 / 向量请求失败 → 纯关键词召回
                candidates
                    .map { Pair(it, localScore(it.text)) }
                    .sortedWith(compareByDescending<Pair<MemoryRow, Int>> { it.second }.thenByDescending { it.first.id })
                    .take(5)
                    .map { it.first.text.take(240) }
            }

            (fixed + scored).distinct().joinToString("\n").take(900)
        } catch (_: Exception) { "" }
    }

    // ══════════════════════════════════════════════
    // 工具函数
    // ══════════════════════════════════════════════
    private fun buildUrl(url: String): String {
        var u = if (url.endsWith("/")) url.dropLast(1) else url
        if (!u.endsWith("/chat/completions")) {
            u += if (u.contains("/v1")) "/chat/completions" else "/v1/chat/completions"
        }
        return u
    }

    private fun callApi(url: String, key: String, model: String, prompt: String): String? {
        return try {
            val body = JSONObject().apply {
                put("model", model)
                put("temperature", 0.3)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt.take(4000))
                    })
                })
            }.toString().toRequestBody("application/json".toMediaTypeOrNull())

            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $key")
                .post(body)
                .build()

            val resp = Http.client.newBuilder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(35, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build().newCall(req).execute()

            if (resp.isSuccessful) {
                JSONObject(resp.body?.string() ?: return null)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
            } else null
        } catch (_: Exception) { null }
    }
}
