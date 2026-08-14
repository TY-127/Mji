package com.moon.aiphone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class CallActivity : AppCompatActivity() {

    private var aiId = ""
    private var aiName = ""
    private var aiPersona = ""
    private var voiceId = ""
    private var aiLang = "默认 (中文)"

    private lateinit var tvAiName: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var btnMic: ImageView
    private lateinit var btnHangup: ImageView
    private lateinit var waveView: WaveAnimView

    // ★ 新增：通话中文字输入
    private lateinit var editInput: EditText
    private lateinit var btnSendText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var durationSeconds = 0
    private var durationTimer: Runnable? = null

    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var isRecording = false
    private var isAiSpeaking = false
    private val amplitudeSamples = mutableListOf<Pair<Long, Int>>()
    private var recordingStartedAt = 0L
    private var amplitudeSampler: Runnable? = null

    private val ttsManager by lazy { TTSManager(this) }
    private val callHistory = mutableListOf<JSONObject>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        aiId = intent.getStringExtra("AI_ID") ?: ""
        aiName = intent.getStringExtra("AI_NAME") ?: ""

        try {
            val db = DatabaseHelper(this).readableDatabase
            db.rawQuery(
                """
    SELECT identityInfo
    FROM Contacts
    WHERE userId=?
    ORDER BY id DESC
    LIMIT 1
    """.trimIndent(),
                arrayOf(aiId)
            ).use { c ->
                if (c.moveToFirst()) aiPersona = c.getString(0) ?: ""
            }
        } catch (_: Exception) {}

        val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        voiceId = pref.getString("voiceId_$aiId", "") ?: ""
        aiLang = pref.getString("aiLang_$aiId", "默认 (中文)") ?: "默认 (中文)"

        buildUI()
        requestMicPermission()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    // ★ 语音识别是否已配置（独立key，缺省回退到硅基流动 ttsApiKey）
    private fun sttConfigured(): Boolean {
        val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val k = (pref.getString("sttApiKey", "") ?: "").ifBlank { pref.getString("ttsApiKey", "") ?: "" }
        return k.isNotBlank()
    }

    private fun buildUI() {
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#1C1C1E"))
            layoutParams = android.widget.FrameLayout.LayoutParams(-1, -1)
        }

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = android.widget.FrameLayout.LayoutParams(-1, -1).also {
                it.gravity = android.view.Gravity.CENTER
            }
        }

        val ivAvatar = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(100), dp(100)).also {
                it.gravity = android.view.Gravity.CENTER_HORIZONTAL
                it.bottomMargin = dp(20)
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor("#3A3A3C"))
            }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            try {
                val db = DatabaseHelper(this@CallActivity).readableDatabase
                db.rawQuery("SELECT avatarUri FROM Contacts WHERE userId=?", arrayOf(aiId))
                    .use { c ->
                        if (c.moveToFirst()) {
                            val uri = c.getString(0) ?: ""
                            if (uri.isNotEmpty()) {
                                if (uri.startsWith("/")) {
                                    val bmp = android.graphics.BitmapFactory.decodeFile(uri)
                                    if (bmp != null) {
                                        setImageBitmap(bmp)
                                        scaleType = ImageView.ScaleType.CENTER_CROP
                                    }
                                } else {
                                    setImageURI(android.net.Uri.parse(uri))
                                    scaleType = ImageView.ScaleType.CENTER_CROP
                                }
                                scaleType = ImageView.ScaleType.CENTER_CROP
                            }
                        }
                    }
            } catch (_: Exception) {
            }
        }
        center.addView(ivAvatar)

        tvAiName = TextView(this).apply {
            text = aiName
            textSize = 26f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) }
        }
        center.addView(tvAiName)

        tvStatus = TextView(this).apply {
            text = "正在连接…"
            textSize = 15f
            setTextColor(android.graphics.Color.parseColor("#8E8E93"))
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(4) }
        }
        center.addView(tvStatus)

        tvDuration = TextView(this).apply {
            text = "00:00"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#636366"))
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(24) }
            visibility = View.GONE
        }
        center.addView(tvDuration)

        waveView = WaveAnimView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(120), dp(40)).also {
                it.gravity = android.view.Gravity.CENTER_HORIZONTAL
                it.bottomMargin = dp(16)
            }
            visibility = View.INVISIBLE
        }
        center.addView(waveView)

        tvSubtitle = TextView(this).apply {
            text = ""
            textSize = 15f
            setTextColor(android.graphics.Color.parseColor("#EBEBF5"))
            gravity = android.view.Gravity.CENTER
            setPadding(dp(32), dp(8), dp(32), dp(8))
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(32) }
        }
        center.addView(tvSubtitle)

        val tvHint = TextView(this).apply {
            text = "点麦克风说话，或在下方打字"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#636366"))
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) }
        }
        center.addView(tvHint)

        root.addView(center)

        // ★ 底部容器：上面一行文字输入，下面一行麦克风+挂断
        val bottomContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = android.widget.FrameLayout.LayoutParams(-1, -2).also {
                it.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                it.bottomMargin = dp(40)
            }
        }

        // ── 文字输入行 ──
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).also {
                it.bottomMargin = dp(24)
                it.leftMargin = dp(24); it.rightMargin = dp(24)
            }
        }

        editInput = EditText(this).apply {
            hint = "在这里打字和TA说话…"
            setHintTextColor(android.graphics.Color.parseColor("#8E8E93"))
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            maxLines = 3
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(android.graphics.Color.parseColor("#2C2C2E"))
            }
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.rightMargin = dp(8) }
        }
        inputRow.addView(editInput)

        btnSendText = TextView(this).apply {
            text = "发送"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(android.graphics.Color.parseColor("#0A84FF"))
            }
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        inputRow.addView(btnSendText)

        bottomContainer.addView(inputRow)

        // ── 麦克风 + 挂断行 ──
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        btnMic = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).also { it.marginEnd = dp(48) }
            setImageResource(android.R.drawable.ic_btn_speak_now)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor("#2C2C2E"))
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        btnRow.addView(btnMic)

        btnHangup = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor("#FF3B30"))
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        btnRow.addView(btnHangup)

        bottomContainer.addView(btnRow)
        root.addView(bottomContainer)
        setContentView(root)

        btnHangup.setOnClickListener { endCall() }

        // ★ 文字发送
        btnSendText.setOnClickListener { sendTextInput() }

        btnMic.setOnClickListener {
            if (isAiSpeaking) return@setOnClickListener
            if (isRecording) {
                stopRecordingAndTranscribe()
            } else {
                if (!sttConfigured()) {
                    Toast.makeText(
                        this,
                        "还没配置语音识别，可以直接在下方打字；或去设置里填识别服务",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                startRecording()
            }
        }
    }

    // ★ 通话中文字输入：不依赖任何识别服务，AI 照样语音回复
    private fun sendTextInput() {
        if (isAiSpeaking) {
            Toast.makeText(this, "等TA说完再发哦", Toast.LENGTH_SHORT).show()
            return
        }
        if (isRecording) return
        val text = editInput.text.toString().trim()
        if (text.isEmpty()) return

        editInput.setText("")
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(editInput.windowToken, 0)
        } catch (_: Exception) {}

        tvSubtitle.text = "你：$text"
        sendToAi(text)
    }

    private fun requestMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        } else {
            onCallConnected()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            onCallConnected()
        } else {
            // ★ 没给麦克风权限也不挂断了——还能打字通话
            Toast.makeText(this, "没有麦克风权限，可以用下方文字框打字通话", Toast.LENGTH_LONG).show()
            onCallConnected()
        }
    }

    private fun onCallConnected() {
        tvStatus.text = "通话中"
        tvDuration.visibility = View.VISIBLE
        startDurationTimer()
        sendToAi("[通话刚接通，你先打个招呼]", isSystem = true)
    }

    private fun startDurationTimer() {
        if (durationTimer != null) return
        durationTimer = object : Runnable {
            override fun run() {
                durationSeconds++
                val m = durationSeconds / 60
                val s = durationSeconds % 60
                tvDuration.text = "%02d:%02d".format(m, s)
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(durationTimer!!, 1000)
    }

    private fun startRecording() {
        if (isRecording) return
        // 没麦克风权限时直接提示用文字
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "没有麦克风权限，请用下方文字框打字", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            recordingFile = File(cacheDir, "call_record_${System.currentTimeMillis()}.m4a")
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(128000)
                setOutputFile(recordingFile!!.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            startAmplitudeSampling()
            tvStatus.text = "录音中…"
            waveView.visibility = View.VISIBLE
            waveView.start()
            (btnMic.background as? android.graphics.drawable.GradientDrawable)
                ?.setColor(android.graphics.Color.parseColor("#30D158"))
        } catch (e: Exception) {
            android.util.Log.d("CALL", "录音失败: ${e.message}")
            Toast.makeText(this, "录音失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecordingAndTranscribe() {
        if (!isRecording) return
        try {
            stopAmplitudeSampling()
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
            (btnMic.background as? android.graphics.drawable.GradientDrawable)
                ?.setColor(android.graphics.Color.parseColor("#2C2C2E"))
            tvStatus.text = "识别中…"

            val file = recordingFile ?: return
            if (!file.exists() || file.length() < 1000) {
                waveView.stop()
                waveView.visibility = View.INVISIBLE
                tvStatus.text = "通话中"
                return
            }

            // ★ 可配置的语音识别：地址/密匙/模型都独立，缺省回退到硅基流动
            val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            val sttUrlBase = (pref.getString("sttApiUrl", "") ?: "").trimEnd('/')
                .ifBlank { "https://api.siliconflow.cn/v1" }
            val sttKey = (pref.getString("sttApiKey", "") ?: "")
                .ifBlank { pref.getString("ttsApiKey", "") ?: "" }
            val sttModel = (pref.getString("sttModel", "") ?: "")
                .ifBlank { "iic/SenseVoiceSmall" }
            val sttUrl = if (sttUrlBase.endsWith("/audio/transcriptions"))
                sttUrlBase else "$sttUrlBase/audio/transcriptions"

            if (sttKey.isBlank()) {
                waveView.stop()
                waveView.visibility = View.INVISIBLE
                tvStatus.text = "通话中"
                try { file.delete() } catch (_: Exception) {}
                Toast.makeText(this, "还没配置语音识别，请直接打字，或去设置填识别服务", Toast.LENGTH_LONG).show()
                return
            }

            Thread {
                try {
                    // 与转写并行分析原始声音；大多数情况下不会额外增加等待时间。
                    val moodFuture = startVoiceMoodAnalysis(file)
                    val requestBody = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("model", sttModel)
                        .addFormDataPart(
                            "file", file.name,
                            file.asRequestBody("audio/m4a".toMediaTypeOrNull())
                        )
                        .build()

                    val req = Request.Builder()
                        .url(sttUrl)
                        .addHeader("Authorization", "Bearer $sttKey")
                        .post(requestBody)
                        .build()

                    val resp = Http.client.newBuilder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build().newCall(req).execute()

                    if (!resp.isSuccessful) {
                        throw RuntimeException("STT失败:${resp.code}")
                    }

                    val result = resp.body?.string() ?: ""

                    val rawText = JSONObject(result)
                        .optString("text", "")
                        .trim()
// 提取情绪标签（仅 SenseVoice 有；换别的识别模型时自动为空，不影响）
                    val emotionMap = mapOf(
                        "<|HAPPY|>" to "😊 开心愉快",
                        "<|SAD|>" to "😢 有些难过",
                        "<|ANGRY|>" to "😠 语气生气",
                        "<|FEARFUL|>" to "😨 有些害怕",
                        "<|DISGUSTED|>" to "😒 语气厌烦",
                        "<|SURPRISED|>" to "😲 惊讶",
                        "<|NEUTRAL|>" to "😐 平静"
                    )
                    val detectedEmotion = emotionMap.entries.firstOrNull { rawText.contains(it.key) }?.value ?: ""
                    val pauseHint = analyzeSpeechPauses()
                    val voiceMood = try {
                        moodFuture?.get(8, TimeUnit.SECONDS).orEmpty()
                    } catch (e: Exception) {
                        moodFuture?.cancel(true)
                        android.util.Log.d("CALL", "副语言分析未及时返回，使用降级结果: ${e.message}")
                        ""
                    }
// 去掉情绪标签，只保留纯文字
                    val text = rawText.replace(Regex("<\\|[A-Z_]+\\|>"), "").trim()
                    file.delete()

                    if (text.isNotEmpty()) {
                        runOnUiThread {
                            val emotionHint = if (detectedEmotion.isNotEmpty()) " $detectedEmotion" else ""
                            val pauseDisplay = if (pauseHint.isNotEmpty()) " · $pauseHint" else ""
                            val moodDisplay = if (voiceMood.isNotEmpty()) " · 已听出语气" else ""
                            tvSubtitle.text = "你：$text$emotionHint$pauseDisplay$moodDisplay"
                            waveView.stop()
                            waveView.visibility = View.INVISIBLE
                            val perception = buildList {
                                if (detectedEmotion.isNotEmpty()) add("识别到的情绪：$detectedEmotion")
                                if (pauseHint.isNotEmpty()) add("说话节奏：$pauseHint")
                                if (voiceMood.isNotEmpty()) add("原始声音分析：$voiceMood")
                            }
                            val textWithPerception = if (perception.isNotEmpty()) {
                                "$text（通话声学线索：${perception.joinToString("；")}。请自然理解这些线索并回应，不要复述分析结果。）"
                            } else text
                            sendToAi(textWithPerception)
                        }
                    } else {
                        runOnUiThread {
                            waveView.stop()
                            waveView.visibility = View.INVISIBLE
                            tvStatus.text = "通话中"
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        waveView.stop()
                        waveView.visibility = View.INVISIBLE
                        tvStatus.text = "通话中"
                        Toast.makeText(this@CallActivity, "识别失败，可以改用下方打字", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        } catch (e: Exception) {
            android.util.Log.d("CALL", "停止录音失败: ${e.message}")
        }
    }

    /**
     * MediaRecorder 每次读取 maxAmplitude 时会返回自上次读取后的峰值。
     * 以 100ms 为一帧记录振幅包络，用于发现用户一句话内部真实的犹豫和停顿。
     */
    private fun startAmplitudeSampling() {
        amplitudeSamples.clear()
        recordingStartedAt = android.os.SystemClock.elapsedRealtime()
        amplitudeSampler = object : Runnable {
            override fun run() {
                if (!isRecording) return
                val amplitude = try { mediaRecorder?.maxAmplitude ?: 0 } catch (_: Exception) { 0 }
                amplitudeSamples.add(
                    (android.os.SystemClock.elapsedRealtime() - recordingStartedAt) to amplitude
                )
                handler.postDelayed(this, 100L)
            }
        }.also { handler.post(it) }
    }

    private fun stopAmplitudeSampling() {
        amplitudeSampler?.let { handler.removeCallbacks(it) }
        amplitudeSampler = null
    }

    /**
     * 使用本次录音自己的低分位振幅估算环境底噪，避免固定阈值在不同手机/房间失效。
     * 只统计两个有声片段之间的静音，开头等用户准备和结尾点按钮的时间不会被误报。
     */
    private fun analyzeSpeechPauses(): String {
        if (amplitudeSamples.size < 8) return ""
        val amplitudes = amplitudeSamples.map { it.second }.sorted()
        val noiseFloor = amplitudes[(amplitudes.size * 0.25f).toInt().coerceIn(amplitudes.indices)]
        val peak = amplitudes.last()
        if (peak < 500) return ""

        val speechThreshold = maxOf(500, (noiseFloor * 2.5f).toInt())
            .coerceAtMost((peak * 0.45f).toInt().coerceAtLeast(500))
        val voiced = amplitudeSamples.map { it.second >= speechThreshold }
        val pauseDurations = mutableListOf<Long>()
        var seenSpeech = false
        var silenceStart = -1

        voiced.forEachIndexed { index, isVoice ->
            if (isVoice) {
                if (seenSpeech && silenceStart >= 0) {
                    val duration = amplitudeSamples[index].first - amplitudeSamples[silenceStart].first
                    if (duration >= 450L) pauseDurations.add(duration)
                }
                seenSpeech = true
                silenceStart = -1
            } else if (seenSpeech && silenceStart < 0) {
                silenceStart = index
            }
        }

        if (pauseDurations.isEmpty()) return ""
        val longest = pauseDurations.maxOrNull() ?: return ""
        val description = when {
            longest >= 1800L -> "有明显的长停顿，像是在犹豫或斟酌"
            pauseDurations.size >= 3 -> "多次短暂停顿，说话有些迟疑"
            longest >= 900L -> "有一处较长停顿"
            else -> "有短暂停顿"
        }
        return "$description（${pauseDurations.size}次，最长${"%.1f".format(Locale.US, longest / 1000.0)}秒）"
    }

    /**
     * 参考 VoxMood 的副语言分析思路，把同一份录音并行交给支持音频输入的多模态模型。
     * 返回空字符串代表功能未启用、配置不完整、接口失败或结果格式不兼容；主通话不会失败。
     */
    private fun startVoiceMoodAnalysis(file: File): CompletableFuture<String>? {
        val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        if (!pref.getBoolean("voiceMoodEnable", false)) return null
        val apiKey = pref.getString("voiceMoodApiKey", "")?.trim().orEmpty()
        if (apiKey.isBlank()) return null
        var apiUrl = pref.getString(
            "voiceMoodApiUrl", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
        )?.trim()?.trimEnd('/').orEmpty()
        if (apiUrl.isBlank()) return null
        if (!apiUrl.endsWith("/chat/completions")) {
            apiUrl += if (apiUrl.endsWith("/v1")) "/chat/completions" else "/v1/chat/completions"
        }
        val model = pref.getString("voiceMoodModel", "qwen-omni-turbo")
            ?.trim().orEmpty().ifBlank { "qwen-omni-turbo" }

        return CompletableFuture.supplyAsync {
            try {
                val audio = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
                val prompt = """
                    分析这段通话录音的副语言特征。只分析声音，不猜测说话内容之外的事实。
                    请简洁输出：情绪；语气；语速；音量；笑声、叹气、哭腔、颤抖、拖长音或尾音；声线质感；一句话状态总结。
                    若某项无法可靠判断就写“无法判断”，不要把普通静音夸大成情绪。
                """.trimIndent()
                val payload = JSONObject().apply {
                    put("model", model)
                    put("stream", false)
                    put("modalities", JSONArray().put("text"))
                    put("messages", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray()
                            .put(JSONObject().apply {
                                put("type", "input_audio")
                                put("input_audio", JSONObject().apply {
                                    put("data", "data:audio/mp4;base64,$audio")
                                    put("format", "m4a")
                                })
                            })
                            .put(JSONObject().apply {
                                put("type", "text")
                                put("text", prompt)
                            }))
                    }))
                }
                val request = Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()
                Http.client.newBuilder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()
                    .newCall(request)
                    .execute().use { response ->
                        if (!response.isSuccessful) {
                            android.util.Log.d("CALL", "副语言分析失败: HTTP ${response.code}")
                            return@use ""
                        }
                        val root = JSONObject(response.body?.string().orEmpty())
                        val content = root.optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("message")
                            ?.opt("content")
                        when (content) {
                            is String -> content.trim().take(800)
                            is JSONArray -> buildString {
                                for (i in 0 until content.length()) {
                                    val part = content.optJSONObject(i)?.optString("text", "").orEmpty()
                                    if (part.isNotBlank()) append(part).append('\n')
                                }
                            }.trim().take(800)
                            else -> ""
                        }
                    }
            } catch (e: Exception) {
                android.util.Log.d("CALL", "副语言分析失败，已降级: ${e.message}")
                ""
            }
        }
    }

    private fun sendToAi(userText: String, isSystem: Boolean = false) {
        val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        var apiUrl = (pref.getString("apiUrl", "") ?: "").trimEnd('/')
        val apiKey = pref.getString("apiKey", "") ?: ""
        val model = pref.getString(
            "modelName",
            ""
        )?.ifBlank { "gpt-4o" } ?: "gpt-4o"
        if (!apiUrl.endsWith("/chat/completions"))
            apiUrl += if (apiUrl.contains("/v1")) "/chat/completions" else "/v1/chat/completions"

        val isChinese = aiLang == "默认 (中文)"

        waveView.visibility = View.VISIBLE
        waveView.start()
        isAiSpeaking = true
        tvStatus.text = "${aiName}说话中…"

        if (!isSystem) {
            callHistory.add(JSONObject().apply {
                put("role", "user")
                put("content", userText)
            })
        }

        Thread {
            try {
                val nowTime = SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.CHINA).format(Date())

                val langBlock = if (isChinese) {
                    "【语言】：必须用中文说话，直接输出说话内容，不要任何格式标签。"
                } else {
                    "【语言强制规则】：你必须用${aiLang}（严禁英语，严禁中文，严禁其他语言）说话。\n输出格式（必须严格遵守，不要有多余文字）：\n【原文】（${aiLang}内容）\n【翻译】（对应中文翻译）"
                }

                val systemPrompt = """
你是 $aiName，人设：$aiPersona。
【当前场景】：你正在和用户通电话。
【当前时间】：$nowTime
$langBlock
【通话规则】：
1. 用口语化的语气，像真实打电话一样自然
2. 回复简短，一次说1-3句话
3. 不要有任何动作描写
                """.trimIndent()

                val messages = JSONArray().apply {
                    put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                    callHistory.forEach { put(it) }
                    if (isSystem) {
                        put(JSONObject().apply { put("role", "user"); put("content", userText) })
                    }
                }

                val body = JSONObject().apply {
                    put("model", model)
                    put("messages", messages)
                    put("max_tokens", 200)
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())

                val req = Request.Builder().url(apiUrl)
                    .addHeader("Authorization", "Bearer $apiKey").post(body).build()
                val resp = Http.client.newBuilder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build().newCall(req).execute()
                val raw = JSONObject(resp.body?.string() ?: return@Thread)
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()

                val ttsText: String
                val subtitleText: String

                if (isChinese) {
                    ttsText = raw
                    subtitleText = "$aiName：$raw"

                    callHistory.add(JSONObject().apply {
                        put("role", "assistant")
                        put("content", raw)
                    })

                    if (isSystem) {
                        callHistory.add(JSONObject().apply {
                            put("role", "user")
                            put("content", userText)
                        })
                    }

                } else {
                    val original = Regex("【原文】(.+?)(?=【翻译】|$)", RegexOption.MULTILINE)
                        .find(raw)?.groupValues?.get(1)?.trim() ?: raw
                    val translation = Regex("【翻译】(.+?)$", RegexOption.MULTILINE)
                        .find(raw)?.groupValues?.get(1)?.trim() ?: ""
                    ttsText = original
                    subtitleText = if (translation.isNotEmpty()) {
                        "$aiName：$original\n（$translation）"
                    } else {
                        "$aiName：$original"
                    }
                    callHistory.add(JSONObject().apply { put("role", "assistant"); put("content", original) })
                }

                runOnUiThread {
                    tvSubtitle.text = subtitleText
                    if (voiceId.isNotEmpty()) {
                        ttsManager.onTtsStart = {
                            waveView.visibility = View.VISIBLE
                            waveView.start()
                            tvStatus.text = "${aiName}说话中…"
                        }
                        ttsManager.onTtsEnd = {
                            isAiSpeaking = false
                            waveView.stop()
                            waveView.visibility = View.INVISIBLE
                            tvStatus.text = "通话中"
                        }
                        ttsManager.speak(ttsText, voiceId)
                    } else {
                        isAiSpeaking = false
                        waveView.stop()
                        waveView.visibility = View.INVISIBLE
                        tvStatus.text = "通话中"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isAiSpeaking = false
                    waveView.stop()
                    waveView.visibility = View.INVISIBLE
                    tvStatus.text = "通话中"
                }
            }
        }.start()
    }

    // 挂断时压缩通话内容写入记忆
    private fun saveCallMemory() {
        if (callHistory.size < 2) return
        Thread {
            try {
                val pref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                var apiUrl = (pref.getString("apiUrl", "") ?: "").trimEnd('/')
                val apiKey = pref.getString("apiKey", "") ?: ""
                val model = pref.getString("modelName", "") ?: ""
                if (apiKey.isEmpty() || apiUrl.isEmpty()) return@Thread
                if (!apiUrl.endsWith("/chat/completions"))
                    apiUrl += if (apiUrl.contains("/v1")) "/chat/completions" else "/v1/chat/completions"

                val callText = callHistory.filter {
                    it.optString("role") != "system"
                }.joinToString("\n") {
                    val role = if (it.optString("role") == "user") "用户" else aiName
                    "$role：${it.optString("content")}"
                }

                val today = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA).format(Date())
                val minutes = durationSeconds / 60
                val seconds = durationSeconds % 60

                val prompt = """
以下是今天（$today）用户和${aiName}的一段通话记录，通话时长 ${minutes}分${seconds}秒：

$callText

请用50-80字总结这通电话的关键内容，以${aiName}的视角，用第一人称写，
包含：聊了什么主题、用户说了什么重要的话、有没有约定或情绪波动。
直接输出总结内容，不要标题不要序号。
            """.trimIndent()

                val body = JSONObject().apply {
                    put("model", model)
                    put("max_tokens", 150)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                    })
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())

                val req = Request.Builder().url(apiUrl)
                    .addHeader("Authorization", "Bearer $apiKey").post(body).build()
                val resp = Http.client.newBuilder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build().newCall(req).execute()
                val summary = JSONObject(resp.body?.string() ?: return@Thread)
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()

                if (summary.isEmpty()) return@Thread

                val memoryText = "【通话记录 $today】$summary"
                val db = DatabaseHelper(this).writableDatabase
                val cv = android.content.ContentValues().apply {
                    put("aiId", aiId)
                    put("memoryText", memoryText)
                    put("category", "shared_event")           // ✅ 修正：原来是 memoryType
                    put("insertTime", System.currentTimeMillis()) // ✅ 修正：原来是 timestamp
                }
                db.insert("MemoryBank", null, cv)
                android.util.Log.d("CALL", "通话记忆已写入: $memoryText")
            } catch (e: Exception) {
                android.util.Log.d("CALL", "写入记忆失败: ${e.message}")
            }
        }.start()
    }

    private fun endCall() {
        durationTimer?.let { handler.removeCallbacks(it) }
        stopAmplitudeSampling()
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (_: Exception) {}

        try {
            recordingFile?.delete()
        } catch (_: Exception) {}
        ttsManager.stop()
        waveView.stop()
        saveCallMemory() // 挂断时保存通话记忆
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        durationTimer?.let { handler.removeCallbacks(it) }
        stopAmplitudeSampling()
        try { mediaRecorder?.stop(); mediaRecorder?.release() } catch (_: Exception) {}
        ttsManager.stop()
    }
}

class WaveAnimView(context: Context) : View(context) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.parseColor("#30D158")
        strokeWidth = 4f
        strokeCap = android.graphics.Paint.Cap.ROUND
        style = android.graphics.Paint.Style.STROKE
    }
    private val handler = Handler(Looper.getMainLooper())
    private var phase = 0f
    private var running = false

    private val animator = object : Runnable {
        override fun run() {
            phase += 0.15f
            invalidate()
            if (running) handler.postDelayed(this, 30)
        }
    }

    fun start() {
        running = true
        handler.post(animator)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(animator)
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = h / 2f
        val bars = 12
        val barW = w / (bars * 2f)
        for (i in 0 until bars) {
            val x = barW + i * (w / bars)
            val amp = if (running) (0.2f + 0.8f * Math.abs(Math.sin((phase + i * 0.5f).toDouble())).toFloat()) else 0.2f
            val barH = cx * amp
            canvas.drawLine(x, cx - barH, x, cx + barH, paint)
        }
    }
}
