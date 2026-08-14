package com.moon.aiphone

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

// 数据模型
data class ChatMessage(val text: String, val isUser: Boolean)

class MessageDetailActivity : AppCompatActivity() {

    private val chatList = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: MessageChatAdapter
    private lateinit var recyclerViewChat: RecyclerView
    private lateinit var editMessage: EditText
    private lateinit var btnSend: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 这里的布局就是包含 RecyclerView 和发送按钮的那个界面
        setContentView(R.layout.activity_message_detail)

        // 初始化组件
        recyclerViewChat = findViewById(R.id.recyclerViewChat)
        editMessage = findViewById(R.id.editMessage)
        btnSend = findViewById(R.id.btnSend)

        // 绑定列表
        chatAdapter = MessageChatAdapter(chatList)
        recyclerViewChat.layoutManager = LinearLayoutManager(this)
        recyclerViewChat.adapter = chatAdapter

        // 发送逻辑
        btnSend.setOnClickListener {
            val content = editMessage.text.toString().trim()
            if (content.isNotEmpty()) {
                addMessage(content, true)
                editMessage.setText("")

                // 模拟 AI 回复
                addMessage("这是测试消息页，不是正式聊天入口。", false)
            }
        }
    }

    private fun addMessage(text: String, isUser: Boolean) {
        chatList.add(ChatMessage(text, isUser))
        chatAdapter.notifyItemInserted(chatList.size - 1)
        recyclerViewChat.scrollToPosition(chatList.size - 1)
    }

    // 适配器代码（直接从之前 ChatActivity 搬过来即可）
    inner class MessageChatAdapter(private val messages: List<ChatMessage>) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int) = if (messages[position].isUser) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                UserViewHolder(inflater.inflate(R.layout.item_chat_user, parent, false))
            } else {
                AiViewHolder(inflater.inflate(R.layout.item_chat_ai, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val message = messages[position]
            if (holder is UserViewHolder) holder.tvUser.text = message.text
            else if (holder is AiViewHolder) holder.tvAi.text = message.text
        }

        override fun getItemCount() = messages.size

        inner class UserViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val tvUser: TextView = v.findViewById(R.id.tvUserMessage)
        }
        inner class AiViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val tvAi: TextView = v.findViewById(R.id.tvAiMessage)
        }
    }
}