package com.moon.aiphone

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.*
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.io.File
import okhttp3.RequestBody.Companion.asRequestBody
class PomodoroActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var totalSeconds = 25 * 60
    private var remainSeconds = totalSeconds
    private var isRunning = false
    private var isWorkPhase = true
    private var completedPomodoros = 0
    private var workMinutes = 25
    private var breakMinutes = 5
    private var taskType = "学习"
    private var distractCount = 0
    private var totalFocusSeconds = 0

    private var aiId = ""
    private var aiName = ""
    private var aiPersona = ""
    private var aiLang = "默认 (中文)"
    private var voiceId = ""
    private var ttsEnabled = false
    private var bgImageUri: Uri? = null

    // Camera
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var cameraEnabled = false
    private val cameraHandler = Handler(Looper.getMainLooper())
    private lateinit var previewView: PreviewView

    private var mediaRecorder: android.media.MediaRecorder? = null
    private var recordingFile: File? = null
    private var isRecording = false
    private lateinit var btnVoice: TextView
    private val voiceHistory = mutableListOf<JSONObject>()
    private lateinit var tvTimer: TextView
    private lateinit var tvPhase: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvDistract: TextView
    private lateinit var btnStartPause: TextView
    private lateinit var tvAiComment: TextView
    private lateinit var ivBackground: ImageView
    private lateinit var overlayLayout: FrameLayout
    private lateinit var tvTitle: TextView

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private val pickBackground = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            bgImageUri = uri
            getSharedPreferences("AppConfig", MODE_PRIVATE).edit()
                .putString("pomodoroBackground_$aiId", uri.toString()).apply()
            loadBackgroundImage(uri)
        }
    }

    private val requestCameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            cameraEnabled = false
            previewView.visibility = android.view.View.GONE
        }
    }
    private val requestMicPermission =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                startVoiceRecording()
            }
        }
    private val ticker = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (remainSeconds > 0) {
                remainSeconds--
                if (isWorkPhase) {
                    totalFocusSeconds++
                    if (totalFocusSeconds % 30 == 0) saveTodayStats()
                }
                updateTimerDisplay()
                handler.postDelayed(this, 1000)
            } else {
                onPhaseComplete()
            }
        }
    }

    // 每7.5分钟截帧识别
    private val sceneCaptureRunnable = object : Runnable {
        override fun run() {
            if (isRunning && isWorkPhase && cameraEnabled) captureAndAnalyze()
            cameraHandler.postDelayed(this, 7 * 60 * 1000L + 30 * 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aiId = intent.getStringExtra("AI_ID") ?: ""
        if (aiId.isEmpty()) {
            try {
                val c = DatabaseHelper(this).readableDatabase.rawQuery("SELECT userId FROM Contacts ORDER BY RANDOM() LIMIT 1", null)
                if (c.moveToFirst()) aiId = c.getString(0) ?: ""
                c.close()
            } catch (_: Exception) {}
        }
        loadAiInfo()

        // ── 根布局 ──────────────────────────────────────────
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // 角色背景（全屏）
        ivBackground = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.85f
        }
        frame.addView(ivBackground)

        // 暗色遮罩
        val dim = android.view.View(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            setBackgroundColor(Color.parseColor("#55000000"))
        }
        frame.addView(dim)

        // 用户摄像头预览（右上角小窗）
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(110), dp(150)).also {
                it.gravity = Gravity.TOP or Gravity.END
                it.topMargin = dp(60); it.marginEnd = dp(12)
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#222222"))
            }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
            }
            visibility = android.view.View.GONE
        }
        frame.addView(previewView)

        // 内容覆盖层
        overlayLayout = FrameLayout(this)
        frame.addView(overlayLayout)

        // ── 顶部栏 ──────────────────────────────────────────
        val topBar = RelativeLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, dp(52)).also { it.gravity = Gravity.TOP }
            setBackgroundColor(Color.parseColor("#99000000"))
        }
        val btnBack = TextView(this).apply {
            text = "‹"; textSize = 28f; setTextColor(Color.WHITE)
            setPadding(dp(16), 0, dp(16), dp(4)); gravity = Gravity.CENTER_VERTICAL
            layoutParams = RelativeLayout.LayoutParams(-2, -1).also {
                it.addRule(RelativeLayout.ALIGN_PARENT_START); it.addRule(RelativeLayout.CENTER_VERTICAL)
            }
            setOnClickListener { finish() }
        }
        tvTitle = TextView(this).apply {
            text = "🍅 ${aiName}陪你专注"; textSize = 15f
            setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
            layoutParams = RelativeLayout.LayoutParams(-2, -2).also { it.addRule(RelativeLayout.CENTER_IN_PARENT) }
        }
        val btnSetting = TextView(this).apply {
            text = "⚙"; textSize = 20f; setTextColor(Color.WHITE)
            setPadding(dp(16), 0, dp(16), 0); gravity = Gravity.CENTER_VERTICAL
            layoutParams = RelativeLayout.LayoutParams(-2, -1).also {
                it.addRule(RelativeLayout.ALIGN_PARENT_END); it.addRule(RelativeLayout.CENTER_VERTICAL)
            }
            setOnClickListener { showSettingDialog() }
        }
        topBar.addView(btnBack); topBar.addView(tvTitle); topBar.addView(btnSetting)
        overlayLayout.addView(topBar)

        // ── 中间计时区 ────────────────────────────────────
        val centerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -1).also { it.gravity = Gravity.CENTER }
        }
        tvPhase = TextView(this).apply {
            text = "专注 · $taskType"; textSize = 16f
            setTextColor(Color.parseColor("#FF6B6B")); setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.bottomMargin = dp(8) }
        }
        centerLayout.addView(tvPhase)
        tvTimer = TextView(this).apply {
            text = formatTime(remainSeconds); textSize = 80f
            setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setShadowLayer(16f, 0f, 4f, Color.parseColor("#AA000000"))
            layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.gravity = Gravity.CENTER_HORIZONTAL }
        }
        centerLayout.addView(tvTimer)
        tvCount = TextView(this).apply {
            text = "今日完成 0 🍅"; textSize = 13f
            setTextColor(Color.parseColor("#CCFFFFFF")); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.topMargin = dp(4) }
        }
        centerLayout.addView(tvCount)
        tvDistract = TextView(this).apply {
            text = ""; textSize = 12f; setTextColor(Color.parseColor("#FFCC88")); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.topMargin = dp(2) }
        }
        centerLayout.addView(tvDistract)
        overlayLayout.addView(centerLayout)

        // ── AI 气泡 ───────────────────────────────────────
        tvAiComment = TextView(this).apply {
            text = "点击开始，我陪着你 💕"
            textSize = 14f; setTextColor(Color.WHITE); setLineSpacing(4f, 1.3f)
            setPadding(dp(8), dp(12), dp(8), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#BB1A1A2E")); cornerRadius = dp(14).toFloat()
            }
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -2).also {
                it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                it.bottomMargin = dp(130); it.marginStart = dp(20); it.marginEnd = dp(20)
            }
        }
        overlayLayout.addView(tvAiComment)

        // ── 底部控制栏 ────────────────────────────────────
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(dp(8), dp(12), dp(8), dp(12))
            layoutParams = FrameLayout.LayoutParams(-1, -2).also { it.gravity = Gravity.BOTTOM }
        }
        val btnReset = TextView(this).apply {
            text = "重置"; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.parseColor("#55FFFFFF")); cornerRadius = dp(20).toFloat() }
            layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.marginEnd = dp(6) }
            setOnClickListener { resetTimer() }
        }
        btnStartPause = TextView(this).apply {
            text = "开始专注"; textSize = 16f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD); setPadding(dp(20), dp(12), dp(20), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.parseColor("#FF3B30")); cornerRadius = dp(24).toFloat() }
            layoutParams = LinearLayout.LayoutParams(-2, -2)
            setOnClickListener { toggleTimer() }
        }
        val btnBg = TextView(this).apply {
            text = "🖼"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.parseColor("#55FFFFFF")); cornerRadius = dp(20).toFloat() }
            layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.marginStart = dp(6)}
            setOnClickListener { pickBackground.launch(arrayOf("image/*")) }
        }
        // 摄像头开关按钮
        val btnCam = TextView(this).apply {
            text = "📷"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.parseColor("#55FFFFFF")); cornerRadius = dp(20).toFloat() }
            layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.marginStart = dp(6) }
            setOnClickListener { toggleCamera(this) }
        }
        btnVoice = TextView(this).apply {
            text = "🎙"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#55FFFFFF")); cornerRadius = dp(20).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.marginStart = dp(6) }
            setOnClickListener { toggleVoiceInput() }
        }
        bottomBar.addView(btnReset); bottomBar.addView(btnStartPause)
        bottomBar.addView(btnBg); bottomBar.addView(btnCam); bottomBar.addView(btnVoice)
        overlayLayout.addView(bottomBar)

        // ── 点击屏幕开小差（排除底部栏和顶部栏区域）────────
        frame.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN && isRunning && isWorkPhase) {
                val y = event.y
                val screenH = frame.height
                if (y > dp(52) && y < screenH - dp(70)) onDistracted()
            }
            false
        }

        setContentView(frame)

        // 恢复背景
        val savedBg = getSharedPreferences("AppConfig", MODE_PRIVATE)
            .getString("pomodoroBackground_$aiId", "") ?: ""
        if (savedBg.isNotEmpty()) {
            bgImageUri = Uri.parse(savedBg)
            loadBackgroundImage(Uri.parse(savedBg))
        }

        updateTimerDisplay()
    }

    // ── 摄像头 ────────────────────────────────────────────
    private fun toggleVoiceInput() {
        if (isRecording) {
            stopRecordingAndSend()
        } else {

            if (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                )
                != PackageManager.PERMISSION_GRANTED
            ) {

                requestMicPermission.launch(
                    Manifest.permission.RECORD_AUDIO
                )

                return
            }

            startVoiceRecording()
        }
    }

    private fun startVoiceRecording() {
        try {
            recordingFile = File(cacheDir, "pomodoro_voice_${System.currentTimeMillis()}.m4a")
            mediaRecorder = android.media.MediaRecorder().apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(128000)
                setOutputFile(recordingFile!!.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            btnVoice.text = "⏹"
            btnVoice.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#FF3B30")); cornerRadius = dp(20).toFloat()
            }
            tvAiComment.text = "录音中…"
        } catch (e: Exception) {
            Toast.makeText(this, "录音失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecordingAndSend() {
        try {
            mediaRecorder?.stop(); mediaRecorder?.release(); mediaRecorder = null
            isRecording = false
            btnVoice.text = "🎙"
            btnVoice.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#55FFFFFF")); cornerRadius = dp(20).toFloat()
            }
            val file = recordingFile ?: return
            if (!file.exists() || file.length() < 1000) {
                tvAiComment.text = "没听到，再说一次？"
                return
            }
            tvAiComment.text = "识别中…"
            val pref = getSharedPreferences("AppConfig", MODE_PRIVATE)
            val ttsKey = pref.getString("ttsApiKey", "") ?: pref.getString("apiKey", "") ?: ""

            Thread {
                try {
                    val requestBody = okhttp3.MultipartBody.Builder()
                        .setType(okhttp3.MultipartBody.FORM)
                        .addFormDataPart("model", "iic/SenseVoiceSmall")
                        .addFormDataPart("file", file.name,
                            file.asRequestBody("audio/m4a".toMediaTypeOrNull()))
                        .build()
                    val req = okhttp3.Request.Builder()
                        .url("https://api.siliconflow.cn/v1/audio/transcriptions")
                        .addHeader("Authorization", "Bearer $ttsKey")
                        .post(requestBody).build()
                    val resp = Http.client.newBuilder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build().newCall(req).execute()
                    val result = resp.body?.string() ?: ""
                    val text = JSONObject(result).optString("text", "").trim()
                    file.delete()
                    if (text.isNotEmpty()) {
                        runOnUiThread {
                            tvAiComment.text = "你：$text"
                            sendVoiceToAi(text)
                        }
                    } else {
                        runOnUiThread { tvAiComment.text = "没听清，再说一次？" }
                    }
                } catch (e: Exception) {
                    runOnUiThread { tvAiComment.text = "识别失败，请重试" }
                }
            }.start()
        } catch (e: Exception) {
            Toast.makeText(this, "停止录音失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendVoiceToAi(userText: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pref = getSharedPreferences("AppConfig", MODE_PRIVATE)
                var apiUrl = pref.getString("apiUrl", "") ?: ""
                val apiKey = pref.getString("apiKey", "") ?: ""
                val modelName = pref.getString("modelName", "") ?: ""
                if (apiUrl.isEmpty() || apiKey.isEmpty()) return@launch
                while (apiUrl.endsWith("/")) apiUrl = apiUrl.dropLast(1)
                if (!apiUrl.endsWith("/chat/completions"))
                    apiUrl = if (apiUrl.endsWith("/v1")) "$apiUrl/chat/completions" else "$apiUrl/v1/chat/completions"

                val isChinese = aiLang == "默认 (中文)"
                val langRule = if (isChinese) "必须用中文。" else "必须用${aiLang}，然后在末尾换行加上中文翻译，格式：原文\n【译】中文翻译"
                val myName = getMyName()

                voiceHistory.add(JSONObject().apply {
                    put("role", "user"); put("content", userText)
                })
                while (voiceHistory.size > 20) {
                    voiceHistory.removeAt(0)
                }
                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "你是${aiName}，人设：${aiPersona.take(150)}。你正在视频陪${myName}${taskType}，TA刚刚对你说了一句话。用口语化的语气简短回复（1-2句），符合你的性格，不要OOC，不要标签。${langRule}")
                    })
                    voiceHistory.takeLast(10).forEach { put(it) }
                }

                val body = JSONObject().apply {
                    put("model", modelName); put("max_tokens", 150)
                    put("messages", messages)
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())

                val resp = Http.client.newBuilder()
                    .connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                    .build().newCall(
                        okhttp3.Request.Builder().url(apiUrl)
                            .addHeader("Authorization", "Bearer $apiKey").post(body).build()
                    ).execute()
                val raw = JSONObject(resp.body?.string() ?: return@launch)
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()

                voiceHistory.add(JSONObject().apply {
                    put("role", "assistant"); put("content", raw)
                })

                withContext(Dispatchers.Main) {
                    val displayText: String
                    val ttsText: String
                    if (!isChinese && raw.contains("【译】")) {
                        val original = raw.substringBefore("【译】").trim()
                        val translation = raw.substringAfter("【译】").trim()
                        displayText = "${aiName}：$original\n$translation"
                        ttsText = original
                    } else {
                        displayText = "${aiName}：$raw"
                        ttsText = raw
                    }
                    tvAiComment.text = displayText
                    speakIfEnabled(ttsText, isChinese)
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { tvAiComment.text = "回复失败，请重试" }
            }
        }
    }
    private fun toggleCamera(btn: TextView) {
        cameraEnabled = !cameraEnabled
        if (cameraEnabled) {
            btn.text = "📷✓"
            previewView.visibility = android.view.View.VISIBLE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                requestCameraPermission.launch(Manifest.permission.CAMERA)
            }
        } else {
            btn.text = "📷"
            previewView.visibility = android.view.View.GONE
            cameraProvider?.unbindAll()
            cameraHandler.removeCallbacks(sceneCaptureRunnable)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageCapture
                )
            } catch (_: Exception) {}
            // 启动定时截帧
            cameraHandler.postDelayed(sceneCaptureRunnable, 7 * 60 * 1000L + 30 * 1000L)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureAndAnalyze() {
        val ic = imageCapture ?: return
        ic.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = imageProxyToBitmap(image)
                image.close()
                if (bitmap != null) analyzeScene(bitmap)
            }
            override fun onError(exception: ImageCaptureException) {}
        })
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            // 旋转修正
            val matrix = android.graphics.Matrix()
            matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        } catch (_: Exception) { null }
    }

    private fun analyzeScene(bitmap: Bitmap) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pref = getSharedPreferences("AppConfig", MODE_PRIVATE)
                var apiUrl = pref.getString("apiUrl", "") ?: ""
                val apiKey = pref.getString("apiKey", "") ?: ""
                val modelName = pref.getString("modelName", "gemini-2.5-pro") ?: "gemini-2.5-pro"
                if (apiUrl.isEmpty() || apiKey.isEmpty()) return@launch
                while (apiUrl.endsWith("/")) apiUrl = apiUrl.dropLast(1)
                if (!apiUrl.endsWith("/chat/completions"))
                    apiUrl = if (apiUrl.endsWith("/v1")) "$apiUrl/chat/completions" else "$apiUrl/v1/chat/completions"

                // 压缩图片转base64
                val out = ByteArrayOutputStream()
                val scaled = Bitmap.createScaledBitmap(bitmap, 512, 512 * bitmap.height / bitmap.width, true)
                scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
                val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

                val isChinese = aiLang == "默认 (中文)"
                val langRule = if (isChinese) "必须用中文。" else "必须用${aiLang}，然后在末尾换行加上中文翻译，格式：原文\n【译】中文翻译"

                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    put("url", "data:image/jpeg;base64,$b64")
                                })
                            })
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", "你是${aiName}，人设：${aiPersona.take(100)}。你正在视频陪${getMyName()}${taskType}。你悄悄看了一眼摄像头，根据画面里的场景，用你的口吻说一句内心的小感触或心声（15字以内），不要评价对方的行为，符合你的性格，不要OOC，不要标签。${langRule}")
                            })
                        })
                    })
                }

                val bodyJson = JSONObject().apply {
                    put("model", modelName); put("temperature", 0.9)
                    put("messages", messages)
                }
                val response = Http.client.newBuilder()
                    .connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                    .build().newCall(
                        Request.Builder().url(apiUrl)
                            .addHeader("Authorization", "Bearer $apiKey")
                            .post(bodyJson.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                            .build()
                    ).execute()
                val reply = JSONObject(response.body?.string() ?: return@launch)
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()

                withContext(Dispatchers.Main) {
                    val displayText: String
                    val ttsText: String
                    if (!isChinese && reply.contains("【译】")) {
                        val original = reply.substringBefore("【译】").trim()
                        val translation = reply.substringAfter("【译】").trim()
                        displayText = "$original\n$translation"
                        ttsText = original
                    } else {
                        displayText = reply
                        ttsText = reply
                    }
                    tvAiComment.text = displayText
                    speakIfEnabled(ttsText, isChinese)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvAiComment.text =
                        "AI连接失败"
                }
            }
        }
    }

    // ── 计时逻辑 ─────────────────────────────────────────
    private fun toggleTimer() {
        if (isRunning) {
            isRunning = false; btnStartPause.text = "继续"
            handler.removeCallbacks(ticker)
        } else {
            isRunning = true; btnStartPause.text = "暂停"
            handler.post(ticker)
            if (remainSeconds == totalSeconds && isWorkPhase) fetchAiLine("start")
        }
    }

    private fun resetTimer() {
        isRunning = false
        handler.removeCallbacks(ticker)

        isWorkPhase = true
        distractCount = 0
        completedPomodoros = 0
        totalFocusSeconds = 0

        totalSeconds = workMinutes * 60
        remainSeconds = totalSeconds

        btnStartPause.text = "开始专注"
        tvPhase.text = "专注 · $taskType"
        tvPhase.setTextColor(Color.parseColor("#FF6B6B"))
        tvDistract.text = ""
        tvCount.text = "今日完成 0 🍅"
        tvAiComment.text = "已重置，重新开始吧。"

        updateTimerDisplay()
    }
    private fun onPhaseComplete() {
        isRunning = false; handler.removeCallbacks(ticker)
        if (isWorkPhase) {
            completedPomodoros++
            tvCount.text = "今日完成 $completedPomodoros 🍅"
            saveTodayStats()
            isWorkPhase = false
            totalSeconds = breakMinutes * 60; remainSeconds = totalSeconds
            tvPhase.text = "休息时间"; tvPhase.setTextColor(Color.parseColor("#34C759"))
            btnStartPause.text = "开始休息"
            syncMemoryToAi(); fetchAiLine("complete")
        } else {
            isWorkPhase = true; distractCount = 0; tvDistract.text = ""
            totalSeconds = workMinutes * 60; remainSeconds = totalSeconds
            tvPhase.text = "专注 · $taskType"; tvPhase.setTextColor(Color.parseColor("#FF6B6B"))
            btnStartPause.text = "开始专注"
            fetchAiLine("rest_end")
        }
        loadTodayStats()
        updateTimerDisplay()
    }
    private fun todayKey(): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
    }

    private fun loadTodayStats() {
        val pref = getSharedPreferences("PomodoroStats", MODE_PRIVATE)
        val key = "${todayKey()}_$aiId"
        completedPomodoros = pref.getInt("completed_$key", 0)
        totalFocusSeconds = pref.getInt("focusSeconds_$key", 0)
        tvCount.text = "今日完成 $completedPomodoros 🍅"
    }

    private fun saveTodayStats() {
        val pref = getSharedPreferences("PomodoroStats", MODE_PRIVATE)
        val key = "${todayKey()}_$aiId"
        pref.edit()
            .putInt("completed_$key", completedPomodoros)
            .putInt("focusSeconds_$key", totalFocusSeconds)
            .apply()
    }
    private fun onDistracted() {
        distractCount++; tvDistract.text = "开小差 ×$distractCount"
        fetchAiLine("distract")

    }

    private fun updateTimerDisplay() { tvTimer.text = formatTime(remainSeconds) }
    private fun formatTime(s: Int) = "%02d:%02d".format(s / 60, s % 60)

    // ── AI 台词 ──────────────────────────────────────────
    private fun fetchAiLine(event: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pref = getSharedPreferences("AppConfig", MODE_PRIVATE)
                var apiUrl = pref.getString("apiUrl", "") ?: ""
                val apiKey = pref.getString("apiKey", "") ?: ""
                val modelName = pref.getString("modelName", "gemini-2.5-pro") ?: "gemini-2.5-pro"
                if (apiUrl.isEmpty() || apiKey.isEmpty()) return@launch
                while (apiUrl.endsWith("/")) apiUrl = apiUrl.dropLast(1)
                if (!apiUrl.endsWith("/chat/completions"))
                    apiUrl = if (apiUrl.endsWith("/v1")) "$apiUrl/chat/completions" else "$apiUrl/v1/chat/completions"

                val myName = getMyName()
                val isChinese = aiLang == "默认 (中文)"
                val langRule = if (isChinese) "必须用中文。" else "必须用${aiLang}，然后在末尾换行加上中文翻译，格式：原文\n【译】中文翻译"

                val prompt = when (event) {
                    "start"    -> "你是${aiName}，人设：${aiPersona.take(120)}。${myName}开始了${workMinutes}分钟${taskType}番茄钟，你在视频陪着TA。说一句鼓励（15字以内），符合你的性格，不要OOC，不要标签。${langRule}"
                    "distract" -> """
你是${aiName}，人设：${aiPersona.take(180)}。
${myName}在${taskType}时第${distractCount}次走神了，你在视频里看见了。

请随机选择一种反应，不要每次都催促：
1. 轻轻吐槽一句
2. 假装没看见但暗示TA
3. 用角色口吻说一句半开玩笑的话
4. 提醒TA回到当前任务
5. 说一句具体的小观察
6. 用很短的一句话拉TA回来

要求：
- 15字以内
- 不要重复“专心/别开小差/回来/继续”这类机械词
- 不要说教
- 不要标签
- 必须符合你的性格
$langRule
""".trimIndent()
                    "complete" -> "你是${aiName}，人设：${aiPersona.take(120)}。${myName}完成了第${completedPomodoros}个番茄钟（${workMinutes}分钟${taskType}），开小差${distractCount}次。夸奖TA（20字以内），符合你的性格，不要OOC，不要标签。${langRule}"
                    "rest_end" -> "你是${aiName}，人设：${aiPersona.take(120)}。${myName}休息结束继续${taskType}。说一句话（15字以内），符合你的性格，不要OOC，不要标签。${langRule}"
                    else -> return@launch
                }

                val bodyJson = JSONObject().apply {
                    put("model", modelName); put("temperature", 0.85)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                    })
                }
                val response = Http.client.newBuilder()
                    .connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                    .build().newCall(
                        Request.Builder().url(apiUrl)
                            .addHeader("Authorization", "Bearer $apiKey")
                            .post(bodyJson.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                            .build()
                    ).execute()
                val reply = JSONObject(response.body?.string() ?: return@launch)
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()

                withContext(Dispatchers.Main) {
                    val displayText: String
                    val ttsText: String
                    if (!isChinese && reply.contains("【译】")) {
                        val original = reply.substringBefore("【译】").trim()
                        val translation = reply.substringAfter("【译】").trim()
                        displayText = "💭 $original\n$translation"
                        ttsText = original
                    } else {
                        displayText = "💭 $reply"
                        ttsText = reply
                    }
                    tvAiComment.text = displayText
                    speakIfEnabled(ttsText, isChinese)
                }
            } catch (_: Exception) {}
        }
    }

    private fun speakIfEnabled(text: String, isChinese: Boolean) {
        if (!ttsEnabled || voiceId.isEmpty()) return
        val ttsText = if (!isChinese) {
            text.filter { c ->
                c.code !in 0x4E00..0x9FFF && c.code !in 0x3000..0x303F &&
                        c.code !in 0xFF00..0xFFEF && c !in "，。！？、；："
            }.trim()
        } else text
        if (ttsText.isNotEmpty()) {
            try { TTSManager(applicationContext).speak(ttsText, voiceId) } catch (_: Exception) {}
        }
    }

    // ── 记忆同步 ─────────────────────────────────────────
    private fun syncMemoryToAi() {
        if (aiId.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val focusMin = totalFocusSeconds / 60
                val dateStr = java.text.SimpleDateFormat("MM月dd日", java.util.Locale.CHINA).format(java.util.Date())
                val memText = "[番茄钟记录] ${dateStr} ${aiName}陪${getMyName()}进行了${taskType}，" +
                        "完成${completedPomodoros}个番茄钟（共约${focusMin}分钟），期间开小差${distractCount}次。"
                val cv = ContentValues().apply {
                    put("aiId", aiId); put("memoryText", memText)
                    put("category", "shared_event"); put("insertTime", System.currentTimeMillis())
                }
                DatabaseHelper(this@PomodoroActivity).writableDatabase.insert("MemoryBank", null, cv)
            } catch (_: Exception) {}
        }
    }

    private fun getMyName(): String {
        return try {
            val c = DatabaseHelper(this).readableDatabase.rawQuery("SELECT myName FROM MyProfile LIMIT 1", null)
            val n = if (c.moveToFirst()) c.getString(0) ?: "你" else "你"
            c.close(); n
        } catch (_: Exception) { "你" }
    }

    // ── 工具 ─────────────────────────────────────────────
    private fun loadAiInfo() {
        try {
            val pref = getSharedPreferences("AppConfig", MODE_PRIVATE)
            val c = DatabaseHelper(this).readableDatabase
                .rawQuery("SELECT realName, identityInfo FROM Contacts WHERE userId=?", arrayOf(aiId))
            if (c.moveToFirst()) { aiName = c.getString(0) ?: "TA"; aiPersona = c.getString(1) ?: "" }
            c.close()
            aiLang = pref.getString("aiLang_$aiId", "默认 (中文)") ?: "默认 (中文)"
            ttsEnabled = pref.getBoolean("ttsEnable_$aiId", false)
            voiceId = pref.getString("voiceId_$aiId", "") ?: ""
        } catch (_: Exception) {}
    }

    private fun loadBackgroundImage(uri: Uri) {
        try {
            val stream = contentResolver.openInputStream(uri)
            val bmp = android.graphics.BitmapFactory.decodeStream(stream)
            stream?.close()
            ivBackground.setImageBitmap(bmp)
        } catch (_: Exception) {}
    }

    // ── 设置弹窗 ─────────────────────────────────────────
    private fun showSettingDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(8))
        }
        // 任务类型
        layout.addView(TextView(this).apply { text = "任务类型"; textSize = 14f; setTextColor(Color.GRAY) })
        val typeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8); it.bottomMargin = dp(16) }
        }
        listOf("学习", "工作").forEach { type ->
            typeRow.addView(TextView(this).apply {
                text = type; textSize = 14f; gravity = Gravity.CENTER
                setPadding(dp(20), dp(8), dp(20), dp(8))
                setTextColor(if (type == taskType) Color.WHITE else Color.parseColor("#555555"))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(if (type == taskType) Color.parseColor("#FF3B30") else Color.parseColor("#EEEEEE"))
                }
                layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.marginEnd = dp(10) }
                setOnClickListener { taskType = type; tvPhase.text = if (isWorkPhase) "专注 · $taskType" else "休息时间" }
            })
        }
        layout.addView(typeRow)
        // 专注时长
        layout.addView(TextView(this).apply { text = "专注时长"; textSize = 14f; setTextColor(Color.GRAY) })
        val durRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8); it.bottomMargin = dp(16) }
        }
        listOf(15, 25, 45).forEach { mins ->
            durRow.addView(TextView(this).apply {
                text = "${mins}分"; textSize = 14f; gravity = Gravity.CENTER
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setTextColor(if (mins == workMinutes) Color.WHITE else Color.parseColor("#555555"))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(if (mins == workMinutes) Color.parseColor("#FF3B30") else Color.parseColor("#EEEEEE"))
                }
                layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.marginEnd = dp(10) }
                setOnClickListener { workMinutes = mins; if (!isRunning) resetTimer() }
            })
        }
        layout.addView(durRow)
        // 更换角色
        layout.addView(TextView(this).apply { text = "陪伴角色：$aiName"; textSize = 14f; setTextColor(Color.GRAY) })
        layout.addView(TextView(this).apply {
            text = "更换角色 ›"; textSize = 14f; setTextColor(Color.parseColor("#007AFF"))
            setPadding(0, dp(8), 0, dp(16))
            setOnClickListener { showAiPickerDialog() }
        })
        android.app.AlertDialog.Builder(this).setTitle("番茄钟设置").setView(layout).setPositiveButton("确定", null).show()
    }

    private fun showAiPickerDialog() {
        try {
            val db = DatabaseHelper(this).readableDatabase
            val c = db.rawQuery("SELECT userId, realName FROM Contacts", null)
            val ids = mutableListOf<String>(); val names = mutableListOf<String>()
            while (c.moveToNext()) { ids.add(c.getString(0)); names.add(c.getString(1)) }
            c.close()
            android.app.AlertDialog.Builder(this).setTitle("选择陪伴角色")
                .setItems(names.toTypedArray()) { _, which ->
                    aiId = ids[which]; aiName = names[which]
                    loadAiInfo()
                    tvTitle.text = "🍅 ${aiName}陪你专注"
                    // 加载该角色的背景
                    val savedBg = getSharedPreferences("AppConfig", MODE_PRIVATE)
                        .getString("pomodoroBackground_$aiId", "") ?: ""
                    if (savedBg.isNotEmpty()) {
                        loadBackgroundImage(Uri.parse(savedBg))
                    } else {
                        ivBackground.setImageDrawable(null)
                    }
                }.show()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try { mediaRecorder?.stop(); mediaRecorder?.release() } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        cameraHandler.removeCallbacksAndMessages(null)
        cameraProvider?.unbindAll()
        if (completedPomodoros > 0 || totalFocusSeconds > 60) syncMemoryToAi()
    }
}