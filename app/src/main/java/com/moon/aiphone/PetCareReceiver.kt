package com.moon.aiphone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Lets the bonded character care for hungry/dirty pets while the user is away. */
class PetCareReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        Thread {
            try { PetHouseRepository(context).autoCare() } finally { pending.finish() }
        }.start()
    }
}
