package com.moon.aiphone

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class RecentChatAdapter(private val chatList: List<RecentChat>) :
    RecyclerView.Adapter<RecentChatAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivAvatar: ImageView = view.findViewById(R.id.ivRecentAvatar)
        val tvName: TextView = view.findViewById(R.id.tvRecentName)
        val tvMsg: TextView = view.findViewById(R.id.tvRecentMsg)
        val tvTime: TextView? = view.findViewById(R.id.tvRecentTime)
        val tvUnread: TextView? = view.findViewById(R.id.tvUnreadCount)
        val itemRoot: View = view.findViewById(R.id.itemRoot)
        val divider: View? = view.findViewById(R.id.divider)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent_chat, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val chat = chatList[position]

        // ── 主题应用 ────────────────────────────────────────
        ThemeManager.applyMsgListItem(holder.itemRoot)
        ThemeManager.applyMsgListText(
            nameView = holder.tvName,
            previewView = holder.tvMsg,
            timeView = holder.tvTime
        )
        // 分割线颜色
        holder.divider?.setBackgroundColor(
            ThemeManager.getColor("--msg-list-divider-color",
                android.graphics.Color.parseColor("#F0F0F0"))
        )

        // ── 数据绑定 ────────────────────────────────────────
        holder.tvName.text = if (chat.isPinned) "📌 ${chat.aiName}" else chat.aiName
        holder.tvMsg.text = chat.lastMsg
        holder.tvTime?.text = chat.msgTime

        // 头像
        holder.ivAvatar.setImageDrawable(null)
        holder.ivAvatar.setBackgroundColor(android.graphics.Color.LTGRAY)
        if (chat.avatarUri.isNotEmpty()) {
            try {
                val bitmap = if (chat.avatarUri.startsWith("/")) {
                    android.graphics.BitmapFactory.decodeFile(chat.avatarUri)
                } else {
                    holder.itemView.context.contentResolver
                        .openInputStream(Uri.parse(chat.avatarUri))
                        ?.use { android.graphics.BitmapFactory.decodeStream(it) }
                }
                if (bitmap != null) {
                    holder.ivAvatar.setImageBitmap(bitmap)
                    holder.ivAvatar.background = null
                }
            } catch (_: Exception) {}
        }

        // 未读红点
        holder.tvUnread?.let { tv ->
            if (chat.unreadCount > 0) {
                tv.visibility = View.VISIBLE
                tv.text = if (chat.unreadCount > 99) "99+" else chat.unreadCount.toString()
            } else {
                tv.visibility = View.GONE
            }
        }

        // 点击进入聊天
        holder.itemView.setOnClickListener {
            if (chat.isGroup) {
                try {
                    val db = DatabaseHelper(holder.itemView.context).readableDatabase
                    val c = db.rawQuery(
                        "SELECT groupId FROM GroupChats WHERE TRIM(IFNULL(groupId,''))=? AND IFNULL(isDisbanded,0)=0 LIMIT 1",
                        arrayOf(chat.aiId.trim())
                    )
                    val exists = c.moveToFirst(); c.close()
                    if (!exists) {
                        android.widget.Toast.makeText(holder.itemView.context, "群聊已解散", android.widget.Toast.LENGTH_SHORT).show()
                        holder.itemView.context.sendBroadcast(Intent("CYBER_NEW_MSG"))
                        return@setOnClickListener
                    }
                } catch (_: Exception) {}
                holder.itemView.context.startActivity(
                    Intent(holder.itemView.context, GroupChatActivity::class.java).apply {
                        putExtra("GROUP_ID", chat.aiId); putExtra("GROUP_NAME", chat.aiName)
                    })
            } else {
                holder.itemView.context.startActivity(
                    Intent(holder.itemView.context, ChatActivity::class.java).apply {
                        putExtra("AI_NAME", chat.aiName); putExtra("AI_ID", chat.aiId)
                    })
            }
        }

        // 长按置顶
        holder.itemView.setOnLongClickListener {
            val ctx = holder.itemView.context
            val action = if (chat.isPinned) "取消置顶" else "置顶"
            android.app.AlertDialog.Builder(ctx)
                .setItems(arrayOf(action)) { _, _ ->
                    try {
                        val db = DatabaseHelper(ctx).writableDatabase
                        val newVal = if (chat.isPinned) 0 else 1
                        if (chat.isGroup) {
                            val c = db.rawQuery(
                                "SELECT groupId FROM GroupChats WHERE TRIM(IFNULL(groupId,''))=? AND IFNULL(isDisbanded,0)=0 LIMIT 1",
                                arrayOf(chat.aiId.trim())
                            )
                            val exists = c.moveToFirst(); c.close()
                            if (!exists) { ctx.sendBroadcast(Intent("CYBER_NEW_MSG")); return@setItems }
                            db.execSQL("UPDATE GroupChats SET isPinned=? WHERE groupId=?", arrayOf(newVal, chat.aiId))
                        } else {
                            db.execSQL("UPDATE Contacts SET isPinned=? WHERE userId=?", arrayOf(newVal, chat.aiId))
                        }
                        ctx.sendBroadcast(Intent("CYBER_NEW_MSG"))
                    } catch (_: Exception) {}
                }.show()
            true
        }
    }

    override fun getItemCount() = chatList.size
}