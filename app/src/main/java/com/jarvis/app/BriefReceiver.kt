package com.jarvis.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Daily brief ka samay aane par alarm yahan aata hai */
class BriefReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DailyBrief.fire(context.applicationContext)
    }
}
