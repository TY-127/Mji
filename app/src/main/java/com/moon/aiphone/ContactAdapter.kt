package com.moon.aiphone

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ContactAdapter(private val contactList: List<Contact>) : RecyclerView.Adapter<ContactAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivAvatar: ImageView = view.findViewById(R.id.ivAvatar)
        val tvName: TextView = view.findViewById(R.id.tvName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contact = contactList[position]
        holder.tvName.text = contact.realName

        // ⚡ 强行过安检防爆版！
        holder.ivAvatar.setImageDrawable(null)
        holder.ivAvatar.setBackgroundColor(android.graphics.Color.LTGRAY)

        if (contact.avatarUri.isNotEmpty()) {
            try {
                val uri = when {
                    contact.avatarUri.startsWith("/") ->
                        Uri.fromFile(java.io.File(contact.avatarUri))
                    else ->
                        Uri.parse(contact.avatarUri)
                }

                holder.itemView.context.contentResolver
                    .openInputStream(uri)
                    ?.use { input ->
                        val bitmap = android.graphics.BitmapFactory.decodeStream(input)
                        if (bitmap != null) {
                            holder.ivAvatar.setImageBitmap(bitmap)
                            holder.ivAvatar.background = null
                        }
                    }

            } catch (_: Exception) {
                // 保持默认头像
            }
        }

        // 核心魔法：点这个联系人，就跳去聊天框！
        holder.itemView.setOnClickListener {
            val intent = Intent(holder.itemView.context, ChatActivity::class.java)
            // 把AI的名字和ID塞进行李箱带过去
            intent.putExtra("AI_NAME", contact.realName)
            intent.putExtra("AI_ID", contact.userId)
            holder.itemView.context.startActivity(intent)
        }
    }

    override fun getItemCount() = contactList.size
}