package com.moon.aiphone

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

fun cleanDialogContent(raw: String): String {
    return raw
        .replace(Regex("【内心】.*?(?=【台词】|【翻译】|【译】|$)", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("【(?:翻译|译|评论翻译|私聊翻译)】[\\s\\S]*$"), "")
        .replace(Regex("【台词】"), "")
        .replace(Regex("【评论】.*?(?=【私聊】|$)", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("【私聊】"), "")
        .replace(Regex("【.*?】"), "")
        .replace(Regex("\\*[^*]{0,50}\\*"), "")
        .replace(Regex("（[^）]{0,500}）"), "")
        .replace(Regex("【动作】[^【\n]*"), "")
        .replace(Regex("【穿着】[^【\n]*"), "")
        .replace(Regex("【语气】\\s*\\S+"), "")
        .trim()
}

class GroupChatActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnSend: ImageView

    private lateinit var bottomBar: LinearLayout
    private val msgList = mutableListOf<GroupMessage>()
    private lateinit var adapter: GroupMsgAdapter
    private lateinit var groupId: String
    private var groupName: String = "群聊"
    private var myId: String = "me"
    private var myName: String = "我"
    private lateinit var tvTitle: TextView
    private var groupAvatarUri: String = ""
    private var isSearching = false
    private val fullMsgList = mutableListOf<GroupMessage>()
    private lateinit var etSearch: EditText
    private lateinit var searchBar: LinearLayout
    private var ivTopAvatar: ImageView? = null
    private val pickGroupImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            val now = System.currentTimeMillis()
            try {
                val db = DatabaseHelper(this).writableDatabase
                val cv = ContentValues().apply {
                    put("groupId", groupId)
                    put("aiId", "")
                    put("content", "[图片]")
                    put("isFromMe", 1)
                    put("senderId", myId)
                    put("senderName", myName)
                    put("msgTime", SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)))
                    put("timestamp", now)
                    put("translatedText", "")
                    put("innerThoughts", "")
                    put("isVoice", 0)
                    put("voiceDuration", 0)
                    put("imageDesc", uri.toString())
                }
                db.insert("ChatHistory", null, cv)
            } catch (_: Exception) {}
            msgList.add(GroupMessage("[图片]", myId, myName, now, true, "", "", false, 0, uri.toString(), groupId = groupId))
            adapter.notifyItemInserted(msgList.size - 1)
            recyclerView.scrollToPosition(msgList.size - 1)
        }
    }
    private var ivDialogAvatar: ImageView? = null

    private val pickGroupAvatar =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                groupAvatarUri = uri.toString()
                ivDialogAvatar?.setImageURI(uri)
                try {
                    val db = DatabaseHelper(this).writableDatabase
                    db.execSQL(
                        "UPDATE GroupChats SET avatarUri=? WHERE groupId=?",
                        arrayOf(groupAvatarUri, groupId)
                    )
                    Toast.makeText(this, "群头像已更新", Toast.LENGTH_SHORT).show()
                    ivTopAvatar?.setImageURI(uri)
                } catch (_: Exception) {
                }
            }
        }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        groupId = intent.getStringExtra("GROUP_ID") ?: ""
        groupName = intent.getStringExtra("GROUP_NAME") ?: "群聊"

        val pref = getSharedPreferences("AppConfig", MODE_PRIVATE)
        myId = pref.getString("myId", "me") ?: "me"
        myName = pref.getString("myName", "我") ?: "我"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(ThemeManager.getColor("--app-bg", Color.parseColor("#F2F2F6")))
        }

        val topBar = RelativeLayout(this).apply {
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50))
            setBackgroundColor(ThemeManager.getColor("--header-bg", Color.WHITE))
        }

        tvTitle = TextView(this).apply {
            text = groupName
            textSize = 17f
            setTextColor(Color.parseColor("#111111"))
            setTypeface(null, Typeface.BOLD)
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).also { it.addRule(RelativeLayout.CENTER_IN_PARENT) }
        }

        val btnBack = TextView(this).apply {
            text = "‹"
            textSize = 32f
            setPadding(dp(16), 0, dp(16), dp(4))
            setTextColor(Color.parseColor("#111111"))
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            ).also {
                it.addRule(RelativeLayout.ALIGN_PARENT_START)
                it.addRule(RelativeLayout.CENTER_VERTICAL)
            }
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { finish() }
        }

        val btnSettings = TextView(this).apply {
            text = "☰"
            textSize = 24f
            setPadding(dp(16), dp(4), dp(16), dp(4))
            setTextColor(Color.parseColor("#111111"))
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            ).also {
                it.addRule(RelativeLayout.ALIGN_PARENT_END)
                it.addRule(RelativeLayout.CENTER_VERTICAL)
            }
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                startActivity(Intent(this@GroupChatActivity, GroupSettingsActivity::class.java)
                    .putExtra("GROUP_ID", groupId)
                    .putExtra("GROUP_NAME", groupName))
            }
        }

        // 群头像
        val ivGroupAvatar = ImageView(this).apply {
            layoutParams = RelativeLayout.LayoutParams(dp(36), dp(36)).also {
                it.addRule(RelativeLayout.CENTER_VERTICAL)
                it.marginStart = dp(52)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            setBackgroundColor(Color.parseColor("#DDDDDD"))
        }
// 读取群头像
        try {
            val db = DatabaseHelper(this).readableDatabase
            val c = db.rawQuery("SELECT avatarUri FROM GroupChats WHERE groupId=?", arrayOf(groupId))
            if (c.moveToFirst()) {
                val uriStr = c.getString(0) ?: ""
                if (uriStr.isNotEmpty()) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val bmp = if (uriStr.startsWith("/")) {
                                android.graphics.BitmapFactory.decodeFile(uriStr)
                            } else {
                                contentResolver.openInputStream(android.net.Uri.parse(uriStr))
                                    ?.use { android.graphics.BitmapFactory.decodeStream(it) }
                            }
                            withContext(Dispatchers.Main) { ivGroupAvatar.setImageBitmap(bmp) }
                        } catch (_: Exception) {}
                    }
                }
            }
            c.close()
        } catch (_: Exception) {}

        val btnSearch = TextView(this).apply {
            text = "🔍"
            textSize = 18f
            setPadding(dp(12), dp(4), dp(8), dp(4))
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            ).also {
                it.addRule(RelativeLayout.LEFT_OF, btnSettings.id)
                it.addRule(RelativeLayout.CENTER_VERTICAL)
            }
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { toggleSearchBar() }
        }
        btnSettings.id = View.generateViewId()
        btnSearch.layoutParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        ).also {
            it.addRule(RelativeLayout.LEFT_OF, btnSettings.id)
            it.addRule(RelativeLayout.CENTER_VERTICAL)
        }
        topBar.addView(btnBack)
        topBar.addView(ivGroupAvatar)
        topBar.addView(tvTitle)
        topBar.addView(btnSettings)
        topBar.addView(btnSearch)
        root.addView(topBar)

        recyclerView = RecyclerView(this).apply {
            // 搜索栏（默认隐藏）
            searchBar = LinearLayout(this@GroupChatActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.WHITE)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(48)
                )
                visibility = View.GONE
            }
            etSearch = EditText(this@GroupChatActivity).apply {
                hint = "搜索聊天记录..."
                textSize = 15f
                setTextColor(Color.BLACK)
                setHintTextColor(Color.GRAY)
                setBackgroundColor(Color.parseColor("#F5F5F5"))
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#F5F5F5"))
                    cornerRadius = dp(16).toFloat()
                }
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        filterMessages(s?.toString() ?: "")
                    }
                })
            }
            val btnCancelSearch = TextView(this@GroupChatActivity).apply {
                text = "取消"
                textSize = 14f
                setTextColor(Color.parseColor("#007AFF"))
                setPadding(dp(12), 0, 0, 0)
                setOnClickListener { toggleSearchBar() }
            }
            searchBar.addView(etSearch)
            searchBar.addView(btnCancelSearch)
            root.addView(searchBar)
            layoutManager =
                LinearLayoutManager(this@GroupChatActivity).also { it.stackFromEnd = true }
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(0, dp(12), 0, dp(12))
            clipToPadding = false
        }
        root.addView(recyclerView)

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60))
            setBackgroundColor(ThemeManager.getColor("--bottom-nav-bg", Color.parseColor("#F7F7F7")))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnPlus = TextView(this).apply {
            text = "+"
            textSize = 28f
            setTextColor(Color.parseColor("#888888"))
            layoutParams =
                LinearLayout.LayoutParams(dp(36), LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER
            setOnClickListener { pickGroupImage.launch(arrayOf("image/*")) }
        }

        etInput = EditText(this).apply {
            hint = "发消息..."
            textSize = 15f
            layoutParams =
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(20).toFloat()
            }
            setPadding(dp(16), 0, dp(16), 0)
            setSingleLine(true)
            setTextColor(Color.BLACK)        // 加这行
            setHintTextColor(Color.GRAY)     // 加这行
        }

        val btnSticker = ImageView(this).apply {
            setImageResource(R.drawable.ic_emoji)
            imageTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#888888"))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).also {
                it.marginStart = dp(4)
                it.marginEnd = dp(4)
            }
            setOnClickListener {
                StickerPanelHelper(this@GroupChatActivity) { sticker ->
                    val now = System.currentTimeMillis()
                    try {
                        val db = DatabaseHelper(this@GroupChatActivity).writableDatabase
                        val cv = ContentValues().apply {
                            put("groupId", groupId)
                            put("aiId", "")
                            put("content", "[表情包]")
                            put("isFromMe", 1)
                            put("senderId", myId)
                            put("senderName", myName)
                            put(
                                "msgTime",
                                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
                            )
                            put("timestamp", now)
                            put("translatedText", "")
                            put("innerThoughts", "")
                            put("isVoice", 0)
                            put("voiceDuration", 0)
                            put("imageDesc", sticker.url)
                        }
                        db.insert("ChatHistory", null, cv)
                    } catch (_: Exception) {
                    }
                    msgList.add(
                        GroupMessage(
                            "[表情包]",
                            myId,
                            myName,
                            now,
                            true,
                            "",
                            "",
                            false,
                            0,
                            sticker.url,
                            groupId = groupId
                        )
                    )
                    adapter.notifyItemInserted(msgList.size - 1)
                    recyclerView.scrollToPosition(msgList.size - 1)
                }.show()
            }
        }

        val btnTriggerAI = ImageView(this).apply {
            setImageResource(R.drawable.ic_tts)
            imageTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#888888"))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).also {
                it.marginStart = dp(8)
                it.marginEnd = dp(4)
            }
            setOnClickListener { triggerAIReply() }
        }

        btnSend = ImageView(this).apply {
            setImageResource(R.drawable.ic_send)
            imageTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#888888"))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).also {
                it.marginStart = dp(4)
            }
            setOnClickListener { sendMessage() }
        }

        bottomBar.addView(btnPlus)
        bottomBar.addView(etInput)
        bottomBar.addView(btnSticker)
        bottomBar.addView(btnTriggerAI)
        bottomBar.addView(btnSend)

        root.addView(bottomBar)
        ThemeManager.init(this)
        setContentView(root)
        adapter = GroupMsgAdapter(msgList, myId, this)
        recyclerView.adapter = adapter
        loadHistory()
        // 检查围观模式
        try {
            val c = DatabaseHelper(this).readableDatabase.rawQuery(
                "SELECT isObserveOnly FROM GroupChats WHERE groupId=?", arrayOf(groupId)
            )
            val isObserve = if (c.moveToFirst()) c.getInt(0) == 1 else false
            c.close()
            if (isObserve) {
                bottomBar.visibility = View.GONE
                // 添加围观模式底栏
                val observeBar = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setBackgroundColor(Color.parseColor("#F7F7F7"))
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(-1, dp(56))
                }
                val etGuide = EditText(this).apply {
                    hint = "输入引导词（可选）…"
                    textSize = 14f
                    setSingleLine(true)
                    setTextColor(Color.BLACK); setHintTextColor(Color.GRAY)
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.WHITE); cornerRadius = dp(18).toFloat()
                    }
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                }
                val btnTrigger = TextView(this).apply {
                    text = "触发对话"
                    textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#07C160")); cornerRadius = dp(18).toFloat()
                    }
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.marginStart = dp(8) }
                    setOnClickListener {
                        val guide = etGuide.text.toString().trim()
                        etGuide.setText("")
                        triggerObserveReply(guide)
                    }
                }
                observeBar.addView(etGuide)
                observeBar.addView(btnTrigger)
                // 找到root并插入
                val rootView = recyclerView.parent as? LinearLayout
                rootView?.addView(observeBar)
            }
        } catch (_: Exception) {}
        // 进入群聊时清除未读
        try {
            DatabaseHelper(this).writableDatabase.execSQL(
                "UPDATE ChatHistory SET isRead=1 WHERE groupId=? AND isRead=0",
                arrayOf(groupId)
            )
            sendBroadcast(Intent("CYBER_NEW_MSG"))
        } catch (_: Exception) {}
    }

    private fun openGroupSettings() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(Color.WHITE)
        }
        val tvHeader = TextView(this).apply {
            text = "群聊设置"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(20) }
            gravity = Gravity.CENTER
        }
        dialogView.addView(tvHeader)

        val avatarLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(20) }
        }
        ivDialogAvatar = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(80), dp(80))
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            setBackgroundColor(Color.parseColor("#DDDDDD"))
            setImageResource(android.R.drawable.ic_menu_gallery)
        }
        try {
            val db = DatabaseHelper(this).readableDatabase
            val cursor = db.rawQuery("SELECT avatarUri FROM GroupChats WHERE groupId=?", arrayOf(groupId))
            if (cursor.moveToFirst()) {
                val uriStr = cursor.getString(0) ?: ""
                if (uriStr.isNotEmpty()) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val uri = android.net.Uri.parse(uriStr)
                            val inputStream = contentResolver.openInputStream(uri)
                            val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                            inputStream?.close()
                            withContext(Dispatchers.Main) {
                                ivDialogAvatar?.setImageBitmap(bitmap)
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
            cursor.close()
        } catch (_: Exception) {
        }

        val tvAvatarHint = TextView(this).apply {
            text = "点击修改群头像"
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, dp(8), 0, 0)
        }
        avatarLayout.addView(ivDialogAvatar)
        avatarLayout.addView(tvAvatarHint)
        avatarLayout.setOnClickListener { pickGroupAvatar.launch(arrayOf("image/*")) }
        dialogView.addView(avatarLayout)

        val etName = EditText(this).apply {
            setText(groupName)
            textSize = 16f
            hint = "输入新群名"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(24) }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setStroke(1, Color.parseColor("#DDDDDD"))
                cornerRadius = dp(8).toFloat()
            }
        }
        dialogView.addView(etName)

        val btnSaveName = TextView(this).apply {
            text = "保存群名"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#007AFF"))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(24) }
        }
        dialogView.addView(btnSaveName)

        val btnClear = TextView(this).apply {
            text = "⚠ 一键清除记忆"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#FF3B30"))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        dialogView.addView(btnClear)

        val btnDeleteGroup = TextView(this).apply {
            text = "💥 解散并删除群聊"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#000000"))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(16) }
        }
        dialogView.addView(btnDeleteGroup)

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        btnSaveName.setOnClickListener {
            val newName = etName.text.toString().trim()
            if (newName.isNotEmpty()) {
                try {
                    val db = DatabaseHelper(this).writableDatabase
                    db.execSQL(
                        "UPDATE GroupChats SET groupName=? WHERE groupId=?",
                        arrayOf(newName, groupId)
                    )
                    groupName = newName
                    tvTitle.text = newName
                    Toast.makeText(this, "群名已修改", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                }
            }
            dialog.dismiss()
        }

        btnClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("严重警告")
                .setMessage("将清除本群所有聊天记录，确定执行吗")
                .setPositiveButton("清除") { _, _ ->
                    try {
                        val db = DatabaseHelper(this).writableDatabase
                        db.delete("ChatHistory", "TRIM(IFNULL(groupId,''))=?", arrayOf(groupId.trim()))
                        msgList.clear()
                        adapter.notifyDataSetChanged()
                        Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        btnDeleteGroup.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("解散群聊")
                .setMessage("确定解散并删除这个群聊吗")
                .setPositiveButton("删除") { _, _ ->
                    val db = DatabaseHelper(this).writableDatabase
                    try {
                        db.execSQL(
                            "UPDATE GroupChats SET isDisbanded=1 WHERE TRIM(IFNULL(groupId,''))=?",
                            arrayOf(groupId.trim())
                        )
                        db.delete("GroupMembers", "groupId=?", arrayOf(groupId))
                        db.delete("ChatHistory", "groupId=?", arrayOf(groupId))
                        db.delete("PendingAiMessages", "groupId=?", arrayOf(groupId))
                    } catch (e: Exception) {
                        android.util.Log.e("GROUP_DELETE", e.stackTraceToString())
                    }

                    sendBroadcast(Intent("CYBER_NEW_MSG"))
                    Toast.makeText(this, "群聊已删除", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    finish()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        dialog.show()
    }

    private fun loadHistory() {
        msgList.clear()

        try {
            val checkDb = DatabaseHelper(this).readableDatabase
            val check = checkDb.rawQuery(
                "SELECT groupId FROM GroupChats WHERE TRIM(IFNULL(groupId,''))=? AND IFNULL(isDisbanded,0)=0 LIMIT 1",
                arrayOf(groupId.trim())
            )
            val exists = check.moveToFirst()
            check.close()
            if (!exists) {
                Toast.makeText(this, "群聊已解散", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
        } catch (_: Exception) {}

        try {
            val db = DatabaseHelper(this).readableDatabase
            val cursor = db.rawQuery(
                "SELECT content, senderId, senderName, timestamp, isFromMe, translatedText, innerThoughts, isVoice, voiceDuration, imageDesc FROM ChatHistory WHERE groupId=? ORDER BY timestamp ASC",
                arrayOf(groupId)
            )
            while (cursor.moveToNext()) {
                msgList.add(
                    GroupMessage(
                        content = cursor.getString(0) ?: "",
                        senderId = cursor.getString(1) ?: "",
                        senderName = cursor.getString(2) ?: "未知",
                        timestamp = cursor.getLong(3),
                        isFromMe = cursor.getInt(4) == 1,
                        translatedText = cursor.getString(5) ?: "",
                        innerThoughts = cursor.getString(6) ?: "",
                        isVoice = cursor.getInt(7) == 1,
                        voiceDuration = cursor.getInt(8),
                        imageDesc = cursor.getString(9) ?: "",
                        isSystem = false,
                        groupId = groupId
                    )
                )
            }
            cursor.close()
        } catch (_: Exception) {
        }
        adapter.notifyDataSetChanged()
        if (msgList.isNotEmpty()) recyclerView.scrollToPosition(msgList.size - 1)
        fullMsgList.clear()
        fullMsgList.addAll(msgList)
    }
    private fun triggerObserveReply(guideTopic: String = "") {
        if (isTriggering) return
        isTriggering = true

        val typingMsg = GroupMessage("正在输入...", "system", "...", System.currentTimeMillis(), false, "", "", false, 0, isSystem = true, groupId = groupId)
        msgList.add(typingMsg)
        adapter.notifyItemInserted(msgList.size - 1)
        recyclerView.scrollToPosition(msgList.size - 1)
        val typingIndex = msgList.size - 1
        fun removeObserveTyping() {
            if (typingIndex in msgList.indices && msgList[typingIndex].isSystem) {
                msgList.removeAt(typingIndex)
                adapter.notifyItemRemoved(typingIndex)
            }
        }

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val pref = getSharedPreferences("AppConfig", MODE_PRIVATE)
                var apiUrl = pref.getString("apiUrl", "")?.trim() ?: ""
                if (!apiUrl.startsWith("http")) apiUrl = "https://$apiUrl"
                while (apiUrl.endsWith("/")) apiUrl = apiUrl.dropLast(1)
                if (!apiUrl.endsWith("/chat/completions")) apiUrl = if (apiUrl.contains("/v1")) "$apiUrl/chat/completions" else "$apiUrl/v1/chat/completions"
                val apiKey = pref.getString("apiKey", "") ?: ""
                val modelName = pref.getString("modelName", "") ?: ""
                val db = DatabaseHelper(this@GroupChatActivity).readableDatabase

                // 取所有AI成员
                val members = mutableListOf<Triple<String, String, String>>() // id, name, persona
                db.rawQuery("SELECT memberId, IFNULL(nickname, memberName), memberName FROM GroupMembers WHERE groupId=? AND isAi=1", arrayOf(groupId)).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: ""; val displayName = c.getString(1).ifEmpty { c.getString(2) }
                        var persona = ""
                        db.rawQuery("SELECT identityInfo FROM Contacts WHERE userId=?", arrayOf(id)).use { pc -> if (pc.moveToFirst()) persona = pc.getString(0) ?: "" }
                        members.add(Triple(id, displayName, persona))
                    }
                }
                if (members.isEmpty()) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        removeObserveTyping()
                        Toast.makeText(this@GroupChatActivity, "\u7fa4\u91cc\u6ca1\u6709\u53ef\u53d1\u8a00\u7684AI\u6210\u5458", Toast.LENGTH_SHORT).show()
                        isTriggering = false
                    }
                    return@launch
                }

                val speakers = if (members.size <= 3) members else members.shuffled().take(4)

                // 取最近聊天记录
                val recentChat = StringBuilder()
                db.rawQuery("SELECT senderName, content FROM ChatHistory WHERE groupId=? ORDER BY timestamp DESC LIMIT 10", arrayOf(groupId)).use { c ->
                    val tmp = mutableListOf<String>()
                    while (c.moveToNext()) tmp.add(0, "${c.getString(0)}: ${c.getString(1)}")
                    tmp.forEach { recentChat.append(it).append("\n") }
                }

                // 取今日日程
                val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                val scheduleInfo = StringBuilder()
                for ((id, name, _) in speakers) {
                    db.rawQuery("SELECT startTime, eventDesc FROM Schedules WHERE aiId=? AND dateStr=? ORDER BY startTime ASC LIMIT 3", arrayOf(id, todayStr)).use { c ->
                        while (c.moveToNext()) scheduleInfo.append("$name 今天${c.getString(0)}: ${c.getString(1)}\n")
                    }
                }
                val ownerName = try {
                    var name = ""
                    db.rawQuery(
                        "SELECT IFNULL(nickname, memberName) FROM GroupMembers WHERE groupId=? AND isOwner=1 AND isAi=1",
                        arrayOf(groupId)
                    ).use { c -> if (c.moveToFirst()) name = c.getString(0) ?: "" }
                    name
                } catch (_: Exception) { "" }

                val ownerRule = if (ownerName.isNotEmpty()) """
【群主特权】：${ownerName}是群主，可以在台词末尾加 [SET_TITLE:成员名:新头衔] 来给某人设置头衔。
例如：[SET_TITLE:keegan:狙击手]。只在剧情自然发展时使用，不要强行使用。
""" else ""
                val speakerDesc = speakers.joinToString("\n") { (id, name, persona) ->
                    val aiLang = pref.getString("aiLang_$id", "默认 (中文)") ?: "默认 (中文)"
                    val reqTrans = pref.getBoolean("autoTrans_$id", false)
                    val langNote = if (aiLang != "默认 (中文)") {
                        if (reqTrans) "语言：必须用${aiLang}，台词末尾加【翻译】中文翻译"
                        else "语言：必须用${aiLang}"
                    } else "语言：中文"

                    // 读取与用户的关系和记忆
                    var relationship = ""
                    var memory = ""
                    try {
                        db.rawQuery("SELECT IFNULL(relationship,'') FROM Contacts WHERE userId=?", arrayOf(id))
                            .use { c -> if (c.moveToFirst()) relationship = c.getString(0) }
                    } catch (_: Exception) {}
                    try {
                        db.rawQuery("SELECT memoryText FROM MemoryBank WHERE aiId=? ORDER BY insertTime DESC LIMIT 5", arrayOf(id))
                            .use { c ->
                                val sb = StringBuilder()
                                while (c.moveToNext()) sb.append(c.getString(0)).append("\n")
                                memory = sb.toString().trim().take(300)
                            }
                    } catch (_: Exception) {}

                    buildString {
                        append("【${name}】\n")
                        append("人设：${persona.take(300)}\n")
                        if (relationship.isNotEmpty()) append("与用户的关系：$relationship\n")
                        if (memory.isNotEmpty()) append("与用户的记忆：$memory\n")
                        append(langNote)
                    }
                }
                val guideStr = if (guideTopic.isNotEmpty()) "\n【本轮引导话题】：$guideTopic\n所有角色的对话必须围绕这个话题展开。" else ""
                val nowTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                var myName = ""
                try {
                    db.rawQuery("SELECT myName FROM MyProfile LIMIT 1", null)
                        .use { c -> if (c.moveToFirst()) myName = c.getString(0) ?: "" }
                } catch (_: Exception) {}

                val prompt = """
你正在模拟名为「$groupName」的群聊，现在只有AI角色在聊天，用户（${if (myName.isNotEmpty()) myName else "用户"}）只是旁观者。
【当前时间】：$nowTime
【角色信息】：
$speakerDesc
${if (scheduleInfo.isNotEmpty()) "【今日行程参考】：\n$scheduleInfo" else ""}
【最近聊天记录】：
${recentChat.toString().ifEmpty { "（群聊刚开始）" }}
$guideStr

请让以上${speakers.size}个角色自然地聊几句，像真实微信群聊一样。
要求：
1. 内容要基于各自的人设和今日行程自然展开
2. 角色之间可以互动、调侃、回应，有来有往
3. 每人说1-2句话，简短自然
4. 禁止动作描写，只输出说出口的话

严格按以下格式，每人之间用 <|SPLIT|> 分隔：
【发送者】角色名
【内心】此刻真实的内心想法（中文，10字以内）
【台词】说的话（严格按该角色语言要求）
【翻译】（仅台词非中文时填写，否则留空）
            """.trimIndent()

                val body = org.json.JSONObject().apply {
                    put("model", modelName); put("temperature", 0.85)
                    put("messages", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply { put("role", "user"); put("content", prompt) })
                    })
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())

                val response = Http.client.newBuilder().connectTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
                    .newCall(Request.Builder().url(apiUrl).addHeader("Authorization", "Bearer $apiKey").post(body).build()).execute()

                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    removeObserveTyping()
                }
                val responseBody = response.body?.string()
                if (!response.isSuccessful) {
                    val code = response.code
                    response.close()
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(this@GroupChatActivity, "\u7fa4\u804a\u89e6\u53d1\u5931\u8d25\uff1aHTTP $code", Toast.LENGTH_SHORT).show()
                        isTriggering = false
                    }
                    return@launch
                }
                if (responseBody == null) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(this@GroupChatActivity, "\u7fa4\u804a\u89e6\u53d1\u5931\u8d25\uff1a\u8fd4\u56de\u4e3a\u7a7a", Toast.LENGTH_SHORT).show()
                        isTriggering = false
                    }
                    return@launch
                }

                val replyContent = org.json.JSONObject(responseBody)
                    .getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
                val blocks = splitGroupReplyBlocks(replyContent)

                for (block in blocks) {
                    val senderName = extractGroupSenderName(block) ?: continue
                    val inner = Regex("【内心】(.*?)(?=【台词】)", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)?.trim() ?: ""
                    val rawDialog = Regex("【台词】(.*?)(?=【翻译】|【译】|$)", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)?.trim() ?: continue
                    val trans = Regex("【(?:翻译|译)】(.*?)$", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)?.trim() ?: ""
                    val member = speakers.find {
                        it.second.trim() == senderName.trim() ||
                                senderName.contains(it.second.trim()) ||
                                it.second.trim().contains(senderName.trim())
                    } ?: continue

// 检测群昵称修改标签
                    val nickTagRegex = Regex("""\[SET_GROUPNICK:([^\]]{1,20})\]""")
                    val nickMatch = nickTagRegex.find(rawDialog)
                    val newGroupNick = nickMatch?.groupValues?.get(1)?.trim() ?: ""

// 检测群主改头衔标签
                    val titleTagRegex = Regex("""\[SET_TITLE:([^:]+):([^\]]{1,10})\]""")
                    val titleMatches = titleTagRegex.findAll(rawDialog).toList()

// 清理标签后的干净台词
                    val finalDialog = rawDialog
                        .replace(nickTagRegex, "")
                        .replace(titleTagRegex, "")
                        .trim()
                    val now = System.currentTimeMillis()
                    kotlinx.coroutines.delay((600..1500).random().toLong())
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        try {
                            DatabaseHelper(this@GroupChatActivity).writableDatabase.insert("ChatHistory", null, ContentValues().apply {
                                put("groupId", groupId); put("aiId", member.first); put("content", finalDialog)
                                put("isFromMe", 0); put("senderId", member.first); put("senderName", senderName)
                                put("msgTime", java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(now)))
                                put("timestamp", now); put("translatedText", trans); put("innerThoughts", inner); put("isVoice", 0); put("voiceDuration", 0)
                            })
                        } catch (_: Exception) {}
                        msgList.add(GroupMessage(finalDialog, member.first, senderName, now, false, trans, inner, false, 0, groupId = groupId))
                        adapter.notifyItemInserted(msgList.size - 1)
                        recyclerView.scrollToPosition(msgList.size - 1)
                        // 处理群昵称修改
                        if (newGroupNick.isNotEmpty()) {
                            try {
                                DatabaseHelper(this@GroupChatActivity).writableDatabase.execSQL(
                                    "UPDATE GroupMembers SET nickname=? WHERE groupId=? AND memberId=?",
                                    arrayOf(newGroupNick, groupId, member.first)
                                )
                                // 发系统消息
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    val sysMsg = GroupMessage(
                                        "「$senderName 将群昵称改为了「$newGroupNick」」",
                                        "system", "", System.currentTimeMillis(), false,
                                        isSystem = true, groupId = groupId
                                    )
                                    msgList.add(sysMsg)
                                    adapter.notifyItemInserted(msgList.size - 1)
                                    recyclerView.scrollToPosition(msgList.size - 1)
                                }
                            } catch (_: Exception) {}
                        }

