package com.moon.aiphone

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorInt

/**
 * ThemeManager — 全局主题引擎
 *
 * 从 CSS 文件里解析 :root { --变量名: 值 } 并缓存。
 * 其他页面调用 ThemeManager.apply*(view) 方法应用颜色。
 * 聊天页（WebView）直接把原始CSS注入，不经过这里。
 */
object ThemeManager {

    // ── CSS 变量默认值（无主题时的兜底） ──────────────────────
    private val defaults = mapOf(
        "--app-bg"                    to "#F7F7F7",
        "--app-bg-image"              to "",          // 背景图URI，空=无图

        "--header-bg"                 to "#F7F7F7",
        "--header-title-color"        to "#111111",
        "--header-icon-color"         to "#111111",
        "--header-border-color"       to "#E5E5EA",

        "--bottom-nav-bg"             to "#FFFFFF",
        "--bottom-nav-icon-color"     to "#111111",
        "--bottom-nav-selected-color" to "#07C160",

        "--msg-list-bg"               to "transparent",
        "--msg-list-item-bg"          to "#FFFFFF",
        "--msg-list-item-radius"      to "0",
        "--msg-list-name-color"       to "#111111",
        "--msg-list-preview-color"    to "#888888",
        "--msg-list-time-color"       to "#BBBBBB",
        "--msg-list-divider-color"    to "#F0F0F0",

        "--contact-list-bg"           to "transparent",
        "--contact-item-bg"           to "#FFFFFF",
        "--contact-name-color"        to "#111111",

        "--discover-bg"               to "#F7F7F7",
        "--discover-item-bg"          to "#FFFFFF",
        "--discover-text-color"       to "#111111",

        "--profile-bg"                to "#F7F7F7",
        "--profile-name-color"        to "#111111",
        "--profile-item-bg"           to "#FFFFFF",
        "--profile-text-color"        to "#111111",

        // 聊天页变量（透传给WebView CSS，不在原生这边解析）
        "--bubble-me-bg"              to "#07C160",
        "--bubble-me-color"           to "#FFFFFF",
        "--bubble-them-bg"            to "#FFFFFF",
        "--bubble-them-color"         to "#111111",
        "--chat-bg"                   to "#F0F0F0",
    )

    // 已解析的变量表（运行时）
    private val vars = mutableMapOf<String, String>()
    private var rawCSS = ""

    // ── 初始化：从 SharedPreferences 加载已保存的CSS ──────────
    fun init(context: Context) {
        val saved = context.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            .getString("globalThemeCSS", "") ?: ""
        if (saved.isNotEmpty()) parseCSS(saved)
    }

    // ── 解析 CSS :root 变量 ───────────────────────────────────
    fun parseCSS(css: String) {
        rawCSS = css
        vars.clear()
        vars.putAll(defaults)

        // 提取 :root { ... } 块
        val rootBlock = Regex(":root\\s*\\{([^}]*)\\}", RegexOption.DOT_MATCHES_ALL)
            .find(css)?.groupValues?.get(1) ?: css

        // 逐行解析 --变量名: 值;
        Regex("(--[\\w-]+)\\s*:\\s*([^;\\n]+)[;\\n]").findAll(rootBlock).forEach { m ->
            val key = m.groupValues[1].trim()
            val value = m.groupValues[2].trim().trimEnd(';').trim()
            if (value.isNotEmpty()) vars[key] = value
        }
    }

    /** 保存CSS到本地并重新解析 */
    fun saveAndApply(context: Context, css: String) {
        context.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            .edit().putString("globalThemeCSS", css).apply()
        parseCSS(css)
    }

    /** 清除主题 */
    fun clear(context: Context) {
        context.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            .edit().remove("globalThemeCSS").apply()
        vars.clear()
        vars.putAll(defaults)
        rawCSS = ""
    }

    /** 获取原始CSS（透传给WebView） */
    fun getRawCSS(): String = rawCSS

    /** 是否有自定义主题 */
    fun hasTheme(): Boolean = rawCSS.isNotEmpty()

    // ── 取变量值 ─────────────────────────────────────────────
    fun get(key: String): String = vars[key] ?: defaults[key] ?: ""

    @ColorInt
    fun getColor(key: String, fallback: Int = Color.TRANSPARENT): Int {
        val v = get(key)
        if (v.isEmpty() || v == "transparent") return fallback
        return try { parseColor(v) } catch (_: Exception) { fallback }
    }

