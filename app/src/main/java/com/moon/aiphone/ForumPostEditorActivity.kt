package com.moon.aiphone

import android.content.ContentValues
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class ForumPostEditorActivity : AppCompatActivity() {

    private val boards = listOf("日常吐槽区", "八卦闲聊区", "情报交流区", "袒露心声区")
    private val boardIds = listOf("daily", "gossip", "info", "heart")
    private var selectedBoard = "daily"
    private var useAlias = false
    private var selectedAliasName = ""

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
        val tvTopTitle = TextView(this).apply {
            text = "发帖"
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
        val btnSubmit = TextView(this).apply {
            text = "发布"
            textSize = 15f
            setTextColor(Color.parseColor("#07C160"))
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            ).also {
                it.addRule(RelativeLayout.ALIGN_PARENT_END)
                it.addRule(RelativeLayout.CENTER_VERTICAL)
            }
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(tvTopTitle)
        topBar.addView(btnBack)
        topBar.addView(btnSubmit)
        root.addView(topBar)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 板块选择
        val tvBoardLabel = TextView(this).apply {
            text = "选择板块"
            textSize = 14f
            setTextColor(Color.parseColor("#555555"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(8) }
        }
        content.addView(tvBoardLabel)

        val boardRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(16) }
        }
        val boardTabViews = mutableListOf<TextView>()
        boards.forEachIndexed { index, name ->
            val tab = TextView(this).apply {
                text = name
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = dp(8) }
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    if (index == 0) {
                        setColor(Color.parseColor("#07C160"))
                    } else {
                        setColor(Color.parseColor("#EEEEEE"))
                    }
                }
                setTextColor(if (index == 0) Color.WHITE else Color.parseColor("#555555"))
                setOnClickListener {
                    selectedBoard = boardIds[index]
                    boardTabViews.forEachIndexed { i, tv ->
                        tv.background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = dp(16).toFloat()
                            setColor(if (i == index) Color.parseColor("#07C160") else Color.parseColor("#EEEEEE"))
                        }
                        tv.setTextColor(if (i == index) Color.WHITE else Color.parseColor("#555555"))
                    }
                }
            }
            boardTabViews.add(tab)
            boardRow.addView(tab)
        }
        content.addView(boardRow)

        // 发帖身份选择
        val tvIdentityLabel = TextView(this).apply {
            text = "发帖身份"
            textSize = 14f
            setTextColor(Color.parseColor("#555555"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(8) }
        }
        content.addView(tvIdentityLabel)

        val pref = getSharedPreferences("AppConfig", MODE_PRIVATE)
        val myName = pref.getString("myName", "我") ?: "我"

        val tvCurrentIdentity = TextView(this).apply {
            text = "当前身份：$myName（真实账号）"
            textSize = 13f
            setTextColor(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(8) }
        }
        content.addView(tvCurrentIdentity)

        val identityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(16) }
        }

        val btnRealId = TextView(this).apply {
            text = "真实账号"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#07C160"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = dp(8) }
        }

        val btnUseAlias = TextView(this).apply {
            text = "+ 使用马甲"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setTextColor(Color.parseColor("#555555"))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#EEEEEE"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        btnRealId.setOnClickListener {
            useAlias = false
            selectedAliasName = ""
            tvCurrentIdentity.text = "当前身份：$myName（真实账号）"
            btnRealId.setTextColor(Color.WHITE)
            btnRealId.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#07C160"))
            }
            btnUseAlias.setTextColor(Color.parseColor("#555555"))
            btnUseAlias.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#EEEEEE"))
            }
        }

        btnUseAlias.setOnClickListener {
            showAliasDialog(tvCurrentIdentity, btnRealId, btnUseAlias)
        }

        identityRow.addView(btnRealId)
        identityRow.addView(btnUseAlias)
        content.addView(identityRow)

        // 标题输入
        val etTitle = EditText(this).apply {
            hint = "输入标题（建议20字以内）"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
            setHintTextColor(Color.parseColor("#BBBBBB"))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(12) }
            setSingleLine(true)
        }
        content.addView(etTitle)

        // 正文输入
        val etContent = EditText(this).apply {
            hint = "分享你想说的..."
            textSize = 15f
            setTextColor(Color.BLACK)
            setHintTextColor(Color.parseColor("#BBBBBB"))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(200)
            ).also { it.bottomMargin = dp(12) }
            gravity = Gravity.TOP
            minLines = 6
        }
        content.addView(etContent)

        scrollView.addView(content)
        root.addView(scrollView)
        setContentView(root)

        btnSubmit.setOnClickListener {
            val title = etTitle.text.toString().trim()
            val postContent = etContent.text.toString().trim()
            if (title.length > 50) {
                Toast.makeText(
                    this,
                    "标题过长",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            if (title.isEmpty()) {
                Toast.makeText(this, "请输入标题", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (postContent.isEmpty()) {
                Toast.makeText(this, "请输入正文", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            submitPost(title, postContent, myName)
        }
    }

    private fun showAliasDialog(
        tvCurrentIdentity: TextView,
        btnRealId: TextView,
        btnUseAlias: TextView
    ) {
        val db = DatabaseHelper(this).readableDatabase
        val cursor = db.rawQuery("SELECT id, aliasName FROM ForumAlias ORDER BY createdAt DESC", null)
        val aliasList = mutableListOf<Pair<Long, String>>()
        while (cursor.moveToNext()) {
            aliasList.add(Pair(cursor.getLong(0), cursor.getString(1) ?: ""))
        }
        cursor.close()

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Color.WHITE)
        }
        val tvTitle = TextView(this).apply {
            text = "选择马甲"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(16) }
        }
        dialogView.addView(tvTitle)

        var dialog: android.app.AlertDialog? = null

        aliasList.forEach { (_, name) ->
            val item = TextView(this).apply {
                text = "🎭 $name"
                textSize = 15f
                setTextColor(Color.parseColor("#333333"))
                setPadding(0, dp(12), 0, dp(12))
                setOnClickListener {
                    useAlias = true
                    selectedAliasName = name

                    tvCurrentIdentity.text = "当前身份：$name（马甲）"

                    btnUseAlias.setTextColor(Color.WHITE)
                    btnUseAlias.background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat()
                        setColor(Color.parseColor("#FF9500"))
                    }

                    btnRealId.setTextColor(Color.parseColor("#555555"))
                    btnRealId.background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat()
                        setColor(Color.parseColor("#EEEEEE"))
                    }
                    dialog?.dismiss()
                }
            }
            dialogView.addView(item)
        }

        // 新建马甲
        val etNewAlias = EditText(this).apply {
            hint = "或输入新马甲名..."
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setStroke(1, Color.parseColor("#DDDDDD"))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(12) }
        }
        dialogView.addView(etNewAlias)

        dialog = android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val btnCreate = TextView(this).apply {
            text = "创建并使用"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#FF9500"))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(0, dp(12), 0, dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(12) }
            setOnClickListener {
                val newName = etNewAlias.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val wdb = DatabaseHelper(this@ForumPostEditorActivity).writableDatabase
                    val cv = ContentValues().apply {
                        put("aliasName", newName)
                        put("avatarColor", "#${(0..16777215).random().toString(16).padStart(6, '0')}")
                        put("createdAt", System.currentTimeMillis())
                    }
                    val check = wdb.rawQuery(
                        "SELECT id FROM ForumAlias WHERE aliasName=? LIMIT 1",
                        arrayOf(newName)
                    )

                    val exists = check.moveToFirst()
                    check.close()

                    if (exists) {
                        Toast.makeText(
                            this@ForumPostEditorActivity,
                            "马甲已存在",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setOnClickListener
                    }
                    wdb.insert("ForumAlias", null, cv)
                    useAlias = true
                    selectedAliasName = newName
                    tvCurrentIdentity.text = "当前身份：$newName（马甲）"
                    btnUseAlias.setTextColor(Color.WHITE)
                    btnUseAlias.background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat()
                        setColor(Color.parseColor("#FF9500"))
                    }
                    btnRealId.setTextColor(Color.parseColor("#555555"))
                    btnRealId.background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat()
                        setColor(Color.parseColor("#EEEEEE"))
                    }
                    dialog?.dismiss()
                } else {
                    Toast.makeText(this@ForumPostEditorActivity, "请输入马甲名", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialogView.addView(btnCreate)
        dialog.show()
    }

    private fun submitPost(title: String, postContent: String, myName: String) {
        val pref = getSharedPreferences("AppConfig", MODE_PRIVATE)
        val myId = pref.getString("myId", "me") ?: "me"
        val authorName = if (useAlias && selectedAliasName.isNotEmpty()) selectedAliasName else myName
        val authorId = if (useAlias) "alias_${selectedAliasName}" else myId
        val randomLikes = (0..30).random()

        try {
            val db = DatabaseHelper(this).writableDatabase
            val cv = ContentValues().apply {
                put("boardId", selectedBoard)
                put("title", title)
                put("content", postContent)
                put("authorId", authorId)
                put("authorName", authorName)
                put("isAlias", if (useAlias) 1 else 0)
                put("likeCount", randomLikes)
                put("timestamp", System.currentTimeMillis())
            }
            db.insert("ForumPosts", null, cv)

            // 发帖后注入所有角色的WorldBook，让角色感知到这条帖子
            val boardName = boards[boardIds.indexOf(selectedBoard)]
            val postSummary = "用户在论坛【$boardName】发了一个帖子，标题：「$title」，内容：「${postContent.take(100)}」，发帖身份：${if (useAlias) "匿名马甲（$selectedAliasName）" else "真实账号"}"
            val dateStr = java.text.SimpleDateFormat("MM月dd日 HH:mm", java.util.Locale.getDefault()).format(java.util.Date())

            Thread {
                try {
                    val contacts =
                        db.rawQuery(
                            """
        SELECT userId
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
                    while (contacts.moveToNext()) {
                        val aiId = contacts.getString(0)
                        // 删旧的论坛感知，插新的
                        val oldContentCursor = db.rawQuery(
                            """
    SELECT content
    FROM UserWorldBook
    WHERE aiId=?
      AND keyword='论坛动态'
    LIMIT 1
    """.trimIndent(),
                            arrayOf(aiId)
                        )

                        var oldContent = ""

                        if (oldContentCursor.moveToFirst()) {
                            oldContent = oldContentCursor.getString(0) ?: ""
                        }
                        oldContentCursor.close()

                        db.execSQL(
                            "DELETE FROM UserWorldBook WHERE aiId=? AND keyword='论坛动态'",
                            arrayOf(aiId)
                        )

                        val mergedContent =
                            (oldContent + "\n" +
                                    "【$dateStr 论坛动态】$postSummary")
                                .trim()
                                .takeLast(4000)
                        val wbCv = android.content.ContentValues().apply {
                            put("aiId", aiId)
                            put("keyword", "论坛动态")
                            put(
                                "content",
                                mergedContent +
                                        "\n如果对话中涉及论坛或这个话题，可以自然地提起，但不要主动透露你知道这是用户发的。"
                            )
                        }
                        db.insert("UserWorldBook", null, wbCv)
                    }
                    contacts.close()
                } catch (_: Exception) {}
            }.start()

            Toast.makeText(this, "发布成功！", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "发布失败", Toast.LENGTH_SHORT).show()
        }
    }
}
