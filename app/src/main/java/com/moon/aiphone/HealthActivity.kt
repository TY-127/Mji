package com.moon.aiphone

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
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
class HealthActivity : AppCompatActivity() {

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private lateinit var contentLayout: LinearLayout
    private var isAnalyzing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val db = DatabaseHelper(this).writableDatabase
            db.execSQL("CREATE TABLE IF NOT EXISTS UserDailyMeals (id INTEGER PRIMARY KEY AUTOINCREMENT, dateStr TEXT, breakfast TEXT, lunch TEXT, dinner TEXT, totalKcal INTEGER, comment TEXT, commentAiName TEXT)")
        } catch (_: Exception) {}

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2F2F6"))
            layoutParams = LinearLayout.LayoutParams(-1, -1)
        }

        // 顶栏
        val topBar = RelativeLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(50))
            setBackgroundColor(Color.WHITE)
        }
        val btnBack = TextView(this).apply {
            text = "‹"; textSize = 32f
            setPadding(dp(16), 0, dp(16), dp(4))
            setTextColor(Color.parseColor("#111111"))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RelativeLayout.LayoutParams(-2, -1).also {
                it.addRule(RelativeLayout.ALIGN_PARENT_START)
                it.addRule(RelativeLayout.CENTER_VERTICAL)
            }
            setOnClickListener { finish() }
        }
        val tvTitle = TextView(this).apply {
            text = "健康饮食"; textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111111"))
            layoutParams = RelativeLayout.LayoutParams(-2, -2).also {
                it.addRule(RelativeLayout.CENTER_IN_PARENT)
            }
        }
        topBar.addView(btnBack); topBar.addView(tvTitle)
        root.addView(topBar)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scrollView.addView(contentLayout)
        root.addView(scrollView)

        setContentView(root)
        loadContent()
    }

    private fun loadContent() {
        contentLayout.removeAllViews()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val db = DatabaseHelper(this).readableDatabase

        val cur = db.rawQuery(
            "SELECT breakfast, lunch, dinner, totalKcal, comment, commentAiName\n" +
                    "FROM UserDailyMeals\n" +
                    "WHERE dateStr=?\n" +
                    "ORDER BY id DESC\n" +
                    "LIMIT 1",
            arrayOf(today)
        )
        var breakfast = ""; var lunch = ""; var dinner = ""
        var totalKcal = 0; var comment = ""; var commentAiName = ""
        if (cur.moveToFirst()) {
            breakfast = cur.getString(0) ?: ""
            lunch = cur.getString(1) ?: ""
            dinner = cur.getString(2) ?: ""
            totalKcal = cur.getInt(3)
            comment = cur.getString(4) ?: ""
            commentAiName = cur.getString(5) ?: ""
        }
        cur.close()

        // ===== 三餐输入卡片 =====
        val meals = listOf(
            Triple("🌅 早餐", breakfast, "早餐吃了什么"),
            Triple("☀️ 午餐", lunch, "午餐吃了什么"),
            Triple("🌙 晚餐", dinner, "晚餐吃了什么")
        )
        val etList = mutableListOf<EditText>()

        meals.forEach { (label, value, hint) ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.WHITE); cornerRadius = dp(12).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(10) }
            }
            card.addView(TextView(this).apply {
                text = label; textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#333333"))
                layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) }
            })
            val et = EditText(this).apply {
                this.hint = hint
                textSize = 14f; setTextColor(Color.BLACK)
                setHintTextColor(Color.parseColor("#BBBBBB"))
                if (value.isNotEmpty()) setText(value)
                minLines = 2
                background = android.graphics.drawable.GradientDrawable().apply {
                    setStroke(1, Color.parseColor("#EEEEEE")); cornerRadius = dp(8).toFloat()
                }
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            }
            etList.add(et)
            card.addView(et)
            contentLayout.addView(card)
        }

        // ===== 保存按钮 =====
        val btnSave = TextView(this).apply {
            text = "保存并分析"; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#FF9500")); cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(-1, dp(46)).also { it.bottomMargin = dp(16) }
        }
        contentLayout.addView(btnSave)

        // ===== 卡路里卡片（有数据才显示）=====
        if (totalKcal > 0) {
            renderKcalCard(totalKcal, breakfast, lunch, dinner)
        }

        // ===== 角色评论 =====
        if (comment.isNotEmpty()) {
            contentLayout.addView(TextView(this).apply {
                text = "── 角色评价 ──"; textSize = 13f; gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#AAAAAA"))
                layoutParams = LinearLayout.LayoutParams(-1, -2).also {
                    it.bottomMargin = dp(10)
                }
            })
            renderComment(commentAiName, comment)
        }

        // 保存逻辑
        btnSave.setOnClickListener {
            if (isAnalyzing) return@setOnClickListener
            val bf = etList[0].text.toString().trim()
            val lc = etList[1].text.toString().trim()
            val dn = etList[2].text.toString().trim()
            if (bf.isEmpty() && lc.isEmpty() && dn.isEmpty()) {
                Toast.makeText(this, "请至少填写一餐", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            isAnalyzing = true
            btnSave.text = "计算中…"
            btnSave.isClickable = false
            generateKcalAndComment(today, bf, lc, dn)
        }
    }

    private fun renderKcalCard(totalKcal: Int, breakfast: String, lunch: String, dinner: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#FFF8F0")); cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(12) }
        }

        // 总卡路里
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(12) }
        }
        headerRow.addView(TextView(this).apply {
            text = "🍽️"; textSize = 26f
            layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.marginEnd = dp(12) }
        })
        val kcalInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        kcalInfo.addView(TextView(this).apply {
            text = "今日摄入 $totalKcal 千卡"
            textSize = 20f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FF6B00"))
        })
        val level = when {
            totalKcal < 1200 -> "摄入偏少，注意营养均衡"
            totalKcal < 1800 -> "摄入适中，继续保持 💪"
            totalKcal < 2500 -> "摄入较多，适当运动消耗一下"
            else -> "摄入过多，明天注意控制哦"
        }
        kcalInfo.addView(TextView(this).apply {
            text = level; textSize = 12f
            setTextColor(Color.parseColor("#888888"))
        })
        headerRow.addView(kcalInfo)
        card.addView(headerRow)

        // 三餐分项
        listOf("早餐" to breakfast, "午餐" to lunch, "晚餐" to dinner).forEach { (label, content) ->
            if (content.isNotEmpty()) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(4) }
                }
                row.addView(TextView(this).apply {
                    text = label; textSize = 13f
                    setTextColor(Color.parseColor("#FF9500"))
                    setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(dp(40), -2)
                })
                row.addView(TextView(this).apply {
                    text = content; textSize = 13f
                    setTextColor(Color.parseColor("#555555"))
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.marginStart = dp(8) }
                })
                card.addView(row)
            }
        }
        contentLayout.addView(card)
    }

    private fun renderComment(aiName: String, comment: String) {
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        bubble.addView(TextView(this).apply {
            text = aiName; textSize = 13f
            setTextColor(Color.parseColor("#FF9500"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(6) }
        })
        bubble.addView(TextView(this).apply {
            text = comment; textSize = 15f
            setTextColor(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        })
        contentLayout.addView(bubble)
    }

    private fun generateKcalAndComment(today: String, breakfast: String, lunch: String, dinner: String) {
        val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val apiKey = pref.getString("apiKey", "") ?: ""
        var apiUrl = (pref.getString("apiUrl", "") ?: "").trimEnd('/')
        val model = pref.getString(
            "modelName",
            ""
        )?.ifBlank { "gpt-4o" } ?: "gpt-4o"
        if (apiKey.isEmpty() || apiUrl.isEmpty()) {
            Toast.makeText(this, "未配置API", Toast.LENGTH_SHORT).show()
            return
        }
        if (!apiUrl.endsWith("/chat/completions"))
            apiUrl += if (apiUrl.contains("/v1")) "/chat/completions" else "/v1/chat/completions"
        val db = DatabaseHelper(this).readableDatabase

        var myId = "my_id"
        try {
            db.rawQuery("SELECT myId FROM MyProfile LIMIT 1", null).use { c ->
                if (c.moveToFirst()) myId = c.getString(0) ?: "my_id"
            }
        } catch (_: Exception) {}
        val cur = db.rawQuery(
            """
    SELECT userId, realName, identityInfo
    FROM Contacts
    WHERE userId != ?
      AND userId IS NOT NULL
      AND TRIM(userId) <> ''
      AND id IN (
          SELECT MAX(id)
          FROM Contacts
          WHERE userId IS NOT NULL
            AND TRIM(userId) <> ''
          GROUP BY userId
      )
    ORDER BY RANDOM()
    LIMIT 1
    """.trimIndent(),
            arrayOf(myId)
        )
        var aiId2 = ""; var aiName = ""; var aiPersona = ""
        if (cur.moveToFirst()) {
            aiId2 = cur.getString(0) ?: ""
            aiName = cur.getString(1) ?: ""
            aiPersona = cur.getString(2) ?: ""
        }
        cur.close()
        if (aiName.isEmpty()) {
            runOnUiThread {
                Toast.makeText(
                    this@HealthActivity,
                    "暂无可用角色",
                    Toast.LENGTH_SHORT
                ).show()
                loadContent()
            }
            return
        }

        val aiLang2 = pref.getString("aiLang_$aiId2", "默认 (中文)") ?: "默认 (中文)"
        val langNote = if (aiLang2 != "默认 (中文)") "【语言规则：评价必须用${aiLang2}，在评价末尾加【翻译】中文翻译】" else ""

        val mealSummary = listOf(
            "早餐" to breakfast, "午餐" to lunch, "晚餐" to dinner
        ).filter { it.second.isNotEmpty() }
            .joinToString("，") { "${it.first}：${it.second}" }

        val prompt = """
用户今天的饮食记录如下：$mealSummary

请完成两件事并严格按JSON格式输出，只输出JSON不要其他内容：
1. 估算今日总摄入卡路里（整数，单位千卡）
2. 你是 $aiName，人设：$aiPersona，用你的口吻对用户今天的饮食写一段评价：
   - 2~4句话，口语化自然
   - 可以担心营养、夸好吃、心疼吃太少、调侃吃太多
   - 严禁羞辱或过激批评
   $langNote
   - 直接写评价内容，不加任何格式标签

输出格式：
{"kcal": 1500, "comment": "评价内容（如有翻译则包含【翻译】中文翻译）"}
        """.trimIndent()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = Http.client.newBuilder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS).build()
                val body = JSONObject().apply {
                    put("model", model)
                    put("max_tokens", 400)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                    })
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())

                val req = Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(body).build()
                val resp = client.newCall(req).execute()

                if (!resp.isSuccessful) {
                    android.util.Log.e(
                        "HealthActivity",
                        "API失败:${resp.code}"
                    )
                    return@launch
                }

                val result =
                    resp.body?.string()
                        ?: return@launch
                val raw = JSONObject(result).getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").getString("content").trim()
                val rawCleaned = raw.replace(Regex("```json|```"), "").trim()
                val jsonMatch = Regex("""\{[\s\S]*\}""").find(rawCleaned)
                val clean = jsonMatch?.value?.trim() ?: rawCleaned
                val data = try { JSONObject(clean) } catch (_: Exception) { JSONObject() }
                if (data.length() == 0) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@HealthActivity, "生成失败，请稍后重试", Toast.LENGTH_SHORT).show()
                        loadContent()
                    }
                    return@launch
                }
                val kcal = data.optInt("kcal", 0)
                val comment = data.optString("comment", "")

                val wdb = DatabaseHelper(this@HealthActivity).writableDatabase
                wdb.execSQL("DELETE FROM UserDailyMeals WHERE dateStr=?", arrayOf(today))
                val cv = ContentValues().apply {
                    put("dateStr", today)
                    put("breakfast", breakfast)
                    put("lunch", lunch)
                    put("dinner", dinner)
                    put("totalKcal", kcal)
                    put("comment", comment)
                    put("commentAiName", aiName)
                }
                wdb.insert("UserDailyMeals", null, cv)

                withContext(Dispatchers.Main) {
                    isAnalyzing = false
                    loadContent()
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    isAnalyzing = false
                    Toast.makeText(this@HealthActivity, "生成失败，请稍后重试", Toast.LENGTH_SHORT).show()
                    loadContent()
                }
            }
        }
    }
}