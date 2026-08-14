package com.moon.aiphone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.Context
import android.os.IBinder
import android.os.Handler
import android.os.Looper

class MusicService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastSong = ""
    private var isSyncingWorldBook = false
    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            try {
                sendBroadcast(Intent("MUSIC_KEEP_PLAYING"))
            } catch (e: Exception) {}
            handler.postDelayed(this, 2000)
        }
    }

    private val syncSongRunnable = object : Runnable {
        override fun run() {
            try {
                val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                val song = (pref.getString("currentSong", "") ?: "")
                    .replace("▶", "").replace("⏸", "").replace("▷", "").trim()
                android.util.Log.d("WidgetDebug", "读到歌名: $song")
                val artist = pref.getString("currentSongArtist", "") ?: ""
                val comments = pref.getString("currentSongComments", "") ?: ""

                if (song.isNotEmpty() && song != lastSong) {
                    lastSong = song
                    // 找出所有正在一起听歌的角色，自动更新世界书
                    if (isSyncingWorldBook) return

                    isSyncingWorldBook = true

                    Thread {
                        try {
                            val db = DatabaseHelper(this@MusicService).writableDatabase
                            val cur = db.rawQuery(
                                "SELECT DISTINCT aiId FROM UserWorldBook WHERE keyword='正在一起听歌'",
                                null
                            )
                            val aiIds = mutableListOf<String>()
                            while (cur.moveToNext()) {
                                aiIds.add(cur.getString(0))
                            }
                            cur.close()

                            val content = """
                                【当前共同听歌状态】
                                你和用户此刻正在同时听的歌曲是：《$song》- $artist
                                注意：这是用户实际播放的歌曲，不是你猜测或推断的，必须以此为准。
                                不得替换成其他歌曲名称。
                                你能听到这首歌，感受到它的旋律和情绪，请根据《$song》这首歌的风格和情绪来聊天。
                                热门评论：
                                $comments
                            """.trimIndent()

                            for (aiId in aiIds) {
                                db.execSQL(
                                    "DELETE FROM UserWorldBook WHERE aiId=? AND keyword='正在一起听歌'",
                                    arrayOf(aiId)
                                )
                                db.execSQL(
                                    "INSERT INTO UserWorldBook (aiId, keyword, content) VALUES (?, ?, ?)",
                                    arrayOf(aiId, "正在一起听歌", content)
                                )
                            }
                        } catch (e: Exception) {
                        } finally {
                            isSyncingWorldBook = false
                        }
                    }.start()
                }
            } catch (e: Exception) {}
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val channelId = "music_keep_alive"

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "音乐保活",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val notification = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            Notification.Builder(this)
        }
            .setContentTitle("正在一起听歌")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(1001, notification)
        handler.post(keepAliveRunnable)
        handler.post(syncSongRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(keepAliveRunnable)
        handler.removeCallbacks(syncSongRunnable)

        try {
            stopForeground(true)
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}