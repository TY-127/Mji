package com.moon.aiphone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView

class WechatActivity : AppCompatActivity() {

    private val badgeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { updateBottomBadge() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wechat)
        supportActionBar?.hide()

        ThemeManager.init(this)
        applyTheme()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, MessageListFragment())
                .commit()
        }

        findViewById<BottomNavigationView>(R.id.bottom_nav).setOnItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.nav_msg       -> MessageListFragment()
                R.id.nav_contact   -> ContactFragment()
                R.id.nav_discovery -> DiscoverFragment()
                R.id.nav_me        -> ProfileFragment()
                else               -> return@setOnItemSelectedListener false
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit()
            true
        }
    }

    fun applyTheme() {
        val ivBg      = findViewById<android.widget.ImageView>(R.id.ivMainBg) ?: return
        val vDim      = findViewById<android.view.View>(R.id.vMainBgDim) ?: return
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav) ?: return

        // 背景图：主题包变量 > 用户手动选的壁纸
        val themeBgUri = ThemeManager.get("--app-bg-image")
        val manualBgUri = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            .getString("mainBg", "") ?: ""
        val bgUri = if (themeBgUri.isNotEmpty()) themeBgUri else manualBgUri

        if (bgUri.isNotEmpty()) {
            val bitmap = try {
                if (bgUri.startsWith("/"))
                    android.graphics.BitmapFactory.decodeFile(bgUri)
                else
                    contentResolver.openInputStream(android.net.Uri.parse(bgUri))
                        ?.use { android.graphics.BitmapFactory.decodeStream(it) }
            } catch (_: Exception) { null }

            if (bitmap != null) {
                ivBg.setImageBitmap(bitmap)
                ivBg.visibility = android.view.View.VISIBLE
                vDim.visibility = android.view.View.VISIBLE
            } else {
                ivBg.visibility = android.view.View.GONE
                vDim.visibility = android.view.View.GONE
            }
        } else {
            ivBg.visibility = android.view.View.GONE
            vDim.visibility = android.view.View.GONE
            // 用 --app-bg 颜色做整体背景
            val bgColor = ThemeManager.getColor("--app-bg", Color.parseColor("#F7F7F7"))
            window.decorView.setBackgroundColor(bgColor)
        }

        ThemeManager.applyBottomNav(bottomNav)
    }

    private fun updateBottomBadge() {
        try {
            val db = DatabaseHelper(this).readableDatabase
            val cur = db.rawQuery("""
                SELECT COUNT(*) FROM ChatHistory
                WHERE isFromMe=0 AND isRead=0
                AND (
                    (IFNULL(groupId,'')='' AND aiId IN (SELECT userId FROM Contacts))
                    OR
                    (IFNULL(groupId,'')<>'' AND groupId IN (
                        SELECT groupId FROM GroupChats WHERE IFNULL(isDisbanded,0)=0
                    ))
                )""".trimIndent(), null)
            var unread = 0
            if (cur.moveToFirst()) unread = cur.getInt(0)
            cur.close()

            val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
            if (unread > 0) {
                val badge = bottomNav.getOrCreateBadge(R.id.nav_msg)
                badge.number = unread
                badge.backgroundColor = Color.RED
                badge.badgeTextColor = Color.WHITE
                badge.isVisible = true
            } else {
                bottomNav.removeBadge(R.id.nav_msg)
            }
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        ThemeManager.init(this)
        applyTheme()
        try {
            ContextCompat.registerReceiver(
                this, badgeReceiver,
                IntentFilter("CYBER_NEW_MSG"), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            updateBottomBadge()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(badgeReceiver) } catch (_: Exception) {}
    }
}