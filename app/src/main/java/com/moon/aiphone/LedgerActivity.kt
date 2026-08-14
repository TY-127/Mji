package com.moon.aiphone

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import android.widget.GridLayout


class LedgerActivity : AppCompatActivity() {

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private val categories = listOf(
        "🍜 吃饭", "🛒 日常开销", "👗 购物", "💍 买首饰",
        "🤝 人情往来", "🚗 交通出行", "🎮 娱乐",
        "💊 医疗健康", "📚 学习教育", "💰 其他收入", "💸 其他支出"
    )
    private val incomeCategories = setOf("💰 其他收入", "🤝 人情往来")

    private lateinit var scrollView: ScrollView
    private lateinit var contentLayout: LinearLayout
    private var currentTab = "day" // day / week / month

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -1)
            setBackgroundColor(Color.parseColor("#F2F2F6"))
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
            text = "记账本"; textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111111"))
            layoutParams = RelativeLayout.LayoutParams(-2, -2).also {
                it.addRule(RelativeLayout.CENTER_IN_PARENT)
            }
        }
        val btnAdd = TextView(this).apply {
            text = "+ 记一笔"; textSize = 14f
            setTextColor(Color.parseColor("#007AFF"))
            setPadding(0, 0, dp(16), 0)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RelativeLayout.LayoutParams(-2, -1).also {
                it.addRule(RelativeLayout.ALIGN_PARENT_END)
                it.addRule(RelativeLayout.CENTER_VERTICAL)
            }
            setOnClickListener { showAddDialog() }
        }
        topBar.addView(btnBack); topBar.addView(tvTitle); topBar.addView(btnAdd)
        root.addView(topBar)

        // tab栏
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(44))
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }
        listOf("今日" to "day", "本周" to "week", "本月" to "month").forEach { (label, key) ->
            val btn = TextView(this).apply {
                text = label; textSize = 14f; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                setTextColor(if (key == currentTab) Color.WHITE else Color.parseColor("#555555"))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(if (key == currentTab) Color.parseColor("#007AFF") else Color.parseColor("#F0F0F0"))
                }
                setOnClickListener {
                    currentTab = key
                    refreshContent()
                    // 刷新tab颜色
                    (parent as LinearLayout).children().forEach { v ->
                        (v as? TextView)?.let { tv ->
                            val selected = tv.text.toString() == label

                            tv.setTextColor(
                                if (selected) Color.WHITE else Color.parseColor("#555555")
                            )

                            (tv.background as? android.graphics.drawable.GradientDrawable)?.setColor(
                                if (selected) Color.parseColor("#007AFF") else Color.parseColor("#F0F0F0")
                            )
                        }
                    }
                }
            }
            val params = btn.layoutParams as LinearLayout.LayoutParams
            if (label != "今日") params.marginStart = dp(8)
            btn.layoutParams = params
            tabBar.addView(btn)
        }
        root.addView(tabBar)

        // 内容区
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scrollView.addView(contentLayout)
        root.addView(scrollView)
        try {
            val db = DatabaseHelper(this).writableDatabase
            db.execSQL("CREATE TABLE IF NOT EXISTS LedgerRecords (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, category TEXT, amount REAL, note TEXT, dateStr TEXT, timestamp INTEGER)")
            db.execSQL("CREATE TABLE IF NOT EXISTS LedgerComments (id INTEGER PRIMARY KEY AUTOINCREMENT, dateStr TEXT, content TEXT, generatedAt INTEGER)")
        } catch (_: Exception) {}
        setContentView(root)
        refreshContent()
    }

    private fun LinearLayout.children(): List<android.view.View> {
        val list = mutableListOf<android.view.View>()
        for (i in 0 until childCount) list.add(getChildAt(i))
        return list
    }

    private fun getDateRange(): Pair<String, String> {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val cal = Calendar.getInstance()
        return when (currentTab) {
            "day" -> {
                val today = sdf.format(cal.time)
                Pair(today, today)
            }
            "week" -> {
                cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                val start = sdf.format(cal.time)
                cal.add(Calendar.DAY_OF_WEEK, 6)
                Pair(start, sdf.format(cal.time))
            }
            else -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                val start = sdf.format(cal.time)
                cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
                Pair(start, sdf.format(cal.time))
            }
        }
    }

    private fun refreshContent() {
        contentLayout.removeAllViews()
        val (start, end) = getDateRange()
        val db = DatabaseHelper(this).readableDatabase

        // 汇总
        val cur = db.rawQuery(
            "SELECT type, SUM(amount) FROM LedgerRecords WHERE dateStr>=? AND dateStr<=? GROUP BY type",
            arrayOf(start, end)
        )
        var income = 0.0; var expense = 0.0
        while (cur.moveToNext()) {
            if (cur.getString(0) == "income") income = cur.getDouble(1)
            else expense = cur.getDouble(1)
        }
        cur.close()

        // 汇总卡片
        val summaryCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(12) }
        }
        listOf("收入" to income, "支出" to expense, "结余" to (income - expense)).forEachIndexed { i, (label, value) ->
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            col.addView(TextView(this).apply {
                text = "¥ ${"%.2f".format(value)}"
                textSize = 18f; setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(when (i) {
                    0 -> Color.parseColor("#34C759")
                    1 -> Color.parseColor("#FF3B30")
                    else -> Color.parseColor("#007AFF")
                })
            })
            col.addView(TextView(this).apply {
                text = label; textSize = 12f; gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#888888"))
            })
            summaryCard.addView(col)
        }
        contentLayout.addView(summaryCard)

        // 明细列表
        val detailCur = db.rawQuery(
            "SELECT type, category, amount, note, dateStr FROM LedgerRecords WHERE dateStr>=? AND dateStr<=? ORDER BY timestamp DESC",
            arrayOf(start, end)
        )
        if (detailCur.count == 0) {
            contentLayout.addView(TextView(this).apply {
                text = "还没有记录，点右上角记一笔吧～"
                textSize = 14f; gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#AAAAAA"))
                setPadding(0, dp(32), 0, 0)
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            })
        }
        while (detailCur.moveToNext()) {
            val type = detailCur.getString(0)
            val category = detailCur.getString(1)
            val amount = detailCur.getDouble(2)
            val note = detailCur.getString(3)
            val date = detailCur.getString(4)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.WHITE)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.WHITE); cornerRadius = dp(10).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) }
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = category; textSize = 24f
                layoutParams = LinearLayout.LayoutParams(dp(40), -2)
            })
            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.marginStart = dp(12) }
            }
            infoCol.addView(TextView(this).apply {
                text = category.replace(Regex("^\\S+\\s"), "")
                textSize = 15f; setTextColor(Color.parseColor("#111111"))
            })
            if (note.isNotEmpty()) infoCol.addView(TextView(this).apply {
                text = note; textSize = 12f; setTextColor(Color.parseColor("#AAAAAA"))
            })
            if (currentTab != "day") infoCol.addView(TextView(this).apply {
                text = date; textSize = 11f; setTextColor(Color.parseColor("#CCCCCC"))
            })
            row.addView(infoCol)
            row.addView(TextView(this).apply {
                text = "${if (type == "income") "+" else "-"}¥${"%.2f".format(amount)}"
                textSize = 16f; setTypeface(null, Typeface.BOLD)
                setTextColor(if (type == "income") Color.parseColor("#34C759") else Color.parseColor("#FF3B30"))
            })
            contentLayout.addView(row)
        }
        detailCur.close()

        // 角色吐槽区（只在今日视图显示）
        if (currentTab == "day") {
            loadOrGenerateComments(start)
        }
    }

    private fun showAddDialog() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Color.WHITE)
        }

        var selectedType = "expense"
        var selectedCategory = categories[0]

        // 收入/支出切换
        val typeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(40)).also { it.bottomMargin = dp(16) }
        }
        val btnExpense = TextView(this).apply {
            text = "支出"; textSize = 15f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#FF3B30")); cornerRadius = dp(8).toFloat()
            }
        }
        val btnIncome = TextView(this).apply {
            text = "收入"; textSize = 15f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f).also { it.marginStart = dp(8) }
            setTextColor(Color.parseColor("#555555"))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#F0F0F0")); cornerRadius = dp(8).toFloat()
            }
        }
        typeBar.addView(btnExpense); typeBar.addView(btnIncome)
        dialogView.addView(typeBar)

        btnExpense.setOnClickListener {
            selectedType = "expense"
            btnExpense.setTextColor(Color.WHITE)
            (btnExpense.background as android.graphics.drawable.GradientDrawable).setColor(Color.parseColor("#FF3B30"))
            btnIncome.setTextColor(Color.parseColor("#555555"))
            (btnIncome.background as android.graphics.drawable.GradientDrawable).setColor(Color.parseColor("#F0F0F0"))
        }
        btnIncome.setOnClickListener {
            selectedType = "income"
            btnIncome.setTextColor(Color.WHITE)
            (btnIncome.background as android.graphics.drawable.GradientDrawable).setColor(Color.parseColor("#34C759"))
            btnExpense.setTextColor(Color.parseColor("#555555"))
            (btnExpense.background as android.graphics.drawable.GradientDrawable).setColor(Color.parseColor("#F0F0F0"))
        }

        // 分类网格
        val tvCatLabel = TextView(this).apply {
            text = "分类"; textSize = 13f; setTextColor(Color.parseColor("#888888"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) }
        }
        dialogView.addView(tvCatLabel)

        val grid = GridLayout(this).apply {
            columnCount = 4
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(16) }
        }
        var selectedCatView: TextView? = null
        categories.forEach { cat ->
            val catBtn = TextView(this).apply {
                text = cat.take(2); textSize = 22f; gravity = Gravity.CENTER
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dp(60); height = dp(60)
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (cat == selectedCategory) Color.parseColor("#E8F0FE") else Color.parseColor("#F5F5F5"))
                    cornerRadius = dp(8).toFloat()
                }
                setOnClickListener {
                    selectedCategory = cat
                    selectedCatView?.background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#F5F5F5")); cornerRadius = dp(8).toFloat()
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#E8F0FE")); cornerRadius = dp(8).toFloat()
                    }
                    selectedCatView = this
                    // ✅ 新增：选了收入分类自动切换到收入
                    if (cat in incomeCategories) {
                        selectedType = "income"
                        btnIncome.setTextColor(Color.WHITE)
                        (btnIncome.background as android.graphics.drawable.GradientDrawable).setColor(Color.parseColor("#34C759"))
                        btnExpense.setTextColor(Color.parseColor("#555555"))
                        (btnExpense.background as android.graphics.drawable.GradientDrawable).setColor(Color.parseColor("#F0F0F0"))
                    } else {
                        selectedType = "expense"
                        btnExpense.setTextColor(Color.WHITE)
                        (btnExpense.background as android.graphics.drawable.GradientDrawable).setColor(Color.parseColor("#FF3B30"))
                        btnIncome.setTextColor(Color.parseColor("#555555"))
                        (btnIncome.background as android.graphics.drawable.GradientDrawable).setColor(Color.parseColor("#F0F0F0"))
                    }
                }
            }

            if (cat == selectedCategory) selectedCatView = catBtn
            grid.addView(catBtn)
        }
        dialogView.addView(grid)

        // 金额输入
        val etAmount = EditText(this).apply {
            hint = "金额（元）"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 16f; setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(12) }
            background = android.graphics.drawable.GradientDrawable().apply {
                setStroke(1, Color.parseColor("#DDDDDD")); cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        dialogView.addView(etAmount)

        // 备注
        val etNote = EditText(this).apply {
            hint = "备注（可选）"; textSize = 15f; setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(20) }
            background = android.graphics.drawable.GradientDrawable().apply {
                setStroke(1, Color.parseColor("#DDDDDD")); cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        dialogView.addView(etNote)

        val btnSave = TextView(this).apply {
            text = "保存"; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, dp(48))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#007AFF")); cornerRadius = dp(10).toFloat()
            }
        }
        dialogView.addView(btnSave)

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))

        btnSave.setOnClickListener {
            val amountStr = etAmount.text.toString().trim()
            if (amountStr.isEmpty()) {
                Toast.makeText(this, "请输入金额", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val amount = amountStr.toDoubleOrNull() ?: return@setOnClickListener
            val note = etNote.text.toString().trim()
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            Thread {
                try {
                    val db = DatabaseHelper(this).writableDatabase
                    val cv = ContentValues().apply {
                        put("type", selectedType)
                        put("category", selectedCategory)
                        put("amount", amount)
                        put("note", note)
                        put("dateStr", dateStr)
                        put("timestamp", System.currentTimeMillis())
                    }
                    db.insert("LedgerRecords", null, cv)

                    db.delete(
                        "LedgerComments",
                        "dateStr=?",
                        arrayOf(dateStr)
                    )
                } catch (_: Exception) {}
                runOnUiThread {
                    dialog.dismiss()
                    refreshContent()
                }
            }.start()
        }
        dialog.show()
    }

    private fun loadOrGenerateComments(today: String) {
        contentLayout.addView(TextView(this).apply {
            text = "── 今日吐槽 ──"; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#AAAAAA"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).also {
                it.topMargin = dp(24); it.bottomMargin = dp(12)
            }
        })

        Thread {
            try {
                val db = DatabaseHelper(this).readableDatabase
                val cur = db.rawQuery(
                    "SELECT content FROM LedgerComments WHERE dateStr=? ORDER BY id DESC LIMIT 1", arrayOf(today)
                )
                if (cur.moveToFirst()) {
                    val content = cur.getString(0)
                    cur.close()
                    runOnUiThread { renderComments(content) }
                } else {
                    cur.close()
                    generateComments(today)
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun generateComments(today: String) {
        val db = DatabaseHelper(this).readableDatabase

        // 拿今日账单摘要
        val cur = db.rawQuery(
            "SELECT type, category, amount FROM LedgerRecords WHERE dateStr=? ORDER BY timestamp DESC",
            arrayOf(today)
        )
        val records = mutableListOf<String>()
        while (cur.moveToNext()) {
            val t = if (cur.getString(0) == "income") "收入" else "支出"
            records.add("${cur.getString(1)} $t ¥${"%.2f".format(cur.getDouble(2))}")
        }
        cur.close()

        if (records.isEmpty()) {
            runOnUiThread {
                contentLayout.addView(TextView(this).apply {
                    text = "今天还没有账单，角色们无话可说～"
                    textSize = 13f; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#BBBBBB"))
                    layoutParams = LinearLayout.LayoutParams(-1, -2)
                })
            }
            return
        }

        // 随机取3个角色
        val contactCur = db.rawQuery(
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
    ORDER BY RANDOM()
    LIMIT 3
    """.trimIndent(),
            null
        )
        val contacts = mutableListOf<Pair<String, String>>()
        while (contactCur.moveToNext()) {
            contacts.add(Pair(contactCur.getString(0), contactCur.getString(1) ?: ""))
        }
        contactCur.close()
        if (contacts.isEmpty()) {
            runOnUiThread {
                contentLayout.addView(TextView(this).apply {
                    text = "暂无角色，无法生成今日吐槽～"
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor("#BBBBBB"))
                    layoutParams = LinearLayout.LayoutParams(-1, -2)
                })
            }
            return
        }

        while (contacts.size < 3) {
            contacts.add(contacts.random())
        }

        val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val apiKey = pref.getString("apiKey", "") ?: ""
        var apiUrl = (pref.getString("apiUrl", "") ?: "").trimEnd('/')
        val model = pref.getString(
            "modelName",
            ""
        )?.ifBlank { "gpt-4o" } ?: "gpt-4o"
        if (apiKey.isEmpty() || apiUrl.isEmpty()) return
        if (!apiUrl.endsWith("/chat/completions"))
            apiUrl += if (apiUrl.contains("/v1")) "/chat/completions" else "/v1/chat/completions"


        val summary = records.joinToString("、")
        val names = contacts.map { it.first }
        val personas = contacts.mapIndexed { i, c -> "${names[i]}：${c.second}" }.joinToString("\n")

        val prompt = """
今天用户的账单记录如下：$summary
请你扮演以下三个角色，以微信群聊的方式，对用户今天的账单情况进行评价，要求：
1. 每人至少说2句话，收入要表示开心或好奇，支出可以心疼或调侃
2. 三人之间要有互动，可以互相认同、抬杠、补充
3. 语气符合各自人设，口语化，有趣
4. 注意区分收入和支出，不要把收入说成花钱
5. 禁止羞辱、嘲讽过激
6. 严格按格式输出，不要有任何多余内容：
【${names[0]}】内容
【${names[1]}】内容
【${names[0]}】内容
【${names[2]}】内容
...

角色人设：
$personas
""".trimIndent()

        try {
            val client = Http.client.newBuilder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
            val body = JSONObject().apply {
                put("model", model)
                put("max_tokens", 800)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                })
            }.toString().toRequestBody("application/json".toMediaTypeOrNull())

            val req = Request.Builder().url(apiUrl).addHeader("Authorization", "Bearer $apiKey").post(body).build()
            val resp = client.newCall(req).execute()

            if (!resp.isSuccessful) {
                android.util.Log.e(
                    "LedgerActivity",
                    "API失败:${resp.code}"
                )
                return
            }

            val result = resp.body?.string() ?: return
            val content = JSONObject(result).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")

            // 存库
            val wdb = DatabaseHelper(this).writableDatabase
            val cv = ContentValues().apply {
                put("dateStr", today)
                put("content", content)
                put("generatedAt", System.currentTimeMillis())
            }
            wdb.insert("LedgerComments", null, cv)

            runOnUiThread { renderComments(content) }
        } catch (_: Exception) {}
    }

    private fun renderComments(raw: String) {
        val lines = raw.trim().split("\n").filter { it.isNotBlank() }
        lines.forEach { line ->
            val match = Regex("^【(.+?)】(.+)$").find(line.trim()) ?: return@forEach
            val name = match.groupValues[1]
            val content = match.groupValues[2].trim()

            val bubble = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(10).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = dp(8) }
            }
            bubble.addView(TextView(this).apply {
                text = name
                textSize = 12f
                setTextColor(Color.parseColor("#007AFF"))
                setTypeface(null, Typeface.BOLD)
            })
            bubble.addView(TextView(this).apply {
                text = content
                textSize = 14f
                setTextColor(Color.parseColor("#333333"))
                layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(4) }
            })
            contentLayout.addView(bubble)
        }
    }}