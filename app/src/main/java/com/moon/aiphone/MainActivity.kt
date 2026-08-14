package com.moon.aiphone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.yalantis.ucrop.UCrop
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import android.view.View
import androidx.viewpager2.widget.ViewPager2
import androidx.recyclerview.widget.RecyclerView

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 全局共享的 OkHttpClient。
 *
 * 所有网络请求都复用这一个实例。需要自定义超时等参数时，使用
 * Http.client.newBuilder()....build() 派生 —— 派生出的 client 共享底层连接池
 * 与线程池，避免每次 new OkHttpClient.Builder().build() 反复创建线程/连接，
 * 防止线程与 socket 泄漏导致的内存上涨和请求间歇性超时。
 */
object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
}

class MainActivity : AppCompatActivity() {

    private val clockHandler = Handler(Looper.getMainLooper())

    private val clockRunnable = object : Runnable {
        override fun run() {
            try {
                val tvTime = findViewById<TextView>(R.id.tvDesktopTime)
                tvTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                clockHandler.postDelayed(this, 1000)
            } catch (e: Exception) {}
        }
    }

    private val cropWallpaper = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val resultUri = UCrop.getOutput(data)

            if (resultUri != null) {
                val sharedPref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                val savedPath = when {
                    resultUri.scheme == "file" ->
                        resultUri.path ?: resultUri.toString()
                    resultUri.scheme == "content" -> {
                        try {
                            val dest = File(filesDir, "wallpaper_saved_${System.currentTimeMillis()}.jpg")
                            contentResolver.openInputStream(resultUri)?.use { input ->
                                dest.outputStream().use { output -> input.copyTo(output) }
                            }
                            dest.absolutePath
                        } catch (e: Exception) {
                            resultUri.toString()
                        }
                    }
                    else -> resultUri.toString()
                }
                sharedPref.edit().putString("wallpaperUri", savedPath).apply()
                Toast.makeText(this, "\u58c1\u7eb8\u5df2\u4fdd\u5b58", Toast.LENGTH_SHORT).show()
                loadWallpaper()
            } else {
                Toast.makeText(this, "\u58c1\u7eb8\u88c1\u526a\u5931\u8d25", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private val pickWallpaper = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->

        if (uri != null) {
            val destinationUri = Uri.fromFile(
                File(
                    filesDir,
                    "wallpaper_${System.currentTimeMillis()}.jpg"
                )
            )
            val options = UCrop.Options()
            options.setCircleDimmedLayer(false)
            options.setShowCropGrid(true)
            options.setToolbarTitle("装修大厅壁纸")
            val uCropIntent = UCrop.of(uri, destinationUri)
                .withAspectRatio(9f, 16f)
                .withMaxResultSize(1080, 1920)
                .withOptions(options)
                .getIntent(this)
            cropWallpaper.launch(uCropIntent)
        }
    }

    // 用户自选封面图片
    private val pickCoverImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val bytes = inputStream?.readBytes()
                inputStream?.close()
                if (bytes != null) {
                    val base64 = "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    getSharedPreferences("AppConfig", Context.MODE_PRIVATE).edit()
                        .putString("currentSongCover", base64).apply()
                    updateMusicWidget()
                }
            } catch (e: Exception) {}
        }
    }

    private val musicUpdateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            updateMusicWidget()
        }
    }

    private fun updateMusicWidget() {
        try {
            val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            val song = (pref.getString("currentSong", "") ?: "")
                .replace("▶", "")
                .replace("⏸", "")
                .replace("▷", "")
                .trim()

            val progress = pref.getString("currentSongProgress", "0") ?: "0"
            val cover = pref.getString("currentSongCover", "") ?: ""
            val artist = pref.getString("currentSongArtist", "") ?: ""

            android.util.Log.d("WidgetDebug", "更新组件: $song $artist")

            val wvMusic = findViewById<android.webkit.WebView>(R.id.wvMusicWidget) ?: return

            val jsSong = org.json.JSONObject.quote(song)
            val jsArtist = org.json.JSONObject.quote(artist)
            val jsCover = org.json.JSONObject.quote(cover)
            val safeProgress = progress.toIntOrNull()?.coerceIn(0, 100) ?: 0

            val js = """
            (function(){
                var t = document.getElementById('title');
                var a = document.getElementById('artist');
                var b = document.getElementById('bar');
                var c = document.getElementById('cover');

                if(t) t.textContent = $jsSong;
                if(a) a.textContent = $jsArtist;
                if(b) b.style.width = '${safeProgress}%';

                if(c && $jsCover.length > 5) {
                    c.style.backgroundImage = "url(" + $jsCover + ")";
                    c.style.backgroundSize = 'cover';
                    c.style.backgroundPosition = 'center';
                    c.innerHTML = '';
                }

                return t ? t.textContent : 'title not found';
            })();
        """.trimIndent()

            wvMusic.evaluateJavascript(js) { result ->
                android.util.Log.d("WidgetDebug", "JS返回: $result")
            }

        } catch (e: Exception) {
            android.util.Log.e("WidgetDebug", "异常: ${e.message}")
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.hide()
        try {
            val db = DatabaseHelper(this).writableDatabase
            db.execSQL("CREATE TABLE IF NOT EXISTS AiDiary (id INTEGER PRIMARY KEY AUTOINCREMENT, aiId TEXT, dateStr TEXT, weather TEXT, location TEXT, content TEXT, summaryForNext TEXT, timestamp INTEGER)")
            db.execSQL("CREATE TABLE IF NOT EXISTS UserDiary (id INTEGER PRIMARY KEY AUTOINCREMENT, dateStr TEXT, weather TEXT, content TEXT, visibleAiIds TEXT, aiAnnotations TEXT, timestamp INTEGER)")
            db.execSQL("CREATE TABLE IF NOT EXISTS UserTravelPlans (id INTEGER PRIMARY KEY AUTOINCREMENT, travelType TEXT, fromPlace TEXT, toPlace TEXT, tripNo TEXT, departTime TEXT, note TEXT, status TEXT DEFAULT 'active', createdAt INTEGER)")
            db.execSQL("CREATE TABLE IF NOT EXISTS UserPackages (id INTEGER PRIMARY KEY AUTOINCREMENT, trackingNo TEXT, carrier TEXT, itemName TEXT, status TEXT DEFAULT 'active', note TEXT, createdAt INTEGER)")
        } catch (e: Exception) {}

        startService(Intent(this, PatienceService::class.java))

        Thread {
            try {
                val db = DatabaseHelper(this).writableDatabase
                db.execSQL("UPDATE MemoryBank SET category='misc_treasure' WHERE category IS NULL OR category=''")
                DatabaseHelper(this).repairLegacyTextArtifacts()
            } catch (e: Exception) {}
        }.start()

        try {
            val db = DatabaseHelper(this).writableDatabase
            db.execSQL("CREATE TABLE IF NOT EXISTS Schedules (id INTEGER PRIMARY KEY AUTOINCREMENT, aiId TEXT, dateStr TEXT, startTime TEXT, endTime TEXT, eventDesc TEXT)")
            db.execSQL("CREATE TABLE IF NOT EXISTS MyEvents (id INTEGER PRIMARY KEY AUTOINCREMENT, dateStr TEXT, eventDesc TEXT)")
        } catch (e: Exception) {}

        findViewById<LinearLayout>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.btnOpenChat).setOnClickListener {
            startActivity(Intent(this, WechatActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.btnOpenWorldBook).setOnClickListener {
            startActivity(Intent(this, WorldBookActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.btnOpenWeather)?.setOnClickListener {
            startActivity(Intent(this, WeatherActivity::class.java))
        }
        findViewById<LinearLayout>(R.id.btnOpenCalendar)?.setOnClickListener {
            startActivity(Intent(this, CalendarActivity::class.java))
        }
// ViewPager2 桌面分页
        val desktopViewPager = findViewById<ViewPager2>(R.id.desktopViewPager)
        val pageIndicator = findViewById<LinearLayout>(R.id.desktopPageIndicator)

        desktopViewPager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            val pages = listOf(R.layout.desktop_page1, R.layout.desktop_page2)
            override fun getItemCount() = pages.size
            override fun getItemViewType(position: Int) = position
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = layoutInflater.inflate(pages[viewType], parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                if (position == 0) bindPage1(holder.itemView)
                if (position == 1) bindPage2(holder.itemView)
            }
        }

// 圆点指示器
        val dots = Array(2) { i ->
            android.view.View(this).apply {
                val size = (8 * resources.displayMetrics.density).toInt()
                val lp = LinearLayout.LayoutParams(size, size).apply { marginStart = 10; marginEnd = 10 }
                layoutParams = lp
                setBackgroundResource(if (i == 0) R.drawable.bg_dot_active
                else R.drawable.bg_dot_inactive)
                pageIndicator.addView(this)
            }
        }

        desktopViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                dots.forEachIndexed { i, dot ->
                    dot.setBackgroundResource(if (i == position) R.drawable.bg_dot_active
                    else R.drawable.bg_dot_inactive)
                }
            }
        })
        val wvMusic = findViewById<android.webkit.WebView>(R.id.wvMusicWidget)
        wvMusic.settings.javaScriptEnabled = true
        wvMusic.settings.domStorageEnabled = true
        wvMusic.settings.allowFileAccess = true
        wvMusic.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        wvMusic.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        wvMusic.isClickable = true
        wvMusic.addJavascriptInterface(object : Any() {
            @android.webkit.JavascriptInterface
            fun openMusic() {
                runOnUiThread {
                    // 桌面音乐卡片：点击进入电台，不再跳网易云/音乐页
                    // 角色点歌仍然保留在 ChatActivity.onMusicCardClick() 里走 MusicActivity
                    val intent = Intent(this@MainActivity, RadioActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    startActivity(intent)
                }
            }
            @android.webkit.JavascriptInterface
            fun pickCover() {
                runOnUiThread {
                    pickCoverImage.launch(arrayOf("image/*"))
                }
            }
            @android.webkit.JavascriptInterface
            fun togglePlay() {
                runOnUiThread {
                    sendBroadcast(Intent("MUSIC_TOGGLE_PLAY"))
                }
            }
            @android.webkit.JavascriptInterface
            fun selectAI() {
                runOnUiThread {
                    showAISelectDialog()
                }
            }
        }, "Android")
        wvMusic.loadDataWithBaseURL(null, getMusicWidgetHtml(), "text/html", "UTF-8", null)

        val ivWallpaper = findViewById<ImageView>(R.id.ivMainWallpaper)
        ivWallpaper.setOnLongClickListener {
            Toast.makeText(this, "🖼️ 启动桌面壁纸装修模式！", Toast.LENGTH_SHORT).show()
            pickWallpaper.launch(arrayOf("image/*"))
            true
        }
    }

    override fun onResume() {
        super.onResume()
        loadWallpaper()
        loadDesktopWeather()
        renderCalendar()
        musicRefreshHandler.post(musicRefreshRunnable)
        clockHandler.post(clockRunnable)
        androidx.core.content.ContextCompat.registerReceiver(this, desktopBadgeReceiver, android.content.IntentFilter("CYBER_NEW_MSG"), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        androidx.core.content.ContextCompat.registerReceiver(this, musicUpdateReceiver, android.content.IntentFilter("MUSIC_INFO_UPDATED"), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        // 清除所有游离的未读消息（aiId为空或不对应任何角色的）
        try {
            DatabaseHelper(this).writableDatabase.execSQL(
                "UPDATE ChatHistory SET isRead=1 WHERE isRead=0 AND (aiId IS NULL OR aiId='' OR aiId NOT IN (SELECT userId FROM Contacts))"
            )
        } catch (_: Exception) {}
        updateDesktopBadge()
        updateMusicWidget()
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockRunnable)
        musicRefreshHandler.removeCallbacks(musicRefreshRunnable)
        try { unregisterReceiver(desktopBadgeReceiver) } catch(e:Exception){}
        try { unregisterReceiver(musicUpdateReceiver) } catch(e:Exception){}
    }
    private fun bindPage1(view: View) {
        view.findViewById<LinearLayout>(R.id.btnOpenMemory)?.setOnClickListener {
            startActivity(Intent(this, MemoryVaultActivity::class.java))
        }
        view.findViewById<View>(R.id.btnOpenDoor)?.setOnClickListener {
            startActivity(Intent(this, OfflineContactActivity::class.java))
        }
        view.findViewById<LinearLayout>(R.id.btnOpenForum)?.setOnClickListener {
            startActivity(Intent(this, ForumActivity::class.java))
        }
        view.findViewById<LinearLayout>(R.id.btnOpenHacker)?.setOnClickListener {
            startActivity(Intent(this, HackerActivity::class.java))
        }
        view.findViewById<View>(R.id.btnOpenMailbox)?.setOnClickListener {
            startActivity(Intent(this, MailboxActivity::class.java))
        }
        view.findViewById<View>(R.id.btnOpenBlindbox)?.setOnClickListener {
            startActivity(Intent(this, BlindboxActivity::class.java))
        }
        view.findViewById<LinearLayout>(R.id.btnOpenGameCenter)?.setOnClickListener {
            startActivity(Intent(this, GameCenterActivity::class.java))
        }
        view.findViewById<LinearLayout>(R.id.btnOpenUniverse)?.setOnClickListener {
            startActivity(Intent(this, UniverseActivity::class.java))
        }
    }

    private fun bindPage2(view: View) {
        view.findViewById<LinearLayout>(R.id.btnOpenDiary)?.setOnClickListener {
            startActivity(Intent(this, DiaryActivity::class.java))
        } // 加入这一行，找到梦男之家的图标按钮
        view.findViewById<View>(R.id.btnOpenDreamHouse)?.setOnClickListener {
            startActivity(Intent(this, DreamHouseActivity::class.java))
        }
        view.findViewById<View>(R.id.btnOpenBookShelf)?.setOnClickListener {
            startActivity(Intent(this, BookShelfActivity::class.java))
        }
        view.findViewById<LinearLayout>(R.id.btnOpenSlot4)?.setOnClickListener {
            startActivity(Intent(this, PomodoroActivity::class.java))
        }
        view.findViewById<View>(R.id.btnOpenTravel)?.setOnClickListener {
            startActivity(Intent(this, TravelActivity::class.java))
        }
        view.findViewById<View>(R.id.btnOpenPackage)?.setOnClickListener {
            startActivity(Intent(this, PackageActivity::class.java))
        }
        view.findViewById<View>(R.id.btnOpenPetHouse)?.setOnClickListener {
            startActivity(Intent(this, PetHouseActivity::class.java))
        }
    }

    private fun loadWallpaper() {
        val sharedPref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val uriStr = sharedPref.getString("wallpaperUri", "") ?: ""
        val ivWallpaper = findViewById<ImageView>(R.id.ivMainWallpaper)

        ivWallpaper.setImageDrawable(null)

        if (uriStr.isNotEmpty()) {
            try {
                val bitmap =
                    if (uriStr.startsWith("/")) {
                        android.graphics.BitmapFactory.decodeFile(uriStr)
                    } else {
                        contentResolver.openInputStream(Uri.parse(uriStr))
                            ?.use { stream ->
                                android.graphics.BitmapFactory.decodeStream(stream)
                            }
                    }

                if (bitmap != null) {
                    ivWallpaper.setImageBitmap(bitmap)
                    ivWallpaper.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                } else {
                    ivWallpaper.setBackgroundColor(android.graphics.Color.BLACK)
                }
            } catch (e: Exception) {
                android.util.Log.e("MAIN_WALLPAPER", e.stackTraceToString())
                ivWallpaper.setBackgroundColor(android.graphics.Color.BLACK)
            }
        } else {
            ivWallpaper.setBackgroundColor(android.graphics.Color.BLACK)
        }
    }
    private val musicRefreshHandler = Handler(Looper.getMainLooper())
    private val musicRefreshRunnable = object : Runnable {
        override fun run() {
            updateMusicWidget()
            musicRefreshHandler.postDelayed(this, 3000)
        }
    }
    private fun getMusicWidgetHtml(): String {
        val html = """<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
* { margin:0; padding:0; box-sizing:border-box; }
body {
  background: rgba(255,255,255,0.15);
  border-radius: 16px;
  height: 80px;
  display: flex;
  align-items: center;
  padding: 0 14px;
  font-family: sans-serif;
  overflow: hidden;
}
.cover {
  width: 56px; height: 56px;
  border-radius: 50%;
  background: #222;
  background-size: cover;
  background-position: center;
  display: flex; align-items: center; justify-content: center;
  font-size: 20px;
  flex-shrink: 0;
  cursor: pointer;
  box-shadow: 0 0 0 4px #444, 0 0 0 6px #222;
  position: relative;
}
.cover::after {
  content: '';
  position: absolute;
  width: 14px; height: 14px;
  border-radius: 50%;
  background: #111;
  box-shadow: 0 0 0 2px #333;
}
.info {
  flex: 1;
  margin: 0 12px;
  overflow: hidden;
  cursor: pointer;
}
.title {
  color: #fff;
  font-size: 14px;
  font-weight: bold;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.artist {
  color: #aaa;
  font-size: 11px;
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.progress {
  height: 3px;
  background: #444;
  border-radius: 2px;
  margin-top: 8px;
}
.progress-bar {
  height: 3px;
  background: #e63946;
  border-radius: 2px;
  width: 0%;
}
.controls {
  display: flex;
  align-items: center;
  gap: 4px;
}
.ctrl-btn {
  width: 38px;
  height: 38px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  font-size: 20px;
  color: white;
}
</style>
</head>
<body>
<div class="cover" id="cover" onclick="Android.pickCover()">&#127925;</div>
<div class="info" onclick="Android.openMusic()">
  <div class="title" id="title">点击打开电台</div>
  <div class="artist" id="artist">&#8212;</div>
  <div class="progress"><div class="progress-bar" id="bar"></div></div>
</div>
<div class="controls">
  <div class="ctrl-btn" onclick="Android.togglePlay()">&#9654;</div>
  <div class="ctrl-btn" onclick="Android.selectAI()">&#9835;</div>
</div>
</body>
</html>"""
        return html
    }

    private fun loadDesktopWeather() {
        try {
            val sharedPref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            val cyberWeather = sharedPref.getString("cyberWeather", "") ?: ""
            val tvDesktopWeather = findViewById<TextView>(R.id.tvDesktopWeather)
            if (cyberWeather.isNotEmpty()) {
                val tempRegex = Regex("(\\d+°C)")
                val condRegex = Regex("(晴|阴|多云|雨|雪|雷阵雨)")
                val temp = tempRegex.findAll(cyberWeather).lastOrNull()?.value ?: "22°C"
                val cond = condRegex.find(cyberWeather)?.value ?: "多云"
                tvDesktopWeather.text = "☁️ $temp $cond"
            } else {
                tvDesktopWeather.text = "☁️ 点击卫星"
            }
        } catch (e: Exception) {}
    }
    private fun showAISelectDialog() {
        try {
            val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            val currentListeningAiId = pref.getString("listeningTogetherAiId", "") ?: ""

            val db = DatabaseHelper(this).readableDatabase
            val cur = db.rawQuery("SELECT userId, realName FROM Contacts ORDER BY id ASC", null)
            val names = mutableListOf<String>()
            val ids = mutableListOf<String>()
            while (cur.moveToNext()) {
                ids.add(cur.getString(0))
                names.add(cur.getString(1))
            }
            cur.close()
            if (names.isEmpty()) return

            // 如果当前有角色在听歌，在列表顶部加"停止听歌"选项
            val displayNames: Array<String>
            val displayIds: List<String>
            if (currentListeningAiId.isNotEmpty()) {
                displayNames = (listOf("⛔ 停止一起听歌") + names).toTypedArray()
                displayIds = listOf("__stop__") + ids
            } else {
                displayNames = names.toTypedArray()
                displayIds = ids
            }

            android.app.AlertDialog.Builder(this)
                .setTitle(if (currentListeningAiId.isNotEmpty()) "正在一起听歌，切换或停止" else "选择一起听歌的角色")
                .setItems(displayNames) { _, which ->
                    if (displayIds[which] == "__stop__") {
                        stopListeningTogether(currentListeningAiId)
                    } else {
                        syncMusicToAI(displayIds[which], displayNames[which])
                    }
                }
                .show()
        } catch (e: Exception) {}
    }
    private fun stopListeningTogether(aiId: String) {
        Thread {
            try {
                val db = DatabaseHelper(this).writableDatabase
                db.execSQL("DELETE FROM UserWorldBook WHERE aiId=? AND keyword='正在一起听歌'", arrayOf(aiId))
            } catch (e: Exception) {}
            getSharedPreferences("AppConfig", Context.MODE_PRIVATE).edit()
                .putString("listeningTogetherAiId", "")
                .apply()
            runOnUiThread {
                Toast.makeText(this, "已停止一起听歌", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }
    private fun syncMusicToAI(aiId: String, aiName: String) {
        val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val song = (pref.getString("currentSong", "") ?: "")
            .replace("▶", "").replace("⏸", "").replace("▷", "").trim()
        val artist = pref.getString("currentSongArtist", "") ?: ""
        val comments = pref.getString("currentSongComments", "") ?: ""
        if (song.isEmpty()) {
            Toast.makeText(this, "还没有播放歌曲", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            try {
                val db = DatabaseHelper(this).writableDatabase
                // 删除旧的听歌条目
                db.execSQL("DELETE FROM UserWorldBook WHERE aiId=? AND keyword='正在一起听歌'", arrayOf(aiId))
                // 插入新的
                val content = """
    【当前共同听歌状态】
    你和用户此刻正在同时听的歌曲是：《$song》- $artist
    注意：这是用户实际播放的歌曲，不是你猜测或推断的，必须以此为准。
    不得替换成其他歌曲名称。
    你能听到这首歌，感受到它的旋律和情绪，请根据《$song》这首歌的风格和情绪来聊天。
    热门评论：
    $comments
""".trimIndent()
                db.execSQL(
                    "INSERT INTO UserWorldBook (aiId, keyword, content) VALUES (?, ?, ?)",
                    arrayOf(aiId, "正在一起听歌", content)
                )
            } catch (e: Exception) {}
            runOnUiThread {
                getSharedPreferences("AppConfig", Context.MODE_PRIVATE).edit()
                    .putString("listeningTogetherAiId", aiId)
                    .apply()
                Toast.makeText(this, "已同步给${aiName}，去聊天吧！", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }
    private val desktopBadgeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            updateDesktopBadge()
        }
    }

    private fun updateDesktopBadge() {
        try {
            val db = DatabaseHelper(this).readableDatabase
            val cur = db.rawQuery("SELECT COUNT(*) FROM ChatHistory\n" +
                    "WHERE isFromMe=0\n" +
                    "AND isRead=0\n" +
                    "AND IFNULL(groupId,'')=''\n" +
                    "AND aiId IN (SELECT userId FROM Contacts)", null)
            var unread = 0
            if (cur.moveToFirst()) unread = cur.getInt(0)
            cur.close()
            val tvBadge = findViewById<TextView>(R.id.tvDesktopChatBadge)
            if (unread > 0) {
                tvBadge.visibility = android.view.View.VISIBLE
                tvBadge.text = if (unread > 99) "99+" else unread.toString()
            } else {
                tvBadge.visibility = android.view.View.GONE
            }
        } catch (e: Exception) {}
    }

    private fun renderCalendar() {
        try {
            val gl = findViewById<GridLayout>(R.id.glCalendarDays)
            gl.removeAllViews()
            gl.rowCount = 1
            val cal = Calendar.getInstance()
            val currentDay = cal.get(Calendar.DAY_OF_MONTH)
            val currentMonth = cal.get(Calendar.MONTH)
            val currentYear = cal.get(Calendar.YEAR)
            findViewById<TextView>(R.id.tvCalendarYearMonth).text = "${currentYear}年${currentMonth + 1}月"
            cal.firstDayOfWeek = Calendar.SUNDAY
            cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
            val displayMetrics = resources.displayMetrics
            val sizePx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, 32f, displayMetrics).toInt()
            for (i in 0..6) {
                val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
                val isToday = (dayOfMonth == currentDay && cal.get(Calendar.MONTH) == currentMonth)
                val tv = TextView(this).apply {
                    text = dayOfMonth.toString()
                    gravity = android.view.Gravity.CENTER
                    textSize = 15f
                    layoutParams = GridLayout.LayoutParams(GridLayout.spec(0), GridLayout.spec(i, 1f)).apply {
                        width = sizePx
                        height = sizePx
                        setGravity(android.view.Gravity.CENTER)
                    }
                    if (isToday) {
                        setTextColor(android.graphics.Color.WHITE)
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        val bg = android.graphics.drawable.GradientDrawable()
                        bg.shape = android.graphics.drawable.GradientDrawable.OVAL
                        bg.setColor(android.graphics.Color.parseColor("#333333"))
                        background = bg
                    } else {
                        setTextColor(android.graphics.Color.parseColor("#777777"))
                        background = null
                    }
                }
                gl.addView(tv)
                cal.add(Calendar.DAY_OF_MONTH, 1)
            }
        } catch (e: Exception) {}
    }
}
