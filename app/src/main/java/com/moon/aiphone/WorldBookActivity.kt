package com.moon.aiphone

import android.content.ContentValues
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class WorldBookActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: WorldBookAdapter
    private val itemList = mutableListOf<WorldBookItem>()
    private var isAdminMode = false
    private lateinit var dbHelper: DatabaseHelper

    private var clickCount = 0
    private var lastClickTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_world_book)
        supportActionBar?.hide()

        dbHelper = DatabaseHelper(this)

        // ===== 新增：升级 UserWorldBook 表，补 priority 和 targetAiId 列 =====
        try {
            dbHelper.writableDatabase.execSQL("ALTER TABLE UserWorldBook ADD COLUMN priority TEXT DEFAULT 'keyword'")
        } catch (e: Exception) {}
        try {
            dbHelper.writableDatabase.execSQL("ALTER TABLE UserWorldBook ADD COLUMN targetAiId TEXT DEFAULT 'global'")
        } catch (e: Exception) {}
        try {
            dbHelper.writableDatabase.execSQL("UPDATE UserWorldBook SET priority='keyword' WHERE priority IS NULL OR priority=''")
        } catch (e: Exception) {}
        try {
            dbHelper.writableDatabase.execSQL("UPDATE UserWorldBook SET targetAiId='global' WHERE targetAiId IS NULL OR targetAiId=''")
        } catch (e: Exception) {}
        // ===== 新增结束 =====

        recyclerView = findViewById(R.id.recyclerViewWorldBook) ?: return
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = WorldBookAdapter(itemList, isAdminMode) {
            runOnUiThread { loadData() }
        }
        recyclerView.adapter = adapter

        val tvSecretTitle = findViewById<TextView?>(R.id.tvSecretTitle)
        val btnAddEntry = findViewById<TextView?>(R.id.btnAddEntry)
        val btnBack = findViewById<TextView?>(R.id.btnBack)

        btnBack?.setOnClickListener { finish() }

        tvSecretTitle?.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastClickTime < 500) clickCount++ else clickCount = 1
            lastClickTime = currentTime
            if (clickCount == 7) {
                isAdminMode = !isAdminMode
                if (isAdminMode) {
                    tvSecretTitle.text = "管理员绝密大厅"
                    tvSecretTitle.setTextColor(android.graphics.Color.parseColor("#EF4444"))
                    Toast.makeText(this, "警告：已潜入管理员底层协议！", Toast.LENGTH_SHORT).show()
                } else {
                    tvSecretTitle.text = "世界书设定"
                    tvSecretTitle.setTextColor(android.graphics.Color.parseColor("#000000"))
                    Toast.makeText(this, "已返回普通用户层。", Toast.LENGTH_SHORT).show()
                }
                loadData()
                clickCount = 0
            }
        }

        btnAddEntry?.setOnClickListener { showAddDialog() }

        findViewById<android.view.View?>(R.id.btnCharRelationship)?.setOnClickListener {
            startActivity(android.content.Intent(this, CharacterRelationshipActivity::class.java))
        }

        loadData()
    }

    private fun showAddDialog() {
        if (isAdminMode) {
            // 管理员模式：原来的简单弹窗，不变
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(50, 40, 50, 10)
            }
            val etKeyword = EditText(this).apply { hint = "触发关键词（可留空=常驻）" }
            val etContent = EditText(this).apply { hint = "设定内容..." }
            layout.addView(etKeyword)
            layout.addView(etContent)
            AlertDialog.Builder(this).setTitle("新增【管理员】绝密设定").setView(layout)
                .setPositiveButton("写入底层") { _, _ ->
                    val kw = etKeyword.text.toString()
                    val ct = etContent.text.toString()
                    if (ct.isNotEmpty()) {
                        val values = ContentValues().apply { put("keyword", kw); put("content", ct) }
                        dbHelper.writableDatabase.insert("AdminWorldBook", null, values)
                        Toast.makeText(this, "写入成功！", Toast.LENGTH_SHORT).show()
                        loadData()
                    }
                }.setNegativeButton("取消", null).show()
            return
        }

        // ===== 新增：用户模式的新添加界面 =====
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        // 关键词输入
        val etKeyword = EditText(this).apply { hint = "触发关键词（关键词触发模式必填，其他可留空）" }
        layout.addView(etKeyword)

        // 内容输入
        val etContent = EditText(this).apply {
            hint = "世界书设定内容..."
            minLines = 3
        }
        layout.addView(etContent)

        // 权重选择
        val tvPriorityLabel = TextView(this).apply {
            text = "权重："
            setPadding(0, 20, 0, 8)
        }
        layout.addView(tvPriorityLabel)
        val priorityOptions = arrayOf("最高（每次必读，放最前）", "中等（每次附带，放后面）", "关键词触发（出现关键词才注入）")
        val priorityValues = arrayOf("high", "medium", "keyword")
        var selectedPriority = "keyword"
        val rgPriority = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        priorityOptions.forEachIndexed { i, label ->
            val rb = RadioButton(this).apply {
                text = label
                id = View.generateViewId()
                isChecked = i == 2
            }
            rgPriority.addView(rb)
            if (i == 2) rgPriority.check(rb.id)
        }
        rgPriority.setOnCheckedChangeListener { group, checkedId ->
            val idx = (0 until group.childCount).firstOrNull { (group.getChildAt(it) as RadioButton).id == checkedId } ?: 2
            selectedPriority = priorityValues[idx]
        }
        layout.addView(rgPriority)

        // 目标角色选择
        val tvTargetLabel = TextView(this).apply {
            text = "适用范围："
            setPadding(0, 20, 0, 8)
        }
        layout.addView(tvTargetLabel)

        // 读取联系人列表
        val contactIds = mutableListOf<String>()
        val contactNames = mutableListOf<String>()
        contactIds.add("global")
        contactNames.add("全局（所有角色）")
        try {
            val cur = dbHelper.readableDatabase.rawQuery("SELECT userId, realName\n" +
                    "FROM Contacts\n" +
                    "ORDER BY realName ASC\n" +
                    "LIMIT 200", null)
            while (cur.moveToNext()) {
                contactIds.add(cur.getString(0))
                contactNames.add(cur.getString(1))
            }
            cur.close()
        } catch (e: Exception) {}

        var selectedTargetId = "global"
        val spinnerTarget = Spinner(this)
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, contactNames)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTarget.adapter = spinnerAdapter
        spinnerTarget.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedTargetId = contactIds[position]
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        layout.addView(spinnerTarget)

        AlertDialog.Builder(this).setTitle("新增世界书设定").setView(layout)
            .setPositiveButton("写入") { _, _ ->
                val kw = etKeyword.text.toString().trim()
                val ct = etContent.text.toString()
                    .trim()
                    .take(2000)
                if (ct.isEmpty()) { Toast.makeText(this, "内容不能为空", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                if (selectedPriority == "keyword" && kw.isEmpty()) { Toast.makeText(this, "关键词触发模式需要填写关键词", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val values = ContentValues().apply {
                    put("keyword", kw)
                    put("content", ct)
                    put("priority", selectedPriority)
                    put("targetAiId", selectedTargetId)
                    put("aiId", selectedTargetId) // 兼容旧字段
                }
                dbHelper.writableDatabase.insert("UserWorldBook", null, values)
                Toast.makeText(this, "写入成功！", Toast.LENGTH_SHORT).show()
                loadData()
            }.setNegativeButton("取消", null).show()
        // ===== 新增结束 =====
    }

    private fun loadData() {
        itemList.clear()
        val db = dbHelper.readableDatabase
        val table = if (isAdminMode) "AdminWorldBook" else "UserWorldBook"
        try {
            // ===== 修改：用户模式显示 priority 和 targetAiId =====
            val cursor = if (isAdminMode) {
                db.rawQuery("SELECT id, keyword, content FROM $table ORDER BY id DESC", null)
            } else {
                db.rawQuery("SELECT id, keyword, content, priority, targetAiId FROM $table ORDER BY id DESC", null)
            }
            while (cursor.moveToNext()) {
                val id = cursor.getInt(0)
                val kw = cursor.getString(1) ?: ""
                val ct = cursor.getString(2) ?: ""
                if (isAdminMode) {
                    itemList.add(WorldBookItem(id, kw, ct))
                } else {
                    val priority = try { cursor.getString(3) ?: "keyword" } catch (e: Exception) { "keyword" }
                    val targetAiId = try { cursor.getString(4) ?: "global" } catch (e: Exception) { "global" }
                    val priorityLabel = when (priority) { "high" -> "【最高】"; "medium" -> "【中等】"; else -> "【关键词】" }
                    val targetLabel = if (targetAiId == "global") "全局" else {
                        var name = targetAiId
                        try {
                            val cur = db.rawQuery("SELECT realName FROM Contacts WHERE userId=?", arrayOf(targetAiId))
                            if (cur.moveToFirst()) name = cur.getString(0)
                            cur.close()
                        } catch (e: Exception) {}
                        name
                    }
                    // 把权重和适用范围拼进关键词字段用于显示
                    val displayKw = "$priorityLabel $targetLabel｜关键词:${kw.ifEmpty { "无" }}"
                    itemList.add(WorldBookItem(id, displayKw, ct))
                }
            }
            cursor.close()
            // ===== 修改结束 =====
        } catch (e: Exception) { e.printStackTrace() }
        adapter.notifyDataSetChanged()
    }
}
