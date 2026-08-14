package com.moon.aiphone

import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class CharacterRelationshipActivity : AppCompatActivity() {

    private lateinit var dbHelper: DatabaseHelper
    private val items = mutableListOf<RelItem>()
    private lateinit var rvAdapter: RelAdapter

    data class RelItem(
        val id: Int,
        val ai1Id: String, val ai1Name: String,
        val ai2Id: String, val ai2Name: String,
        val relationship: String, val note: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        dbHelper = DatabaseHelper(this)
        try {
            dbHelper.writableDatabase.execSQL(
                "CREATE TABLE IF NOT EXISTS CharacterRelationships (id INTEGER PRIMARY KEY AUTOINCREMENT, aiId1 TEXT, aiId2 TEXT, relationship TEXT, note TEXT DEFAULT '')"
            )
        } catch (_: Exception) {}

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF2F2F6.toInt())
        }

        val header = RelativeLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(50))
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(16), 0, dp(16), 0)
        }
        val btnBack = TextView(this).apply {
            text = "< 返回"; textSize = 16f
            setTextColor(0xFF000000.toInt())
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RelativeLayout.LayoutParams(-2, -1).also {
                it.addRule(RelativeLayout.ALIGN_PARENT_START)
            }
        }
        val tvTitle = TextView(this).apply {
            text = "角色关系图谱"; textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = RelativeLayout.LayoutParams(-2, -1).also {
                it.addRule(RelativeLayout.CENTER_IN_PARENT)
            }
        }
        val btnAdd = TextView(this).apply {
            text = "新增 +"; textSize = 15f
            setTextColor(0xFF007AFF.toInt())
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RelativeLayout.LayoutParams(-2, -1).also {
                it.addRule(RelativeLayout.ALIGN_PARENT_END)
            }
        }
        header.addView(btnBack); header.addView(tvTitle); header.addView(btnAdd)
        root.addView(header)

        val tvTip = TextView(this).apply {
            text = "在此设定角色之间的关系，AI在朋友圈互动时将严格遵守，防止出现不符合人设的暧昧行为"
            textSize = 12f; setTextColor(0xFF888888.toInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        root.addView(tvTip)

        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@CharacterRelationshipActivity)
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        rvAdapter = RelAdapter(items) { item -> confirmDelete(item) }
        rv.adapter = rvAdapter
        root.addView(rv)

        setContentView(root)
        btnBack.setOnClickListener { finish() }
        btnAdd.setOnClickListener { showAddDialog() }
        loadData()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun confirmDelete(item: RelItem) {
        AlertDialog.Builder(this)
            .setTitle("删除关系")
            .setMessage("确定删除「${item.ai1Name}」和「${item.ai2Name}」的关系设定吗？")
            .setPositiveButton("删除") { _, _ ->
                dbHelper.writableDatabase.delete("CharacterRelationships", "id=?", arrayOf(item.id.toString()))
                loadData()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun showAddDialog() {
        val contacts = mutableListOf<Pair<String, String>>()
        try {
            dbHelper.readableDatabase.rawQuery(
                "SELECT userId, realName FROM Contacts WHERE userId IS NOT NULL AND TRIM(userId)<>'' ORDER BY realName ASC", null
            ).use { c ->
                while (c.moveToNext()) contacts.add(c.getString(0) to c.getString(1))
            }
        } catch (_: Exception) {}

        if (contacts.size < 2) {
            Toast.makeText(this, "需要至少两个角色才能设定关系", Toast.LENGTH_SHORT).show()
            return
        }

        val names = contacts.map { it.second }.toTypedArray()
        var idx1 = 0; var idx2 = if (contacts.size > 1) 1 else 0

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        fun addLabel(text: String, topPad: Int = 0) = layout.addView(TextView(this).apply {
            this.text = text; textSize = 13f; setTextColor(0xFF555555.toInt())
            if (topPad > 0) setPadding(0, dp(topPad), 0, dp(4))
        })

        addLabel("角色一：")
        val sp1 = Spinner(this).also {
            it.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            it.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { idx1 = pos }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            layout.addView(it)
        }

        addLabel("角色二：", topPad = 10)
        val sp2 = Spinner(this).also {
            it.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            it.setSelection(if (contacts.size > 1) 1 else 0)
            it.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { idx2 = pos }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            layout.addView(it)
        }

        addLabel("关系类型：", topPad = 10)
        val relOptions = arrayOf("好友", "死党", "情敌", "死敌", "竞争对手", "同事", "兄弟姐妹", "师生", "前任", "暗恋", "恋人", "陌生人")
        val spRel = Spinner(this).also {
            it.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, relOptions)
            layout.addView(it)
        }

        addLabel("补充说明（可选）：", topPad = 10)
        val etNote = EditText(this).apply { hint = "如：表面是朋友但暗中较劲"; layout.addView(this) }

        AlertDialog.Builder(this).setTitle("新增角色关系").setView(layout)
            .setPositiveButton("确定") { _, _ ->
                if (idx1 == idx2) {
                    Toast.makeText(this, "请选择两个不同的角色", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val ai1 = contacts[idx1]; val ai2 = contacts[idx2]
                val rel = relOptions[spRel.selectedItemPosition]
                dbHelper.writableDatabase.insert("CharacterRelationships", null, ContentValues().apply {
                    put("aiId1", ai1.first); put("aiId2", ai2.first)
                    put("relationship", rel)
                    put("note", etNote.text.toString().trim())
                })
                Toast.makeText(this, "已设定「${ai1.second}」和「${ai2.second}」：$rel", Toast.LENGTH_SHORT).show()
                loadData()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun loadData() {
        items.clear()
        try {
            dbHelper.readableDatabase.rawQuery(
                "SELECT cr.id, cr.aiId1, IFNULL(c1.realName,cr.aiId1), cr.aiId2, IFNULL(c2.realName,cr.aiId2), cr.relationship, IFNULL(cr.note,'') " +
                "FROM CharacterRelationships cr " +
                "LEFT JOIN Contacts c1 ON c1.userId=cr.aiId1 " +
                "LEFT JOIN Contacts c2 ON c2.userId=cr.aiId2 " +
                "ORDER BY cr.id DESC", null
            ).use { c ->
                while (c.moveToNext())
                    items.add(RelItem(c.getInt(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6)))
            }
        } catch (_: Exception) {}
        rvAdapter.notifyDataSetChanged()
    }
}

class RelAdapter(
    private val items: List<CharacterRelationshipActivity.RelItem>,
    private val onDelete: (CharacterRelationshipActivity.RelItem) -> Unit
) : RecyclerView.Adapter<RelAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvMain: TextView = v.findViewWithTag("main")
        val tvSub: TextView = v.findViewWithTag("sub")
        val btnDel: TextView = v.findViewWithTag("del")
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                setMargins(0, dp(ctx, 4), 0, 0)
            }
        }
        val textArea = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val tvMain = TextView(ctx).apply {
            tag = "main"; textSize = 15f; setTextColor(0xFF222222.toInt())
        }
        val tvSub = TextView(ctx).apply {
            tag = "sub"; textSize = 12f; setTextColor(0xFF999999.toInt())
            setPadding(0, dp(ctx, 2), 0, 0)
        }
        textArea.addView(tvMain); textArea.addView(tvSub)
        val btnDel = TextView(ctx).apply {
            tag = "del"; text = "删除"; textSize = 14f
            setTextColor(0xFFFF3B30.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 12), 0, 0, 0)
        }
        card.addView(textArea); card.addView(btnDel)
        return VH(card)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tvMain.text = "「${item.ai1Name}」${item.relationship}「${item.ai2Name}」"
        holder.tvSub.text = item.note.ifBlank { "互为${item.relationship}" }
        holder.btnDel.setOnClickListener { onDelete(item) }
    }

    override fun getItemCount() = items.size
}

fun getCharacterRelationshipText(db: android.database.sqlite.SQLiteDatabase, aiId1: String, aiId2: String): String? {
    return try {
        db.rawQuery(
            "SELECT relationship, note FROM CharacterRelationships WHERE (aiId1=? AND aiId2=?) OR (aiId1=? AND aiId2=?) LIMIT 1",
            arrayOf(aiId1, aiId2, aiId2, aiId1)
        ).use { c ->
            if (c.moveToFirst()) {
                val rel = c.getString(0) ?: return@use null
                val note = c.getString(1) ?: ""
                if (note.isNotBlank()) "$rel（$note）" else rel
            } else null
        }
    } catch (_: Exception) { null }
}
