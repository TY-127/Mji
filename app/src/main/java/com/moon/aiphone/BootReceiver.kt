package com.moon.aiphone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) {

                val serviceIntent =
                    Intent(context, PatienceService::class.java)

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(
                "BootReceiver",
                e.stackTraceToString()
            )
        }
    }
}