// 处理头衔修改
                        for (match in titleMatches) {
                            val targetName = match.groupValues[1].trim()
                            val newTitle = match.groupValues[2].trim()
                            try {
                                DatabaseHelper(this@GroupChatActivity).writableDatabase.execSQL(
                                    "UPDATE GroupMembers SET title=? WHERE groupId=? AND (memberName=? OR nickname=?)",
                                    arrayOf(newTitle, groupId, targetName, targetName)
                                )
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    val sysMsg = GroupMessage(
                                        "「$senderName 授予 $targetName 头衔【$newTitle】」",
                                        "system", "", System.currentTimeMillis(), false,
                                        isSystem = true, groupId = groupId
                                    )
                                    msgList.add(sysMsg)
                                    adapter.notifyItemInserted(msgList.size - 1)
                                    recyclerView.scrollToPosition(msgList.size - 1)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
                withContext(kotlinx.coroutines.Dispatchers.Main) { isTriggering = false }
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    removeObserveTyping()
                    Toast.makeText(this@GroupChatActivity, "触发失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    isTriggering = false
                }
            }
        }
    }
    private fun toggleSearchBar() {
        isSearching = !isSearching
        if (isSearching) {
            searchBar.visibility = View.VISIBLE
            etSearch.requestFocus()
            val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            imm?.showSoftInput(etSearch, 0)
        } else {
            searchBar.visibility = View.GONE
            etSearch.setText("")
            val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(etSearch.windowToken, 0)
            // 恢复完整列表
            msgList.clear()
            msgList.addAll(fullMsgList)
            adapter.notifyDataSetChanged()
            recyclerView.scrollToPosition(msgList.size - 1)
        }
    }

    private fun filterMessages(keyword: String) {
        msgList.clear()
        if (keyword.isBlank()) {
            msgList.addAll(fullMsgList)
        } else {
            msgList.addAll(fullMsgList.filter {
                it.content.contains(keyword, ignoreCase = true)
            })
        }
        adapter.notifyDataSetChanged()
    }
    private fun encodeGroupImageToBase64(uri: android.net.Uri): String {
        return try {
            val input = contentResolver.openInputStream(uri) ?: return ""

            val options = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = 2
            }

            val original = android.graphics.BitmapFactory.decodeStream(input, null, options)
            input.close()

            if (original == null) return ""

            val maxSize = 512
            val scale = minOf(
                maxSize.toFloat() / original.width,
                maxSize.toFloat() / original.height,
                1f
            )

            val scaled =
                if (scale < 1f) {
                    android.graphics.Bitmap.createScaledBitmap(
                        original,
                        (original.width * scale).toInt(),
                        (original.height * scale).toInt(),
                        true
                    )
                } else {
                    original
                }

            val baos = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, baos)

            val result = android.util.Base64.encodeToString(
                baos.toByteArray(),
                android.util.Base64.NO_WRAP
            )

            if (scaled !== original) scaled.recycle()
            original.recycle()

            result
        } catch (_: Exception) {
            ""
        }
    }
    private fun sendMessage() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) return
        val now = System.currentTimeMillis()
        try {
            val db = DatabaseHelper(this).writableDatabase
            val cv = ContentValues().apply {
                put("groupId", groupId)
                put("aiId", "")
                put("content", text)
                put("isFromMe", 1)
                put("senderId", myId)
                put("senderName", myName)
                put("msgTime", SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)))
                put("timestamp", now)
                put("translatedText", "")
                put("innerThoughts", "")
                put("isVoice", 0)
                put("voiceDuration", 0)
                put("imageDesc", "")
            }
            db.insert("ChatHistory", null, cv)
        } catch (_: Exception) {}
        msgList.add(GroupMessage(text, myId, myName, now, true, "", "", false, 0, ""))
        adapter.notifyItemInserted(msgList.size - 1)
        recyclerView.scrollToPosition(msgList.size - 1)
        etInput.setText("")
        isTriggering = false
    }

    private var isTriggering = false

    private fun triggerAIReply() {
        if (isTriggering) return
        isTriggering = true
        val typingMsg = GroupMessage("正在输入...", "system", "...", System.currentTimeMillis(), false, "", "", false, 0, isSystem = true, groupId = groupId)
        msgList.add(typingMsg)
        adapter.notifyItemInserted(msgList.size - 1)
        recyclerView.scrollToPosition(msgList.size - 1)
        val typingIndex = msgList.size - 1
        fun removeTyping() {
            if (typingIndex < msgList.size && msgList[typingIndex].isSystem) {
                msgList.removeAt(typingIndex)
                adapter.notifyItemRemoved(typingIndex)
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pref = getSharedPreferences("AppConfig", MODE_PRIVATE)
                var apiUrl = pref.getString("apiUrl", "")?.trim() ?: ""
                if (apiUrl.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        removeTyping(); isTriggering = false
                        Toast.makeText(this@GroupChatActivity, "API网址为空", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                if (!apiUrl.startsWith("http")) apiUrl = "https://$apiUrl"
                while (apiUrl.endsWith("/")) apiUrl = apiUrl.dropLast(1)
                if (!apiUrl.endsWith("/chat/completions")) {
                    apiUrl = if (apiUrl.contains("/v1")) "$apiUrl/chat/completions" else "$apiUrl/v1/chat/completions"
                }

                val apiKey = pref.getString("apiKey", "") ?: ""
                val modelName =
                    pref.getString("modelName", "gemini-3.1-pro")?.takeIf { it.isNotBlank() }
                        ?: "gemini-3.1-pro"
                val db = DatabaseHelper(this@GroupChatActivity).readableDatabase

                val memberCursor = db.rawQuery(
                    "SELECT memberId, memberName FROM GroupMembers WHERE groupId=? AND isAi=1",
                    arrayOf(groupId)
                )
                val allAiMembers = mutableListOf<Pair<String, String>>()
                while (memberCursor.moveToNext()) {
                    allAiMembers.add(
                        Pair(
                            memberCursor.getString(0) ?: "",
                            memberCursor.getString(1) ?: "未知"
                        )
                    )
                }
                memberCursor.close()

                if (allAiMembers.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        removeTyping(); isTriggering = false
                        Toast.makeText(this@GroupChatActivity, "群里没AI成员", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val speakers =
                    if (allAiMembers.size <= 3) allAiMembers else allAiMembers.shuffled()
                        .take(4)

                val chatHistoryBuilder = StringBuilder()
                val imageMessages = mutableListOf<GroupMessage>()
// 直接从数据库取最近20条，不受msgList截断影响
                try {
                    val hc = db.rawQuery(
                        "SELECT content, senderName, isFromMe, imageDesc FROM ChatHistory WHERE groupId=? ORDER BY timestamp DESC LIMIT 12",
                        arrayOf(groupId)
                    )
                    val tempList = mutableListOf<String>()
                    while (hc.moveToNext()) {
                        val imageDesc = hc.getString(3) ?: ""
                        val sName = hc.getString(1) ?: ""
                        val content = hc.getString(0) ?: ""
                        if (imageDesc.isNotEmpty() && hc.getInt(2) == 1) {
                            tempList.add(0, "$sName: [发送了一张图片]")
                            // 找对应的msgList里的图片消息
                            msgList.lastOrNull { it.imageDesc == imageDesc }?.let { imageMessages.add(it) }
                        } else {
                            tempList.add(0, "$sName: $content")
                        }
                    }
                    hc.close()
                    tempList.forEach { chatHistoryBuilder.append(it).append("\n") }
                } catch (_: Exception) {
                    for (m in msgList.takeLast(20)) {
                        if (m.imageDesc.isNotEmpty() && m.isFromMe) {
                            chatHistoryBuilder.append("${m.senderName}: [发送了一张图片]\n")
                            imageMessages.add(m)
                        } else {
                            chatHistoryBuilder.append("${m.senderName}: ${m.content}\n")
                        }
                    }
                }
                val groupContext = chatHistoryBuilder.toString()
                if (groupContext.trim().isEmpty()) {
                    withContext(Dispatchers.Main) {
                        removeTyping(); isTriggering = false
                        Toast.makeText(this@GroupChatActivity, "请先发一条消息再触发AI回复", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val timeContext = when (hour) {
                    in 6..8 -> "清晨，大多数人刚起床或准备出门"
                    in 9..11 -> "上午，正常工作学习时间"
                    in 12..13 -> "中午，午饭或午休时间"
                    in 14..16 -> "下午，正常活动时间"
                    in 17..18 -> "傍晚，下班放学时间"
                    in 19..21 -> "晚上，大多数人在休息或娱乐"
                    in 22..24 -> "深夜，大多数人已入睡或准备睡觉"
                    else -> "凌晨，绝大多数人在睡觉"
                }
                val nowTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

                var myProfileInfo = ""
                val profileCursor =
                    db.rawQuery("SELECT myName, mbti, identity FROM MyProfile", null)
                if (profileCursor.moveToFirst()) {
                    myProfileInfo = "用户姓名: ${profileCursor.getString(0)}, MBTI: ${
                        profileCursor.getString(1)
                    }, 身份设定: ${profileCursor.getString(2)}"
                }
                profileCursor.close()

                val speakerInfos = StringBuilder()
                for ((index, speaker) in speakers.withIndex()) {
                    val aiId = speaker.first
                    val aiName = speaker.second
                    var aiPersona = ""
                    val contactCursor = db.rawQuery(
                        "SELECT identityInfo FROM Contacts WHERE userId=?",
                        arrayOf(aiId)
                    )
                    if (contactCursor.moveToFirst()) aiPersona =
                        contactCursor.getString(0) ?: ""
                    contactCursor.close()
                    var cyberMemory = ""
                    val memoryCursor = db.rawQuery(
                        "SELECT memoryText FROM MemoryBank WHERE aiId=? ORDER BY insertTime DESC LIMIT 5",
                        arrayOf(aiId)
                    )
                    val memSb = StringBuilder()
                    while (memoryCursor.moveToNext()) {
                        memSb.append(memoryCursor.getString(0) ?: "").append("\n")
                    }
                    memoryCursor.close()
                    cyberMemory = memSb.toString().trim()
                    val aiLang = pref.getString("aiLang_$aiId", "默认 (中文)") ?: "默认 (中文)"
                    val reqTrans = pref.getBoolean("autoTrans_$aiId", false)
                    speakerInfos.append("第${index + 1}个发言者：\n")
                    speakerInfos.append("【名字】：$aiName\n")
                    speakerInfos.append("【角色设定】：${aiPersona.take(300)}\n")
                    speakerInfos.append("【潜意识(与群主私聊的记忆)】：${cyberMemory.take(200)}\n")
                    speakerInfos.append("【语言要求】：$aiLang\n")
                    speakerInfos.append("【是否需要给中文翻译】：${if (reqTrans) "是" else "否"}\n\n")
                }

                val systemPrompt = """
你当前正在模拟一个名为"$groupName"的微信群聊。
【群主(我)的情报】：$myProfileInfo
【当前时间】：$nowTime（$timeContext）

本次将被选中发言的AI角色情报如下（共${speakers.size}名）：
$speakerInfos

最近的群聊记录：
${if (groupContext.isEmpty()) "（群聊刚建立，还没有消息）" else groupContext}

【最新一条消息】：$myName 刚刚说："${msgList.lastOrNull { it.isFromMe }?.content ?: ""}"
⚠️ 所有角色的回复必须直接回应上面这条最新消息的内容，禁止无视它。

【强制互动规则】：
1. 必须让上述 ${speakers.size} 个角色全部出场发言，每人都要针对最新消息做出回应。
2. 角色间形成接力式回应，但核心都是围绕用户说的话展开。
3. 保持各自人设性格。话不要太多，符合真实微信聊天的简短风格。
4. 禁止无缘无故催人吃饭睡觉，除非用户主动提到饿了或困了。
5. 禁止说教，禁止重复上一轮已经说过的话。

【强制输出格式】：
必须严格按照以下格式输出每个人的回复，每人回复之间用 <|SPLIT|> 分隔。

⚠️ 铁律：
- 【台词】只能是该角色说出口的原话，禁止在台词里添加任何解释、补充或翻译内容
- 【翻译】只能是【台词】的逐字中文翻译，严禁添加台词原文中没有的内容
- 【内心】的想法绝对不能出现在【台词】里
- 严禁动作描写、旁白、*号动作、括号动作

【发送者】AI的名字
【内心】真实的内心想法(中文)
【台词】你要发送的内容(严格使用该角色的【语言要求】，只写说出口的话)
【翻译】仅当台词非中文时提供逐字翻译，否则留空
<|SPLIT|>
【发送者】下一个AI的名字
...

⚠️ 再次强调：每个角色的回复必须独立，用 <|SPLIT|> 严格分隔。
""".trimIndent()

                val messagesArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                }
                if (imageMessages.isEmpty()) {
                    messagesArray.put(JSONObject().apply {
                        put("role", "user")
                        put("content", "请根据以上群聊记录，以各角色身份回复。")
                    })
                }
                // 加这一条，Gemini必须有普通user消息
                for (imgMsg in imageMessages) {
                    try {
                        val uri = android.net.Uri.parse(imgMsg.imageDesc)
                        val base64 = encodeGroupImageToBase64(uri)

                        if (base64.isNotEmpty() && base64.length < 2_000_000) {
                            val contentArray = JSONArray()
                            contentArray.put(JSONObject().apply {
                                put("type", "text")
                                put("text", "${imgMsg.senderName} 发送了这张图片：")
                            })
                            contentArray.put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    put("url", "data:image/jpeg;base64,$base64")
                                })
                            })
                            messagesArray.put(JSONObject().apply {
                                put("role", "user")
                                put("content", contentArray)
                            })
                        }
                    } catch (_: Exception) {}
                }
                val bodyJson = JSONObject().apply {
                    put("model", modelName)
                    put("temperature", 0.7)
                    put("max_tokens", 2000)  // 群聊多角色留足量，但仍封顶防退化烧 token
                    put("messages", messagesArray)
                }
                val request = Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(
                        bodyJson.toString()
                            .toRequestBody("application/json".toMediaTypeOrNull())
                    )
                    .build()

                val response = Http.client.newBuilder().connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS).build().newCall(request).execute()
                withContext(Dispatchers.Main) {
                    if (typingIndex < msgList.size && msgList[typingIndex].senderId == "system") {
                        msgList.removeAt(typingIndex)
                        adapter.notifyItemRemoved(typingIndex)
                    }
                }
                val body = response.body?.string() ?: run {
                    withContext(Dispatchers.Main) { removeTyping(); isTriggering = false }
                    return@launch
                }
                if (!response.isSuccessful) {
                    val errDetail = try {
                        JSONObject(body).optJSONObject("error")?.optString("message")
                            ?.takeIf { it.isNotBlank() } ?: body.take(300)
                    } catch (_: Exception) { body.take(300) }
                    withContext(Dispatchers.Main) {
                        android.util.Log.e("GROUP_API_ERROR", "code=${response.code} 完整错误: $body")
                        Toast.makeText(this@GroupChatActivity, "API错误 ${response.code}：$errDetail", Toast.LENGTH_LONG).show()
                        isTriggering = false
                    }
                    return@launch
                }

                val replyContent = JSONObject(body).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()
                // 封顶段数，防止模型退化吐出海量 <|SPLIT|> 灌爆群聊
                val blocks = splitGroupReplyBlocks(replyContent).take(20)

                for (block in blocks) {
                    val senderName = extractGroupSenderName(block) ?: ""
                    val inner = Regex("【内心】(.*?)【台词】", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)?.trim() ?: ""
                    val rawDialog = Regex("【台词】(.*?)(?=【翻译】|【译】|$)", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)?.trim()
                        ?: Regex("【台词】(.*?)$", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)?.trim() ?: ""
                    val transRaw = Regex("【(?:翻译|译)】(.*?)$", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)?.trim() ?: ""
                    val fullDialog = cleanDialogContent(rawDialog)
                    val trans = if (transRaw.length > fullDialog.length * 2) "" else transRaw

                    if (fullDialog.length > 1 && senderName.isNotEmpty()) {
                        // 忽略大小写匹配：英文名（如 kee/Kee/KEE）大小写不一致时不能把该角色的发言静默丢掉
                        val cleanSender = senderName.trim()
                        val aiId = (speakers.find { it.second.trim().equals(cleanSender, ignoreCase = true) }
                            ?: speakers.find {
                                cleanSender.contains(it.second.trim(), ignoreCase = true) ||
                                        it.second.trim().contains(cleanSender, ignoreCase = true)
                            })?.first ?: continue  // 匹配失败直接跳过，不乱猜

                        val sentences = fullDialog.split(Regex("(?<=[。！？；.!?])")).filter { it.trim().length > 1 }
                        val msgCount = (1..3).random().coerceAtMost(sentences.size)
                        val selectedSentences = sentences.take(msgCount)
                        val transSentences = if (trans.isNotEmpty()) {
                            trans.split(Regex("(?<=[。！？；.!?])")).filter { it.isNotBlank() }
                        } else emptyList()

                        for ((index, sentence) in selectedSentences.withIndex()) {
                            if (index > 0) delay((300..800).random().toLong())
                            val isLastMsg = (index == selectedSentences.size - 1)
                            val now = System.currentTimeMillis()
                            val isTtsEnabled = pref.getBoolean("isTtsEnabled_$aiId", false)
                            val voiceProb = pref.getInt("voiceProb_$aiId", 0)
                            val willSendVoice = isLastMsg && isTtsEnabled && (0..100).random() < voiceProb
                            val dur = if (willSendVoice) (sentence.length / 4).coerceIn(2, 60) else 0
                            val currentInner = if (isLastMsg) inner else ""
                            val currentTrans = when {
                                trans.isEmpty() -> ""
                                selectedSentences.size == 1 -> trans
                                transSentences.size == selectedSentences.size -> transSentences[index]
                                index == selectedSentences.lastIndex -> trans
                                else -> ""
                            }

                            withContext(Dispatchers.Main) {
                                try {
                                    val wdb = DatabaseHelper(this@GroupChatActivity).writableDatabase
                                    val cv = ContentValues().apply {
                                        put("groupId", groupId)
                                        put("aiId", aiId)
                                        put("content", sentence)
                                        put("isFromMe", 0)
                                        put("senderId", aiId)
                                        put("senderName", senderName)
                                        put("msgTime", SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)))
                                        put("timestamp", now)
                                        put("translatedText", currentTrans)
                                        put("innerThoughts", currentInner)
                                        put("isVoice", if (willSendVoice) 1 else 0)
                                        put("voiceDuration", dur)
                                    }
                                    wdb.insert("ChatHistory", null, cv)
                                } catch (_: Exception) {}
                                msgList.add(GroupMessage(sentence, aiId, senderName, now, false, currentTrans, currentInner, willSendVoice, dur, groupId = groupId))
                                adapter.notifyItemInserted(msgList.size - 1)
                                recyclerView.scrollToPosition(msgList.size - 1)
                                // 写入记忆宫殿，与私聊互通
                            }
                        }
                        // 不同角色之间额外延迟
                        delay((500..1000).random().toLong())
                    }
                }
                withContext(Dispatchers.Main) { isTriggering = false }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    removeTyping()
                    Toast.makeText(this@GroupChatActivity, "群聊请求失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    isTriggering = false
                }
            }
        }
    }
    private fun splitGroupReplyBlocks(raw: String): List<String> {
        val bySplit = raw.split("<|SPLIT|>").map { it.trim() }.filter { it.isNotEmpty() }
        if (bySplit.size > 1) return bySplit
        val marker = Regex("(?=(?:【\\s*发送者\\s*】|\\[\\s*发送者\\s*\\]|^\\s*发送者\\s*[:：]))", setOf(RegexOption.MULTILINE))
        return marker.split(raw)
            .map { it.trim() }
            .filter { it.isNotEmpty() && extractGroupSenderName(it) != null }
            .ifEmpty { bySplit }
    }

    private fun extractGroupSenderName(block: String): String? {
        val patterns = listOf(
            Regex("【\\s*发送者\\s*】\\s*([^【\\[\\n:：]+)"),
            Regex("\\[\\s*发送者\\s*\\]\\s*([^【\\[\\n:：]+)"),
            Regex("(?m)^\\s*发送者\\s*[:：]\\s*([^\\n]+)")
        )
        for (p in patterns) {
            val name = p.find(block)?.groupValues?.get(1)?.trim()
                ?.trim('：', ':', '】', ']', ' ')
            if (!name.isNullOrBlank()) return name
        }
        return null
    }
}

