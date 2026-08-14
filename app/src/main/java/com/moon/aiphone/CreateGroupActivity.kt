package com.moon.aiphone

import android.content.ContentValues
import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.UUID
import android.widget.Toast
import android.net.Uri
import android.content.Intent
class CreateGroupActivity : AppCompatActivity() {

    private lateinit var etGroupName: EditText
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ContactSelectAdapter
    private val contactList = mutableListOf<ContactItem>()
    private val selectedContacts = mutableSetOf<ContactItem>()
    private lateinit var switchObserve: Switch
    private var selectedOwnerId = ""
    private var selectedOwnerName = ""
    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2F2F6"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val topBar = RelativeLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50))
            setBackgroundColor(Color.WHITE)
        }

        val btnCancel = TextView(this).apply {
            text = "取消"
            textSize = 16f
            setTextColor(Color.parseColor("#111111"))
            setPadding(dp(16), 0, dp(16), 0)
            gravity = Gravity.CENTER_VERTICAL
            val p = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
            p.addRule(RelativeLayout.ALIGN_PARENT_START)
            layoutParams = p
            setOnClickListener { finish() }
        }

        val tvTitle = TextView(this).apply {
            text = "创建群聊"
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#111111"))
            val p = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            )
            p.addRule(RelativeLayout.CENTER_IN_PARENT)
            layoutParams = p
        }

        val btnDone = TextView(this).apply {
            text = "完成"
            textSize = 16f
            setTextColor(Color.parseColor("#007AFF"))
            setPadding(dp(16), 0, dp(16), 0)
            gravity = Gravity.CENTER_VERTICAL
            val p = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
            p.addRule(RelativeLayout.ALIGN_PARENT_END)
            layoutParams = p
            setOnClickListener { createGroup() }
        }

        topBar.addView(btnCancel)
        topBar.addView(tvTitle)
        topBar.addView(btnDone)
        root.addView(topBar)

        etGroupName = EditText(this).apply {
            hint = "群聊名称 (必填)"
            textSize = 16f
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.topMargin = dp(12)
                it.bottomMargin = dp(12)
            }
        }
        root.addView(etGroupName)
