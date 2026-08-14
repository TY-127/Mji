package com.moon.aiphone

import android.content.Context
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val token: String,
    val tools: JSONArray,
    val enabled: Boolean = true
)

data class McpToolBinding(
    val exposedName: String,
    val server: McpServerConfig,
    val originalName: String,
    val description: String,
    val inputSchema: JSONObject,
    val dangerous: Boolean
)

class McpAuthRequiredException(
    val endpoint: String,
    val resourceMetadataUrl: String?,
    message: String = "这个 MCP 需要 OAuth 授权"
) : Exception(message)

class McpManager(private val context: Context) {
    private val pref = context.getSharedPreferences("McpConfig", Context.MODE_PRIVATE)
    private val jsonType = "application/json; charset=utf-8".toMediaTypeOrNull()

    fun listServers(): List<McpServerConfig> {
        val array = try { JSONArray(pref.getString("servers", "[]")) } catch (_: Exception) { JSONArray() }
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                add(McpServerConfig(
                    o.optString("id"), o.optString("name"), o.optString("url"),
                    o.optString("token"), o.optJSONArray("tools") ?: JSONArray(),
                    o.optBoolean("enabled", true)
                ))
            }
        }
    }

    fun install(name: String, url: String, token: String): McpServerConfig {
        val normalizedInput = normalizeInputUrl(url)
        val github = resolveGitHubRepository(normalizedInput)
        val endpoint = github?.first ?: normalizedInput
        val suggestedName = github?.second.orEmpty()
        validateRemoteUrl(endpoint)
        val tools = discoverTools(endpoint, token)
        require(tools.length() > 0) { "服务器没有公开任何工具" }
        val config = McpServerConfig(
            id = UUIDCompat.random(), name = name.ifBlank { suggestedName.ifBlank { URI(endpoint).host } },
            url = endpoint, token = token.trim(), tools = tools
        )
        val servers = listServers().filterNot { it.url == config.url }.toMutableList().apply { add(config) }
        saveServers(servers)
        return config
    }

    private fun normalizeInputUrl(raw: String): String {
        val value = raw.trim()
        return if (value.startsWith("github.com/", ignoreCase = true) ||
            value.startsWith("www.github.com/", ignoreCase = true)) "https://$value" else value
    }

    /** GitHub 仓库只能作为发现入口；只提取仓库明确声明的远程 HTTPS MCP。 */
    private fun resolveGitHubRepository(raw: String): Pair<String, String>? {
        val uri = try { URI(raw.trim()) } catch (_: Exception) { return null }
        if (!uri.host.equals("github.com", true)) return null
        val parts = uri.path.trim('/').split('/').filter { it.isNotBlank() }
        require(parts.size >= 2) { "GitHub 仓库链接不完整" }
        val owner = parts[0]
        val repo = parts[1].removeSuffix(".git")
        require(owner.matches(Regex("[A-Za-z0-9_.-]+")) && repo.matches(Regex("[A-Za-z0-9_.-]+"))) {
            "GitHub 仓库名称无效"
        }

        val candidates = mutableListOf<String>()
        var hasLocalPackage = false
        for (path in listOf("server.json", ".mcp/server.json", "mcp.json", ".mcp.json")) {
            val text = githubFile(owner, repo, path) ?: continue
            try {
                val json = JSONObject(text)
                val remotes = json.optJSONArray("remotes")
                if (remotes != null) for (i in 0 until remotes.length()) {
                    val remote = remotes.optJSONObject(i) ?: continue
                    val type = remote.optString("type")
                    if (type == "streamable-http" || type == "sse") candidates.add(remote.optString("url"))
                }
                collectJsonUrls(json, candidates)
                hasLocalPackage = hasLocalPackage || json.has("packages") || text.contains("\"command\"") || text.contains("\"stdio\"")
            } catch (_: Exception) {}
        }

        val readme = githubFile(owner, repo, "README.md").orEmpty()
        Regex("https://[^\\s\\])>\\\"']+", RegexOption.IGNORE_CASE).findAll(readme).forEach { match ->
            val candidate = match.value.trimEnd('.', ',', ';', ':')
            val lower = candidate.lowercase(Locale.US)
            if (!lower.contains("github.com") && !lower.contains("npmjs.com") &&
                (lower.endsWith("/mcp") || lower.contains("/mcp?") || lower.endsWith("/sse"))) {
                candidates.add(candidate)
            }
        }
        hasLocalPackage = hasLocalPackage || Regex("\\b(npx|uvx|python|docker)\\b", RegexOption.IGNORE_CASE).containsMatchIn(readme)

        val endpoint = candidates.firstOrNull { candidate ->
            !candidate.contains('{') && try { validateRemoteUrl(candidate); true } catch (_: Exception) { false }
        }
        if (endpoint == null) {
            if (hasLocalPackage) throw IllegalArgumentException("这个仓库只有本地 stdio/命令行 MCP，需要先部署成远程 HTTPS 服务")
            throw IllegalArgumentException("仓库中没有找到明确的远程 MCP 地址（server.json remotes 或 README /mcp 链接）")
        }
        return endpoint to repo
    }

    private fun collectJsonUrls(value: Any?, output: MutableList<String>) {
        when (value) {
            is JSONObject -> {
                value.keys().forEach { key ->
                    val child = value.opt(key)
                    if (key.equals("url", true) && child is String && child.startsWith("https://")) output.add(child)
                    else collectJsonUrls(child, output)
                }
            }
            is JSONArray -> for (i in 0 until value.length()) collectJsonUrls(value.opt(i), output)
        }
    }

    private fun githubFile(owner: String, repo: String, path: String): String? {
        val encodedPath = path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") }
        val request = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/contents/$encodedPath")
            .addHeader("Accept", "application/vnd.github.raw+json")
            .addHeader("User-Agent", "Mji-Android")
            .get().build()
        return Http.client.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .build().newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> response.body?.string()
                    404 -> null
                    403 -> throw IllegalArgumentException("GitHub 访问频率受限，请稍后再试")
                    else -> null
                }
            }
    }

    fun remove(id: String) = saveServers(listServers().filterNot { it.id == id })

    fun recentLogs(): JSONArray = try { JSONArray(pref.getString("logs", "[]")) } catch (_: Exception) { JSONArray() }

    fun toolBindings(): List<McpToolBinding> {
        val result = mutableListOf<McpToolBinding>()
        listServers().filter { it.enabled }.forEachIndexed { serverIndex, server ->
            for (i in 0 until server.tools.length()) {
                val tool = server.tools.optJSONObject(i) ?: continue
                val original = tool.optString("name")
                if (original.isBlank()) continue
                val description = tool.optString("description")
                val annotations = tool.optJSONObject("annotations")
                val dangerous = annotations?.optBoolean("readOnlyHint", false) != true &&
                    Regex("delete|remove|send|post|publish|write|create|update|edit|pay|purchase|transfer|删除|发送|发布|写入|创建|修改|支付|购买|转账", RegexOption.IGNORE_CASE)
                        .containsMatchIn("$original $description")
                result.add(McpToolBinding(
                    exposedName = "mcp_${serverIndex}_${original.replace(Regex("[^A-Za-z0-9_-]"), "_").take(45)}",
                    server = server,
                    originalName = original,
                    description = "[${server.name}] $description".take(900),
                    inputSchema = tool.optJSONObject("inputSchema") ?: JSONObject().put("type", "object"),
                    dangerous = dangerous
                ))
            }
        }
        return result.take(64)
    }

    fun openAiTools(bindings: List<McpToolBinding>): JSONArray = JSONArray().apply {
        bindings.forEach { binding ->
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", binding.exposedName)
                    put("description", binding.description)
                    put("parameters", binding.inputSchema)
                })
            })
        }
    }

    fun callTool(binding: McpToolBinding, arguments: JSONObject): String {
        validateRemoteUrl(binding.server.url)
        val init = rpc(binding.server, "initialize", JSONObject().apply {
            put("protocolVersion", "2025-11-25")
            put("capabilities", JSONObject())
            put("clientInfo", JSONObject().put("name", "Mji").put("version", "1.0"))
        }, 1, null)
        val session = init.second
        notifyInitialized(binding.server, session)
        val result = rpc(binding.server, "tools/call", JSONObject().apply {
            put("name", binding.originalName)
            put("arguments", arguments)
        }, 2, session).first
        logCall(binding, arguments, result)
        return result.toString().take(8000)
    }

    private fun discoverTools(url: String, token: String): JSONArray {
        val temp = McpServerConfig("preview", "preview", url, token, JSONArray())
        val init = rpc(temp, "initialize", JSONObject().apply {
            put("protocolVersion", "2025-11-25")
            put("capabilities", JSONObject())
            put("clientInfo", JSONObject().put("name", "Mji").put("version", "1.0"))
        }, 1, null)
        notifyInitialized(temp, init.second)
        return rpc(temp, "tools/list", JSONObject(), 2, init.second).first.optJSONArray("tools") ?: JSONArray()
    }

    private fun notifyInitialized(server: McpServerConfig, session: String?) {
        val body = JSONObject().apply {
            put("jsonrpc", "2.0"); put("method", "notifications/initialized"); put("params", JSONObject())
        }
        val builder = Request.Builder().url(server.url)
            .addHeader("Accept", "application/json, text/event-stream")
            .addHeader("MCP-Protocol-Version", "2025-11-25")
            .post(body.toString().toRequestBody(jsonType))
        if (server.token.isNotBlank()) builder.addHeader("Authorization", "Bearer ${server.token}")
        if (!session.isNullOrBlank()) builder.addHeader("Mcp-Session-Id", session)
        Http.client.newBuilder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
            .build().newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful && response.code != 202) error("MCP 初始化确认失败: HTTP ${response.code}")
            }
    }

    private fun rpc(server: McpServerConfig, method: String, params: JSONObject, id: Int, session: String?): Pair<JSONObject, String?> {
        val body = JSONObject().apply {
            put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
        }
        val builder = Request.Builder().url(server.url)
            .addHeader("Accept", "application/json, text/event-stream")
            .addHeader("MCP-Protocol-Version", "2025-11-25")
            .post(body.toString().toRequestBody(jsonType))
        if (server.token.isNotBlank()) builder.addHeader("Authorization", "Bearer ${server.token}")
        if (!session.isNullOrBlank()) builder.addHeader("Mcp-Session-Id", session)
        Http.client.newBuilder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            .build().newCall(builder.build()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (response.code == 401) {
                    val challenge = response.header("WWW-Authenticate").orEmpty()
                    val metadata = Regex("resource_metadata=\\\"([^\\\"]+)\\\"")
                        .find(challenge)?.groupValues?.getOrNull(1)
                    throw McpAuthRequiredException(server.url, metadata)
                }
                if (!response.isSuccessful) error("MCP HTTP ${response.code}: ${raw.take(200)}")
                val payload = if (raw.trimStart().startsWith("data:")) {
                    raw.lineSequence().firstOrNull { it.startsWith("data:") }?.removePrefix("data:")?.trim().orEmpty()
                } else raw
                val json = JSONObject(payload)
                json.optJSONObject("error")?.let { error(it.optString("message", "MCP 调用失败")) }
                return (json.optJSONObject("result") ?: JSONObject()) to response.header("Mcp-Session-Id")
            }
    }

    private fun validateRemoteUrl(raw: String) {
        val uri = try { URI(raw.trim()) } catch (_: Exception) { throw IllegalArgumentException("MCP 地址无效") }
        require(uri.scheme.equals("https", true)) { "只允许 HTTPS MCP 地址" }
        val host = uri.host ?: throw IllegalArgumentException("MCP 地址缺少域名")
        require(!host.equals("localhost", true) && !host.endsWith(".local", true)) { "不允许连接本地地址" }
        val addresses = try { InetAddress.getAllByName(host).toList() } catch (_: Exception) { throw IllegalArgumentException("无法解析 MCP 域名") }
        require(addresses.none { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress }) {
            "不允许连接内网地址"
        }
    }

    private fun saveServers(servers: List<McpServerConfig>) {
        val array = JSONArray()
        servers.forEach { s -> array.put(JSONObject().apply {
            put("id", s.id); put("name", s.name); put("url", s.url); put("token", s.token)
            put("tools", s.tools); put("enabled", s.enabled)
        }) }
        pref.edit().putString("servers", array.toString()).apply()
    }

    private fun logCall(binding: McpToolBinding, args: JSONObject, result: JSONObject) {
        val logs = try { JSONArray(pref.getString("logs", "[]")) } catch (_: Exception) { JSONArray() }
        logs.put(JSONObject().apply {
            put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date()))
            put("server", binding.server.name); put("tool", binding.originalName)
            put("arguments", args); put("result", result.toString().take(1000))
        })
        val trimmed = JSONArray()
        for (i in maxOf(0, logs.length() - 100) until logs.length()) trimmed.put(logs.get(i))
        pref.edit().putString("logs", trimmed.toString()).apply()
    }
}

private object UUIDCompat { fun random(): String = java.util.UUID.randomUUID().toString() }
