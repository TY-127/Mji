package com.moon.aiphone

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 线下见面 —— 设置页（替代原来的弹窗）
 * 两种用法：
 *  1) 新见面：OfflineContactActivity -> 本页 -> OfflineChatActivity（startActivity 进聊天）
 *  2) 编辑模式（EDIT_MODE=true）：聊天页右上角"修改见面设置"进来，保存后 setResult 回传，
 *     聊天页在 onActivityResult 里就地更新，不重开聊天。
 */
class OfflineSetupActivity : AppCompatActivity() {

    private var aiId = ""
    private var aiName = ""
    private var editMode = false

    // 选择结果（编辑模式下先用传入值初始化）
    private var meetLocation = ""
    private var meetTime = ""
    private var meetMood = ""
    private var meetPerson = "第二人称"
    private var meetAko = false
    private var styleChoice = "默认"   // 默认 / 海明威·冰山白描 / 自定义

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        aiId = intent.getStringExtra("AI_ID") ?: ""
        aiName = intent.getStringExtra("AI_NAME") ?: ""
        editMode = intent.getBooleanExtra("EDIT_MODE", false)

        // 编辑模式：用传入的当前值预填
        val incomingStyle = intent.getStringExtra("MEET_STYLE") ?: ""
        if (editMode) {
            meetLocation = intent.getStringExtra("MEET_LOCATION") ?: ""
            meetTime = intent.getStringExtra("MEET_TIME") ?: ""
            meetMood = intent.getStringExtra("MEET_MOOD") ?: ""
            meetPerson = intent.getStringExtra("MEET_PERSON") ?: "第二人称"
            meetAko = intent.getBooleanExtra("MEET_AKO", false)
            styleChoice = when {
                incomingStyle.isEmpty() -> "默认"
                incomingStyle == HEMINGWAY_STYLE -> "海明威·冰山白描"
                else -> "自定义"
            }
        }