// 围观模式开关
        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(12) }
        }
        val tvObserve = TextView(this).apply {
            text = "仅围观模式（角色自聊，用户旁观）"
            textSize = 15f; setTextColor(Color.parseColor("#111111"))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        switchObserve = Switch(this)
        switchRow.addView(tvObserve)
        switchRow.addView(switchObserve)
        root.addView(switchRow)
        val tvLabel = TextView(this).apply {
            text = "选择成员"
            textSize = 14f
            setTextColor(Color.parseColor("#888888"))
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        root.addView(tvLabel)

        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@CreateGroupActivity)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(Color.WHITE)
        }
        root.addView(recyclerView)
        val ownerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) }
        }
        val tvOwnerLabel = TextView(this).apply {
            text = "群主角色：未选择"
            textSize = 15f; setTextColor(Color.parseColor("#111111"))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val btnPickOwner = TextView(this).apply {
            text = "选择 ›"
            textSize = 14f; setTextColor(Color.parseColor("#007AFF"))
            setOnClickListener {
                val members = selectedContacts.toList()
                if (members.isEmpty()) {
                    Toast.makeText(this@CreateGroupActivity, "请先选择群成员", Toast.LENGTH_SHORT)
                        .show()
                    return@setOnClickListener
                }
                val names = members.map { it.name }.toTypedArray()
                android.app.AlertDialog.Builder(this@CreateGroupActivity)
                    .setTitle("选择群主角色")
                    .setItems(names) { _, which ->
                        selectedOwnerId = members[which].userId
                        selectedOwnerName = members[which].name
                        tvOwnerLabel.text = "群主角色：$selectedOwnerName"
                    }.show()
            }
        }
        ownerRow.addView(tvOwnerLabel)
        ownerRow.addView(btnPickOwner)
        root.addView(ownerRow)
        setContentView(root)
        loadContacts()
    }

    private fun loadContacts() {
        contactList.clear()
        try {
            val db = DatabaseHelper(this).readableDatabase
            val cursor = db.rawQuery(
                """
    SELECT userId, realName, avatarUri
    FROM Contacts
    WHERE userId IS NOT NULL
      AND TRIM(userId) <> ''
      AND id IN (
          SELECT MAX(id)
          FROM Contacts
          GROUP BY userId
      )
    ORDER BY id DESC
    """.trimIndent(),
                null
            )
            while (cursor.moveToNext()) {
                contactList.add(
                    ContactItem(
                        userId = cursor.getString(0) ?: "",
                        name = cursor.getString(1) ?: "未知",
                        avatarPath = cursor.getString(2) ?: ""
                    )
                )
            }
            cursor.close()
        } catch (e: Exception) {
        }

        adapter = ContactSelectAdapter(contactList, selectedContacts)
        recyclerView.adapter = adapter
    }

    private fun createGroup() {
        val groupName = etGroupName.text.toString().trim()
        if (groupName.isEmpty()) {
            Toast.makeText(this, "给群起个名字吧！", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedOwnerId.isBlank()) {
            Toast.makeText(this, "请选择群主角色", Toast.LENGTH_SHORT).show()
            return
        }

        val newGroupId = "group_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
        try {
            val db = DatabaseHelper(this).writableDatabase

            // 写入 GroupChats 表
            db.insert("GroupChats", null, ContentValues().apply {
                put("groupId", newGroupId)
                put("groupName", groupName)
                put("createdAt", System.currentTimeMillis())
                put("isObserveOnly", if (switchObserve.isChecked) 1 else 0)
            })


            for (contact in selectedContacts) {

                db.delete(
                    "GroupMembers",
                    "groupId=? AND memberId=?",
                    arrayOf(newGroupId, contact.userId)
                )

                val isOwner = contact.userId == selectedOwnerId

                db.insert("GroupMembers", null, ContentValues().apply {
                    put("groupId", newGroupId)
                    put("memberId", contact.userId)
                    put("memberName", contact.name)
                    put("isAi", 1)
                    put("nickname", "")
                    put("title", if (isOwner) "群主" else "")
                    put("isOwner", if (isOwner) 1 else 0)  // 新字段
                })
            }
// 用户自己加入，普通成员
            val myPref = getSharedPreferences("AppConfig", MODE_PRIVATE)
            db.insert("GroupMembers", null, ContentValues().apply {
                put("groupId", newGroupId)
                put("memberId", myPref.getString("myId", "me"))
                put("memberName", myPref.getString("myName", "我"))
                put("isAi", 0)
                put("nickname", "")
                put("title", "")
                put("isOwner", 0)
            })

            sendBroadcast(Intent("CYBER_NEW_MSG"))
            Toast.makeText(this, "群聊 [$groupName] 创建成功！", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "创建失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    data class ContactItem(val userId: String, val name: String, val avatarPath: String)

    class ContactSelectAdapter(
        private val list: List<ContactItem>,
        private val selectedSet: MutableSet<ContactItem>
    ) : RecyclerView.Adapter<ContactSelectAdapter.VH>() {

        inner class VH(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
            val avatar = ImageView(root.context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(dp(40), dp(40)).also { it.marginEnd = dp(12) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
                setBackgroundColor(Color.parseColor("#DDDDDD"))
            }
            val tvName = TextView(root.context).apply {
                textSize = 16f
                setTextColor(Color.parseColor("#111111"))
                layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val checkBox = CheckBox(root.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                isClickable = false
            }

            init {
                root.apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    addView(avatar)
                    addView(tvName)
                    addView(checkBox)
                }
            }

            private fun dp(n: Int): Int =
                (n * root.context.resources.displayMetrics.density).toInt()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LinearLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            holder.avatar.setImageDrawable(null)
            holder.avatar.setImageResource(android.R.drawable.sym_def_app_icon)
            holder.tvName.text = item.name
            holder.checkBox.isChecked = selectedSet.contains(item)

            if (item.avatarPath.isNotEmpty()) {
                try {
                    val bitmap =
                        if (item.avatarPath.startsWith("/")) {
                            android.graphics.BitmapFactory.decodeFile(item.avatarPath)
                        } else {
                            holder.itemView.context.contentResolver
                                .openInputStream(android.net.Uri.parse(item.avatarPath))
                                ?.use {
                                    android.graphics.BitmapFactory.decodeStream(it)
                                }
                        }

                    if (bitmap != null) {
                        holder.avatar.setImageBitmap(bitmap)
                    } else {
                        holder.avatar.setImageResource(android.R.drawable.sym_def_app_icon)
                    }

                } catch (_: Exception) {
                    holder.avatar.setImageResource(android.R.drawable.sym_def_app_icon)
                }
            } else {
                holder.avatar.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            holder.itemView.setOnClickListener {
                if (selectedSet.contains(item)) {
                    selectedSet.remove(item)
                    holder.checkBox.isChecked = false
                } else {
                    selectedSet.add(item)
                    holder.checkBox.isChecked = true
                }
            }
        }

        override fun getItemCount() = list.size
    }
}