data class GroupMessage(
    val content: String,
    val senderId: String,
    val senderName: String,
    val timestamp: Long,
    val isFromMe: Boolean,
    var translatedText: String = "",
    val innerThoughts: String = "",
    var isVoice: Boolean = false,
    var voiceDuration: Int = 0,
    var imageDesc: String = "",
    var isSystem: Boolean = false,
    val groupId: String = ""
)

class GroupMsgAdapter(
    private val msgList: MutableList<GroupMessage>,
    private val myId: String,
    private val context: android.content.Context
) : androidx.recyclerview.widget.RecyclerView.Adapter<GroupMsgAdapter.ViewHolder>() {

    private var currentPlayingPosition: Int = -1
    private var ttsManager: TTSManager? = null

    class ViewHolder(view: android.view.View) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        val tvSystemMsg: android.widget.TextView = view.findViewById(R.id.tvSystemMsg)
        val layoutLeft: android.widget.RelativeLayout = view.findViewById(R.id.layoutLeft)
        val layoutRight: android.widget.RelativeLayout = view.findViewById(R.id.layoutRight)
        val ivAvatarLeft: android.widget.ImageView = view.findViewById(R.id.ivAvatarLeft)
        val ivAvatarRight: android.widget.ImageView = view.findViewById(R.id.ivAvatarRight)
        val tvMsgLeft: android.widget.TextView = view.findViewById(R.id.tvMsgLeft)
        val tvMsgRight: android.widget.TextView = view.findViewById(R.id.tvMsgRight)
        val tvTimeLeft: android.widget.TextView = view.findViewById(R.id.tvTimeLeft)
        val tvTimeRight: android.widget.TextView = view.findViewById(R.id.tvTimeRight)
        val tvReadStatus: android.widget.TextView = view.findViewById(R.id.tvReadStatus)
        val layoutVoiceLeft: android.widget.LinearLayout =
            view.findViewById(R.id.layoutVoiceLeft)
        val tvVoiceDurationLeft: android.widget.TextView =
            view.findViewById(R.id.tvVoiceDurationLeft)
        val tvVoiceToText: android.widget.TextView = view.findViewById(R.id.tvVoiceToText)
        val btnPlayVoiceIcon: android.widget.TextView = view.findViewById(R.id.btnPlayVoiceIcon)
        val tvTranslatedTextLeft: android.widget.TextView =
            view.findViewById(R.id.tvTranslatedTextLeft)
        val ivImageLeft: android.widget.ImageView = view.findViewById(R.id.ivImageLeft)
        val ivImageRight: android.widget.ImageView = view.findViewById(R.id.ivImageRight)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat, parent, false)
        if (ttsManager == null) {
            ttsManager = TTSManager(parent.context.applicationContext)
        }
        return ViewHolder(view)
    }

    private fun callTranslateApi(
        content: String,
        onSuccess: (String) -> Unit,
        onFail: () -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pref =
                    context.getSharedPreferences(
                        "AppConfig",
                        android.content.Context.MODE_PRIVATE
                    )
                var apiUrl = pref.getString("apiUrl", "") ?: ""
                val apiKey = pref.getString("apiKey", "") ?: ""
                val transModel = pref.getString("transModel_global", "") ?: ""
                val modelName = if (transModel.isNotEmpty()) transModel else (pref.getString(
                    "modelName",
                    "gemini-2.5-pro"
                ) ?: "gemini-2.5-pro")
                while (apiUrl.endsWith("/")) apiUrl = apiUrl.dropLast(1)
                if (!apiUrl.endsWith("/chat/completions")) {
                    apiUrl =
                        if (apiUrl.endsWith("/v1")) "$apiUrl/chat/completions" else "$apiUrl/v1/chat/completions"
                }

                val bodyJson = org.json.JSONObject().apply {
                    put("model", modelName)
                    put("temperature", 0.3)
                    put("messages", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("role", "user")
                            put(
                                "content",
                                "请把下面这句话翻译成自然的简体中文。只输出中文译文，不要输出标点占位、不要解释。如果原文无法翻译，请输出：无法翻译。\n\n原文：$content"
                            )
                        })
                    })
                }
                val request = okhttp3.Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(
                        bodyJson.toString()
                            .toRequestBody("application/json".toMediaTypeOrNull())
                    )
                    .build()
                val response = Http.client.newBuilder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build().newCall(request).execute()
                val body = response.use { if (it.isSuccessful) it.body?.string() else null }
                if (body.isNullOrBlank()) {
                    withContext(Dispatchers.Main) { onFail() }
                    return@launch
                }
                val translated = org.json.JSONObject(body).getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()

                if (
                    translated.isBlank() ||
                    translated == "." ||
                    translated == "。" ||
                    translated.length <= 1
                ) {
                    withContext(Dispatchers.Main) { onFail() }
                } else {
                    withContext(Dispatchers.Main) { onSuccess(translated) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onFail() }
            }
        }
    }

    private fun setupTranslateButton(
        holder: ViewHolder,
        msg: GroupMessage,
        position: Int
    ) {
        holder.tvVoiceToText.visibility = android.view.View.VISIBLE
        holder.tvVoiceToText.isClickable = true
        val translationTag = "${msg.groupId}:${msg.senderId}:${msg.timestamp}"
        holder.tvVoiceToText.tag = translationTag
        holder.tvTranslatedTextLeft.visibility = android.view.View.GONE
        holder.tvVoiceToText.text = if (msg.translatedText.isNotEmpty()) "查看翻译" else "翻译"
        holder.tvTranslatedTextLeft.text = msg.translatedText

        holder.tvVoiceToText.setOnClickListener {
            if (holder.tvTranslatedTextLeft.visibility == android.view.View.VISIBLE) {
                holder.tvTranslatedTextLeft.visibility = android.view.View.GONE
                holder.tvVoiceToText.text =
                    if (msg.translatedText.isNotEmpty()) "查看翻译" else "翻译"
                return@setOnClickListener
            }
            if (msg.translatedText.isNotEmpty()) {
                holder.tvTranslatedTextLeft.visibility = android.view.View.VISIBLE
                holder.tvVoiceToText.text = "收起翻译"
            } else {
                holder.tvVoiceToText.text = "翻译中..."
                holder.tvVoiceToText.isClickable = false
                callTranslateApi(
                    content = cleanDialogContent(msg.content),
                    onSuccess = success@{ translated ->
                        try {
                            val db = DatabaseHelper(context).writableDatabase
                            db.execSQL(
                                "UPDATE ChatHistory SET translatedText=? WHERE groupId=? AND senderId=? AND timestamp=?",
                                arrayOf(translated, msg.groupId, msg.senderId, msg.timestamp)
                            )
                        } catch (_: Exception) {
                        }
                        msg.translatedText = translated
                        if (holder.tvVoiceToText.tag != translationTag) return@success
                        holder.tvTranslatedTextLeft.text = translated
                        holder.tvTranslatedTextLeft.visibility = android.view.View.VISIBLE
                        holder.tvVoiceToText.text = "收起翻译"
                        holder.tvVoiceToText.isClickable = true
                    },
                    onFail = fail@{
                        if (holder.tvVoiceToText.tag != translationTag) return@fail
                        holder.tvVoiceToText.text = "翻译失败"
                        holder.tvVoiceToText.isClickable = true
                    }
                )
            }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val msg = msgList[position]
        val isMe = msg.isFromMe
        val msgTime = msg.timestamp
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis
        val yesterdayStart = todayStart - 86400000L

        val currentTime = when {
            msgTime >= todayStart -> java.text.SimpleDateFormat(
                "HH:mm",
                java.util.Locale.getDefault()
            ).format(java.util.Date(msgTime))

            msgTime >= yesterdayStart -> "昨天 " + java.text.SimpleDateFormat(
                "HH:mm",
                java.util.Locale.getDefault()
            ).format(java.util.Date(msgTime))

            else -> java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(msgTime))
        }

        val isLastInGroup =
            position == msgList.size - 1 || msgList[position + 1].isFromMe != msg.isFromMe

        var myAvatarPath = ""
        var aiAvatarPath = ""
        try {
            val db = DatabaseHelper(context).readableDatabase
            if (isMe) {
                val c = db.rawQuery("SELECT myAvatarUri FROM MyProfile", null)
                if (c.moveToFirst()) myAvatarPath = c.getString(0) ?: ""
                c.close()
            } else {
                val c = db.rawQuery(
                    "SELECT avatarUri FROM Contacts WHERE userId=?",
                    arrayOf(msg.senderId)
                )
                if (c.moveToFirst()) aiAvatarPath = c.getString(0) ?: ""
                c.close()
            }
        } catch (e: Exception) {
        }

        if (msg.isSystem) {
            holder.tvSystemMsg.visibility = android.view.View.VISIBLE
            holder.layoutLeft.visibility = android.view.View.GONE
            holder.layoutRight.visibility = android.view.View.GONE
            holder.tvSystemMsg.text = msg.content
        } else {
            holder.tvSystemMsg.visibility = android.view.View.GONE
            if (isMe) {
                holder.layoutRight.visibility = android.view.View.VISIBLE
                holder.layoutLeft.visibility = android.view.View.GONE

                if (msg.imageDesc.isNotEmpty()) {
                    holder.tvMsgRight.visibility = android.view.View.GONE
                    holder.ivImageRight.visibility = android.view.View.VISIBLE
                    try {
                        if (msg.imageDesc.startsWith("http")) {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val url = java.net.URL(msg.imageDesc)
                                    val bitmap =
                                        android.graphics.BitmapFactory.decodeStream(url.openStream())
                                    withContext(Dispatchers.Main) {
                                        holder.ivImageRight.setImageBitmap(
                                            bitmap
                                        )
                                    }
                                } catch (e: Exception) {
                                }
                            }
                        } else {
                            val uri = android.net.Uri.parse(msg.imageDesc)
                            val inputStream = context.contentResolver.openInputStream(uri)
                            val bitmap =
                                android.graphics.BitmapFactory.decodeStream(inputStream)
                            holder.ivImageRight.setImageBitmap(bitmap)
                            inputStream?.close()
                        }
                    } catch (e: Exception) {
                        holder.ivImageRight.setBackgroundColor(android.graphics.Color.DKGRAY)
                    }
                } else {
                    holder.tvMsgRight.visibility = android.view.View.VISIBLE
                    holder.ivImageRight.visibility = android.view.View.GONE
                    holder.tvMsgRight.text = msg.content
                }

                if (isLastInGroup) {
                    holder.tvTimeRight.visibility = android.view.View.VISIBLE
                    holder.tvReadStatus.visibility = android.view.View.VISIBLE
                    holder.tvTimeRight.text = currentTime
                    holder.tvReadStatus.text = "已读"
                } else {
                    holder.tvTimeRight.visibility = android.view.View.GONE
                    holder.tvReadStatus.visibility = android.view.View.GONE
                }
                holder.ivAvatarRight.setImageBitmap(null)
                if (myAvatarPath.isNotEmpty()) {
                    try {
                        val bitmap = if (myAvatarPath.startsWith("/")) {
                            android.graphics.BitmapFactory.decodeFile(myAvatarPath)
                        } else {
                            context.contentResolver.openInputStream(android.net.Uri.parse(myAvatarPath))
                                ?.use { android.graphics.BitmapFactory.decodeStream(it) }
                        }
                        if (bitmap != null) holder.ivAvatarRight.setImageBitmap(bitmap)
                    } catch (e: Exception) {
                    }
                }
            } else {
                holder.layoutLeft.visibility = android.view.View.VISIBLE
                holder.layoutRight.visibility = android.view.View.GONE

                if (isLastInGroup) {
                    holder.tvTimeLeft.visibility = android.view.View.VISIBLE
                    holder.tvTimeLeft.text = currentTime
                } else {
                    holder.tvTimeLeft.visibility = android.view.View.GONE
                }

                holder.ivAvatarLeft.setImageBitmap(null)
                holder.ivAvatarLeft.setBackgroundColor(android.graphics.Color.parseColor("#DDDDDD"))
                if (aiAvatarPath.isNotEmpty()) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val bitmap = if (aiAvatarPath.startsWith("/")) {
                                android.graphics.BitmapFactory.decodeFile(aiAvatarPath)
                            } else {
                                context.contentResolver.openInputStream(android.net.Uri.parse(aiAvatarPath))
                                    ?.use { android.graphics.BitmapFactory.decodeStream(it) }
                            }
                            withContext(Dispatchers.Main) {
                                if (holder.adapterPosition == position && bitmap != null) {
                                    holder.ivAvatarLeft.setImageBitmap(bitmap)
                                }
                            }
                        } catch (e: Exception) {}
                    }
                }

                holder.ivAvatarLeft.setOnClickListener {
                    try {
                        val innerVoice =
                            if (msg.innerThoughts.isNotEmpty()) msg.innerThoughts else "（这家伙脑子里一片空白...或者他把真心话藏得太深了）"
                        val layout = android.widget.LinearLayout(context).apply {
                            orientation = android.widget.LinearLayout.VERTICAL
                            setPadding(60, 60, 60, 60)
                            setBackgroundColor(android.graphics.Color.parseColor("#E6111111"))
                        }
                        val tvTitle = android.widget.TextView(context).apply {
                            text = "💭 窃听心声 (${msg.senderName})"
                            setTextColor(android.graphics.Color.parseColor("#888888"))
                            textSize = 12f
                            setPadding(0, 0, 0, 30)
                        }
                        val tvContent = android.widget.TextView(context).apply {
                            text = innerVoice
                            setTextColor(android.graphics.Color.WHITE)
                            textSize = 16f
                            setTypeface(null, android.graphics.Typeface.ITALIC)
                        }
                        layout.addView(tvTitle)
                        layout.addView(tvContent)
                        val dialog =
                            android.app.AlertDialog.Builder(context).setView(layout).create()
                        dialog.window?.setBackgroundDrawable(
                            android.graphics.drawable.ColorDrawable(
                                android.graphics.Color.TRANSPARENT
                            )
                        )
                        dialog.show()
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(
                            context,
                            "窃听失败！",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                // 读取头衔
                var memberTitle = ""
                try {
                    DatabaseHelper(context).readableDatabase.rawQuery(
                        "SELECT IFNULL(title,'') FROM GroupMembers WHERE groupId=? AND memberId=?",
                        arrayOf(msg.groupId, msg.senderId)
                    ).use { c -> if (c.moveToFirst()) memberTitle = c.getString(0) ?: "" }
                } catch (_: Exception) {}

                val displayName = if (memberTitle.isNotEmpty()) "${msg.senderName} [$memberTitle]" else msg.senderName
                val nameSpan = android.text.SpannableString("$displayName\n")
                nameSpan.setSpan(
                    android.text.style.ForegroundColorSpan(android.graphics.Color.GRAY),
                    0,
                    nameSpan.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                nameSpan.setSpan(
                    android.text.style.RelativeSizeSpan(0.8f),
                    0,
                    nameSpan.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                val fullText = android.text.TextUtils.concat(nameSpan, msg.content)

                if (msg.imageDesc.isNotEmpty()) {
                    holder.tvMsgLeft.visibility = android.view.View.GONE
                    holder.layoutVoiceLeft.visibility = android.view.View.GONE
                    holder.ivImageLeft.visibility = android.view.View.VISIBLE
                    holder.tvVoiceToText.visibility = android.view.View.GONE
                    holder.tvTranslatedTextLeft.visibility = android.view.View.GONE
                    try {
                        if (msg.imageDesc.startsWith("http")) {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val url = java.net.URL(msg.imageDesc)
                                    val bitmap =
                                        android.graphics.BitmapFactory.decodeStream(url.openStream())
                                    withContext(Dispatchers.Main) {
                                        holder.ivImageLeft.setImageBitmap(
                                            bitmap
                                        )
                                    }
                                } catch (e: Exception) {
                                }
                            }
                        } else {
                            val uri = android.net.Uri.parse(msg.imageDesc)
                            val inputStream = context.contentResolver.openInputStream(uri)
                            holder.ivImageLeft.setImageBitmap(
                                android.graphics.BitmapFactory.decodeStream(
                                    inputStream
                                )
                            )
                            inputStream?.close()
                        }
                    } catch (e: Exception) {
                        holder.ivImageLeft.setBackgroundColor(android.graphics.Color.DKGRAY)
                    }
                } else if (msg.isVoice) {
                    holder.tvMsgLeft.visibility = android.view.View.VISIBLE
                    holder.tvMsgLeft.text = fullText
                    holder.ivImageLeft.visibility = android.view.View.GONE
                    holder.layoutVoiceLeft.visibility = android.view.View.VISIBLE
                    holder.tvVoiceDurationLeft.text = "${msg.voiceDuration}\""
                    holder.btnPlayVoiceIcon.text =
                        if (currentPlayingPosition == position) "⏸️" else "▶️"

                    holder.layoutVoiceLeft.setOnClickListener {
                        if (currentPlayingPosition == position) {
                            currentPlayingPosition = -1
                            notifyItemChanged(position)
                        } else {
                            val oldPos = currentPlayingPosition
                            currentPlayingPosition = position
                            if (oldPos != -1) notifyItemChanged(oldPos)
                            notifyItemChanged(position)
                            try {
                                val charPref = context.getSharedPreferences(
                                    "AppConfig",
                                    android.content.Context.MODE_PRIVATE
                                )
                                val voiceId =
                                    charPref.getString("voiceId_${msg.senderId}", "") ?: ""
                                if (voiceId.isEmpty()) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "voiceId为空",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    ttsManager?.speak(msg.content, voiceId)
                                    android.widget.Toast.makeText(
                                        context,
                                        "正在播放",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(
                                    context,
                                    "播放失败",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    setupTranslateButton(holder, msg, position)
                } else {
                    holder.tvMsgLeft.visibility = android.view.View.VISIBLE
                    holder.layoutVoiceLeft.visibility = android.view.View.GONE
                    holder.ivImageLeft.visibility = android.view.View.GONE
                    holder.tvMsgLeft.text = fullText
                    setupTranslateButton(holder, msg, position)
                }
            }
        }

        holder.itemView.setOnLongClickListener {
            val currentPosition = holder.adapterPosition
            if (currentPosition == RecyclerView.NO_ID.toInt()) return@setOnLongClickListener true
            val currentMsg = msgList[currentPosition]
            val options = if (currentMsg.isFromMe) arrayOf("撤回消息", "删除消息") else arrayOf("删除消息")
            android.app.AlertDialog.Builder(context)
                .setItems(options) { _, which ->
                    val option = options[which]
                    when (option) {
                        "删除消息" -> {
                            try {
                                val db = DatabaseHelper(context).writableDatabase
                                db.delete(
                                    "ChatHistory", "groupId=? AND senderId=? AND timestamp=?",
                                    arrayOf(currentMsg.groupId, currentMsg.senderId, currentMsg.timestamp.toString())
                                )
                            } catch (_: Exception) {}
                            if (currentPosition < msgList.size) {
                                msgList.removeAt(currentPosition)
                                notifyItemRemoved(currentPosition)
                            }
                        }
                        "撤回消息" -> {
                            try {
                                val db = DatabaseHelper(context).writableDatabase
                                db.delete(
                                    "ChatHistory", "groupId=? AND senderId=? AND timestamp=?",
                                    arrayOf(currentMsg.groupId, currentMsg.senderId, currentMsg.timestamp.toString())
                                )
                            } catch (_: Exception) {}
                            if (currentPosition < msgList.size) {
                                msgList[currentPosition] = currentMsg.copy(
                                    content = "你撤回了一条消息",
                                    translatedText = "",
                                    innerThoughts = ""
                                )
                                notifyItemChanged(currentPosition)
                            }
                        }
                    }
                }
                .show()
            true
        }
    }

    override fun getItemCount() = msgList.size
}
