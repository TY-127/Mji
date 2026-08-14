package com.moon.aiphone

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray

class GameCenterActivity : AppCompatActivity() {

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1a1a2e"))
            layoutParams = LinearLayout.LayoutParams(-1, -1)
        }

        // 顶栏
        val topBar = RelativeLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(52))
            setBackgroundColor(Color.parseColor("#12122a"))
        }
        val btnBack = TextView(this).apply {
            text = "‹"; textSize = 32f
            setTextColor(Color.WHITE)
            setPadding(dp(16), 0, dp(16), dp(4))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RelativeLayout.LayoutParams(-2, -1).also {
                it.addRule(RelativeLayout.ALIGN_PARENT_START)
                it.addRule(RelativeLayout.CENTER_VERTICAL)
            }
            setOnClickListener { finish() }
        }
        val tvTitle = TextView(this).apply {
            text = "🎮 游戏中心"; textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = RelativeLayout.LayoutParams(-2, -2).also {
                it.addRule(RelativeLayout.CENTER_IN_PARENT)
            }
        }
        topBar.addView(btnBack); topBar.addView(tvTitle)
        root.addView(topBar)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scrollView.addView(content)
        root.addView(scrollView)
        setContentView(root)

        // 读取联系人
        val contacts = mutableListOf<Pair<String, String>>() // id, name
        try {
            val db = DatabaseHelper(this).readableDatabase
            val cur = db.rawQuery(
                """
    SELECT userId, realName
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
    ORDER BY id DESC
    """.trimIndent(),
                null
            )

            cur.use {
                while (it.moveToNext()) {
                    contacts.add(
                        Pair(
                            it.getString(0) ?: "",
                            it.getString(1) ?: "未知"
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        // 选角色
        content.addView(TextView(this).apply {
            text = "选择一起玩的角色"; textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(10) }
        })

        var selectedAiId = if (contacts.isNotEmpty()) contacts[0].first else ""
        var selectedAiName = if (contacts.isNotEmpty()) contacts[0].second else ""

        val spinner = android.widget.Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(48)).also { it.bottomMargin = dp(24) }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#252540"))
                cornerRadius = dp(12).toFloat()
                setStroke(1, Color.parseColor("#7c83fd"))
            }
            setPadding(dp(16), 0, dp(16), 0)
        }
        val nameList = contacts.map { it.second }
        val spinnerAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, nameList).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.adapter = spinnerAdapter
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                selectedAiId = contacts[position].first
                selectedAiName = contacts[position].second
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        content.addView(spinner)

        // 读取游戏列表
        content.addView(TextView(this).apply {
            text = "选择游戏"; textSize = 13f
            setTextColor(Color.parseColor("#888888"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(12) }
        })

        try {
            val json = assets.open("games/games.json").bufferedReader().readText()
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val game = arr.getJSONObject(i)
                val id = game.optString("id")
                val name = game.optString("name")
                val icon = game.optString("icon")
                val desc = game.optString("description")
                val file = game.optString("file")

                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#252540"))
                        cornerRadius = dp(14).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(10) }
                    isClickable = true
                    isFocusable = true
                    foreground = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).getDrawable(0)
                }
                card.addView(TextView(this).apply {
                    text = icon; textSize = 36f
                    layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).also { it.marginEnd = dp(14) }
                    gravity = Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#1a1a3e"))
                        cornerRadius = dp(12).toFloat()
                    }
                })
                val infoCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }
                infoCol.addView(TextView(this).apply {
                    text = name; textSize = 16f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                })
                infoCol.addView(TextView(this).apply {
                    text = desc; textSize = 12f
                    setTextColor(Color.parseColor("#888888"))
                    layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(4) }
                })
                card.addView(infoCol)
                card.addView(TextView(this).apply {
                    text = "▶"; textSize = 16f
                    setTextColor(Color.parseColor("#7c83fd"))
                })
                card.setOnClickListener {
                    if (selectedAiId.isBlank()) {
                        Toast.makeText(this, "请先添加角色", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    val intent = Intent(this, GamePlayActivity::class.java).apply {
                        putExtra("GAME_FILE", file)
                        putExtra("GAME_NAME", name)
                        putExtra("AI_ID", selectedAiId)
                        putExtra("AI_NAME", selectedAiName)
                    }
                    startActivity(intent)
                }
                content.addView(card)
            }
        } catch (e: Exception) {
            android.util.Log.e("GameCenterActivity", e.stackTraceToString())

            content.addView(TextView(this).apply {
                text = "暂无游戏"; textSize = 14f
                setTextColor(Color.parseColor("#666666"))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(40) }
            })
        }
    }
}
