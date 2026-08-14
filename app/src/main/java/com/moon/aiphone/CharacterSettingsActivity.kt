package com.moon.aiphone

import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

class CharacterSettingsActivity : AppCompatActivity() {
    private var aiId: String = ""
    private var aiName: String = ""
    private var memoryContent: String = ""
    private var appearanceContent: String = ""

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private val pickPresetOnline = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importPreset(uri, isOffline = false) }

    private val pickPresetOffline = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importPreset(uri, isOffline = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.init(this)
        supportActionBar?.hide()
        aiId = intent.getStringExtra("AI_ID") ?: ""
        aiName = intent.getStringExtra("AI_NAME") ?: ""

        if (aiName.isEmpty()) {
            try {
                val c = DatabaseHelper(this).readableDatabase
                    .rawQuery("SELECT realName FROM Contacts WHERE userId=?", arrayOf(aiId))
                if (c.moveToFirst()) aiName = c.getString(0) ?: ""
                c.close()
            } catch (_: Exception) {}
        }

        val sharedPref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)

        // 读取记忆库
        try {
            val db = DatabaseHelper(this).readableDatabase
            val cur = db.rawQuery(
                "SELECT memoryText FROM MemoryBank WHERE aiId=? AND category='misc' ORDER BY insertTime DESC",
                arrayOf(aiId)
            )
            val sb = StringBuilder()
            while (cur.moveToNext()) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append(cur.getString(0) ?: "")
            }
            cur.close()
            memoryContent = sb.toString()
        } catch (_: Exception) {}

        // 读取外形描写
        try {
            val c = DatabaseHelper(this).readableDatabase
                .rawQuery("SELECT appearance FROM Contacts WHERE userId=?", arrayOf(aiId))
            if (c.moveToFirst()) appearanceContent = c.getString(0) ?: ""
            c.close()
        } catch (_: Exception) {}

        val scroll = ScrollView(this).apply {
            setBackgroundColor(ThemeManager.getColor("--app-bg", Color.parseColor("#F2F2F6")))
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(-1, -2)
        }
        scroll.addView(root)

        // ── 顶部栏 ──────────────────────────────────────────
        val topBar = RelativeLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(50))
            setBackgroundColor(ThemeManager.getColor("--header-bg", Color.WHITE)); elevation = 4f
        }
        val btnBack = TextView(this).apply {
            text = "‹"; textSize = 28f; setTextColor(Color.BLACK); setPadding(dp(16), 0, dp(16), dp(4))
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { finish() }
        }
        val tvTopTitle = TextView(this).apply {
            text = "聊天设置"; textSize = 17f; setTypeface(null, Typeface.BOLD); setTextColor(Color.BLACK)
            layoutParams = RelativeLayout.LayoutParams(-2, -2).also { it.addRule(RelativeLayout.CENTER_IN_PARENT) }
        }
        topBar.addView(btnBack); topBar.addView(tvTopTitle)
        root.addView(topBar)

        // ── 角色卡片 ────────────────────────────────────────
        val cardAvatar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(12) }
        }
        val ivAvatar = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).also { it.marginEnd = dp(14) }
            scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
                }
            }
            setBackgroundColor(Color.parseColor("#DDDDDD"))
        }
        try {
            val c = DatabaseHelper(this).readableDatabase.rawQuery("SELECT avatarUri FROM Contacts WHERE userId=?", arrayOf(aiId))
            if (c.moveToFirst()) {
                val uri = c.getString(0) ?: ""
                if (uri.isNotEmpty()) {
                    val bitmap = if (uri.startsWith("/")) {
                        android.graphics.BitmapFactory.decodeFile(uri)
                    } else {
                        contentResolver.openInputStream(android.net.Uri.parse(uri))
                            ?.use { android.graphics.BitmapFactory.decodeStream(it) }
                    }
                    if (bitmap != null) ivAvatar.setImageBitmap(bitmap)
                }
            }
            c.close()
        } catch (_: Exception) {}

        val tvAvatarName = TextView(this).apply {
            text = aiName; textSize = 17f; setTypeface(null, Typeface.BOLD); setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        cardAvatar.addView(ivAvatar); cardAvatar.addView(tvAvatarName)
        cardAvatar.addView(TextView(this).apply { text = "›"; textSize = 22f; setTextColor(Color.parseColor("#CCCCCC")) })
        cardAvatar.setOnClickListener {
            startActivity(Intent(this, AddContactActivity::class.java).putExtra("EDIT_AI_ID", aiId))
            finish()
        }
        root.addView(cardAvatar)

        // ── 分组1：聊天设置 ──────────────────────────────────
        addSectionLabel(root, "聊天")
        val group1 = createCard(root)

        // 查找聊天记录
        addCardRow(group1, "查找聊天记录", rightText = "搜索内容 ›", showDivider = true) {
            showSearchChatDialog()
        }
        addCardRow(group1, "按日期查看与导出", rightText = "日历 ›", showDivider = false) {
            startActivity(Intent(this, ChatActivity::class.java).apply {
                putExtra("AI_ID", aiId)
                putExtra("AI_NAME", aiName)
                putExtra("OPEN_HISTORY", true)
            })
        }

        // 置顶开关
        val isPinned = try {
            val c = DatabaseHelper(this).readableDatabase
                .rawQuery("SELECT isPinned FROM Contacts WHERE userId=?", arrayOf(aiId))
            val v = if (c.moveToFirst()) c.getInt(0) == 1 else false
            c.close(); v
        } catch (_: Exception) { false }

        val switchPin = Switch(this)
        switchPin.isChecked = isPinned
        addSwitchRow(group1, "置顶聊天", switchPin, showDivider = true) { checked ->
            try {
                DatabaseHelper(this).writableDatabase.execSQL(
                    "UPDATE Contacts SET isPinned=? WHERE userId=?", arrayOf(if (checked) 1 else 0, aiId)
                )
                sendBroadcast(Intent("CYBER_NEW_MSG"))
            } catch (_: Exception) {}
        }

        // 设置聊天背景
        addCardRow(group1, "设置聊天背景", showDivider = true) { showWallpaperMenu() }

        // ── 分组2：AI 核心逻辑 ──────────────────────────────
        addSectionLabel(root, "AI 核心逻辑")
        val group2 = createCard(root)

        // 上下文记忆深度
        val historyDepth = sharedPref.getString("history_depth_$aiId", "40")?: "40"
        addCardRow(group2, "上下文记忆深度", rightText = "$historyDepth 条 ›", showDivider = false) {
            showEditDialog("设置 AI 读取历史消息的条数", historyDepth) { v ->
                sharedPref.edit()
                    .putString("history_depth_$aiId", v)
                    .apply()
                recreate()
            }
        }

        // 修改记忆库
        addCardRow(group2, "修改角色记忆 (MemoryBank)", rightText = "点击编辑 ›", showDivider = true) {
            val et = EditText(this).apply {
                setText(memoryContent); gravity = Gravity.TOP
                minLines = 8; setPadding(dp(16), dp(16), dp(16), dp(16)); background = null
            }
            AlertDialog.Builder(this).setTitle("长期记忆").setView(et)
                .setPositiveButton("保存") { _, _ -> memoryContent = et.text.toString(); saveMemoryToDb() }
                .setNegativeButton("取消", null).show()
        }

        // 外形描写
        addCardRow(group2, "角色外形描写（生图用）", rightText = "点击编辑 ›", showDivider = true) {
            val et = EditText(this).apply {
                setText(appearanceContent); gravity = Gravity.TOP
                minLines = 5; setPadding(dp(16), dp(16), dp(16), dp(16)); background = null
                hint = "描述角色外貌，用于生成图片时参考，如：长黑发，大眼睛，白皙皮肤..."
            }
            AlertDialog.Builder(this).setTitle("外形描写").setView(et)
                .setPositiveButton("保存") { _, _ ->
                    appearanceContent = et.text.toString().trim()
                    try {
                        DatabaseHelper(this).writableDatabase.execSQL(
                            "UPDATE Contacts SET appearance=? WHERE userId=?",
                            arrayOf(appearanceContent, aiId)
                        )
                        Toast.makeText(this, "外形描写已保存", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                }
                .setNegativeButton("取消", null).show()
        }

        // ── 分组3：语音与语言 ───────────────────────────────
        addSectionLabel(root, "语音与语言")
        val group3 = createCard(root)

        // 语音概率 SeekBar
        val currentProb = sharedPref.getInt("voiceProb_$aiId", 30)
        addSeekBarRow(group3, "自动语音触发概率", currentProb) { progress ->
            sharedPref.edit().putInt("voiceProb_$aiId", progress).apply()
        }

        // TTS 开关
        val switchTts = Switch(this).apply { isChecked = sharedPref.getBoolean("ttsEnable_$aiId", false) }
        addSwitchRow(group3, "开启语音生成 (TTS)", switchTts, showDivider = true) { checked ->
            sharedPref.edit().putBoolean("ttsEnable_$aiId", checked).apply()
        }

        // 音色 ID
        addCardRow(group3, "音色配置 ID", rightText = "${sharedPref.getString("voiceId_$aiId", "未设置")} ›", showDivider = true) {
            showEditDialog("输入音色 ID", sharedPref.getString("voiceId_$aiId", "") ?: "") { v ->
                sharedPref.edit().putString("voiceId_$aiId", v).apply()
                recreate()
            }
        }

        // 角色语言
        val langOptions = try { resources.getStringArray(R.array.language_options) } catch (_: Exception) { arrayOf("默认 (中文)", "英文") }
        val savedLang = sharedPref.getString("aiLang_$aiId", "默认 (中文)") ?: "默认 (中文)"
        addCardRow(group3, "角色对话语言", rightText = "$savedLang ›", showDivider = true) {
            AlertDialog.Builder(this).setTitle("选择语言").setItems(langOptions) { _, which ->
                sharedPref.edit().putString("aiLang_$aiId", langOptions[which]).apply()
                recreate()
            }.show()
        }

        // 自动翻译
        val switchTrans = Switch(this).apply { isChecked = sharedPref.getBoolean("autoTrans_$aiId", false) }
        addSwitchRow(group3, "开启回复自动翻译", switchTrans, showDivider = true) { checked ->
            sharedPref.edit().putBoolean("autoTrans_$aiId", checked).apply()
        }

        // ── 分组4：朋友圈 ──────────────────────────────────
        addSectionLabel(root, "朋友圈")
        val groupMoments = createCard(root)
        val momentPostProb = sharedPref.getInt("momentPostProb_$aiId", 50)
        addSeekBarRow(groupMoments, "角色发朋友圈概率", momentPostProb) { progress ->
            sharedPref.edit().putInt("momentPostProb_$aiId", progress).apply()
        }

        // ── 分组5：角色关系 ─────────────────────────────────
        addSectionLabel(root, "角色关系")
        val group4 = createCard(root)
        val relOptions = listOf("陌生人", "普通朋友", "好友", "恋人", "暗恋对象", "家人")
        val currentRel = try {
            val c = DatabaseHelper(this).readableDatabase
                .rawQuery("SELECT relationship FROM Contacts WHERE userId=?", arrayOf(aiId))
            val v = if (c.moveToFirst()) c.getString(0) ?: "普通朋友" else "普通朋友"
            c.close(); v
        } catch (_: Exception) { "普通朋友" }

        addCardRow(group4, "与角色的关系", rightText = "$currentRel ›", showDivider = false) {
            AlertDialog.Builder(this).setTitle("选择关系").setItems(relOptions.toTypedArray()) { _, which ->
                try {
                    DatabaseHelper(this).writableDatabase.execSQL(
                        "UPDATE Contacts SET relationship=? WHERE userId=?",
                        arrayOf(relOptions[which], aiId)
                    )
                    recreate()
                } catch (_: Exception) {}
            }.show()
        }

        // ── 分组6：预设与高级管理 ────────────────────────────
        addSectionLabel(root, "高级管理")
        val group5 = createCard(root)

        addCardRow(group5, "📦 导入角色预设文件", showDivider = false) {
            AlertDialog.Builder(this).setTitle("导入预设")
                .setItems(arrayOf("📱 线上预设", "🎭 线下预设", "🗑️ 清除所有预设")) { _, which ->
                    when (which) {
                        0 -> pickPresetOnline.launch(arrayOf("application/json", "*/*"))
                        1 -> pickPresetOffline.launch(arrayOf("application/json", "*/*"))
                        2 -> {
                            DatabaseHelper(this).writableDatabase.execSQL(
                                "UPDATE Contacts SET presetOnline='', presetOffline='' WHERE userId=?", arrayOf(aiId)
                            )
                            Toast.makeText(this, "预设已清空", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.show()
        }

        // 拉黑
        val blockPref = getSharedPreferences("BlockList", Context.MODE_PRIVATE)
        val isBlocked = blockPref.getBoolean(aiId, false)
        val blockLabel = if (isBlocked) "🟢 刑满释放" else "🚫 暂时拉黑"
        val blockColor = if (isBlocked) "#34C759" else "#FF9500"
        addCardRow(group5, blockLabel, textColor = blockColor, showDivider = true) {
            val nowBlocked = blockPref.getBoolean(aiId, false)
            blockPref.edit().putBoolean(aiId, !nowBlocked).apply()
            recreate()
        }

        addCardRow(group5, "清空所有聊天记录", textColor = "#FF3B30", showDivider = true) {
            AlertDialog.Builder(this).setTitle("确认清空")
                .setMessage("确定要删除与 $aiName 的所有历史消息吗？此操作不可恢复！")
                .setPositiveButton("清空") { _, _ ->
                    DatabaseHelper(this).writableDatabase.delete(
                        "ChatHistory",
                        "aiId=?",
                        arrayOf(aiId)
                    )

                    sendBroadcast(Intent("CYBER_NEW_MSG"))

                    Toast.makeText(this, "记录已清空", Toast.LENGTH_SHORT).show()
                }.setNegativeButton("取消", null).show()
        }

        addCardRow(group5, "清空记忆宫殿", textColor = "#FF3B30", showDivider = true) {
            AlertDialog.Builder(this).setTitle("确认清空")
                .setMessage("确定要清空 $aiName 的所有长期记忆吗？")
                .setPositiveButton("清空") { _, _ ->
                    DatabaseHelper(this).writableDatabase.delete("MemoryBank", "aiId=?", arrayOf(aiId))
                    memoryContent = ""
                    Toast.makeText(this, "记忆已清空", Toast.LENGTH_SHORT).show()
                }.setNegativeButton("取消", null).show()
        }

        addCardRow(group5, "彻底删除此角色", textColor = "#FF3B30", showDivider = true) {
            AlertDialog.Builder(this).setTitle("彻底删除")
                .setMessage("确定删除角色 $aiName？此操作不可恢复！")
                .setPositiveButton("删除") { _, _ ->
                    val db = DatabaseHelper(this).writableDatabase

                    db.delete("Contacts", "userId=?", arrayOf(aiId))
                    db.delete("ChatHistory", "aiId=?", arrayOf(aiId))
                    db.delete("MemoryBank", "aiId=?", arrayOf(aiId))

                    db.delete("Schedules", "aiId=?", arrayOf(aiId))
                    db.delete("NpcEvents", "aiId=?", arrayOf(aiId))
                    db.delete("GroupMembers", "memberId=?", arrayOf(aiId))
                    Toast.makeText(this, "💥 物理超度成功！", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                }.setNegativeButton("取消", null).show()
        }

        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(60)) })
        setContentView(scroll)
    }

    // ── 辅助函数 ──────────────────────────────────────────
    private fun saveMemoryToDb() {
        try {
            val db = DatabaseHelper(this).writableDatabase
            db.delete("MemoryBank", "aiId=? AND category='misc'", arrayOf(aiId))
            if (memoryContent.isNotEmpty()) {
                val v = ContentValues().apply {
                    put("aiId", aiId); put("memoryText", memoryContent)
                    put("category", "misc"); put("insertTime", System.currentTimeMillis())
                }
                db.insert("MemoryBank", null, v)
            }
            Toast.makeText(this, "记忆已保存", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    private fun addSectionLabel(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text; textSize = 13f; setTextColor(Color.GRAY)
            setPadding(dp(16), dp(16), dp(16), dp(8))
        })
    }

    private fun createCard(parent: LinearLayout) = LinearLayout(this).also {
        it.orientation = LinearLayout.VERTICAL
        it.setBackgroundColor(ThemeManager.getColor("--profile-item-bg", Color.WHITE))
        parent.addView(it)
    }

    private fun addDivider(parent: LinearLayout) = parent.addView(View(this).apply {
        setBackgroundColor(Color.parseColor("#EEEEEE"))
        layoutParams = LinearLayout.LayoutParams(-1, 1).also { it.marginStart = dp(16) }
    })

    private fun addCardRow(
        parent: LinearLayout, text: String, rightText: String = "",
        textColor: String = "#000000", showDivider: Boolean = true, onClick: () -> Unit
    ) {
        if (showDivider && parent.childCount > 0) addDivider(parent)
        val row = RelativeLayout(this).apply {
            setPadding(dp(16), dp(15), dp(16), dp(15))
            isClickable = true; isFocusable = true
            val out = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
            setBackgroundResource(out.resourceId)
        }
        row.addView(TextView(this).apply {
            this.text = text; textSize = 16f; setTextColor(Color.parseColor(textColor))
            layoutParams = RelativeLayout.LayoutParams(-2, -2).also { it.addRule(RelativeLayout.CENTER_VERTICAL) }
        })
        if (rightText.isNotEmpty()) {
            row.addView(TextView(this).apply {
                this.text = rightText; textSize = 14f; setTextColor(Color.GRAY)
                layoutParams = RelativeLayout.LayoutParams(-2, -2).also {
                    it.addRule(RelativeLayout.ALIGN_PARENT_END)
                    it.addRule(RelativeLayout.CENTER_VERTICAL)
                }
            })
        }
        row.setOnClickListener { onClick() }
        parent.addView(row)
    }

    private fun addSwitchRow(
        parent: LinearLayout, text: String, sw: Switch,
        showDivider: Boolean = true, onChange: (Boolean) -> Unit
    ) {
        if (showDivider && parent.childCount > 0) addDivider(parent)
        val row = RelativeLayout(this).apply { setPadding(dp(16), dp(10), dp(16), dp(10)) }
        row.addView(TextView(this).apply {
            this.text = text; textSize = 16f; setTextColor(Color.BLACK)
            layoutParams = RelativeLayout.LayoutParams(-2, -2).also { it.addRule(RelativeLayout.CENTER_VERTICAL) }
        })
        sw.layoutParams = RelativeLayout.LayoutParams(-2, -2).also {
            it.addRule(RelativeLayout.ALIGN_PARENT_END)
            it.addRule(RelativeLayout.CENTER_VERTICAL)
        }
        sw.setOnCheckedChangeListener { _, c -> onChange(c) }
        row.addView(sw); parent.addView(row)
    }

    private fun addSeekBarRow(parent: LinearLayout, text: String, current: Int, onProgress: (Int) -> Unit) {
        if (parent.childCount > 0) addDivider(parent)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val header = RelativeLayout(this)
        header.addView(TextView(this).apply {
            this.text = text; textSize = 16f; setTextColor(Color.BLACK)
        })
        val tvVal = TextView(this).apply {
            this.text = "$current%"; textSize = 14f; setTextColor(Color.parseColor("#007AFF"))
            layoutParams = RelativeLayout.LayoutParams(-2, -2).also { it.addRule(RelativeLayout.ALIGN_PARENT_END) }
        }
        header.addView(tvVal)
        val sb = SeekBar(this).apply {
            max = 100; progress = current; setPadding(0, dp(10), 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { tvVal.text = "$p%"; onProgress(p) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        container.addView(header); container.addView(sb); parent.addView(container)
    }

    private fun showEditDialog(title: String, current: String, onSave: (String) -> Unit) {
        val et = EditText(this).apply { setText(current); setPadding(dp(20), dp(16), dp(20), dp(16)) }
        AlertDialog.Builder(this).setTitle(title).setView(et)
            .setPositiveButton("保存") { _, _ -> onSave(et.text.toString()) }.show()
    }

    // ── 查找聊天记录 ──────────────────────────────────────
    private fun showSearchChatDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2F2F6"))
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        val searchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8)); gravity = Gravity.CENTER_VERTICAL
        }
        val etSearch = EditText(this).apply {
            hint = "搜索聊天内容..."; textSize = 15f; setTextColor(Color.BLACK)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#F5F5F5")); cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        searchBar.addView(etSearch)
        layout.addView(searchBar)
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@CharacterSettingsActivity)
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        layout.addView(rv)

        data class ChatMsg(val content: String, val isFromMe: Boolean, val time: String, val timestamp: Long)
        val allMsgs = mutableListOf<ChatMsg>()
        try {
            val c = DatabaseHelper(this).readableDatabase.rawQuery(
                "SELECT content, isFromMe, msgTime, timestamp FROM ChatHistory WHERE aiId=? ORDER BY timestamp ASC",
                arrayOf(aiId)
            )
            while (c.moveToNext()) {
                allMsgs.add(ChatMsg(c.getString(0) ?: "", c.getInt(1) == 1, c.getString(2) ?: "", c.getLong(3)))
            }
            c.close()
        } catch (_: Exception) {}

        fun groupByDay(msgs: List<ChatMsg>): List<Any> {
            val result = mutableListOf<Any>()
            var currentDate = ""
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            for (msg in msgs) {
                val date = try { sdf.format(java.util.Date(msg.timestamp)) } catch (_: Exception) { "" }
                if (date != currentDate) { currentDate = date; result.add(date) }
                result.add(msg)
            }
            return result
        }

        var displayItems = groupByDay(allMsgs)

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemViewType(position: Int) = if (displayItems[position] is String) 0 else 1
            override fun getItemCount() = displayItems.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                return if (viewType == 0) {
                    val tv = TextView(parent.context).apply {
                        textSize = 12f; setTextColor(Color.GRAY); gravity = Gravity.CENTER
                        setPadding(0, dp(12), 0, dp(6)); layoutParams = RecyclerView.LayoutParams(-1, -2)
                    }
                    object : RecyclerView.ViewHolder(tv) {}
                } else {
                    val container = LinearLayout(parent.context).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(6))
                        layoutParams = RecyclerView.LayoutParams(-1, -2)
                    }
                    object : RecyclerView.ViewHolder(container) {}
                }
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val item = displayItems[position]
                if (item is String) {
                    (holder.itemView as TextView).text = item
                } else {
                    val msg = item as ChatMsg
                    val msgLayout = holder.itemView as LinearLayout
                    msgLayout.removeAllViews()
                    val isMe = msg.isFromMe
                    val bubble = TextView(msgLayout.context).apply {
                        text = msg.content; textSize = 14f
                        setTextColor(if (isMe) Color.WHITE else Color.BLACK)
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(if (isMe) Color.parseColor("#07C160") else Color.WHITE)
                            cornerRadius = dp(12).toFloat()
                        }
                        layoutParams = LinearLayout.LayoutParams(-2, -2).also {
                            it.gravity = if (isMe) Gravity.END else Gravity.START
                        }
                    }
                    val tvTime = TextView(msgLayout.context).apply {
                        text = msg.time; textSize = 10f; setTextColor(Color.GRAY); setPadding(0, dp(2), 0, 0)
                        layoutParams = LinearLayout.LayoutParams(-2, -2).also {
                            it.gravity = if (isMe) Gravity.END else Gravity.START
                        }
                    }
                    msgLayout.addView(bubble); msgLayout.addView(tvTime)
                }
            }
        }
        rv.adapter = adapter

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val kw = s?.toString() ?: ""
                val filtered = if (kw.isBlank()) allMsgs else allMsgs.filter { it.content.contains(kw, ignoreCase = true) }
                displayItems = groupByDay(filtered)
                adapter.notifyDataSetChanged()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        AlertDialog.Builder(this)
            .setTitle("查找聊天记录 (共${allMsgs.size}条)")
            .setView(layout).setNegativeButton("关闭", null).show()
            .window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.9).toInt(),
                (resources.displayMetrics.heightPixels * 0.8).toInt()
            )
    }

    // ── 壁纸 ──────────────────────────────────────────────
    private fun showWallpaperMenu() {
        val options = arrayOf("🖼️ 选择图片", "🎨 选择纯色", "🗑️ 恢复默认")
        AlertDialog.Builder(this).setTitle("设置聊天背景").setItems(options) { _, which ->
            when (which) {
                0 -> pickWallpaper.launch(arrayOf("image/*"))
                1 -> showColorPicker()
                2 -> {
                    getSharedPreferences("AppConfig", Context.MODE_PRIVATE).edit()
                        .remove("chatBg_$aiId")
                        .apply()
                    sendBroadcast(Intent("CHAT_BG_CHANGED").putExtra("aiId", aiId))
                    Toast.makeText(this, "背景已重置", Toast.LENGTH_SHORT).show()
                }
            }
        }.show()
    }

    private val pickWallpaper = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                // 复制到内部存储，不依赖URI权限
                val fileName = "chatbg_${aiId}_${System.currentTimeMillis()}.jpg"
                val destFile = java.io.File(filesDir, fileName)
                contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                getSharedPreferences("AppConfig", Context.MODE_PRIVATE).edit()
                    .putString("chatBg_$aiId", destFile.absolutePath)
                    .apply()
                sendBroadcast(Intent("CHAT_BG_CHANGED").putExtra("aiId", aiId))
                Toast.makeText(this, "聊天背景已设置", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "设置失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun showColorPicker() {
        val colors = arrayOf("浅灰 #F2F2F6", "米白 #FAF7F2", "深夜 #1C1C1E")
        val values = arrayOf("#F2F2F6", "#FAF7F2", "#1C1C1E")
        AlertDialog.Builder(this).setItems(colors) { _, i ->
            getSharedPreferences("AppConfig", Context.MODE_PRIVATE).edit()
                .putString("chatBg_$aiId", "color:${values[i]}").apply()
            sendBroadcast(Intent("CHAT_BG_CHANGED").putExtra("aiId", aiId))
            Toast.makeText(this, "聊天背景已设置", Toast.LENGTH_SHORT).show()
        }.show()
    }

    // ── 导入预设 ──────────────────────────────────────────
    private fun importPreset(uri: android.net.Uri, isOffline: Boolean) {
        Thread {
            try {
                val jsonStr = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return@Thread
                val json = JSONObject(jsonStr)
                val prompts = json.optJSONArray("prompts") ?: return@Thread
                val items = mutableListOf<Triple<String, String, Boolean>>()
                for (i in 0 until prompts.length()) {
                    val p = prompts.getJSONObject(i)
                    items.add(Triple(p.optString("name", "未命名"), p.optString("content", ""), p.optBoolean("enabled", true)))
                }
                runOnUiThread {
                    val names = items.map { it.first }.toTypedArray()
                    val checked = BooleanArray(items.size) { items[it].third }
                    AlertDialog.Builder(this).setTitle("选择要导入的条目")
                        .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
                        .setPositiveButton("导入") { _, _ ->
                            val sb = StringBuilder()
                            items.forEachIndexed { i, triple ->
                                if (checked[i]) {
                                    if (!isOffline) {
                                        val skip = listOf("<thinking>", "<draft>", "【台词】")
                                        if (skip.none { triple.second.contains(it) }) sb.append(triple.second).append("\n\n")
                                    } else sb.append(triple.second).append("\n\n")
                                }
                            }
                            val col = if (isOffline) "presetOffline" else "presetOnline"
                            DatabaseHelper(this).writableDatabase.execSQL(
                                "UPDATE Contacts SET $col=? WHERE userId=?", arrayOf(sb.toString().trim(), aiId)
                            )
                            Toast.makeText(this, "导入成功", Toast.LENGTH_SHORT).show()
                        }.show()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "错误: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }
}
