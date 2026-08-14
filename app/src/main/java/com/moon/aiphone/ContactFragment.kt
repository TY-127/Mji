package com.moon.aiphone

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ContactFragment : Fragment() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: MixedContactAdapter
    private val itemList = mutableListOf<Any>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_contact, container, false)
        view.findViewById<ImageView>(R.id.btnAddContact)?.setOnClickListener {
            startActivity(Intent(requireContext(), AddContactActivity::class.java))
        }
        view.findViewById<ImageView>(R.id.btnCreateGroup)?.setOnClickListener {
            startActivity(Intent(requireContext(), CreateGroupActivity::class.java))
        }
        recyclerView = view.findViewById(R.id.contactRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = MixedContactAdapter(itemList)
        recyclerView.adapter = adapter
        return view
    }

    override fun onResume() {
        super.onResume()
        loadAllData()
    }

    private fun loadAllData() {
        itemList.clear()

        val helper = DatabaseHelper(requireContext())
        val db = helper.readableDatabase

        try {
            db.rawQuery(
                """
            SELECT groupId, 
                   CASE 
                       WHEN groupName IS NULL OR TRIM(groupName) = '' THEN '未知群聊'
                       ELSE groupName
                   END AS showName,
                   IFNULL(avatarUri, '')
            FROM GroupChats
            WHERE IFNULL(isDisbanded, 0) = 0
              AND groupId IS NOT NULL
              AND TRIM(groupId) <> ''
            ORDER BY id DESC
            """.trimIndent(),
                null
            ).use { groupCursor ->
                while (groupCursor.moveToNext()) {
                    val gId = groupCursor.getString(0) ?: ""
                    val gName = groupCursor.getString(1) ?: "未知群聊"
                    val gAvatar = groupCursor.getString(2) ?: ""

                    if (gId.isNotBlank()) {
                        itemList.add(GroupData(gId, "[群] $gName", gAvatar))
                    }
                }
            }

            db.rawQuery(
                """
            SELECT id, userId, realName, IFNULL(avatarUri, '')
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
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getInt(0)
                    val userId = cursor.getString(1) ?: ""
                    val realName = cursor.getString(2) ?: "未知"
                    val avatarUri = cursor.getString(3) ?: ""

                    if (userId.isNotBlank()) {
                        itemList.add(Contact(id, userId, realName, avatarUri))
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ContactFragment", e.stackTraceToString())
        } finally {
            helper.close()
        }

        adapter.notifyDataSetChanged()
        recyclerView.visibility = if (itemList.isEmpty()) View.GONE else View.VISIBLE
    }

    data class GroupData(val groupId: String, val name: String, val avatar: String)

    private fun loadBitmap(context: android.content.Context, uriStr: String): android.graphics.Bitmap? {
        if (uriStr.isEmpty()) return null
        return try {
            if (uriStr.startsWith("/")) {
                android.graphics.BitmapFactory.decodeFile(uriStr)
            } else {
                context.contentResolver
                    .openInputStream(android.net.Uri.parse(uriStr))
                    ?.use { android.graphics.BitmapFactory.decodeStream(it) }
            }
        } catch (_: Exception) { null }
    }

    inner class MixedContactAdapter(private val list: List<Any>) : RecyclerView.Adapter<MixedContactAdapter.VH>() {

        inner class VH(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
            val avatar = ImageView(root.context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(50), dp(50)).also { it.marginEnd = dp(16) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    clipToOutline = true
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: android.graphics.Outline) {
                            outline.setOval(0, 0, view.width, view.height)
                        }
                    }
                }
            }
            val tvName = TextView(root.context).apply {
                textSize = 18f
                setTextColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            init {
                root.apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    addView(avatar)
                    addView(tvName)
                }
            }
            private fun dp(n: Int): Int = (n * root.context.resources.displayMetrics.density).toInt()
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

        override fun getItemCount() = list.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = list[position]
            if (item is GroupData) {
                holder.tvName.text = item.name
                val bitmap = loadBitmap(holder.itemView.context, item.avatar)
                holder.avatar.setImageDrawable(null)

                if (bitmap != null) {
                    holder.avatar.setImageBitmap(bitmap)
                } else {
                    holder.avatar.setImageResource(android.R.drawable.ic_menu_gallery)
                }
                holder.itemView.setOnClickListener {
                    val intent = Intent(holder.itemView.context, GroupChatActivity::class.java)
                    intent.putExtra("GROUP_ID", item.groupId)
                    intent.putExtra("GROUP_NAME", item.name.replace("[群] ", ""))
                    holder.itemView.context.startActivity(intent)
                }
            } else if (item is Contact) {
                holder.tvName.text = item.realName
                val bitmap = loadBitmap(holder.itemView.context, item.avatarUri ?: "")
                holder.avatar.setImageDrawable(null)


                if (bitmap != null) {
                    holder.avatar.setImageBitmap(bitmap)
                } else {
                    holder.avatar.setImageResource(android.R.drawable.sym_def_app_icon)
                }
                holder.itemView.setOnClickListener {
                    val intent = Intent(holder.itemView.context, ChatActivity::class.java)
                    intent.putExtra("AI_ID", item.userId)
                    intent.putExtra("AI_NAME", item.realName)
                    intent.putExtra("USER_ID", item.userId)
                    intent.putExtra("USER_NAME", item.realName)
                    intent.putExtra("USER_AVATAR", item.avatarUri ?: "")
                    holder.itemView.context.startActivity(intent)
                }
            }
        }
    }
}