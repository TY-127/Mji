package com.moon.aiphone

import android.content.Context
import android.graphics.Color

/**
 * “门”页面的轻量主题协议。
 *
 * 页面本身是原生 Android View，不能直接应用浏览器选择器。因此这里读取 CSS 自定义变量，
 * 再把变量映射到对应的原生控件。若输入是完整 HTML，<style> 仍参与变量解析，<body>
 * 会作为聊天页底层的纯装饰背景显示（JavaScript 始终关闭）。
 */
data class DoorTheme(
    val raw: String = "",
    val screenBg: Int = Color.parseColor("#F4F1EA"),
    val headerBg: Int = Color.parseColor("#FCFAF5"),
    val headerText: Int = Color.parseColor("#2E2A35"),
    val storyBg: Int = Color.TRANSPARENT,
    val cardBg: Int = Color.parseColor("#FFFDF8"),
    val userCardBg: Int = Color.parseColor("#F6F0E7"),
    val text: Int = Color.parseColor("#2F2A33"),
    val userText: Int = Color.parseColor("#403741"),
    val mutedText: Int = Color.parseColor("#867B85"),
    val border: Int = Color.parseColor("#DED4C9"),
    val accent: Int = Color.parseColor("#76536D"),
    val inputAreaBg: Int = Color.parseColor("#FCFAF5"),
    val inputBg: Int = Color.parseColor("#FFFFFF"),
    val inputText: Int = Color.parseColor("#2F2A33"),
    val inputHint: Int = Color.parseColor("#9A9098"),
    val sendBg: Int = Color.parseColor("#76536D"),
    val sendText: Int = Color.WHITE,
    val regenBg: Int = Color.parseColor("#B57A62"),
    val optionBg: Int = Color.parseColor("#F3EAF0"),
    val optionText: Int = Color.parseColor("#694C63"),
    val optionBorder: Int = Color.parseColor("#D8C1D0"),
    val cardBorderWidthDp: Float = 0f,
    val cardRadiusDp: Float = 18f,
    val avatarSizeDp: Int = 64,
    val avatarRadiusDp: Float = 32f,
    val contentTextSp: Float = 16f,
    val itemHorizontalDp: Int = 16,
    val itemVerticalDp: Int = 18,
    val lineHeight: Float = 1.55f,
    val backgroundCss: String = "",
    val backgroundHtml: String = ""
)

object DoorThemeManager {
    private const val PREF_KEY_PREFIX = "doorTheme_"

    private val styleRegex = Regex("<style[^>]*>([\\s\\S]*?)</style>", RegexOption.IGNORE_CASE)
    private val bodyRegex = Regex("<body[^>]*>([\\s\\S]*?)</body>", RegexOption.IGNORE_CASE)
    private val variableRegex = Regex("--door-([a-z0-9-]+)\\s*:\\s*([^;}{]+)", RegexOption.IGNORE_CASE)

    fun load(context: Context, aiId: String): DoorTheme {
        val raw = context.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            .getString(PREF_KEY_PREFIX + aiId, "").orEmpty()
        return parse(raw)
    }

    fun save(context: Context, aiId: String, raw: String) {
        context.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            .edit().putString(PREF_KEY_PREFIX + aiId, raw).apply()
    }

    fun clear(context: Context, aiId: String) {
        context.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
            .edit().remove(PREF_KEY_PREFIX + aiId).apply()
    }

