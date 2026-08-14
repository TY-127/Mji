package com.moon.aiphone

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 开源版不需要账号、联网授权或设备绑定，直接进入本地应用。
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
