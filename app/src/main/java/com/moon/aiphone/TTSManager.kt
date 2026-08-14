package com.moon.aiphone

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 二合一 TTS：根据设置里的 "ttsProvider" 自动选择走哪个服务器。
 *   ttsProvider = "siliconflow" → 硅基流动（默认）
 *   ttsProvider = "guannan"     → 朋友的服务器
 *
 * 对外接口完全不变（speak / stop / onTtsStart / onTtsEnd），
 * 所以 CallActivity、ChatActivity 里的调用一行都不用改。
 */
class TTSManager(private val context: Context) {

    private val isSpeaking = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null

    // 电台人声音量：默认 70%。RadioActivity 的滑杆会调用 setPlaybackVolume() 实时修改。
    private var playbackVolume: Float = 0.70f

    var onTtsStart: (() -> Unit)? = null
    var onTtsEnd: (() -> Unit)? = null

    fun setPlaybackVolume(percent: Int) {
        playbackVolume = (percent.coerceIn(0, 100) / 100f).coerceIn(0f, 1f)
        mainHandler.post {
            try {
                mediaPlayer?.setVolume(playbackVolume, playbackVolume)
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.reset()
                it.release()
            }
        } catch (_: Exception) {
        } finally {
            mediaPlayer = null
            isSpeaking.set(false)
        }
    }

    fun speak(text: String, voiceId: String = "FunAudioLLM/CosyVoice2-0.5B") {
        val pref = context.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val provider = pref.getString("ttsProvider", "siliconflow") ?: "siliconflow"
        if (provider == "guannan") {
            speakGuannan(text, voiceId)
        } else {
            speakSiliconFlow(text, voiceId)
        }
    }

    private fun sanitizeSpeechText(raw: String): String {
        return raw
            .replace(Regex("<meta>[\\s\\S]*?</meta>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("【\\s*(翻译|译文|评论翻译|私聊翻译)\\s*】[：:]?[\\s\\S]*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[\\s*(翻译|译文|评论翻译|私聊翻译)\\s*\\][：:]?[\\s\\S]*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("【\\s*(原文|台词|评论|私聊)\\s*】[：:]?"), "")
            .replace(Regex("\\*[^*]{1,80}\\*"), "")
            .replace(Regex("（[^）]{1,80}(?:动作|走|看|笑|叹气|沉默|停顿|握|抱|靠|坐|站|转身|低头|抬头)[^）]*）"), "")
            .replace(Regex("\\([^)]{1,80}(?:action|smile|laugh|sigh|pause|walk|look|hug|sit|stand)[^)]*\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[\\[【]\\s*[^\\]】]{0,12}(?:动作|心声|内心|翻译)[^\\]】]{0,12}[\\]】]"), "")
            .replace(Regex("[\\r\\n]+"), " ")
            .trim()
    }

    /**
     * 判断清洗后的文本里是否还有"能读出来"的内容。
     * 只有标点 / 省略号 / 破折号 / emoji / 空白的字符串（例如清洗掉动作描写后只剩 "……" 或 "——"），
     * 会让朋友服务器合成出空/异响音频，所以这类要当成空文本跳过，不发出去。
     * 判据：至少要含一个字母、汉字或数字。
     */
    private fun hasSpeakableContent(s: String): Boolean {
        return s.any { Character.isLetterOrDigit(it) }
    }

    // ========== 硅基流动（你原来的逻辑，原样保留）==========
    private fun speakSiliconFlow(text: String, voiceId: String) {
        thread {
            var audioFile: File? = null
            try {
                val pref = context.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                val apiKey = pref.getString("ttsApiKey", "") ?: ""
                if (apiKey.isBlank()) {
                    Log.e("TTS_DEBUG", "ttsApiKey为空")
                    return@thread
                }

                val aiLang = pref.getString("aiLang_current", "默认 (中文)") ?: "默认 (中文)"

                var cleanText = sanitizeSpeechText(text)

                if (aiLang.contains("英", ignoreCase = true) || aiLang.contains("English", ignoreCase = true)) {
                    cleanText = cleanText
                        .replace(Regex("（[\\u4e00-\\u9fff\\s\\S]*?）"), "")
                        .replace(Regex("\\([\\u4e00-\\u9fff\\s\\S]*?\\)"), "")
                        .replace(Regex("[\\u4e00-\\u9fff\\u3000-\\u303f\\uff00-\\uffef]+"), "")
                        .trim()
                }

                if (cleanText.isBlank() || !hasSpeakableContent(cleanText)) {
                    Log.e("TTS_DEBUG", "cleanText无可朗读内容(空或纯标点)，跳过: '$cleanText'")
                    return@thread
                }

                val conn = (URL("https://api.siliconflow.cn/v1/audio/speech")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 20000
                    readTimeout = 30000
                    doOutput = true
                }

                val payload = JSONObject().apply {
                    put("model", "FunAudioLLM/CosyVoice2-0.5B")
                    put("voice", voiceId)
                    put("input", cleanText)
                    put("response_format", "mp3")
                    put("speed", 1.0)
                    put("gain", 0)
                }.toString()

                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                if (code != 200) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    Log.e("TTS_DEBUG", "硅基流动失败 code=$code err=$err")
                    return@thread
                }

                audioFile = File(context.cacheDir, "ai_voice_${System.currentTimeMillis()}.mp3")
                conn.inputStream.use { input ->
                    FileOutputStream(audioFile).use { output -> input.copyTo(output) }
                }

                if (!audioFile.exists() || audioFile.length() <= 0L) {
                    Log.e("TTS_DEBUG", "音频文件为空")
                    return@thread
                }

                playFile(audioFile)
            } catch (e: Exception) {
                Log.e("TTS_DEBUG", "speak异常(硅基): ${e.message}")
                try { audioFile?.delete() } catch (_: Exception) {}
            }
        }
    }

