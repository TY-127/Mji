package com.moon.aiphone

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

data class DoorContact(val id: String, val name: String)

class OfflineContactActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#000000"))
        }

        val title = TextView(this).apply {
            text = "选择要推开的门"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, 60, 0, 60)
            gravity = Gravity.CENTER
        }
        root.addView(title)

        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@OfflineContactActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        root.addView(rv)
        setContentView(root)

        val list = mutableListOf<DoorContact>()
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
                    list.add(
                        DoorContact(
                            it.getString(0) ?: "",
                            it.getString(1) ?: "未知"
                        )
                    )
                }
            }
        } catch (e: Exception) {}

        rv.adapter = DoorAdapter(list)
    }

    inner class DoorAdapter(val data: List<DoorContact>) : RecyclerView.Adapter<DoorAdapter.VH>() {
        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): VH {
            val tv = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(50, 50, 50, 50)
                textSize = 18f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#1A1A1A"))
            }
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, pos: Int) {
            val item = data[pos]
            holder.tv.text = "🚪 推开 ${item.name} 的门"
            holder.itemView.setOnClickListener {
                // 改为先进入“见面设置页”，设置完成后再进入聊天页
                val intent = android.content.Intent(this@OfflineContactActivity, OfflineSetupActivity::class.java)
                intent.putExtra("AI_ID", item.id)
                intent.putExtra("AI_NAME", item.name)
                startActivity(intent)
            }
        }
        override fun getItemCount() = data.size
    }
}