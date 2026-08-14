package com.moon.aiphone

import android.content.Context
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

class MailboxBridge(private val context: Context, private val db: DatabaseHelper) {

    @JavascriptInterface
    fun getContacts(): String {
        return try {
            val arr = JSONArray()
            val chats = db.getRecentChats()
            chats.forEach { contact ->
                val obj = JSONObject()
                obj.put("id", contact.aiId)
                obj.put("name", contact.aiName)
                obj.put("avatar", contact.avatarUri)
                db.readableDatabase.rawQuery(
                    """
    SELECT identityInfo
    FROM Contacts
    WHERE userId=?
    ORDER BY id DESC
    LIMIT 1
    """.trimIndent(),
                    arrayOf(contact.aiId)
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        obj.put("identity", cursor.getString(0) ?: "")
                    } else {
                        obj.put("identity", "")
                    }
                }
                arr.put(obj)
            }
            arr.toString()
        } catch (e: Exception) {
            android.util.Log.e("MailboxBridge", "getContacts失败: ${e.message}", e)
            "[]"
        }
    }

    @JavascriptInterface
    fun getApiConfig(): String {
        val prefs = context.getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        val obj = JSONObject()
        obj.put("apiUrl", prefs.getString("apiUrl", "") ?: "")
        obj.put("apiKey", prefs.getString("apiKey", "") ?: "")
        obj.put(
            "modelName",
            prefs.getString("modelName", "")?.ifBlank { "gpt-4o" } ?: "gpt-4o"
        )
        return obj.toString()
    }

    @JavascriptInterface
    fun saveTreasury(json: String) {
        try {
            context.getSharedPreferences("BlindBoxData", Context.MODE_PRIVATE)
                .edit().putString("treasury", json).commit()
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun loadTreasury(): String {
        return try {
            context.getSharedPreferences("BlindBoxData", Context.MODE_PRIVATE)
                .getString("treasury", "[]") ?: "[]"
        } catch (e: Exception) { "[]" }
    }

    @JavascriptInterface
    fun onLetterAnswered(aiId: String, question: String, answer: String) {
        try {
            val dateStr = java.text.SimpleDateFormat("MM月dd日", java.util.Locale.getDefault()).format(java.util.Date())
            val memoryText = "【匿名信箱 $dateStr】收到匿名提问：「${question.take(50)}」，回答了：「${answer.take(80)}」"
            val cv = android.content.ContentValues().apply {
                put("aiId", aiId)
                put("memoryText", memoryText)
                put("category", "shared_event")
                put("insertTime", System.currentTimeMillis())
            }
            db.writableDatabase.insert("MemoryBank", null, cv)
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun saveTodayData(json: String) {
        try {
            context.getSharedPreferences("BlindBoxData", Context.MODE_PRIVATE)
                .edit().putString("today", json).commit()
        } catch (e: Exception) {}
    }

    @JavascriptInterface
    fun loadTodayData(): String {
        return try {
            context.getSharedPreferences("BlindBoxData", Context.MODE_PRIVATE)
                .getString("today", "null") ?: "null"
        } catch (e: Exception) { "null" }
    }
}