        val ctx = this

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        // 顶栏
        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(40, 40, 40, 40)
            gravity = Gravity.CENTER_VERTICAL
            elevation = 8f
        }
        val backBtn = TextView(ctx).apply {
            text = "←"; setTextColor(Color.BLACK); textSize = 22f
            setPadding(0, 0, 20, 0)
            setOnClickListener { finish() }
        }
        val title = TextView(ctx).apply {
            text = if (editMode) "修改见面设置 · $aiName" else "见面设置 · $aiName"
            setTextColor(Color.BLACK); textSize = 18f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(backBtn)
        topBar.addView(title)
        root.addView(topBar)

        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 40)
        }

        // ===== 前情背景 =====
        layout.addView(sectionLabel(ctx, "前情背景（可选）"))
        val bgInput = makeEditText(ctx, "两人关系的近况、上次见面的情况…", multiline = true)
        if (editMode) bgInput.setText(intent.getStringExtra("MEET_BACKGROUND") ?: "")
        layout.addView(bgInput)

        // ===== 面具（用户本次扮演身份）=====
        layout.addView(sectionLabel(ctx, "面具 · 你本次扮演的身份"))
        layout.addView(hintLabel(ctx, "留空则沿用你在 chat / 档案中的默认身份"))
        val maskInput = makeEditText(ctx, "例如：一名路过的医生 / 你的同班同学 / 留空用默认…", multiline = true)
        if (editMode) maskInput.setText(intent.getStringExtra("MEET_MASK") ?: "")
        layout.addView(maskInput)

        // ===== 人称 =====
        layout.addView(sectionLabel(ctx, "人称 · 如何称呼你"))
        val persons = listOf("第一人称", "第二人称", "第三人称")
        layout.addView(
            chipGroup(ctx, persons, preselect = meetPerson) { meetPerson = it }
        )
        layout.addView(hintLabel(ctx, "第一人称＝“我”视角；第二人称＝称你为“你”；第三人称＝用名字/TA"))

        // ===== 地点 =====
        layout.addView(sectionLabel(ctx, "地点"))
        val locations = listOf("咖啡馆", "公园长椅", "你的住所", "对方住所", "图书馆", "便利店", "车站附近", "海边")
        val locPreselect = if (meetLocation in locations) meetLocation else null
        layout.addView(chipGroup(ctx, locations, preselect = locPreselect) { meetLocation = it })
        val locCustom = makeEditText(ctx, "或自定义地点…", multiline = false)
        if (editMode && meetLocation.isNotEmpty() && locPreselect == null) locCustom.setText(meetLocation)
        layout.addView(locCustom)

        // ===== 时间 =====
        layout.addView(sectionLabel(ctx, "时间"))
        val times = listOf("清晨", "上午", "正午", "下午", "傍晚", "深夜")
        layout.addView(chipGroup(ctx, times, preselect = meetTime.ifEmpty { null }) { meetTime = it })

        // ===== 情境氛围 =====
        layout.addView(sectionLabel(ctx, "情境氛围"))
        val moods = listOf("日常温馨", "暧昧拉扯", "久别重逢", "争执和好", "安静陪伴", "雨天偶遇")
        layout.addView(chipGroup(ctx, moods, preselect = meetMood.ifEmpty { null }) { meetMood = it })

        // ===== 文风 =====
        layout.addView(sectionLabel(ctx, "文风"))
        val styles = listOf("默认", "海明威·冰山白描", "自定义")
        val styleCustom = makeEditText(ctx, "自定义文风要求（选“自定义”时生效）…", multiline = true)
        if (editMode && styleChoice == "自定义") styleCustom.setText(incomingStyle)
        layout.addView(chipGroup(ctx, styles, preselect = styleChoice) { styleChoice = it })
        layout.addView(hintLabel(ctx, "“海明威·冰山白描”＝无感情倾向的通俗白描，重人物叙写"))
        layout.addView(styleCustom)

        // ===== 盲盒小剧场 =====
        layout.addView(sectionLabel(ctx, "盲盒小剧场"))
        val skitRow = switchRow(
            ctx,
            "开启后，AI 会随机穿插一段 HTML 渲染的小剧场\n（聊天软件 / 老式设备 / 信纸 / 抽奖转盘等）"
        )
        val skitSwitch = skitRow.second
        if (editMode) skitSwitch.isChecked = intent.getBooleanExtra("MEET_SKIT", false)
        layout.addView(skitRow.first)

        // ===== 自动行动选项 =====
        layout.addView(sectionLabel(ctx, "自动行动选项"))
        val akoRow = switchRow(
            ctx,
            "开启后，AI 每次输出末尾会给出 6 个行动选项\n（点一下填入输入框，可修改后再发送）"
        )
        val akoSwitch = akoRow.second
        akoSwitch.isChecked = meetAko
        layout.addView(akoRow.first)

        // ===== 主按钮 =====
        val startBtn = Button(ctx).apply {
            text = if (editMode) "保存设置" else "推开门 · 开始见面"
            setTextColor(Color.WHITE); textSize = 16f
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#4A7C59")); cornerRadius = 16f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 140
            ).apply { topMargin = 48 }
            setOnClickListener {
                if (locCustom.text.toString().trim().isNotEmpty()) {
                    meetLocation = locCustom.text.toString().trim()
                }
                val styleText = when (styleChoice) {
                    "海明威·冰山白描" -> HEMINGWAY_STYLE
                    "自定义" -> styleCustom.text.toString().trim()
                    else -> ""
                }

                if (editMode) {
                    // 回传给聊天页
                    val result = Intent().apply {
                        putExtra("MEET_LOCATION", meetLocation)
                        putExtra("MEET_TIME", meetTime)
                        putExtra("MEET_MOOD", meetMood)
                        putExtra("MEET_BACKGROUND", bgInput.text.toString().trim())
                        putExtra("MEET_MASK", maskInput.text.toString().trim())
                        putExtra("MEET_PERSON", meetPerson)
                        putExtra("MEET_STYLE", styleText)
                        putExtra("MEET_SKIT", skitSwitch.isChecked)
                        putExtra("MEET_AKO", akoSwitch.isChecked)
                    }
                    setResult(RESULT_OK, result)
                    finish()
                } else {
                    val sessionId = getOrCreateActiveSessionId()
                    val next = Intent(ctx, OfflineChatActivity::class.java).apply {
                        putExtra("AI_ID", aiId)
                        putExtra("AI_NAME", aiName)
                        putExtra("OFFLINE_SESSION_ID", sessionId)
                        putExtra("MEET_LOCATION", meetLocation)
                        putExtra("MEET_TIME", meetTime)
                        putExtra("MEET_MOOD", meetMood)
                        putExtra("MEET_BACKGROUND", bgInput.text.toString().trim())
                        putExtra("MEET_MASK", maskInput.text.toString().trim())
                        putExtra("MEET_PERSON", meetPerson)
                        putExtra("MEET_STYLE", styleText)
                        putExtra("MEET_SKIT", skitSwitch.isChecked)
                        putExtra("MEET_AKO", akoSwitch.isChecked)
                    }
                    startActivity(next)
                    finish()
                }
            }
        }
        layout.addView(startBtn)

        scroll.addView(layout)
        root.addView(scroll)
        setContentView(root)
    }

    private fun getOrCreateActiveSessionId(): String {
        val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val key = "offlineActiveSession_$aiId"
        pref.getString(key, "")?.takeIf { it.isNotBlank() }?.let { return it }

        try {
            val db = DatabaseHelper(this).writableDatabase
            try { db.execSQL("ALTER TABLE OfflineChatHistory ADD COLUMN sessionId TEXT DEFAULT ''") } catch (_: Exception) {}
            db.rawQuery(
                "SELECT IFNULL(sessionId,'') FROM OfflineChatHistory WHERE aiId=? ORDER BY timestamp DESC LIMIT 1",
                arrayOf(aiId)
            ).use { c ->
                if (c.moveToFirst()) {
                    val existing = c.getString(0) ?: ""
                    if (existing.isNotBlank()) {
                        pref.edit().putString(key, existing).apply()
                        return existing
                    }
                }
            }
        } catch (_: Exception) {}

        val created = "door_${aiId}_${System.currentTimeMillis()}"
        pref.edit().putString(key, created).apply()
        return created
    }

    // ---------- 小工具 ----------

    private fun sectionLabel(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 15f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.parseColor("#333333"))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 32; bottomMargin = 12 }
    }

    private fun hintLabel(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor("#999999"))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 8 }
    }

    private fun makeEditText(ctx: Context, hint: String, multiline: Boolean): EditText =
        EditText(ctx).apply {
            this.hint = hint
            setHintTextColor(Color.parseColor("#AAAAAA"))
            setTextColor(Color.BLACK)
            textSize = 14f
            if (multiline) { minLines = 2; maxLines = 5 }
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            setPadding(24, 20, 24, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }

    /** 一行：左侧说明文字 + 右侧开关，返回(行容器, 开关) */
    private fun switchRow(ctx: Context, desc: String): Pair<LinearLayout, Switch> {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4 }
        }
        val descTv = TextView(ctx).apply {
            text = desc
            textSize = 13f; setTextColor(Color.parseColor("#666666"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = Switch(ctx)
        row.addView(descTv)
        row.addView(sw)
        return row to sw
    }

    /**
     * 流式 chip 选择组（4 个换行）。可选 preselect 默认选中项。
     */
    private fun chipGroup(
        ctx: Context,
        items: List<String>,
        preselect: String? = null,
        onSelect: (String) -> Unit
    ): LinearLayout {
        val chipViews = mutableListOf<TextView>()

        fun paint(chip: TextView, selected: Boolean) {
            (chip.background as? android.graphics.drawable.GradientDrawable)?.setColor(
                if (selected) Color.parseColor("#4A7C59") else Color.parseColor("#F0F0F0")
            )
            chip.setTextColor(if (selected) Color.WHITE else Color.parseColor("#555555"))
        }

        items.forEach { label ->
            val chip = TextView(ctx).apply {
                text = label
                textSize = 13f
                setPadding(28, 12, 28, 12)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#F0F0F0"))
                    cornerRadius = 40f
                    setStroke(1, Color.parseColor("#DDDDDD"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 12, 12) }
                setOnClickListener {
                    onSelect(label)
                    chipViews.forEach { paint(it, false) }
                    paint(this, true)
                }
            }
            paint(chip, label == preselect)
            chipViews.add(chip)
        }

        val wrapLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        var rowLayout = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        chipViews.forEachIndexed { i, chip ->
            rowLayout.addView(chip)
            if ((i + 1) % 4 == 0) {
                wrapLayout.addView(rowLayout)
                rowLayout = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            }
        }
        if (rowLayout.childCount > 0) wrapLayout.addView(rowLayout)
        return wrapLayout
    }

    companion object {
        // “海明威·冰山白描”文风（来自用户提供的风格设定）
        const val HEMINGWAY_STYLE = """无倾向的通俗白描文学风格，重视人物叙写。
keyword：SIMPLE but SHARP
核心：writer=海明威；theory=冰山原则。
采用无感情倾向的白描手法：只写“角色做了什么”，不写“没做什么”，像用简单线条勾勒的图画，清爽、静谧、直白。
去除所有作者评价，仅展现客观现实和角色真实心理活动。完全学习现代通俗文学风格。
叙事特色：注重整体氛围的构建，富有条理、循序渐进的白描，为读者还原完全真实的画面；人物中心化叙事，叙事完全服务于人物塑造，客观展现人物行为不做评价；叙事现实化，注重逻辑与细节，严格参照真实生活，无任何刻意美化与幻想。
语言特色：叙事者即旁白，无任何情感倾向，只客观展现事实，不解释潜台词；环境描写节制，只保留与人物相关的关键细节；人称代词多样化以避免单调。
文本组织：长句短句灵活结合，段落多样化；完全使用白话，禁止使用“深渊”“宇宙”等高深意象表达情绪或构成比喻。
对话：完全生活化，杜绝科学术语。
（示例风格仅供借鉴、不可照抄：如海明威《越野滑雪》中克制、干净的对白与动作描写。）"""
    }
}
