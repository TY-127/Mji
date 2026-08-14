package com.moon.aiphone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

class PatienceService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val checkInterval = 60 * 1000L
    private val CHANNEL_ID = "patience_channel"

    private val checkRunnable = object : Runnable {
        override fun run() {
            checkAllContacts()
            handler.postDelayed(this, checkInterval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("后台运行中")
            .setContentText("角色随时待命")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(1, notification)
        handler.post(checkRunnable)
        Log.d("PatienceService", "=== 自动发消息服务已启动 ===")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(checkRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "耐心值服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun checkAllContacts() {
        try {
            val db = DatabaseHelper(this).readableDatabase
            val cursor = db.rawQuery(
                """
    SELECT userId, realName, patience
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
            )

            cursor.use {
                while (it.moveToNext()) {
                    val aiId = it.getString(0) ?: ""
                    val aiName = it.getString(1) ?: "未知"
                    val patience = it.getInt(2)

                    if (aiId.isNotBlank()) {
                        checkAndTrigger(aiId, aiName, patience)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PatienceService", "联系人查库报错: ", e)
        }
    }

    // 记录正在触发中的aiId，防止异步写库前重复触发
    private val triggeringSet = mutableSetOf<String>()

    private fun checkAndTrigger(aiId: String, aiName: String, patienceMinutes: Int) {
        try {
            if (aiId.isBlank()) return
            if (patienceMinutes <= 0) return
            // 如果这个AI已经在触发中，跳过
            if (triggeringSet.contains(aiId)) return

            val db = DatabaseHelper(this).readableDatabase
            val cursor = db.rawQuery(
                "SELECT timestamp, isFromMe FROM ChatHistory WHERE aiId=? AND IFNULL(groupId,'')='' ORDER BY timestamp DESC LIMIT 1",
                arrayOf(aiId)
            )
            val lastTime: Long
            val lastIsFromMe: Boolean
            if (cursor.moveToFirst()) {
                lastTime = cursor.getLong(0)
                lastIsFromMe = cursor.getInt(1) == 1
            } else {
                lastTime = 0L
                lastIsFromMe = false
            }
            cursor.close()
            if (!lastIsFromMe) return

            val now = System.currentTimeMillis()
            val diffMinutes = if (lastTime == 0L) 0 else (now - lastTime) / 1000 / 60

            Log.d("PatienceService", "检查 $aiName : 距上次 $diffMinutes 分钟, 阈值 $patienceMinutes 分钟")

            if (lastTime > 0L && diffMinutes >= patienceMinutes) {
                val recentTriggerWindow = (patienceMinutes * 60 * 1000L).coerceAtLeast(10 * 60 * 1000L)
                val recentlyTriggered = try {
                    db.rawQuery(
                        "SELECT triggeredAt FROM PendingAiMessages WHERE aiId=? ORDER BY triggeredAt DESC LIMIT 1",
                        arrayOf(aiId)
                    ).use { c ->
                        c.moveToFirst() && now - c.getLong(0) < recentTriggerWindow
                    }
                } catch (_: Exception) { false }
                if (recentlyTriggered) {
                    Log.d("PatienceService", "跳过 $aiName：最近已触发过主动消息")
                    return
                }
                // 检查两种拉黑状态，任意一种都不发消息
                val blockPref = getSharedPreferences("BlockList", Context.MODE_PRIVATE)
                val isBlockedByUser = blockPref.getBoolean(aiId, false)
                val isBlockedByAi = blockPref.getBoolean("ai_blocks_user_$aiId", false)
                if (isBlockedByUser || isBlockedByAi) {
                    Log.d("PatienceService", "跳过 $aiName：拉黑状态中")
                    return
                }
                Log.d("PatienceService", "!!! 触发 $aiName 的主动消息 !!!")
                triggeringSet.add(aiId)
                triggerAiMessage(aiId, aiName)
            }
        } catch (e: Exception) {
            Log.e("PatienceService", "检测触发条件时报错: ", e)
        }
    }

    private fun triggerAiMessage(aiId: String, aiName: String) {
        try {
            // 不调API，只记录"该发消息了"的时间点和模拟发送时间
            val db = DatabaseHelper(this).writableDatabase
            val pref = getSharedPreferences("AppConfig", MODE_PRIVATE)

            // 读取上条私聊消息时间；只有用户最后发言后，才允许进入主动消息队列
            val lastRow = DatabaseHelper(this).readableDatabase.rawQuery(
                "SELECT timestamp, isFromMe FROM ChatHistory WHERE aiId=? AND IFNULL(groupId,'')='' ORDER BY timestamp DESC LIMIT 1",
                arrayOf(aiId)
            ).use { c ->
                if (c.moveToFirst()) c.getLong(0) to (c.getInt(1) == 1) else System.currentTimeMillis() to false
            }
            if (!lastRow.second) {
                triggeringSet.remove(aiId)
                return
            }
            val lastMsgTime = lastRow.first

            // 读取耐心值
            val patience = DatabaseHelper(this).readableDatabase.rawQuery(
                "SELECT patience FROM Contacts WHERE userId=?", arrayOf(aiId)
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 60 }

            if (patience <= 0) {
                triggeringSet.remove(aiId)
                return
            }

            // 模拟发送时间 = 上条消息时间 + 耐心值（分钟）
            val simulatedTime = lastMsgTime + patience * 60 * 1000L

            // 存入待发队列
            val cv = android.content.ContentValues().apply {
                put("aiId", aiId)
                put("simulatedTime", simulatedTime)
                put("triggeredAt", System.currentTimeMillis())
                put("isDone", 0)
            }
            try {
                db.execSQL("CREATE TABLE IF NOT EXISTS PendingAiMessages (id INTEGER PRIMARY KEY AUTOINCREMENT, aiId TEXT, simulatedTime INTEGER, triggeredAt INTEGER, isDone INTEGER DEFAULT 0)")
            } catch (_: Exception) {}
            val exists = db.rawQuery(
                """
    SELECT id
    FROM PendingAiMessages
    WHERE aiId=?
      AND isDone=0
    LIMIT 1
    """.trimIndent(),
                arrayOf(aiId)
            ).use { c ->
                c.moveToFirst()
            }

            if (!exists) {
                db.insert("PendingAiMessages", null, cv)
                Log.d("PatienceService", "$aiName 已加入待发队列，模拟时间：$simulatedTime")
            } else {
                Log.d("PatienceService", "$aiName 已有待发消息，跳过重复入队")
            }
        } catch (e: Exception) {
            Log.e("PatienceService", "存待发标记失败: ", e)
        } finally {
            triggeringSet.remove(aiId)
        }
    }

    private fun sendMessageNotification(aiName: String, message: String, aiId: String) {
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("AI_ID", aiId) // 修复了大小写匹配
            putExtra("AI_NAME", aiName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(this, aiId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(aiName)
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(aiId.hashCode(), notification)
    }
}
