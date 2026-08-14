package com.moon.aiphone

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.yalantis.ucrop.UCrop
import java.io.File

class GroupSettingsActivity : AppCompatActivity() {

    private var groupId = ""
    private var groupName = ""
    private var myId = ""
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private val cropAvatar = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val uri = UCrop.getOutput(data) ?: return@registerForActivityResult
            try {
                val uriStr = uri.toString()
                val path = if (uriStr.startsWith("file://")) uriStr.removePrefix("file://") else uriStr
                DatabaseHelper(this).writableDatabase.execSQL(
                    "UPDATE GroupChats SET avatarUri=? WHERE groupId=?", arrayOf(path, groupId)
                )
                loadAvatar(path)
                Toast.makeText(this, "群头像已更新", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
    }

    private val pickAvatar = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val dest = Uri.fromFile(File(filesDir, "groupavatar_${System.currentTimeMillis()}.jpg"))
            val opts = UCrop.Options().apply { setCircleDimmedLayer(true); setToolbarTitle("裁剪群头像") }
            cropAvatar.launch(UCrop.of(uri, dest).withAspectRatio(1f, 1f).withMaxResultSize(400, 400).withOptions(opts).getIntent(this))
        }
    }

    private var ivAvatar: ImageView? = null
    private var switchObserve: Switch? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.init(this)
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        groupId = intent.getStringExtra("GROUP_ID") ?: ""
        groupName = intent.getStringExtra("GROUP_NAME") ?: "群聊"
        myId = getSharedPreferences("AppConfig", MODE_PRIVATE).getString("myId", "me") ?: "me"

        if (groupId.isBlank()) {
            Toast.makeText(this, "群聊ID异常", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(ThemeManager.getColor("--app-bg", Color.parseColor("#F2F2F6")))
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(-1, -2)
        }
        scroll.addView(root)

        // 顶部栏
        val topBar = android.widget.RelativeLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(50))
            setBackgroundColor(ThemeManager.getColor("--header-bg", Color.WHITE))
            elevation = 4f
        }
        topBar.addView(TextView(this).apply {
            text = "‹"; textSize = 28f; setTextColor(Color.BLACK); setPadding(dp(16), 0, dp(16), dp(4))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = android.widget.RelativeLayout.LayoutParams(-2, -1).also { it.addRule(android.widget.RelativeLayout.ALIGN_PARENT_START) }
            setOnClickListener { finish() }
        })
        topBar.addView(TextView(this).apply {
            text = "群聊设置"; textSize = 17f; setTypeface(null, Typeface.BOLD); setTextColor(Color.BLACK)
            layoutParams = android.widget.RelativeLayout.LayoutParams(-2, -2).also { it.addRule(android.widget.RelativeLayout.CENTER_IN_PARENT) }
        })
        root.addView(topBar)

        // 群头像
        val avatarCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(20), dp(16), dp(20))
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(12) }
        }
        ivAvatar = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(80), dp(80))
            scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setOval(0, 0, v.width, v.height) }
            }
            setBackgroundColor(Color.parseColor("#DDDDDD"))
        }
        avatarCard.addView(ivAvatar)
        avatarCard.addView(TextView(this).apply {
            text = "点击更换群头像"; textSize = 12f; setTextColor(Color.GRAY); setPadding(0, dp(8), 0, 0)
        })
        avatarCard.setOnClickListener { pickAvatar.launch(arrayOf("image/*")) }
        root.addView(avatarCard)

        // 加载当前头像
        try {
            val c = DatabaseHelper(this).readableDatabase.rawQuery("SELECT avatarUri FROM GroupChats WHERE groupId=?", arrayOf(groupId))
            if (c.moveToFirst()) { val p = c.getString(0) ?: ""; if (p.isNotEmpty()) loadAvatar(p) }
            c.close()
        } catch (_: Exception) {}

        // 群名
        addSection(root, "基本信息")
        val nameCard = createCard(root)
        addRow(nameCard, "群聊名称", rightText = "$groupName ›") {
            val et = EditText(this).apply { setText(groupName); setPadding(dp(20), dp(16), dp(20), dp(16)) }
            AlertDialog.Builder(this).setTitle("修改群名").setView(et)
                .setPositiveButton("保存") { _, _ ->
                    val newName = et.text.toString().trim()
                    if (newName.isNotEmpty()) {
                        DatabaseHelper(this).writableDatabase.execSQL("UPDATE GroupChats SET groupName=? WHERE groupId=?", arrayOf(newName, groupId))
                        groupName = newName
                        Toast.makeText(this, "群名已保存", Toast.LENGTH_SHORT).show()
                        recreate()
                    }
                }.setNegativeButton("取消", null).show()
        }

        // 仅围观模式
        addSection(root, "群模式")
        val modeCard = createCard(root)
        val isObserve = try {
            val c = DatabaseHelper(this).readableDatabase.rawQuery("SELECT isObserveOnly FROM GroupChats WHERE groupId=?", arrayOf(groupId))
            val v = if (c.moveToFirst()) c.getInt(0) == 1 else false; c.close(); v
        } catch (_: Exception) { false }
        switchObserve = Switch(this).apply { isChecked = isObserve }
        addSwitchRow(modeCard, "仅围观模式（用户不能发言）", switchObserve!!) { checked ->
            DatabaseHelper(this).writableDatabase.execSQL("UPDATE GroupChats SET isObserveOnly=? WHERE groupId=?", arrayOf(if (checked) 1 else 0, groupId))
            Toast.makeText(this, if (checked) "已开启围观模式" else "已关闭围观模式", Toast.LENGTH_SHORT).show()
        }

        // 成员管理（昵称+头衔）
        addSection(root, "成员管理")
        val memberCard = createCard(root)
        try {
            val db = DatabaseHelper(this).readableDatabase
            val c = db.rawQuery(
                """
SELECT memberId, memberName, IFNULL(nickname,''), IFNULL(title,''), IFNULL(isOwner,0)
FROM GroupMembers
WHERE groupId=? AND isAi=1
ORDER BY IFNULL(isOwner,0) DESC, id ASC
""".trimIndent(),
                arrayOf(groupId)
            )
            var first = true
            while (c.moveToNext()) {
                val memberId = c.getString(0) ?: ""
                val memberName = c.getString(1) ?: ""
                val nickname = c.getString(2)
                val title = c.getString(3)
                val isOwner = c.getInt(4) == 1
                val displayName = buildString {
                    if (nickname.isNotEmpty()) append("$memberName（$nickname）") else append(memberName)
                    if (isOwner) append(" 👑")
                }
                val displayRight = if (title.isNotEmpty()) "[$title] ›" else "设置头衔 ›"
                if (!first) addDivider(memberCard)
                first = false
                addRow(memberCard, displayName, rightText = displayRight) {
                    showMemberEditDialog(memberId, memberName, nickname, title)
                }
            }
            c.close()
        } catch (_: Exception) {}

        // 危险操作
        addSection(root, "危险操作")
        val dangerCard = createCard(root)
        addRow(dangerCard, "清空聊天记录", textColor = "#FF3B30") {
            AlertDialog.Builder(this).setTitle("确认清空").setMessage("将删除本群所有聊天记录，不可恢复。")
                .setPositiveButton("清空") { _, _ ->
                    DatabaseHelper(this).writableDatabase.delete("ChatHistory", "groupId=?", arrayOf(groupId))
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
                }.setNegativeButton("取消", null).show()
        }
        addDivider(dangerCard)
        addRow(dangerCard, "解散并删除群聊", textColor = "#FF3B30") {
            AlertDialog.Builder(this).setTitle("解散群聊").setMessage("确定解散并删除这个群聊吗？")
                .setPositiveButton("删除") { _, _ ->
                    val db = DatabaseHelper(this).writableDatabase
                    try {
                        val gid = groupId.trim()

                        val a = db.delete("GroupChats", "TRIM(IFNULL(groupId,''))=?", arrayOf(gid))
                        val b = db.delete("GroupMembers", "TRIM(IFNULL(groupId,''))=?", arrayOf(gid))
                        val c = db.delete("ChatHistory", "TRIM(IFNULL(groupId,''))=?", arrayOf(gid))

                        try {
                            db.delete("PendingAiMessages", "TRIM(IFNULL(groupId,''))=?", arrayOf(gid))
                        } catch (_: Exception) {}

                        try {
                            db.delete("Groups", "TRIM(IFNULL(groupId,''))=?", arrayOf(gid))
                        } catch (_: Exception) {}

                        android.util.Log.e("GROUP_DELETE", "GroupChats=$a GroupMembers=$b ChatHistory=$c groupId=$gid")

                    } catch (e: Exception) {
                        android.util.Log.e("GROUP_DELETE", e.stackTraceToString())
                    }

                    sendBroadcast(Intent("CYBER_NEW_MSG"))
                    setResult(RESULT_OK)

                    Toast.makeText(this, "群聊已解散", Toast.LENGTH_SHORT).show()
                    finish()
                }.setNegativeButton("取消", null).show()
        }

        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(60)) })
        setContentView(scroll)
    }

    private fun showMemberEditDialog(memberId: String, memberName: String, currentNickname: String, currentTitle: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        layout.addView(TextView(this).apply { text = "群昵称"; textSize = 13f; setTextColor(Color.GRAY) })
        val etNickname = EditText(this).apply {
            setText(currentNickname); hint = "留空则显示原名"
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(12) }
        }
        layout.addView(etNickname)
        layout.addView(TextView(this).apply { text = "群头衔"; textSize = 13f; setTextColor(Color.GRAY) })
        val etTitle = EditText(this).apply {
            setText(currentTitle); hint = "例如：群主、小可爱、打手"
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        layout.addView(etTitle)

        AlertDialog.Builder(this).setTitle("编辑 $memberName").setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val newNickname = etNickname.text.toString().trim()
                val newTitle = etTitle.text.toString().trim()
                try {
                    DatabaseHelper(this).writableDatabase.execSQL(
                        "UPDATE GroupMembers SET nickname=?, title=? WHERE groupId=? AND memberId=?",
                        arrayOf(newNickname, newTitle, groupId, memberId)
                    )
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                    recreate()
                } catch (_: Exception) {}
            }.setNegativeButton("取消", null).show()
    }

    private fun loadAvatar(path: String) {
        try {
            val bmp = if (path.startsWith("/")) android.graphics.BitmapFactory.decodeFile(path)
            else contentResolver.openInputStream(Uri.parse(path))?.use { android.graphics.BitmapFactory.decodeStream(it) }
            if (bmp != null) ivAvatar?.setImageBitmap(bmp)
        } catch (_: Exception) {}
    }

    private fun addSection(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text; textSize = 13f; setTextColor(Color.GRAY)
            setPadding(dp(16), dp(16), dp(16), dp(8))
        })
    }

    private fun createCard(parent: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(ThemeManager.getColor("--profile-item-bg", Color.WHITE)); parent.addView(this)
    }

    private fun addDivider(parent: LinearLayout) = parent.addView(View(this).apply {
        setBackgroundColor(Color.parseColor("#EEEEEE"))
        layoutParams = LinearLayout.LayoutParams(-1, 1).also { it.marginStart = dp(16) }
    })

    private fun addRow(parent: LinearLayout, text: String, rightText: String = "", textColor: String = "#000000", onClick: () -> Unit) {
        val row = android.widget.RelativeLayout(this).apply {
            setPadding(dp(16), dp(15), dp(16), dp(15)); isClickable = true; isFocusable = true
            val out = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
            setBackgroundResource(out.resourceId)
        }
        row.addView(TextView(this).apply {
            this.text = text; textSize = 16f; setTextColor(Color.parseColor(textColor))
            layoutParams = android.widget.RelativeLayout.LayoutParams(-2, -2).also { it.addRule(android.widget.RelativeLayout.CENTER_VERTICAL) }
        })
        if (rightText.isNotEmpty()) row.addView(TextView(this).apply {
            this.text = rightText; textSize = 14f; setTextColor(Color.GRAY)
            layoutParams = android.widget.RelativeLayout.LayoutParams(-2, -2).also {
                it.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END)
                it.addRule(android.widget.RelativeLayout.CENTER_VERTICAL)
            }
        })
        row.setOnClickListener { onClick() }
        parent.addView(row)
    }

    private fun addSwitchRow(parent: LinearLayout, text: String, sw: Switch, onChange: (Boolean) -> Unit) {
        val row = android.widget.RelativeLayout(this).apply { setPadding(dp(16), dp(10), dp(16), dp(10)) }
        row.addView(TextView(this).apply {
            this.text = text; textSize = 16f; setTextColor(Color.BLACK)
            layoutParams = android.widget.RelativeLayout.LayoutParams(-2, -2).also { it.addRule(android.widget.RelativeLayout.CENTER_VERTICAL) }
        })
        sw.layoutParams = android.widget.RelativeLayout.LayoutParams(-2, -2).also {
            it.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END)
            it.addRule(android.widget.RelativeLayout.CENTER_VERTICAL)
        }
        sw.setOnCheckedChangeListener { _, c -> onChange(c) }
        row.addView(sw); parent.addView(row)
    }
}