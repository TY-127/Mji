package com.moon.aiphone

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class PhoneLockActivity : AppCompatActivity() {

    private var aiId = ""
    private var aiName = ""
    private var inputPassword = ""
    private lateinit var tvDisplay: TextView
    private lateinit var tvHint: TextView
    private var wrongCount = 0

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aiId = intent.getStringExtra("AI_ID") ?: ""
        aiName = intent.getStringExtra("AI_NAME") ?: ""

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // 顶部返回
        val btnBack = TextView(this).apply {
            text = "‹ 返回"
            textSize = 14f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(Color.parseColor("#00FF41"))
            setPadding(dp(16), dp(16), dp(16), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { finish() }
        }
        root.addView(btnBack)

        // 目标信息
        val tvTarget = TextView(this).apply {
            text = "// 目标：${aiName.uppercase()}\n// 正在尝试解锁..."
            textSize = 13f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(Color.parseColor("#00AA44"))
            setPadding(dp(16), dp(8), dp(16), dp(24))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(tvTarget)

        // 密码显示区
        tvDisplay = TextView(this).apply {
            text = "______"
            textSize = 32f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#00FF41"))
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.topMargin = dp(16)
                it.bottomMargin = dp(8)
            }
        }
        root.addView(tvDisplay)

        // 提示文字
        tvHint = TextView(this).apply {
            text = "输入6位密码"
            textSize = 12f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(Color.parseColor("#005500"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(32) }
        }
        root.addView(tvHint)

        // 数字键盘
        val keypadLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val keys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("⌫", "0", "✓")
        )

        for (row in keys) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            for (key in row) {
                val btn = TextView(this).apply {
                    text = key
                    textSize = 22f
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    setTextColor(Color.parseColor("#00FF41"))
                    gravity = Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setStroke(1, Color.parseColor("#003300"))
                        cornerRadius = dp(8).toFloat()
                        setColor(Color.parseColor("#0A0A0A"))
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(80), dp(70)).also {
                        it.setMargins(dp(8), dp(8), dp(8), dp(8))
                    }
                    setOnClickListener {
                        when (key) {
                            "⌫" -> {
                                if (inputPassword.isNotEmpty()) {
                                    inputPassword = inputPassword.dropLast(1)
                                    updateDisplay()
                                }
                            }
                            "✓" -> checkPassword()
                            else -> {
                                if (inputPassword.length < 6) {
                                    inputPassword += key
                                    updateDisplay()
                                    if (inputPassword.length == 6) {
                                        Handler(Looper.getMainLooper()).postDelayed({
                                            checkPassword()
                                        }, 300)
                                    }
                                }
                            }
                        }
                    }
                }
                rowLayout.addView(btn)
            }
            keypadLayout.addView(rowLayout)
        }
        root.addView(keypadLayout)

        setContentView(root)
        // 检查是否已有密码，没有就AI生成
        checkAndGeneratePassword()
    }
    private fun checkAndGeneratePassword() {
        Thread {
            try {
                val db = DatabaseHelper(this).readableDatabase
                val cursor = db.rawQuery(
                    """
    SELECT lockPassword
    FROM Contacts
    WHERE userId=?
    ORDER BY id DESC
    LIMIT 1
    """.trimIndent(),
                    arrayOf(aiId)
                )
                val existingPw = if (cursor.moveToFirst()) cursor.getString(0) ?: "" else ""
                cursor.close()

                if (existingPw.isNotEmpty()) return@Thread

                // 没有密码，调AI生成
                val pref = getSharedPreferences("AppConfig", MODE_PRIVATE)
                var apiUrl = pref.getString("apiUrl", "") ?: ""
                val apiKey = pref.getString("apiKey", "") ?: ""
                val modelName = pref.getString(
                    "modelName",
                    ""
                )?.ifBlank { "gpt-4o" } ?: "gpt-4o"
                if (apiUrl.isEmpty() || apiKey.isEmpty()) return@Thread
                while (apiUrl.endsWith("/")) apiUrl = apiUrl.dropLast(1)
                if (!apiUrl.endsWith("/chat/completions")) {
                    apiUrl = if (apiUrl.endsWith("/v1")) "$apiUrl/chat/completions" else "$apiUrl/v1/chat/completions"
                }

                var aiPersona = ""
                val pCursor = db.rawQuery(
                    """
    SELECT identityInfo
    FROM Contacts
    WHERE userId=?
    ORDER BY id DESC
    LIMIT 1
    """.trimIndent(),
                    arrayOf(aiId)
                )
                if (pCursor.moveToFirst()) aiPersona = pCursor.getString(0) ?: ""
                pCursor.close()

                val prompt = """
                你是 $aiName，人设：$aiPersona
                请根据你的人设，生成一个对你有特殊意义的6位数手机锁屏密码。
                只输出6位数字，不要任何解释，不要换行，不要空格。
                例如：158423
            """.trimIndent()

                val bodyJson = org.json.JSONObject().apply {
                    put("model", modelName)
                    put("temperature", 0.7)
                    put("messages", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
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

                val body = response.body?.string() ?: return@Thread

                if (!response.isSuccessful) {
                    android.util.Log.e(
                        "PhoneLock",
                        "API失败 code=${response.code}"
                    )
                    return@Thread
                }

                val content = org.json.JSONObject(body)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content", "")
                    ?.trim()
                    ?: ""
                // 提取6位数字
                val password = content.filter { it.isDigit() }.take(6)
                if (password.length == 6) {
                    val wdb = DatabaseHelper(this).writableDatabase
                    wdb.execSQL(
                        """
    UPDATE Contacts
    SET lockPassword=?
    WHERE id=(
        SELECT MAX(id)
        FROM Contacts
        WHERE userId=?
    )
    """.trimIndent(),
                        arrayOf(password, aiId)
                    )
                    runOnUiThread {
                        tvHint.text = "输入6位密码"
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }
    private fun updateDisplay() {
        val filled = "●".repeat(inputPassword.length)
        val empty = "_".repeat(6 - inputPassword.length)
        tvDisplay.text = "$filled$empty"
    }

    private fun checkPassword() {
        val masterPassword = "666666"
        var correctPassword = masterPassword

        // 从数据库读角色专属密码
        try {
            val db = DatabaseHelper(this).readableDatabase
            val cursor = db.rawQuery(
                """
    SELECT lockPassword
    FROM Contacts
    WHERE userId=?
    ORDER BY id DESC
    LIMIT 1
    """.trimIndent(),
                arrayOf(aiId)
            )

            if (cursor.moveToFirst()) {
                val pw = cursor.getString(0) ?: ""
                if (pw.isNotEmpty()) correctPassword = pw
            }
            cursor.close()
        } catch (_: Exception) {}

        if (inputPassword == correctPassword || inputPassword == masterPassword) {
            // 解锁成功
            tvHint.setTextColor(Color.parseColor("#00FF41"))
            tvHint.text = "// 解锁成功！正在入侵..."
            tvDisplay.setTextColor(Color.parseColor("#00FF41"))
            Handler(Looper.getMainLooper()).postDelayed({
                val intent = Intent(this, PhoneViewActivity::class.java).apply {
                    putExtra("AI_ID", aiId)
                    putExtra("AI_NAME", aiName)
                }
                startActivity(intent)
                finish()
            }, 800)
        } else {
            wrongCount++
            tvHint.setTextColor(Color.RED)
            tvDisplay.setTextColor(Color.RED)

            var hint = ""
            try {
                val db = DatabaseHelper(this).readableDatabase
                val cursor = db.rawQuery(
                    """
    SELECT lockPassword
    FROM Contacts
    WHERE userId=?
    ORDER BY id DESC
    LIMIT 1
    """.trimIndent(),
                    arrayOf(aiId)
                )
                if (cursor.moveToFirst()) {
                    val pw = cursor.getString(0) ?: ""
                    if (pw.length == 6) {
                        val firstTwo = pw.take(2)
                        val lastOne = pw.last()
                        val sum = pw.map { it.digitToInt() }.sum()
                        hint = "\n💡 提示：开头是 $firstTwo，结尾是 $lastOne，各位数字之和为 $sum"
                    }
                }
                cursor.close()
            } catch (_: Exception) {}

            when (wrongCount) {
                1 -> tvHint.text = "✗ 密码错误，再想想~"
                2 -> tvHint.text = "✗ 再错一次就要锁定了哦$hint"
                else -> tvHint.text = "⚠ 系统警告！$hint"
            }

            Handler(Looper.getMainLooper()).postDelayed({
                inputPassword = ""
                updateDisplay()
                tvDisplay.setTextColor(Color.parseColor("#00FF41"))
                tvHint.setTextColor(Color.parseColor("#00AA44"))
                tvHint.text = "输入6位密码"
            }, 2000)
        }
    }
}