    fun parse(rawInput: String): DoorTheme {
        val raw = rawInput.trim()
        if (raw.isEmpty()) return DoorTheme()

        val styleBlocks = styleRegex.findAll(raw).map { it.groupValues[1] }.toList()
        val css = if (styleBlocks.isNotEmpty()) styleBlocks.joinToString("\n") else raw
        val values = variableRegex.findAll(css).associate {
            it.groupValues[1].lowercase() to it.groupValues[2].trim().trim('"', '\'')
        }
        val defaults = DoorTheme()

        fun color(name: String, fallback: Int): Int = parseColor(values[name], fallback)
        fun number(name: String, fallback: Float, min: Float, max: Float): Float {
            val parsed = values[name]?.replace(Regex("(?i)(dp|sp|px)$"), "")?.trim()?.toFloatOrNull()
            return (parsed ?: fallback).coerceIn(min, max)
        }

        return DoorTheme(
            raw = raw,
            screenBg = color("screen-bg", defaults.screenBg),
            headerBg = color("header-bg", defaults.headerBg),
            headerText = color("header-text", defaults.headerText),
            storyBg = color("story-bg", defaults.storyBg),
            cardBg = color("card-bg", defaults.cardBg),
            userCardBg = color("user-card-bg", defaults.userCardBg),
            text = color("text", defaults.text),
            userText = color("user-text", defaults.userText),
            mutedText = color("muted-text", defaults.mutedText),
            border = color("border", defaults.border),
            accent = color("accent", defaults.accent),
            inputAreaBg = color("input-area-bg", defaults.inputAreaBg),
            inputBg = color("input-bg", defaults.inputBg),
            inputText = color("input-text", defaults.inputText),
            inputHint = color("input-hint", defaults.inputHint),
            sendBg = color("send-bg", defaults.sendBg),
            sendText = color("send-text", defaults.sendText),
            regenBg = color("regen-bg", defaults.regenBg),
            optionBg = color("option-bg", defaults.optionBg),
            optionText = color("option-text", defaults.optionText),
            optionBorder = color("option-border", defaults.optionBorder),
            cardBorderWidthDp = number("card-border-width", defaults.cardBorderWidthDp, 0f, 4f),
            cardRadiusDp = number("card-radius", defaults.cardRadiusDp, 0f, 48f),
            avatarSizeDp = number("avatar-size", defaults.avatarSizeDp.toFloat(), 32f, 104f).toInt(),
            avatarRadiusDp = number("avatar-radius", defaults.avatarRadiusDp, 0f, 52f),
            contentTextSp = number("text-size", defaults.contentTextSp, 12f, 24f),
            itemHorizontalDp = number("item-horizontal-padding", defaults.itemHorizontalDp.toFloat(), 0f, 40f).toInt(),
            itemVerticalDp = number("item-vertical-gap", defaults.itemVerticalDp.toFloat(), 4f, 48f).toInt(),
            lineHeight = number("line-height", defaults.lineHeight, 1f, 2.4f),
            backgroundCss = if (bodyRegex.containsMatchIn(raw)) css else "",
            backgroundHtml = extractSafeBody(raw)
        )
    }

    private fun parseColor(value: String?, fallback: Int): Int {
        if (value.isNullOrBlank()) return fallback
        val v = value.trim()
        return try {
            when {
                v.equals("transparent", true) -> Color.TRANSPARENT
                Regex("#[0-9a-fA-F]{3}").matches(v) -> {
                    val r = v[1]; val g = v[2]; val b = v[3]
                    Color.parseColor("#$r$r$g$g$b$b")
                }
                Regex("rgba?\\([^)]*\\)", RegexOption.IGNORE_CASE).matches(v) -> parseRgb(v, fallback)
                else -> Color.parseColor(v)
            }
        } catch (_: Exception) {
            fallback
        }
    }

    private fun parseRgb(value: String, fallback: Int): Int {
        val parts = value.substringAfter('(').substringBeforeLast(')').split(',').map { it.trim() }
        if (parts.size !in 3..4) return fallback
        val r = parts[0].toIntOrNull()?.coerceIn(0, 255) ?: return fallback
        val g = parts[1].toIntOrNull()?.coerceIn(0, 255) ?: return fallback
        val b = parts[2].toIntOrNull()?.coerceIn(0, 255) ?: return fallback
        val alpha = if (parts.size == 4) {
            val a = parts[3].toFloatOrNull() ?: return fallback
            if (a <= 1f) (a * 255).toInt() else a.toInt().coerceIn(0, 255)
        } else 255
        return Color.argb(alpha, r, g, b)
    }

    private fun extractSafeBody(raw: String): String {
        if (!raw.contains('<')) return ""
        val body = bodyRegex.find(raw)?.groupValues?.get(1).orEmpty()
        if (body.isBlank()) return ""
        return body
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<(iframe|object|embed|form)[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\son[a-z]+\\s*=\\s*(['\"]).*?\\1", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    fun backgroundDocument(theme: DoorTheme): String = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
          html,body{margin:0;width:100%;height:100%;overflow:hidden;background:transparent;}
          *{box-sizing:border-box;pointer-events:none;user-select:none;}
          ${theme.backgroundCss}
        </style></head><body>${theme.backgroundHtml}</body></html>
    """.trimIndent()
}
