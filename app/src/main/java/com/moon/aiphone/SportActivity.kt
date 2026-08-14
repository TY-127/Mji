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

class SportActivity : AppCompatActivity() {

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private lateinit var scrollView: ScrollView
    private lateinit var contentLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val db = DatabaseHelper(this).writableDatabase
            db.execSQL("CREATE TABLE IF NOT EXISTS AiDailySteps (id INTEGER PRIMARY KEY AUTOINCREMENT, aiId TEXT, aiName TEXT, steps INTEGER, dateStr TEXT)")
            db.execSQL(
                """
    CREATE TABLE IF NOT EXISTS UserDailySport (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        dateStr TEXT,
        steps INTEGER,
        sportDesc TEXT,
        weightKg REAL,
        comment TEXT,
        commentAiName TEXT,
        commentGeneratedAt INTEGER
    )
    """.trimIndent()
            )
            try {
                db.execSQL("ALTER TABLE UserDailySport ADD COLUMN commentAiName TEXT DEFAULT ''")
            } catch (_: Exception) {}
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
            text = "运动"; textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111111"))
            layoutParams = RelativeLayout.LayoutParams(-2, -2).also {
                it.addRule(RelativeLayout.CENTER_IN_PARENT)
            }
        }
        topBar.addView(btnBack); topBar.addView(tvTitle)
        root.addView(topBar)

        scrollView = ScrollView(this).apply {
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
        val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val db = DatabaseHelper(this).readableDatabase

        // 读取今日用户数据
        val cur = db.rawQuery("SELECT steps, sportDesc, weightKg, comment, IFNULL(commentAiName,'') FROM UserDailySport WHERE dateStr=?", arrayOf(today))
        var userSteps = 0; var sportDesc = ""; var weightKg = 0.0; var existingComment = ""; var existingCommentAiName = ""
        if (cur.moveToFirst()) {
            userSteps = cur.getInt(0)
            sportDesc = cur.getString(1) ?: ""
            weightKg = cur.getDouble(2)
            existingComment = cur.getString(3) ?: ""
            existingCommentAiName = cur.getString(4) ?: ""
        }
        cur.close()

        // ===== 输入卡片 =====
        val inputCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(12) }
        }
        inputCard.addView(TextView(this).apply {
            text = "今日运动"; textSize = 15f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111111"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(12) }
        })

        // 步数输入
        val etSteps = EditText(this).apply {
            hint = "今日步数"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 15f; setTextColor(Color.BLACK)
            if (userSteps > 0) setText(userSteps.toString())
            background = android.graphics.drawable.GradientDrawable().apply {
                setStroke(1, Color.parseColor("#DDDDDD")); cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(10) }
        }
        inputCard.addView(etSteps)

        // 体重输入
        val savedWeight = pref.getFloat("userWeightKg", 0f)
        val etWeight = EditText(this).apply {
            hint = "体重（kg）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 15f; setTextColor(Color.BLACK)
            if (savedWeight > 0) setText(savedWeight.toString())
            background = android.graphics.drawable.GradientDrawable().apply {
                setStroke(1, Color.parseColor("#DDDDDD")); cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(10) }
        }
        inputCard.addView(etWeight)

        // 运动描述
        val etSport = EditText(this).apply {
            hint = "今日运动情况（如：跑步30分钟、游泳1小时）"
            textSize = 15f; setTextColor(Color.BLACK)
            if (sportDesc.isNotEmpty()) setText(sportDesc)
            minLines = 2
            background = android.graphics.drawable.GradientDrawable().apply {
                setStroke(1, Color.parseColor("#DDDDDD")); cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(14) }
        }
        inputCard.addView(etSport)

        val btnSave = TextView(this).apply {
            text = "保存并生成评价"; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#34C759")); cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(-1, dp(46))
        }
        inputCard.addView(btnSave)
        contentLayout.addView(inputCard)

        // ===== 卡路里卡片 =====
        if (userSteps > 0 && weightKg > 0) {
            val kcal = calcCalories(userSteps, weightKg)
            val kcalCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#FFF5E6")); cornerRadius = dp(12).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(12) }
                gravity = Gravity.CENTER_VERTICAL
            }
            kcalCard.addView(TextView(this).apply {
                text = "🔥"; textSize = 28f
                layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.marginEnd = dp(14) }
            })
            val kcalInfo = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            kcalInfo.addView(TextView(this).apply {
                text = "$kcal 千卡"; textSize = 22f; setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#FF6B00"))
            })
            kcalInfo.addView(TextView(this).apply {
                text = "今日消耗热量（$userSteps 步 · ${weightKg}kg）"
                textSize = 12f; setTextColor(Color.parseColor("#888888"))
            })
            kcalCard.addView(kcalInfo)
            contentLayout.addView(kcalCard)
        }

        // ===== 微信运动排名 =====
        val rankCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(12) }
        }
        rankCard.addView(TextView(this).apply {
            text = "步数排行榜"; textSize = 15f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111111"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(12) }
        })

        // 构建排名列表：用户 + 所有AI
        data class RankEntry(val name: String, val steps: Int, val isMe: Boolean)
        val entries = mutableListOf<RankEntry>()
        if (userSteps > 0) {
            val myName = getSharedPreferences("AppConfig", Context.MODE_PRIVATE).getString("myName", "我") ?: "我"
            entries.add(RankEntry(myName, userSteps, true))
        }

        val aiCur = db.rawQuery("SELECT aiName, MAX(steps)\n" +
                "FROM AiDailySteps\n" +
                "WHERE dateStr=?\n" +
                "GROUP BY aiId", arrayOf(today))
        while (aiCur.moveToNext()) {
            entries.add(RankEntry(aiCur.getString(0), aiCur.getInt(1), false))
        }
        aiCur.close()

        if (entries.isEmpty()) {
            rankCard.addView(TextView(this).apply {
                text = "暂无数据，填写步数后显示排名"
                textSize = 13f; setTextColor(Color.parseColor("#AAAAAA"))
                layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) }
            })
        } else {
            entries.sortByDescending { it.steps }
            val medals = listOf("🥇", "🥈", "🥉")
            entries.forEachIndexed { i, entry ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                    layoutParams = LinearLayout.LayoutParams(-1, -2)
                    if (entry.isMe) setBackgroundColor(Color.parseColor("#F0FFF4"))
                }
                row.addView(TextView(this).apply {
                    text = if (i < 3) medals[i] else "${i + 1}"
                    textSize = if (i < 3) 20f else 14f
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor("#888888"))
                    layoutParams = LinearLayout.LayoutParams(dp(36), -2)
                })
                row.addView(TextView(this).apply {
                    text = entry.name
                    textSize = 15f
                    setTextColor(if (entry.isMe) Color.parseColor("#34C759") else Color.parseColor("#111111"))
                    setTypeface(null, if (entry.isMe) Typeface.BOLD else Typeface.NORMAL)
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                })
                // 步数进度条
                val maxSteps =
                    entries.maxOf { it.steps }
                        .coerceAtLeast(1)
                        .toFloat()
                val barWrap = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(dp(120), -2)
                }
                barWrap.addView(TextView(this).apply {
                    text = "${entry.steps}步"
                    textSize = 12f
                    setTextColor(if (entry.isMe) Color.parseColor("#34C759") else Color.parseColor("#555555"))
                    gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(-1, -2)
                })
                val barBg = android.view.View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(-1, dp(4)).also { it.topMargin = dp(3) }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#EEEEEE")); cornerRadius = dp(2).toFloat()
                    }
                }
                // 用 post 来在布局完成后绘制进度
                val barFill = android.view.View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ((entry.steps / maxSteps) * dp(120)).toInt().coerceAtLeast(dp(4)), dp(4)
                    ).also { it.topMargin = dp(3) }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(if (entry.isMe) Color.parseColor("#34C759") else Color.parseColor("#007AFF"))
                        cornerRadius = dp(2).toFloat()
                    }
                }
                barWrap.addView(barFill)
                row.addView(barWrap)
                rankCard.addView(row)

                if (i < entries.size - 1) {
                    rankCard.addView(android.view.View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(-1, 1).also { it.marginStart = dp(36) }
                        setBackgroundColor(Color.parseColor("#F0F0F0"))
                    })
                }
            }
        }
        contentLayout.addView(rankCard)

        // ===== 角色评价 =====
        contentLayout.addView(TextView(this).apply {
            text = "── 今日评价 ──"; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#AAAAAA"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).also {
                it.topMargin = dp(8); it.bottomMargin = dp(10)
            }
        })

        if (existingComment.isNotEmpty()) {
            renderComment(existingComment, existingCommentAiName)
        } else if (userSteps > 0) {
            contentLayout.addView(TextView(this).apply {
                text = "保存运动数据后生成评价～"
                textSize = 13f; gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#BBBBBB"))
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            })
        } else {
            contentLayout.addView(TextView(this).apply {
                text = "填写今日步数后，角色会来评价你哦～"
                textSize = 13f; gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#BBBBBB"))
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            })
        }

        // 保存按钮逻辑
        btnSave.setOnClickListener {
            val stepsStr = etSteps.text.toString().trim()
            val weightStr = etWeight.text.toString().trim()
            val sport = etSport.text.toString().trim()
            if (stepsStr.isEmpty()) { Toast.makeText(this, "请输入步数", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (weightStr.isEmpty()) { Toast.makeText(this, "请输入体重", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val steps = stepsStr.toIntOrNull() ?: return@setOnClickListener
            val weight = weightStr.toDoubleOrNull() ?: return@setOnClickListener
            // 保存体重到pref供下次自动填入
            pref.edit().putFloat("userWeightKg", weight.toFloat()).apply()
            Thread {
                try {
                    val wdb = DatabaseHelper(this).writableDatabase
                    db.execSQL("DELETE FROM UserDailySport WHERE dateStr=?", arrayOf(today))
                    val cv = ContentValues().apply {
                        put("dateStr", today)
                        put("steps", steps)
                        put("sportDesc", sport)
                        put("weightKg", weight)
                        put("comment", "")
                        put("commentAiName", "")
                        put("commentGeneratedAt", 0L)
                    }
                    wdb.insert("UserDailySport", null, cv)
                } catch (_: Exception) {}
                runOnUiThread {
                    Toast.makeText(this, "已保存，正在生成评价…", Toast.LENGTH_SHORT).show()
                    generateComment(today, steps, weight, sport)
                }
            }.start()
        }
    }

    private fun calcCalories(steps: Int, weightKg: Double): Int {
        // 公式：步数 × 体重kg × 0.0006（千卡/步/kg）
        return (steps * weightKg * 0.0006).toInt()
    }

    private fun generateComment(today: String, steps: Int, weightKg: Double, sportDesc: String) {
        val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val apiKey = pref.getString("apiKey", "") ?: ""
        var apiUrl = (pref.getString("apiUrl", "") ?: "").trimEnd('/')
        val model = pref.getString("modelName", "") ?: ""
        if (apiKey.isEmpty() || apiUrl.isEmpty()) return
        if (!apiUrl.endsWith("/chat/completions"))
            apiUrl += if (apiUrl.contains("/v1")) "/chat/completions" else "/v1/chat/completions"


        val db = DatabaseHelper(this).readableDatabase
        var myId = "my_id"
        try {
            db.rawQuery("SELECT myId FROM MyProfile LIMIT 1", null).use { c ->
                if (c.moveToFirst()) myId = c.getString(0) ?: "my_id"
            }
        } catch (_: Exception) {}
        val cur = db.rawQuery("SELECT userId, realName, identityInfo FROM Contacts WHERE userId != ? ORDER BY RANDOM() LIMIT 1", arrayOf(myId))
        var aiId2 = ""; var aiName = ""; var aiPersona = ""
        if (cur.moveToFirst()) {
            aiId2 = cur.getString(0) ?: ""
            aiName = cur.getString(1) ?: ""
            aiPersona = cur.getString(2) ?: ""
        }
        cur.close()
        if (aiName.isEmpty()) return
        val pref2 = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val aiLang2 = pref2.getString("aiLang_$aiId2", "默认 (中文)") ?: "默认 (中文)"
        val langNote = if (aiLang2 != "默认 (中文)") "【语言规则：评价必须用${aiLang2}，在评价末尾加【翻译】中文翻译】" else ""

        val kcal = calcCalories(steps, weightKg)
        val prompt = """
你是 $aiName，人设：$aiPersona
用户今天走了 $steps 步，体重 ${weightKg}kg，消耗约 $kcal 千卡。
运动情况：${if (sportDesc.isEmpty()) "未填写" else sportDesc}
请用你的口吻，对用户今天的运动情况写一段评价，要求：
1. 2-4句话，口语化自然
2. 可以鼓励、心疼、调侃，但严禁嘲讽或羞辱
3. 结合步数多少给出不同反应（少：心疼/催促；多：夸奖/惊叹）
4. 直接输出评价内容，不要加任何标签或前缀

$langNote
        """.trimIndent()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = Http.client.newBuilder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS).build()
                val body = JSONObject().apply {
                    put("model", model)
                    put("max_tokens", 300)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                    })
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val req = Request.Builder().url(apiUrl).addHeader("Authorization", "Bearer $apiKey").post(body).build()
                val resp = client.newCall(req).execute()
                val result = resp.body?.string() ?: return@launch
                val comment = JSONObject(result).getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").getString("content").trim()

                val wdb = DatabaseHelper(this@SportActivity).writableDatabase
                wdb.execSQL("UPDATE UserDailySport\n" +
                        "SET comment=?,\n" +
                        "    commentAiName=?,\n" +
                        "    commentGeneratedAt=?\n" +
                        "WHERE dateStr=?",
                    arrayOf<Any>(
                        comment,
                        aiName,
                        System.currentTimeMillis(),
                        today
                    ))

                withContext(Dispatchers.Main) {
                    loadContent()
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SportActivity, "评价生成失败，请稍后重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun renderComment(comment: String, savedAiName: String = "") {
        val db = DatabaseHelper(this).readableDatabase
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val name = if (savedAiName.isNotBlank()) savedAiName else try {
            db.rawQuery("SELECT commentAiName FROM UserDailySport WHERE dateStr=?", arrayOf(today)).use { cur ->
                if (cur.moveToFirst()) cur.getString(0) ?: "角色" else "角色"
            }
        } catch (_: Exception) { "角色" }

        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        bubble.addView(TextView(this).apply {
            text = name; textSize = 13f
            setTextColor(Color.parseColor("#34C759"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(6) }
        })
        bubble.addView(TextView(this).apply {
            text = comment; textSize = 15f
            setTextColor(Color.parseColor("#333333"))
            lineHeight = (15 * 1.6 * resources.displayMetrics.scaledDensity).toInt()
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        })
        contentLayout.addView(bubble)
    }

}
