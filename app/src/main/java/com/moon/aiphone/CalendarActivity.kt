package com.moon.aiphone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

data class CharItem(val id: String, val name: String, val avatar: String, val persona: String)

class CalendarActivity : AppCompatActivity() {
    private val charList = mutableListOf<CharItem>()
    private lateinit var adapter: CharAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calendar)
        supportActionBar?.hide()

        val rv = findViewById<RecyclerView>(R.id.rvCharacterSchedules)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = CharAdapter(charList) { clickedChar ->
            // ⚡ 极其智能的分流雷达！！
            if (clickedChar.persona == "BOSS_SUPREME") {
                startActivity(Intent(this, MyCalendarActivity::class.java))
            } else {
                val intent = Intent(this, ScheduleDetailActivity::class.java)
                intent.putExtra("aiId", clickedChar.id)
                intent.putExtra("aiName", clickedChar.name)
                intent.putExtra("aiPersona", clickedChar.persona)
                intent.putExtra("aiAvatar", clickedChar.avatar)
                startActivity(intent)
            }
        }
        rv.adapter = adapter
        loadCharacters()
    }

    private fun loadCharacters() {
        charList.clear()
        try {
            val db = DatabaseHelper(this).readableDatabase
            var myId = "my_id"
            var myName = "我"
            var myAvatar = ""

            val pCur = db.query("MyProfile", null, null, null, null, null, null)
            if (pCur.moveToFirst()) {
                // ✅ 1. 安全读取我的 ID
                myId = pCur.getSafeString("myId").ifEmpty { "me" }

                // ✅ 2. 安全读取 myName，如果为空就用 "我" 兜底
                myName = pCur.getSafeString("myName").ifEmpty { "我" }

                // ✅ 3. 安全读取我的头像路径
                myAvatar = pCur.getSafeString("myAvatarUri")
            }
            pCur.close()

            // ⚡ 极其霸道地把老板死死钉在绝对的C位（第一行）！！
            charList.add(CharItem(myId, myName, myAvatar, "BOSS_SUPREME"))

            val cur = db.rawQuery(
                """
    SELECT userId, realName, avatarUri, identityInfo
    FROM Contacts
    WHERE userId IS NOT NULL
      AND TRIM(userId) <> ''
      AND userId != ?
      AND id IN (
          SELECT MAX(id)
          FROM Contacts
          WHERE userId IS NOT NULL
            AND TRIM(userId) <> ''
          GROUP BY userId
      )
    ORDER BY id DESC
    """.trimIndent(),
                arrayOf(myId)
            )
            while (cur.moveToNext()) {
                charList.add(CharItem(
                    cur.getString(0),
                    cur.getString(1) ?: "未知",
                    cur.getString(2) ?: "",
                    cur.getString(3) ?: ""
                ))
            }
            cur.close()
            adapter.notifyDataSetChanged()
        } catch (e: Exception) {}
    }

    inner class CharAdapter(private val list: List<CharItem>, private val onClick: (CharItem) -> Unit) : RecyclerView.Adapter<CharAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val ivAvatar: ImageView = v.findViewById(R.id.ivCharAvatar)
            val tvName: TextView = v.findViewById(R.id.tvCharName)
            val tvSub: TextView = v.findViewById(R.id.tvCharSubtitle)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_schedule_char, parent, false))
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.tvName.text = item.name

            if (item.persona == "BOSS_SUPREME") {
                holder.tvSub.text = "📍 点击设定现实行程"
                holder.tvSub.setTextColor(android.graphics.Color.parseColor("#4FC3F7"))
            } else {
                holder.tvSub.text = "点击查看今日独立行程"
                holder.tvSub.setTextColor(android.graphics.Color.parseColor("#999999"))
            }

            if (item.avatar.isNotEmpty()) {
                try {
                    val bitmap = if (item.avatar.startsWith("/")) {
                        android.graphics.BitmapFactory.decodeFile(item.avatar)
                    } else {
                        holder.itemView.context.contentResolver
                            .openInputStream(Uri.parse(item.avatar))
                            ?.use { android.graphics.BitmapFactory.decodeStream(it) }
                    }

                    if (bitmap != null) {
                        holder.ivAvatar.setImageBitmap(bitmap)
                    } else {
                        holder.ivAvatar.setBackgroundColor(android.graphics.Color.LTGRAY)
                    }
                } catch (e: Exception) {
                    holder.ivAvatar.setBackgroundColor(android.graphics.Color.LTGRAY)
                }
            } else holder.ivAvatar.setBackgroundColor(android.graphics.Color.LTGRAY)

            holder.itemView.setOnClickListener { onClick(item) }
        }
        override fun getItemCount() = list.size
    }
}