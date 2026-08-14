package com.moon.aiphone

import android.content.Context

object UserProfileManager {

    // 读取完整档案，返回给 AI 用的摘要文本
    fun getSummaryForAi(context: Context): String {
        return try {
            val map = loadAll(context)
            if (map.isEmpty()) return ""

            val sb = StringBuilder("【用户档案摘要】\n")

            // 基础信息
            val basic = listOfNotNull(
                map["name"]?.let { "姓名：$it" },
                map["age"]?.let { "年龄：${it}岁" },
                map["mbti"]?.let { "MBTI：$it" },
                map["gender"]?.let { "性别：$it" }
            ).joinToString("，")
            if (basic.isNotEmpty()) sb.append("基本：$basic\n")

            // 外形
            val appearance = listOfNotNull(
                map["height"]?.let { "身高体重：$it" },
                map["hair"]?.let { "发型：$it" },
                map["eyes"]?.let { "眼睛：$it" },
                map["skin"]?.let { "肤色：$it" },
                map["mark"]?.let { "标志：$it" }
            ).joinToString("，")
            if (appearance.isNotEmpty()) sb.append("外形：$appearance\n")

            // 性格
            val personality = listOfNotNull(
                map["personality_tags"]?.let { "性格标签：$it" },
                map["likes"]?.let { "喜欢：$it" },
                map["dislikes"]?.let { "讨厌：$it" },
                map["catchphrase"]?.let { "口头禅：$it" }
            ).joinToString("，")
            if (personality.isNotEmpty()) sb.append("性格：$personality\n")

            // 喜好
            val prefs = listOfNotNull(
                map["color"]?.let { "喜欢的颜色：$it" },
                map["music"]?.let { "音乐：$it" },
                map["food"]?.let { "食物：$it" },
                map["season"]?.let { "季节：$it" }
            ).joinToString("，")
            if (prefs.isNotEmpty()) sb.append("喜好：$prefs\n")

            // 关键事件
            val events = map["events"]
            if (!events.isNullOrEmpty()) {
                sb.append("人生关键事件：${events.replace("||", "；")}\n")
            }

            // 隐藏面（只给 AI 参考，不直接说出）
            val secret = map["secret"]
            if (!secret.isNullOrEmpty()) {
                sb.append("【隐藏面，角色可感知但不可直接提及】：$secret\n")
            }

            sb.toString().trim()
        } catch (_: Exception) { "" }
    }

    // 只返回外形描述（梦男之家生图用）
    fun getAppearanceOnly(context: Context): String {
        return try {
            val map = loadAll(context)
            // 过滤掉身高体重等数字信息，只保留视觉特征
            listOfNotNull(
                map["hair"]?.let { it },
                map["eyes"]?.let { it },
                map["skin"]?.let { it },
                map["mark"]?.let { it }
            ).joinToString("，")
        } catch (_: Exception) { "" }
    }

    private fun loadAll(context: Context): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            DatabaseHelper(context).readableDatabase
                .rawQuery("SELECT fieldKey, fieldValue FROM UserProfile", null)
                .use { c ->
                    while (c.moveToNext()) {
                        val v = c.getString(1) ?: ""
                        if (v.isNotEmpty()) map[c.getString(0)] = v
                    }
                }
        } catch (_: Exception) {}
        return map
    }
}