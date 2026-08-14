package com.moon.aiphone

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MemoryVaultActivity : AppCompatActivity() {

    data class ContactItem(val aiId: String, val aiName: String, val memoryCount: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory_vault)
        supportActionBar?.hide()

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        val rv = findViewById<RecyclerView>(R.id.rvMemoryContacts)
        rv.layoutManager = LinearLayoutManager(this)

        val contacts = loadContacts()
        rv.adapter = ContactAdapter(contacts) { aiId, aiName ->
            val intent = Intent(this, MemoryDetailActivity::class.java)
            intent.putExtra("aiId", aiId)
            intent.putExtra("aiName", aiName)
            startActivity(intent)
        }
    }

    private fun loadContacts(): List<ContactItem> {
        val list = mutableListOf<ContactItem>()
        try {
            val db = DatabaseHelper(this).readableDatabase
            val cur = db.rawQuery(
                """
    SELECT c.userId,
           c.realName,
           COUNT(m.id) as cnt
    FROM Contacts c
    LEFT JOIN MemoryBank m
        ON c.userId = m.aiId
    WHERE c.userId IS NOT NULL
      AND TRIM(c.userId) <> ''
    GROUP BY c.userId, c.realName
    ORDER BY cnt DESC, c.realName ASC
    """.trimIndent(),
                null
            )
            while (cur.moveToNext()) {
                val aiId = cur.getString(0)
                val aiName = cur.getString(1)
                val count = cur.getInt(2)
                list.add(ContactItem(aiId, aiName, count))
            }
            cur.close()
        } catch (e: Exception) {}
        return list
    }

    class ContactAdapter(private val items: List<ContactItem>, private val onClick: (String, String) -> Unit) : RecyclerView.Adapter<ContactAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvContactName)
            val tvCount: TextView = v.findViewById(R.id.tvMemoryCount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_memory_contact, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvName.text = item.aiName
            holder.tvCount.text = "共 ${item.memoryCount} 条（分6类存储）"
            holder.itemView.setOnClickListener { onClick(item.aiId, item.aiName) }
        }

        override fun getItemCount() = items.size
    }
}