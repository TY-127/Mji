package com.moon.aiphone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment

class ProfileFragment : Fragment() {
    private lateinit var tvName: TextView
    private lateinit var tvId: TextView
    private lateinit var ivAvatar: ImageView

    private val pickMainWallpaper = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                requireContext().getSharedPreferences("AppConfig", android.content.Context.MODE_PRIVATE)
                    .edit().putString("mainBg", uri.toString()).apply()
                requireContext().sendBroadcast(Intent("MAIN_BG_CHANGED"))
                android.widget.Toast.makeText(requireContext(), "壁纸已设置", android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_profile, container, false)

        tvName = view.findViewById(R.id.tvMyName)
        tvId = view.findViewById(R.id.tvMyId)
        ivAvatar = view.findViewById(R.id.ivMyAvatar)

        view.findViewById<RelativeLayout>(R.id.layoutUserInfo).setOnClickListener {
            startActivity(Intent(requireContext(), EditProfileActivity::class.java))
        }

        // 长按头像换壁纸
        ivAvatar.setOnLongClickListener {
            val options = arrayOf("🖼️ 更换主界面壁纸", "🗑️ 清除壁纸（恢复默认）")
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("主界面背景")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> pickMainWallpaper.launch(arrayOf("image/*"))
                        1 -> {
                            requireContext().getSharedPreferences("AppConfig", android.content.Context.MODE_PRIVATE)
                                .edit().remove("mainBg").apply()
                            requireContext().sendBroadcast(Intent("MAIN_BG_CHANGED"))
                            android.widget.Toast.makeText(requireContext(), "已清除壁纸", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }.show()
            true
        }

        view.findViewById<RelativeLayout>(R.id.layoutLedger).setOnClickListener {
            startActivity(Intent(requireContext(), LedgerActivity::class.java))
        }
        view.findViewById<RelativeLayout>(R.id.layoutSport).setOnClickListener {
            startActivity(Intent(requireContext(), SportActivity::class.java))
        }
        view.findViewById<RelativeLayout>(R.id.layoutHealth).setOnClickListener {
            startActivity(Intent(requireContext(), HealthActivity::class.java))
        }

        // ── APP 主题入口 ──────────────────────────────────────
        // 在 fragment_profile.xml 的服务/运动/健康 后面加一个 layoutTheme 行
        // 如果暂时不想改 XML，注释掉下面这行也可以先编译通过
        view.findViewById<RelativeLayout?>(R.id.layoutTheme)?.setOnClickListener {
            startActivity(Intent(requireContext(), AppThemeSettingsActivity::class.java))
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        try {
            val db = DatabaseHelper(requireContext()).readableDatabase
            val cursor = db.query("MyProfile", null, null, null, null, null, null)
            if (cursor.moveToFirst()) {
                val name = cursor.getSafeString("myName").ifEmpty { "我" }
                val myId = cursor.getSafeString("myId")
                val avatarUri = cursor.getSafeString("myAvatarUri")
                tvName.text = name
                tvId.text = "用户ID: $myId"
                ivAvatar.setImageDrawable(null)
                ivAvatar.setBackgroundColor(android.graphics.Color.LTGRAY)
                if (avatarUri.isNotEmpty()) {
                    try {
                        val bitmap = if (avatarUri.startsWith("/"))
                            android.graphics.BitmapFactory.decodeFile(avatarUri)
                        else requireContext().contentResolver
                            .openInputStream(Uri.parse(avatarUri))
                            ?.use { android.graphics.BitmapFactory.decodeStream(it) }
                        if (bitmap != null) {
                            ivAvatar.setImageBitmap(bitmap); ivAvatar.background = null
                        } else ivAvatar.setImageResource(android.R.drawable.sym_def_app_icon)
                    } catch (_: Exception) {
                        ivAvatar.setImageResource(android.R.drawable.sym_def_app_icon)
                    }
                } else ivAvatar.setImageResource(android.R.drawable.sym_def_app_icon)
            }
            cursor.close()
        } catch (_: Exception) {}
    }
}