package com.jarvis.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** Auto-reply notification ke "Bhejo" / "Mat bhejo" button yahan aate hain */
class AutoReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        if (id < 0) return
        when (intent.action) {
            AutoReply.ACTION_SEND -> Toast.makeText(context, AutoReply.send(context.applicationContext, id), Toast.LENGTH_SHORT).show()
            AutoReply.ACTION_SKIP -> AutoReply.skip(context.applicationContext, id)
        }
    }
}
