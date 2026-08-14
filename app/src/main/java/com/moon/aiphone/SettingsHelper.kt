package com.moon.aiphone

import android.content.Context

class SettingsHelper(context: Context) {

    private val prefs = context.getSharedPreferences("AiPhoneSettings", Context.MODE_PRIVATE)

    fun saveApiConfig(url: String, apiKey: String) {
        prefs.edit().putString("API_URL", url).putString("API_KEY", apiKey).apply()
    }

    fun getApiUrl(): String {
        return prefs.getString("API_URL", "") ?: ""
    }

    fun getApiKey(): String {
        return prefs.getString("API_KEY", "") ?: ""
    }
}