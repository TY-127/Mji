package com.moon.aiphone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class McpOAuthActivity : AppCompatActivity() {
    private val pending by lazy { getSharedPreferences("McpOAuthPending", MODE_PRIVATE) }
    private val callbackUri = "mji://oauth/mcp"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "正在连接 MCP 授权服务…"
            textSize = 18f
            gravity = android.view.Gravity.CENTER
        })
        if (intent?.data?.scheme == "mji") handleCallback(intent.data!!) else startAuthorization()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.takeIf { it.scheme == "mji" }?.let { handleCallback(it) }
    }

    private fun startAuthorization() {
        val endpoint = intent.getStringExtra("endpoint").orEmpty()
        val metadataHint = intent.getStringExtra("metadata")?.takeIf { it.isNotBlank() }
        val displayName = intent.getStringExtra("name").orEmpty()
        if (endpoint.isBlank()) return fail("缺少 MCP 地址")
        Thread {
            try {
                val resourceMeta = fetchFirstJson(buildList {
                    if (metadataHint != null) add(metadataHint)
                    val uri = URI(endpoint)
                    add("${uri.scheme}://${uri.authority}/.well-known/oauth-protected-resource${uri.path}")
                    add("${uri.scheme}://${uri.authority}/.well-known/oauth-protected-resource")
                })
                val authServer = resourceMeta.optJSONArray("authorization_servers")?.optString(0)
                    ?: throw IllegalArgumentException("服务没有声明 OAuth 授权服务器")
                requireSafeHttps(authServer)
                val issuer = authServer.trimEnd('/')
                val authMeta = fetchFirstJson(listOf(
                    "$issuer/.well-known/oauth-authorization-server",
                    "${URI(issuer).scheme}://${URI(issuer).authority}/.well-known/oauth-authorization-server${URI(issuer).path}"
                ).distinct())
                val authorizationEndpoint = authMeta.optString("authorization_endpoint")
                val tokenEndpoint = authMeta.optString("token_endpoint")
                val registrationEndpoint = authMeta.optString("registration_endpoint")
                requireSafeHttps(authorizationEndpoint); requireSafeHttps(tokenEndpoint)
                if (registrationEndpoint.isBlank()) throw IllegalArgumentException("授权服务器不支持动态客户端注册，当前无法连接")
                requireSafeHttps(registrationEndpoint)

                val registration = JSONObject().apply {
                    put("client_name", "M叽")
                    put("redirect_uris", JSONArray().put(callbackUri))
                    put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
                    put("response_types", JSONArray().put("code"))
                    put("token_endpoint_auth_method", "none")
                }
                val regRequest = Request.Builder().url(registrationEndpoint)
                    .post(registration.toString().toRequestBody("application/json".toMediaTypeOrNull())).build()
                val reg = executeJson(regRequest)
                val clientId = reg.optString("client_id").ifBlank { throw IllegalArgumentException("客户端注册失败") }

                val verifier = randomUrlSafe(48)
                val challenge = Base64.encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                )
                val state = randomUrlSafe(24)
                pending.edit()
                    .putString("endpoint", endpoint).putString("name", displayName)
                    .putString("tokenEndpoint", tokenEndpoint).putString("clientId", clientId)
                    .putString("verifier", verifier).putString("state", state).apply()
                val scope = authMeta.optString("scopes_supported").ifBlank { "" }
                val authUri = Uri.parse(authorizationEndpoint).buildUpon()
                    .appendQueryParameter("response_type", "code")
                    .appendQueryParameter("client_id", clientId)
                    .appendQueryParameter("redirect_uri", callbackUri)
                    .appendQueryParameter("code_challenge", challenge)
                    .appendQueryParameter("code_challenge_method", "S256")
                    .appendQueryParameter("state", state)
                    .appendQueryParameter("resource", endpoint)
                    .apply { if (scope.isNotBlank() && !scope.startsWith("[")) appendQueryParameter("scope", scope) }
                    .build()
                runOnUiThread { startActivity(Intent(Intent.ACTION_VIEW, authUri)) }
            } catch (e: Exception) { fail(e.message ?: "OAuth 初始化失败") }
        }.start()
    }

    private fun handleCallback(uri: Uri) {
        val error = uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) return fail("授权被拒绝：$error")
        val state = uri.getQueryParameter("state")
        val code = uri.getQueryParameter("code")
        if (state.isNullOrBlank() || state != pending.getString("state", "")) return fail("OAuth state 校验失败")
        if (code.isNullOrBlank()) return fail("授权服务没有返回 code")
        Thread {
            try {
                val tokenEndpoint = pending.getString("tokenEndpoint", "").orEmpty()
                val clientId = pending.getString("clientId", "").orEmpty()
                val verifier = pending.getString("verifier", "").orEmpty()
                val endpoint = pending.getString("endpoint", "").orEmpty()
                val name = pending.getString("name", "").orEmpty()
                val tokenRequest = Request.Builder().url(tokenEndpoint).post(FormBody.Builder()
                    .add("grant_type", "authorization_code").add("code", code)
                    .add("redirect_uri", callbackUri).add("client_id", clientId)
                    .add("code_verifier", verifier).add("resource", endpoint).build()).build()
                val token = executeJson(tokenRequest).optString("access_token")
                    .ifBlank { throw IllegalArgumentException("授权服务没有返回 access_token") }
                val installed = McpManager(this).install(name, endpoint, token)
                pending.edit().clear().apply()
                runOnUiThread {
                    Toast.makeText(this, "OAuth 授权成功，已安装 ${installed.tools.length()} 个工具", Toast.LENGTH_LONG).show()
                    finish()
                }
            } catch (e: Exception) { fail(e.message ?: "OAuth 换取令牌失败") }
        }.start()
    }

    private fun fetchFirstJson(urls: List<String>): JSONObject {
        var last: Exception? = null
        urls.forEach { url ->
            try { requireSafeHttps(url); return executeJson(Request.Builder().url(url).get().build()) }
            catch (e: Exception) { last = e }
        }
        throw last ?: IllegalArgumentException("无法发现 OAuth 元数据")
    }

    private fun executeJson(request: Request): JSONObject = Http.client.newBuilder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        .newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalArgumentException("OAuth HTTP ${response.code}: ${text.take(160)}")
            JSONObject(text)
        }

    private fun requireSafeHttps(raw: String) {
        val uri = URI(raw)
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()) { "OAuth 地址必须是 HTTPS" }
        require(!uri.host.equals("localhost", true)) { "不允许本地 OAuth 地址" }
    }

    private fun randomUrlSafe(bytes: Int): String = ByteArray(bytes).also { SecureRandom().nextBytes(it) }.let {
        Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun fail(message: String) = runOnUiThread {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
