package com.moon.aiphone

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MemoryDetailActivity : AppCompatActivity() {

    data class MemoryEntry(val id: Int, val memoryText: String, val category: String)

    private lateinit var aiId: String
    private lateinit var aiName: String
    private var currentCategory = "user_info"

    private val tabUserInfo by lazy { findViewById<TextView>(R.id.tabUserInfo) }
    private val tabSharedEvent by lazy { findViewById<TextView>(R.id.tabSharedEvent) }
    private val tabFuturePlan by lazy { findViewById<TextView>(R.id.tabFuturePlan) }
    private val tabMiscTreasure by lazy { findViewById<TextView>(R.id.tabMiscTreasure) }
    private val tabKeyEvent by lazy { findViewById<TextView>(R.id.tabKeyEvent) }
    private val tabTimeline by lazy { findViewById<TextView>(R.id.tabTimeline) }
    private val rvMemoryList by lazy { findViewById<RecyclerView>(R.id.rvMemoryList) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory_detail)
        supportActionBar?.hide()

        aiId = intent.getStringExtra("aiId") ?: ""
        aiName = intent.getStringExtra("aiName") ?: ""
        findViewById<TextView>(R.id.tvAiName).text = "${aiName}的记忆"
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        // 手动新增记忆按钮
        findViewById<TextView>(R.id.btnAddMemory).setOnClickListener {
            showAddDialog()
        }

        rvMemoryList.layoutManager = LinearLayoutManager(this)

        tabUserInfo.setOnClickListener { switchTab("user_info", tabUserInfo) }
        tabSharedEvent.setOnClickListener { switchTab("shared_event", tabSharedEvent) }
        tabFuturePlan.setOnClickListener { switchTab("future_plan", tabFuturePlan) }
        tabMiscTreasure.setOnClickListener { switchTab("misc_treasure", tabMiscTreasure) }
        tabKeyEvent.setOnClickListener { switchTab("key_event", tabKeyEvent) }
        tabTimeline.setOnClickListener { switchTab("timeline", tabTimeline) }

        switchTab("user_info", tabUserInfo)
    }

    private fun switchTab(category: String, tab: TextView) {
        currentCategory = category
        listOf(tabUserInfo, tabSharedEvent, tabFuturePlan, tabMiscTreasure, tabKeyEvent, tabTimeline).forEach {
            it.setTextColor(android.graphics.Color.parseColor("#999999"))
            it.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        tab.setTextColor(android.graphics.Color.WHITE)
        tab.setBackgroundResource(R.drawable.bg_glass_panel)
        loadMemories()
    }

    private fun loadMemories() {
        val list = mutableListOf<MemoryEntry>()
        try {
            val db = DatabaseHelper(this).readableDatabase
            val cur = db.rawQuery(
                "SELECT id, memoryText, category FROM MemoryBank WHERE aiId=? AND category=? ORDER BY id DESC",
                arrayOf(aiId, currentCategory)
            )
            while (cur.moveToNext()) {
                list.add(MemoryEntry(cur.getInt(0), cur.getString(1), cur.getString(2)))
            }
            cur.close()
        } catch (e: Exception) {}

        val emptyTv = findViewById<TextView>(R.id.tvEmptyMemory)
        if (list.isEmpty()) {
            emptyTv.visibility = View.VISIBLE
            emptyTv.text = when (currentCategory) {
                "timeline" -> "还没有时间线记忆\n聊满30条消息后自动生成"
                "key_event" -> "还没有关键事件记忆\n聊到重要时刻自动记录"
                else -> "此分类暂无记忆\n点右上角＋手动添加，或切换其他标签查看"
            }
        } else {
            emptyTv.visibility = View.GONE
        }

        rvMemoryList.adapter = MemoryAdapter(list) { entry -> showEditDialog(entry) }
    }

    private fun showAddDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }

        val categoryLabel = TextView(this).apply {
            text = "分类：${getCategoryName(currentCategory)}"
            setTextColor(android.graphics.Color.parseColor("#888888"))
            textSize = 12f
            setPadding(0, 0, 0, 12)
        }

        val etContent = EditText(this).apply {
            hint = "输入记忆内容..."
            minLines = 3
            maxLines = 6
            setHintTextColor(android.graphics.Color.parseColor("#888888"))
        }

        layout.addView(categoryLabel)
        layout.addView(etContent)

        AlertDialog.Builder(this)
            .setTitle("✍️ 手动添加记忆")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val content = etContent.text.toString().trim()
                if (content.isNotEmpty()) {
                    try {
                        val cv = android.content.ContentValues().apply {
                            put("aiId", aiId)
                            put("memoryText", content)
                            put("category", currentCategory)
                            put("insertTime", System.currentTimeMillis())
                        }
                        DatabaseHelper(this).writableDatabase.insert("MemoryBank", null, cv)
                        loadMemories()
                        android.widget.Toast.makeText(this, "记忆已保存", android.widget.Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {}
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEditDialog(entry: MemoryEntry) {
        val isAuto = entry.category == "timeline" || entry.category == "key_event"
        val builder = AlertDialog.Builder(this)
            .setTitle(if (isAuto) "🤖 自动生成的记忆" else "📝 记忆内容")
            .setMessage(entry.memoryText)
            .setNegativeButton("关闭", null)
            .setPositiveButton("删除") { _, _ ->
                try {
                    DatabaseHelper(this).writableDatabase.delete(
                        "MemoryBank", "id=?", arrayOf(entry.id.toString())
                    )
                    loadMemories()
                } catch (e: Exception) {}
            }

        // 非自动记忆可以编辑
        if (!isAuto) {
            builder.setNeutralButton("编辑") { _, _ ->
                showEditContentDialog(entry)
            }
        }
        builder.show()
    }

    private fun showEditContentDialog(entry: MemoryEntry) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 20)
        }
        val etContent = EditText(this).apply {
            setText(entry.memoryText)
            minLines = 3
            maxLines = 6
        }
        layout.addView(etContent)

        AlertDialog.Builder(this)
            .setTitle("编辑记忆")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val newText = etContent.text.toString().trim()
                if (newText.isNotEmpty()) {
                    try {
                        val cv = android.content.ContentValues().apply {
                            put("memoryText", newText)
                            put("embedding", "")
                            put("insertTime", System.currentTimeMillis())

                        }
                        DatabaseHelper(this).writableDatabase.update(
                            "MemoryBank", cv, "id=?", arrayOf(entry.id.toString())
                        )
                        loadMemories()
                    } catch (e: Exception) {}
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun getCategoryName(category: String) = when (category) {
        "user_info" -> "用户信息"
        "shared_event" -> "共同经历"
        "future_plan" -> "未来计划"
        "misc_treasure" -> "珍贵碎片"
        "key_event" -> "关键时刻"
        "timeline" -> "时间线"
        else -> category
    }

    class MemoryAdapter(
        private val items: List<MemoryEntry>,
        private val onClick: (MemoryEntry) -> Unit
    ) : RecyclerView.Adapter<MemoryAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvMemory: TextView = v.findViewById(R.id.tvMemoryText)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_memory_entry, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvMemory.text = item.memoryText
            holder.itemView.setOnClickListener { onClick(item) }
            holder.itemView.setOnLongClickListener { onClick(item); true }
        }

        override fun getItemCount() = items.size
    }
}