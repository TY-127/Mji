package com.moon.aiphone

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class McpSettingsActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private val manager by lazy { McpManager(this) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "MCP 工具"
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        scroll.addView(list)
        setContentView(scroll)
        render()
    }

    private fun render() {
        list.removeAllViews()
        list.addView(TextView(this).apply {
            text = "远程 MCP 工具"
            textSize = 22f
            setTextColor(Color.BLACK)
        })
        list.addView(TextView(this).apply {
            text = "支持 HTTPS MCP 地址或公开 GitHub 仓库。GitHub 导入只读取 server.json/配置和 README 中明确声明的远程地址，不会执行仓库代码。"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(8), 0, dp(16))
        })
        list.addView(Button(this).apply {
            text = "＋ 添加 MCP 地址或 GitHub 仓库"
            setOnClickListener { showInstallDialog() }
        })
        list.addView(Button(this).apply {
            text = "查看最近调用记录"
            setOnClickListener { showLogs() }
        })

        val servers = manager.listServers()
        if (servers.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "还没有安装 MCP 工具"
                gravity = Gravity.CENTER
                setPadding(0, dp(28), 0, dp(28))
                setTextColor(Color.GRAY)
            })
        }
        servers.forEach { server ->
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.WHITE); cornerRadius = dp(12).toFloat()
                }
                val lp = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
                layoutParams = lp
                addView(TextView(context).apply {
                    text = "${server.name} · ${server.tools.length()} 个工具"
                    textSize = 17f; setTextColor(Color.BLACK)
                })
                addView(TextView(context).apply {
                    text = server.url
                    textSize = 12f; setTextColor(Color.GRAY)
                })
                addView(Button(context).apply {
                    text = "查看工具 / 卸载"
                    setOnClickListener { showServer(server) }
                })
            })
        }
    }

    private fun showInstallDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
        }
        val name = EditText(this).apply { hint = "名称，例如：天气工具" }
        val url = EditText(this).apply { hint = "远程 /mcp 地址或 GitHub 仓库链接"; inputType = InputType.TYPE_TEXT_VARIATION_URI }
        val token = EditText(this).apply { hint = "Bearer Token（没有可留空）"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        box.addView(name); box.addView(url); box.addView(token)
        val dialog = AlertDialog.Builder(this).setTitle("导入 MCP")
            .setView(box).setNegativeButton("取消", null).setPositiveButton("检查并安装", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val endpoint = url.text.toString().trim()
                if (endpoint.isBlank()) { url.error = "请输入 MCP 地址"; return@setOnClickListener }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).text = "正在检查…"
                Thread {
                    try {
                        val installed = manager.install(name.text.toString().trim(), endpoint, token.text.toString())
                        runOnUiThread {
                            dialog.dismiss()
                            Toast.makeText(this, "已安装 ${installed.tools.length()} 个工具", Toast.LENGTH_SHORT).show()
                            render()
                        }
                    } catch (auth: McpAuthRequiredException) {
                        runOnUiThread {
                            dialog.dismiss()
                            startActivity(android.content.Intent(this, McpOAuthActivity::class.java).apply {
                                putExtra("endpoint", auth.endpoint)
                                putExtra("metadata", auth.resourceMetadataUrl.orEmpty())
                                putExtra("name", name.text.toString().trim())
                            })
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).text = "检查并安装"
                            Toast.makeText(this, e.message ?: "安装失败", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun showServer(server: McpServerConfig) {
        val names = buildList {
            for (i in 0 until server.tools.length()) {
                val tool = server.tools.optJSONObject(i) ?: continue
                add("• ${tool.optString("name")}\n  ${tool.optString("description").take(120)}")
            }
        }.joinToString("\n\n")
        AlertDialog.Builder(this).setTitle(server.name)
            .setMessage(names.ifBlank { "没有工具" })
            .setNegativeButton("关闭", null)
            .setPositiveButton("卸载") { _, _ ->
                manager.remove(server.id); render()
            }.show()
    }

    private fun showLogs() {
        val logs = manager.recentLogs()
        val text = buildList {
            for (i in maxOf(0, logs.length() - 30) until logs.length()) {
                val log = logs.optJSONObject(i) ?: continue
                add("${log.optString("time")} · ${log.optString("server")}\n${log.optString("tool")}\n参数：${log.opt("arguments")}")
            }
        }.asReversed().joinToString("\n\n")
        AlertDialog.Builder(this).setTitle("MCP 调用记录")
            .setMessage(text.ifBlank { "暂无调用记录" }).setPositiveButton("关闭", null).show()
    }
}
