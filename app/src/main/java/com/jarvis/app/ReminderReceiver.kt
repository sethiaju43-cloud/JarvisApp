package com.jarvis.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Reminder ka samay aane par alarm yahan aata hai aur notification dikhata hai */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1L)
        if (id >= 0L) Reminders.fire(context.applicationContext, id)
    }
}
