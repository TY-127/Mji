package com.moon.aiphone

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class WeatherActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_weather)
        supportActionBar?.hide()

        val etCityName = findViewById<EditText>(R.id.etCityName)
        val tvDisplay = findViewById<TextView>(R.id.tvWeatherDisplay)

        val sharedPref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        etCityName.setText(sharedPref.getString("lastCity", ""))
        tvDisplay.text = sharedPref.getString("cyberWeather", "等待接收真实卫星信号...")

        findViewById<TextView>(R.id.btnFetchWeather).setOnClickListener {
            val city = etCityName.text.toString().trim()
            if (city.isEmpty()) {
                Toast.makeText(this, "大姐，填个城市名啊！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tvDisplay.text = "🛰️ 正在强行击穿次元壁，抓取现实雷达..."

            sharedPref.edit().putString("lastCity", city).apply()
            fetchRealWeather(city, tvDisplay)
        }
    }

    private fun fetchRealWeather(city: String, tvDisplay: TextView) {
        val mainHandler = Handler(Looper.getMainLooper())
        val sharedPref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)

        Thread {
            try {
                val encodedCity = java.net.URLEncoder.encode(city, "UTF-8")
                val client = Http.client.newBuilder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS)
                    .callTimeout(12, TimeUnit.SECONDS)
                    .build()
                var lastError = ""
                // Open-Meteo 国内可直连，作为首选；wttr.in 在部分网络下长期无响应，仅作回退
                var rawWeather = fetchOpenMeteoWeather(client, encodedCity)
                if (rawWeather.isEmpty()) {
                    val urls = listOf(
                        "https://wttr.in/$encodedCity?format=%C+%t",
                        "https://zh.wttr.in/$encodedCity?format=%C+%t"
                    )
                    for (realWeatherUrl in urls) {
                        try {
                            val request = Request.Builder()
                                .url(realWeatherUrl)
                                .header("User-Agent", "AiPhone/1.0 weather fetch")
                                .build()
                            client.newCall(request).execute().use { response ->
                                val body = response.body?.string()?.trim().orEmpty()
                                if (response.isSuccessful && body.isNotEmpty() && !body.contains("Unknown", ignoreCase = true)) {
                                    rawWeather = body
                                } else {
                                    lastError = "HTTP ${response.code}"
                                }
                            }
                        } catch (e: Exception) {
                            lastError = e.message ?: e.javaClass.simpleName
                        }
                        if (rawWeather.isNotEmpty()) break
                    }
                    if (rawWeather.isEmpty() && lastError.isEmpty()) lastError = "无返回数据"
                }
                mainHandler.post {
                    if (rawWeather.isEmpty()) {
                        tvDisplay.text = "❌ 天气抓取失败：${lastError.ifEmpty { "无返回数据" }}"
                        return@post
                    }
                    rawWeather = rawWeather.replace("+", "").replace("\n", " ").trim()
                    val finalWeatherStr = "【当前${city}天气】：$rawWeather"
                    tvDisplay.text = finalWeatherStr
                    sharedPref.edit().putString("cyberWeather", finalWeatherStr).apply()
                    Toast.makeText(this@WeatherActivity, "⛈️ 现实天气已接管！全局同步完成！", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                mainHandler.post { tvDisplay.text = "❌ 底层死机：${e.message}" }
            }}
    }

    private fun fetchOpenMeteoWeather(client: OkHttpClient, encodedCity: String): String {
        return try {
            val geoReq = Request.Builder()
                .url("https://geocoding-api.open-meteo.com/v1/search?name=$encodedCity&count=1&language=zh&format=json")
                .header("User-Agent", "AiPhone/1.0 weather fetch")
                .build()
            val geoBody = client.newCall(geoReq).execute().use { resp ->
                if (!resp.isSuccessful) return ""
                resp.body?.string().orEmpty()
            }
            val geo = JSONObject(geoBody).optJSONArray("results")?.optJSONObject(0) ?: return ""
            val lat = geo.optDouble("latitude")
            val lon = geo.optDouble("longitude")
            val weatherReq = Request.Builder()
                .url("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&timezone=auto")
                .header("User-Agent", "AiPhone/1.0 weather fetch")
                .build()
            val weatherBody = client.newCall(weatherReq).execute().use { resp ->
                if (!resp.isSuccessful) return ""
                resp.body?.string().orEmpty()
            }
            val current = JSONObject(weatherBody).optJSONObject("current") ?: return ""
            val temp = current.optDouble("temperature_2m")
            val code = current.optInt("weather_code", -1)
            "${weatherCodeText(code)} ${temp}°C"
        } catch (_: Exception) {
            ""
        }
    }

    private fun weatherCodeText(code: Int): String = when (code) {
        0 -> "晴"
        1, 2 -> "少云"
        3 -> "阴"
        45, 48 -> "雾"
        51, 53, 55, 56, 57 -> "毛毛雨"
        61, 63, 65, 66, 67 -> "雨"
        71, 73, 75, 77 -> "雪"
        80, 81, 82 -> "阵雨"
        85, 86 -> "阵雪"
        95, 96, 99 -> "雷暴"
        else -> "天气"
    }
}
