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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView


private val terminalHandler = Handler(Looper.getMainLooper())
private var terminalRunnable: Runnable? = null
class HackerActivity : AppCompatActivity() {

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // 顶部栏
        val topBar = RelativeLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50))
            setBackgroundColor(Color.BLACK)
        }
        val btnBack = TextView(this).apply {
            text = "‹"
            textSize = 32f
            setPadding(dp(16), 0, dp(16), dp(4))
            setTextColor(Color.parseColor("#00FF41"))
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
        val tvTitle = TextView(this).apply {
            text = "// CYBER_INTRUDE v2.0"
            textSize = 14f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(Color.parseColor("#00FF41"))
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).also { it.addRule(RelativeLayout.CENTER_IN_PARENT) }
        }
        topBar.addView(btnBack)
        topBar.addView(tvTitle)
        root.addView(topBar)

        // 分割线
        val divider = android.view.View(this).apply {
            setBackgroundColor(Color.parseColor("#00FF41"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        }
        root.addView(divider)

        // 滚动终端文字区域
        val tvTerminal = TextView(this).apply {
            text = "> 正在扫描目标节点...\n> 检测到 ${getContactCount()} 个活跃目标\n> 选择渗透目标："
            textSize = 13f
            setTypeface(Typeface.MONOSPACE)
            setTextColor(Color.parseColor("#00FF41"))
            setPadding(dp(16), dp(12), dp(16), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(tvTerminal)

        // 联系人列表
        val recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@HackerActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }
        root.addView(recyclerView)

        setContentView(root)

        // 加载联系人
        loadContacts(recyclerView)

        // 打字机效果
        animateTerminal(tvTerminal)
    }

    private fun getContactCount(): Int {
        return try {
            val db = DatabaseHelper(this).readableDatabase
            val cursor = db.rawQuery(
                """
    SELECT COUNT(*)
    FROM (
        SELECT MAX(id)
        FROM Contacts
        WHERE userId IS NOT NULL
          AND TRIM(userId) <> ''
        GROUP BY userId
    )
    """.trimIndent(),
                null
            )
            val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
            cursor.close()
            count
        } catch (e: Exception) { 0 }
    }

    private fun animateTerminal(tv: TextView) {
        val lines = listOf(
            "> 正在扫描局域网...",
            "> 检测到加密通信节点...",
            "> 破解防火墙中...",
            "> 渗透成功，选择目标开始入侵："
        )
        var index = 0
        terminalRunnable = object : Runnable {
            override fun run() {
                if (index < lines.size) {
                    tv.text = lines.take(index + 1).joinToString("\n")
                    index++
                    terminalHandler.postDelayed(this, 600)
                }
            }
        }
        terminalRunnable?.let {
            terminalHandler.post(it)
        }
    }

    private fun loadContacts(recyclerView: RecyclerView) {
        try {
            val db = DatabaseHelper(this).readableDatabase
            val cursor = db.rawQuery(
                """
    SELECT userId, realName, avatarUri
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
            val contacts = mutableListOf<Triple<String, String, String>>()
            while (cursor.moveToNext()) {
                contacts.add(Triple(
                    cursor.getString(0) ?: "",
                    cursor.getString(1) ?: "",
                    cursor.getString(2) ?: ""
                ))
            }
            cursor.close()

            recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val item = LinearLayout(parent.context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(dp(16), dp(14), dp(16), dp(14))
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = RecyclerView.LayoutParams(
                            RecyclerView.LayoutParams.MATCH_PARENT,
                            RecyclerView.LayoutParams.WRAP_CONTENT
                        )
                        setBackgroundColor(Color.BLACK)
                    }
                    return object : RecyclerView.ViewHolder(item) {}
                }

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    val (userId, name, _) = contacts[position]
                    val item = holder.itemView as LinearLayout
                    item.removeAllViews()

                    // 头像占位
                    val ivAvatar = android.widget.ImageView(item.context).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).also {
                            it.marginEnd = dp(4)
                        }
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(android.graphics.Color.parseColor("#003300"))
                            cornerRadius = dp(6).toFloat()
                            setStroke(1, android.graphics.Color.parseColor("#00FF41"))
                        }
                        clipToOutline = true
                    }

// 加载头像
                    val avatarUri = contacts[position].third
                    if (avatarUri.isNotEmpty()) {
                        try {
                            val bitmap = if (avatarUri.startsWith("/")) {
                                android.graphics.BitmapFactory.decodeFile(avatarUri)
                            } else {
                                item.context.contentResolver.openInputStream(android.net.Uri.parse(avatarUri))
                                    ?.use { android.graphics.BitmapFactory.decodeStream(it) }
                            }
                            if (bitmap != null) ivAvatar.setImageBitmap(bitmap)
                        } catch (_: Exception) {}
                    }

                    val tvInfo = LinearLayout(item.context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val tvName = TextView(item.context).apply {
                        text = "TARGET_${name.ifBlank { "UNKNOWN" }.uppercase()}"
                        textSize = 14f
                        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                        setTextColor(Color.parseColor("#00FF41"))
                    }
                    val tvId = TextView(item.context).apply {
                        text = "ID: ${userId.take(12)}..."
                        textSize = 11f
                        setTypeface(Typeface.MONOSPACE)
                        setTextColor(Color.parseColor("#005500"))
                    }
                    tvInfo.addView(tvName)
                    tvInfo.addView(tvId)

                    val tvHack = TextView(item.context).apply {
                        text = "[入侵]"
                        textSize = 13f
                        setTypeface(Typeface.MONOSPACE)
                        setTextColor(Color.parseColor("#00FF41"))
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setStroke(1, Color.parseColor("#00FF41"))
                            cornerRadius = dp(4).toFloat()
                        }
                        setPadding(dp(8), dp(4), dp(8), dp(4))
                    }

                    item.addView(ivAvatar)
                    item.addView(tvInfo)
                    item.addView(tvHack)

                    // 分割线
                    item.setOnClickListener {
                        val intent = Intent(item.context, PhoneLockActivity::class.java).apply {
                            putExtra("AI_ID", userId)
                            putExtra("AI_NAME", name)
                        }
                        item.context.startActivity(intent)
                    }

                    // 底部绿线
                    item.foreground = null
                }

                override fun getItemCount() = contacts.size
            }
        } catch (e: Exception) {
            Toast.makeText(this, "扫描失败", Toast.LENGTH_SHORT).show()
        }
    }
    override fun onDestroy() {
        terminalRunnable?.let {
            terminalHandler.removeCallbacks(it)
        }
        super.onDestroy()
    }
}