    // ========== 朋友的服务器 ==========
    private fun speakGuannan(text: String, voiceId: String) {
        thread {
            var audioFile: File? = null
            try {
                val pref = context.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                val apiKey = pref.getString("guannanApiKey", "") ?: ""
                if (apiKey.isBlank()) {
                    Log.e("TTS_DEBUG", "guannanApiKey为空")
                    return@thread
                }
                val apiUrl = pref.getString(
                    "guannanApiUrl", "http://47.83.255.223:8080/guannan"
                )?.trim().orEmpty()
                if (!apiUrl.startsWith("https://") && !apiUrl.startsWith("http://")) {
                    Log.e("TTS_DEBUG", "朋友 TTS 服务器地址未配置或无效")
                    return@thread
                }

                // 角色设置里的音色 ID 直接交给朋友服务器处理。这里不能维护声音白名单：
                // 服务器新增音色后，旧白名单会把它误判为无效并静默回退成 krueger。
                // 只有角色没有配置音色，或调用方使用了硅基 TTS 的默认模型占位值时，
                // 才使用朋友服务器的全局默认音色。
                val defaultVoice = pref.getString("guannanVoice", "krueger")
                    ?.trim()
                    .orEmpty()
                    .ifBlank { "krueger" }
                val configuredVoice = voiceId.trim()
                val voice = configuredVoice.takeUnless {
                    it.isBlank() || it == "FunAudioLLM/CosyVoice2-0.5B"
                } ?: defaultVoice

                val targetLang = pref.getString("guannanLang", "en") ?: "en"

                val cleanText = sanitizeSpeechText(text)
                // 注意：朋友的服务器会自己翻译成 target_lang，所以这里不删中文，原样发过去就行。

                if (cleanText.isBlank() || !hasSpeakableContent(cleanText)) {
                    Log.e("TTS_DEBUG", "cleanText无可朗读内容(空或纯标点)，跳过: '$cleanText'")
                    return@thread
                }

                val conn = (URL(apiUrl)
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 20000
                    readTimeout = 60000   // 翻译+合成，给久一点
                    doOutput = true
                }

                val payload = JSONObject().apply {
                    put("text", cleanText)
                    put("voice", voice)
                    put("target_lang", targetLang)
                    put("api_key", apiKey)
                }.toString()

                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val body = if (code == 200) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    Log.e("TTS_DEBUG", "朋友服务器失败 code=$code err=$err")
                    return@thread
                }

                val json = JSONObject(body)
                if (!json.optBoolean("success", false)) {
                    Log.e("TTS_DEBUG", "朋友服务器返回失败: ${json.optString("error")}")
                    return@thread
                }

                val audioBase64 = json.optString("audio", "")
                if (audioBase64.isBlank()) {
                    Log.e("TTS_DEBUG", "audio字段为空")
                    return@thread
                }
                Log.e("TTS_DEBUG", "剩余额度: ${json.optInt("remaining_quota", -1)}")

                val audioBytes = Base64.decode(audioBase64, Base64.DEFAULT)
                audioFile = File(context.cacheDir, "ai_voice_${System.currentTimeMillis()}.mp3")
                FileOutputStream(audioFile).use { it.write(audioBytes) }

                if (!audioFile.exists() || audioFile.length() <= 0L) {
                    Log.e("TTS_DEBUG", "音频文件为空")
                    return@thread
                }

                playFile(audioFile)
            } catch (e: Exception) {
                Log.e("TTS_DEBUG", "speak异常(朋友): ${e.message}")
                try { audioFile?.delete() } catch (_: Exception) {}
            }
        }
    }

    // ========== 公用播放逻辑（两个服务器都用这一套）==========
    private fun playFile(file: File) {
        mainHandler.post {
            try {
                stop()

                val mp = MediaPlayer()
                mediaPlayer = mp

                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )

                mp.setDataSource(file.absolutePath)

                mp.setOnPreparedListener { player ->
                    isSpeaking.set(true)
                    onTtsStart?.invoke()
                    player.setVolume(playbackVolume, playbackVolume)
                    player.start()
                    Log.e("TTS_DEBUG", "开始播放，人声音量=${(playbackVolume * 100).toInt()}%")
                }

                mp.setOnCompletionListener { player ->
                    isSpeaking.set(false)
                    onTtsEnd?.invoke()
                    try { player.reset(); player.release() } catch (_: Exception) {}
                    if (mediaPlayer === player) mediaPlayer = null
                    try { file.delete() } catch (_: Exception) {}
                }

                mp.setOnErrorListener { player, what, extra ->
                    Log.e("TTS_DEBUG", "播放错误 what=$what extra=$extra")
                    isSpeaking.set(false)
                    onTtsEnd?.invoke()
                    try { player.reset(); player.release() } catch (_: Exception) {}
                    if (mediaPlayer === player) mediaPlayer = null
                    try { file.delete() } catch (_: Exception) {}
                    true
                }

                mp.prepareAsync()
            } catch (e: Exception) {
                Log.e("TTS_DEBUG", "播放异常: ${e.message}")
                isSpeaking.set(false)
                onTtsEnd?.invoke()
            }
        }
    }
}
