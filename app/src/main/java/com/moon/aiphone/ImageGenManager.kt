package com.moon.aiphone

import android.content.Context
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.TimeUnit

object ImageGenManager {

    fun generate(context: Context, prompt: String): String? {
        return try {
            val pref = context.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            var apiUrl = (pref.getString("imgApiUrl", "") ?: "").trimEnd('/')
            val apiKey = pref.getString("imgApiKey", "") ?: ""
            val model = pref.getString("imgModel", "")?.ifBlank { "gpt-image-1" } ?: "gpt-image-1"
            val negativePrompt = pref.getString("imgNegativePrompt", "") ?: ""

            android.util.Log.d("ImageGen", "开始生图 model=$model url=$apiUrl")

            if (apiUrl.isEmpty() || apiKey.isEmpty() || model.isEmpty()) {
                android.util.Log.e("ImageGen", "配置缺失 url=$apiUrl key=${apiKey.take(8)} model=$model")
                return null
            }

            val isGemini = model.contains("gemini", ignoreCase = true)
            val isReplicate = apiUrl.contains("replicate", ignoreCase = true)

            val endpoint = buildEndpoint(apiUrl, model, isGemini, isReplicate)
            android.util.Log.d("ImageGen", "endpoint=$endpoint")

            val bodyJson = when {
                isGemini -> JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", prompt) })
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", JSONArray().apply {
                            put("IMAGE")
                            put("TEXT")
                        })
                    })
                }

                isReplicate -> JSONObject().apply {
                    put("version", model)
                    put("input", JSONObject().apply {
                        put("prompt", prompt)
                        put("width", 1024)
                        put("height", 1024)
                        if (negativePrompt.isNotEmpty()) put("negative_prompt", negativePrompt)
                    })
                }

                else -> JSONObject().apply {
                    put("model", model)
                    put("prompt", prompt)
                    put("n", 1)
                    put("size", "1024x1024")
                    if (negativePrompt.isNotEmpty()) put("negative_prompt", negativePrompt)

                    // gpt-image-1 很多兼容站不接受 response_format，乱加会直接 400。
                    // dall-e 老接口才保留 b64_json。
                    if (model.contains("dall-e", ignoreCase = true)) {
                        put("response_format", "b64_json")
                    }
                }
            }

            android.util.Log.d("ImageGen", "请求体: ${bodyJson.toString().take(300)}")

            val reqBuilder = Request.Builder()
                .url(endpoint)
                .post(bodyJson.toString().toRequestBody("application/json".toMediaTypeOrNull()))

            if (isGemini && apiUrl.contains("generativelanguage.googleapis.com", ignoreCase = true)) {
                reqBuilder.addHeader("x-goog-api-key", apiKey)
            } else {
                reqBuilder.addHeader("Authorization", "Bearer $apiKey")
            }

            val client = Http.client.newBuilder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

            val resp = client.newCall(reqBuilder.build()).execute()
            val respBody = resp.body?.string() ?: ""
            android.util.Log.d("ImageGen", "响应 code=${resp.code} body=${respBody.take(500)}")

            if (!resp.isSuccessful || respBody.isBlank()) {
                android.util.Log.e("ImageGen", "请求失败 code=${resp.code} body=$respBody")
                return null
            }

            parseImageResponse(context, JSONObject(respBody), isGemini, isReplicate, apiKey, client)
        } catch (e: Exception) {
            android.util.Log.e("ImageGen", "生图异常: ${e.javaClass.simpleName} ${e.message}", e)
            null
        }
    }

    private fun buildEndpoint(apiUrl: String, model: String, isGemini: Boolean, isReplicate: Boolean): String {
        val base = apiUrl.trimEnd('/')
        return when {
            isGemini -> when {
                base.contains(":generateContent") -> base
                base.contains("/models/") -> "$base:generateContent"
                base.endsWith("/v1beta") || base.endsWith("/v1") -> "$base/models/$model:generateContent"
                else -> "$base/v1beta/models/$model:generateContent"
            }

            isReplicate -> if (base.endsWith("/predictions")) base else "$base/predictions"

            base.endsWith("/images/generations") -> base
            base.contains("/images/generations") -> base.substringBefore("/images/generations") + "/images/generations"
            base.endsWith("/chat/completions") -> base.replace("/chat/completions", "/images/generations")
            base.endsWith("/v1") -> "$base/images/generations"
            else -> "$base/v1/images/generations"
        }
    }

    private fun parseImageResponse(
        context: Context,
        json: JSONObject,
        isGemini: Boolean,
        isReplicate: Boolean,
        apiKey: String,
        client: OkHttpClient
    ): String? {
        return try {
            when {
                isGemini || json.has("candidates") -> {
                    val parts = json.getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")

                    var b64 = ""
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        val inline = when {
                            part.has("inlineData") -> part.getJSONObject("inlineData")
                            part.has("inline_data") -> part.getJSONObject("inline_data")
                            else -> null
                        }
                        if (inline != null) {
                            b64 = inline.optString("data", "")
                            if (b64.isNotEmpty()) break
                        }
                    }

                    if (b64.isEmpty()) {
                        android.util.Log.e("ImageGen", "Gemini返回中没有图片数据: $json")
                        return null
                    }

                    val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    saveBytesToLocal(context, bytes)
                }

                isReplicate || (json.has("id") && json.has("urls")) -> {
                    val getUrl = json.getJSONObject("urls").getString("get")
                    pollReplicateResult(context, getUrl, apiKey, client)
                }

                json.has("data") -> {
                    val dataArr = json.getJSONArray("data")
                    if (dataArr.length() == 0) return null
                    val first = dataArr.getJSONObject(0)
                    when {
                        first.has("b64_json") -> {
                            val bytes = android.util.Base64.decode(first.getString("b64_json"), android.util.Base64.DEFAULT)
                            saveBytesToLocal(context, bytes)
                        }
                        first.has("url") -> downloadToLocal(context, first.getString("url"))
                        else -> {
                            android.util.Log.e("ImageGen", "data[0]没有图片字段: $first")
                            null
                        }
                    }
                }

                json.has("url") -> downloadToLocal(context, json.getString("url"))

                json.has("image") -> {
                    val bytes = android.util.Base64.decode(json.getString("image"), android.util.Base64.DEFAULT)
                    saveBytesToLocal(context, bytes)
                }

                json.has("output") -> {
                    val output = json.get("output")
                    val imgUrl = when (output) {
                        is JSONArray -> if (output.length() > 0) output.getString(0) else ""
                        is String -> output
                        else -> ""
                    }
                    if (imgUrl.isBlank()) null else downloadToLocal(context, imgUrl)
                }

                else -> {
                    android.util.Log.e("ImageGen", "未知返回格式: $json")
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ImageGen", "解析响应异常: ${e.message}", e)
            null
        }
    }

    private fun pollReplicateResult(
        context: Context,
        getUrl: String,
        apiKey: String,
        client: OkHttpClient,
        maxAttempts: Int = 60
    ): String? {
        repeat(maxAttempts) { attempt ->
            Thread.sleep(5000)
            try {
                val resp = client.newCall(
                    Request.Builder().url(getUrl)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .build()
                ).execute()

                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                val status = json.optString("status")
                android.util.Log.d("ImageGen", "Replicate轮询 attempt=$attempt status=$status")

                when (status) {
                    "succeeded" -> {
                        val output = json.get("output")
                        val imgUrl = when (output) {
                            is JSONArray -> if (output.length() > 0) output.getString(0) else ""
                            is String -> output
                            else -> ""
                        }
                        return if (imgUrl.isBlank()) null else downloadToLocal(context, imgUrl)
                    }
                    "failed", "canceled" -> {
                        android.util.Log.e("ImageGen", "Replicate失败: $body")
                        return null
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ImageGen", "Replicate轮询异常: ${e.message}")
            }
        }
        android.util.Log.e("ImageGen", "Replicate超时")
        return null
    }

    fun generateFromChatContext(
        context: Context,
        aiName: String,
        aiPersona: String,
        aiAppearance: String = "",
        recentChat: String,
        onResult: (localPath: String?, usedPrompt: String) -> Unit
    ) {
        Thread {
            try {
                val pref = context.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                var chatUrl = (pref.getString("apiUrl", "") ?: "").trimEnd('/')
                val chatKey = pref.getString("apiKey", "") ?: ""
                val chatModel = pref.getString("modelName", "")?.ifBlank { "gpt-4o" } ?: "gpt-4o"

                if (chatUrl.isBlank() || chatKey.isBlank()) {
                    android.util.Log.e("ImageGen", "Prompt生成配置缺失 chatUrl=$chatUrl key=${chatKey.take(8)}")
                    onResult(null, "")
                    return@Thread
                }

                if (!chatUrl.endsWith("/chat/completions")) {
                    chatUrl += if (chatUrl.endsWith("/v1")) "/chat/completions" else "/v1/chat/completions"
                }

                val promptGenBody = JSONObject().apply {
                    put("model", chatModel)
                    put("max_tokens", 150)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", """
你是 $aiName，人设：$aiPersona。
${if (aiAppearance.isNotEmpty()) "你的外貌特征：$aiAppearance" else ""}
根据以下最近的对话，你决定给对方发一张照片。
对话记录：
$recentChat

请用英文生成一段适合图片生成模型的描述（60词以内），描述你要发的这张照片的内容，要符合你的人设和当前场景。
${if (aiAppearance.isNotEmpty()) "如果照片中出现你本人，必须严格符合你的外貌特征描述。" else ""}
只输出英文描述，不要任何解释，不要引号。
                            """.trimIndent())
                        })
                    })
                }.toString().toRequestBody("application/json".toMediaTypeOrNull())

                val promptResp = Http.client.newBuilder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
                    .newCall(
                        Request.Builder().url(chatUrl)
                            .addHeader("Authorization", "Bearer $chatKey")
                            .post(promptGenBody)
                            .build()
                    )
                    .execute()

                val promptBody = promptResp.body?.string() ?: ""
                android.util.Log.d("ImageGen", "Prompt响应 code=${promptResp.code} body=${promptBody.take(300)}")

                if (!promptResp.isSuccessful || promptBody.isBlank()) {
                    android.util.Log.e("ImageGen", "Prompt生成失败:${promptResp.code} body=$promptBody")
                    onResult(null, "")
                    return@Thread
                }

                val rawPrompt = JSONObject(promptBody)
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()

                val userPositivePrompt = pref.getString("imgUserPrompt", "") ?: ""
                val noFace = "first-person POV photo, shot from the person's own perspective looking outward, like a personal phone camera snapshot, may include own hands or items held, never show any face or head, focus on what the person sees in front of them, candid real-life moment"
                val imgPrompt = "$noFace, $rawPrompt${if (userPositivePrompt.isNotEmpty()) ", $userPositivePrompt" else ""}"
                val localPath = generate(context, imgPrompt)

                android.util.Log.d("ImageGen", "生图结果: $localPath")
                onResult(localPath, imgPrompt)
            } catch (e: Exception) {
                android.util.Log.e("ImageGen", "generateFromChatContext异常: ${e.message}", e)
                onResult(null, "")
            }
        }.start()
    }

    private fun downloadToLocal(context: Context, url: String): String? {
        return try {
            android.util.Log.d("ImageGen", "下载图片: $url")
            val conn = URL(url).openConnection()
            conn.connectTimeout = 60_000
            conn.readTimeout = 120_000
            conn.connect()

            val bytes = conn.getInputStream().use { it.readBytes() }
            saveBytesToLocal(context, bytes)
        } catch (e: Exception) {
            android.util.Log.e("ImageGen", "下载失败: ${e.message}", e)
            null
        }
    }

    private fun saveBytesToLocal(context: Context, bytes: ByteArray): String? {
        return try {
            if (bytes.isEmpty()) return null

            val fileName = "M叽_${System.currentTimeMillis()}.jpg"
            val internalFile = java.io.File(context.filesDir, fileName)
            java.io.FileOutputStream(internalFile).use { it.write(bytes) }
            val internalPath = "[REAL_IMG]${internalFile.absolutePath}"

            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(
                            android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_PICTURES + "/M叽"
                        )
                    }
                    val u = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    u?.let { uri ->
                        context.contentResolver.openOutputStream(uri)?.use { out -> out.write(bytes) }
                    }
                } else {
                    val dir = java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES),
                        "M叽"
                    )
                    if (!dir.exists()) dir.mkdirs()
                    java.io.FileOutputStream(java.io.File(dir, fileName)).use { it.write(bytes) }
                }
            } catch (e: Exception) {
                android.util.Log.w("ImageGen", "存相册失败但不影响显示: ${e.message}")
            }

            android.util.Log.d("ImageGen", "图片保存完成: $internalPath")
            internalPath
        } catch (e: Exception) {
            android.util.Log.e("ImageGen", "保存失败: ${e.message}", e)
            null
        }
    }
}