    fun getFloat(key: String, fallback: Float = 0f): Float {
        return get(key).replace("px", "").replace("dp", "").trim().toFloatOrNull() ?: fallback
    }

    // ── 颜色解析（支持 #hex / rgb() / rgba()） ───────────────
    @ColorInt
    fun parseColor(value: String): Int {
        val v = value.trim()
        if (v.startsWith("#")) return Color.parseColor(v)

        val rgbaMatch = Regex("rgba?\\(([^)]+)\\)").find(v)
        if (rgbaMatch != null) {
            val parts = rgbaMatch.groupValues[1].split(",").map { it.trim() }
            val r = parts.getOrNull(0)?.toFloatOrNull()?.toInt() ?: 0
            val g = parts.getOrNull(1)?.toFloatOrNull()?.toInt() ?: 0
            val b = parts.getOrNull(2)?.toFloatOrNull()?.toInt() ?: 0
            val a = parts.getOrNull(3)?.toFloatOrNull() ?: 1f
            return Color.argb((a * 255).toInt(), r, g, b)
        }

        return Color.parseColor(v)
    }

    // ════════════════════════════════════════════════════════
    // 快捷应用方法 — 各页面直接调用
    // ════════════════════════════════════════════════════════

    /** 应用全局背景色到 View */
    fun applyAppBg(view: View) {
        val bg = get("--app-bg")
        if (bg == "transparent" || bg.isEmpty()) {
            view.setBackgroundColor(Color.TRANSPARENT)
        } else {
            try { view.setBackgroundColor(parseColor(bg)) } catch (_: Exception) {}
        }
    }

    /** 应用顶栏样式 */
    fun applyHeader(headerView: View, titleView: TextView? = null) {
        val bg = getColor("--header-bg", Color.parseColor("#F7F7F7"))
        headerView.setBackgroundColor(bg)
        titleView?.setTextColor(getColor("--header-title-color", Color.BLACK))
    }

    /** 应用消息列表 item 背景 */
    fun applyMsgListItem(itemView: View) {
        val bg = get("--msg-list-item-bg")
        val radius = getFloat("--msg-list-item-radius", 0f)
        if (radius > 0f) {
            val drawable = GradientDrawable().apply {
                try { setColor(parseColor(bg)) } catch (_: Exception) { setColor(Color.WHITE) }
                cornerRadius = radius
            }
            itemView.background = drawable
        } else {
            try { itemView.setBackgroundColor(parseColor(bg)) } catch (_: Exception) {
                itemView.setBackgroundColor(Color.WHITE)
            }
        }
    }

    /** 应用消息列表文字颜色 */
    fun applyMsgListText(nameView: TextView, previewView: TextView, timeView: TextView? = null) {
        nameView.setTextColor(getColor("--msg-list-name-color", Color.BLACK))
        previewView.setTextColor(getColor("--msg-list-preview-color", Color.GRAY))
        timeView?.setTextColor(getColor("--msg-list-time-color", Color.LTGRAY))
    }

    /** 应用底部导航 */
    fun applyBottomNav(
        bottomNav: com.google.android.material.bottomnavigation.BottomNavigationView
    ) {
        val bgColor = getColor("--bottom-nav-bg", Color.WHITE)
        bottomNav.setBackgroundColor(bgColor)

        val iconColor = getColor("--bottom-nav-icon-color", Color.parseColor("#111111"))
        val selectedColor = getColor("--bottom-nav-selected-color", Color.parseColor("#07C160"))

        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val colors = intArrayOf(selectedColor, iconColor)
        val csl = ColorStateList(states, colors)
        bottomNav.itemIconTintList = csl
        bottomNav.itemTextColor = csl
    }

    /** 应用通讯录 item */
    fun applyContactItem(itemView: View, nameView: TextView) {
        try { itemView.setBackgroundColor(parseColor(get("--contact-item-bg"))) } catch (_: Exception) {}
        nameView.setTextColor(getColor("--contact-name-color", Color.BLACK))
    }

    /** 应用个人页 */
    fun applyProfileItem(itemView: View, textView: TextView? = null) {
        try { itemView.setBackgroundColor(parseColor(get("--profile-item-bg"))) } catch (_: Exception) {}
        textView?.setTextColor(getColor("--profile-text-color", Color.BLACK))
    }
}