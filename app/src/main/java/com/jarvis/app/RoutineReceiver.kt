package com.jarvis.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Routine ka samay aane par alarm yahan aata hai */
class RoutineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        if (id >= 0) Routines.fire(context.applicationContext, id)
    }
}
