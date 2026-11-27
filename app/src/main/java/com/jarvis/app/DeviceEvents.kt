package com.jarvis.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

/*
 * Phone ke events (charger, battery, headphone, bluetooth, airplane, storage, app install...) sunne aur batane wala Android hissa.
 * Bina AI/API ke. Receiver app ke process mein jeeta hai (JarvisApp aur notification listener se shuru hota hai).
 * Switch sirf tab kaam karta hai jab user (tool se) chalu kare. Default band.
 */
object DeviceEvents {

    private const val PREFS = "jarvis_events"
    private const val CHANNEL = "jarvis_events"
    private val gate = EventGate()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var main: BroadcastReceiver? = null

    @Volatile
    private var pkg: BroadcastReceiver? = null

    @Volatile
    private var primed = false

    @Volatile
    private var lastPct = -1

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun settings(ctx: Context): EventSettings {
        val p = sp(ctx)
        return EventSettings(
            p.getBoolean("master", false),
            DeviceEventLogic.decodeGroups(p.getString("groups", "") ?: ""),
            p.getBoolean("speak", false),
            DeviceEventLogic.clampLow(p.getInt("low", DeviceEventLogic.DEFAULT_LOW))
        )
    }

    private fun save(ctx: Context, s: EventSettings) {
        sp(ctx).edit().putBoolean("master", s.master).putString("groups", DeviceEventLogic.encodeGroups(s.groups))
            .putBoolean("speak", s.speak).putInt("low", s.lowPct).apply()
    }

    // ---------- tools ke liye ----------

    private fun btAllowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun setFromArgs(ctx: Context, a: JSONObject): String {
        val group = DeviceEventLogic.groupFromText(a.optString("event", ""))
            ?: return "Ye event samajh nahi aaya. Inme se chuno: " + DeviceEventLogic.GROUPS.joinToString(", ") + ", ya all."
        val on = a.optBoolean("on", true)
        var s = DeviceEventLogic.apply(settings(ctx), group, on)
        val speak = if (a.has("speak")) a.optBoolean("speak", s.speak) else s.speak
        val low = if (a.has("low_battery_percent")) DeviceEventLogic.clampLow(a.optInt("low_battery_percent", s.lowPct)) else s.lowPct
        s = EventSettings(s.master, s.groups, speak, low)
        save(ctx, s)
        if (s.master) start(ctx) else stop(ctx)
        var msg = "Theek hai ✅ " + DeviceEventLogic.describe(s) + " Ye bina AI/internet ke apne aap chalega."
        if (on && (group == "bluetooth" || group == "all") && !btAllowed(ctx)) {
            msg += "\n⚠️ Bluetooth events ke liye Android 12+ par 'Nearby devices' permission chahiye, jo abhi nahi hai, isliye bluetooth wali ghoshna nahi aayegi."
        }
        return msg
    }

    fun listText(ctx: Context): String = DeviceEventLogic.describe(settings(ctx))

    // ---------- receiver ----------

    @Synchronized
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (!settings(app).master || main != null) return
        try {
            primed = false
            val f = IntentFilter()
            f.addAction(Intent.ACTION_POWER_CONNECTED)
            f.addAction(Intent.ACTION_POWER_DISCONNECTED)
            f.addAction(Intent.ACTION_BATTERY_CHANGED)
            f.addAction(Intent.ACTION_HEADSET_PLUG)
            f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            f.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            f.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            f.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            f.addAction(Intent.ACTION_DEVICE_STORAGE_LOW)
            f.addAction(Intent.ACTION_DEVICE_STORAGE_OK)
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    try {
                        handle(context.applicationContext, intent)
                    } catch (e: Exception) {
                    }
                }
            }
            ContextCompat.registerReceiver(app, r, f, ContextCompat.RECEIVER_EXPORTED)
            main = r

            val pf = IntentFilter()
            pf.addAction(Intent.ACTION_PACKAGE_ADDED)
            pf.addAction(Intent.ACTION_PACKAGE_REMOVED)
            pf.addDataScheme("package")
            val pr = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    try {
                        handlePackage(context.applicationContext, intent)
                    } catch (e: Exception) {
                    }
                }
            }
            ContextCompat.registerReceiver(app, pr, pf, ContextCompat.RECEIVER_EXPORTED)
            pkg = pr
        } catch (e: Exception) {
            main = null
            pkg = null
        }
    }

    @Synchronized
    fun stop(ctx: Context) {
        val app = ctx.applicationContext
        try {
            main?.let { app.unregisterReceiver(it) }
            pkg?.let { app.unregisterReceiver(it) }
        } catch (e: Exception) {
        }
        main = null
        pkg = null
    }

    private fun emit(ctx: Context, ev: DeviceEvent) {
        val s = settings(ctx)
        if (!DeviceEventLogic.isOn(s, ev.kind)) return
        val d = gate.decide(ev, System.currentTimeMillis())
        if (d is GateDecision.Say) announce(ctx, ev, d.text, s.speak)
    }

    private fun handle(ctx: Context, intent: Intent) {
        val s = settings(ctx)
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> chargerChanged(ctx, true)
            Intent.ACTION_POWER_DISCONNECTED -> chargerChanged(ctx, false)
            Intent.ACTION_BATTERY_CHANGED -> {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val full = status == BatteryManager.BATTERY_STATUS_FULL
                val charging = full || status == BatteryManager.BATTERY_STATUS_CHARGING
                lastPct = pct
                gate.noteLevel(pct, s.lowPct)
                if (!primed) { // pehli (sticky) khabar sirf yaad rakho, bolo mat
                    primed = true
                    return
                }
                val ev = DeviceEventLogic.fromBattery(pct, charging, full, s.lowPct)
                if (ev != null) emit(ctx, ev)
            }
            Intent.ACTION_HEADSET_PLUG ->
                emit(ctx, DeviceEvent(if (intent.getIntExtra("state", 0) == 1) EventKind.HEADSET_ON else EventKind.HEADSET_OFF))
            BluetoothDevice.ACTION_ACL_CONNECTED -> emit(ctx, DeviceEvent(EventKind.BT_ON, btName(ctx, intent)))
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> emit(ctx, DeviceEvent(EventKind.BT_OFF, btName(ctx, intent)))
            Intent.ACTION_AIRPLANE_MODE_CHANGED ->
                emit(ctx, DeviceEvent(if (intent.getBooleanExtra("state", false)) EventKind.AIRPLANE_ON else EventKind.AIRPLANE_OFF))
            PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                val on = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
                emit(ctx, DeviceEvent(if (on) EventKind.SAVER_ON else EventKind.SAVER_OFF))
            }
            AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                val mode = when (intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1)) {
                    AudioManager.RINGER_MODE_SILENT -> "silent"
                    AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                    AudioManager.RINGER_MODE_NORMAL -> "normal"
                    else -> ""
                }
                if (mode.isNotEmpty()) emit(ctx, DeviceEvent(EventKind.RINGER, mode))
            }
            Intent.ACTION_DEVICE_STORAGE_LOW -> emit(ctx, DeviceEvent(EventKind.STORAGE_LOW))
            Intent.ACTION_DEVICE_STORAGE_OK -> emit(ctx, DeviceEvent(EventKind.STORAGE_OK))
        }
    }

    private fun chargerChanged(ctx: Context, connected: Boolean) {
        val kind = if (connected) EventKind.CHARGER_ON else EventKind.CHARGER_OFF
        if (!DeviceEventLogic.isOn(settings(ctx), kind)) return
        gate.submitCharger(connected, System.currentTimeMillis())
        handler.postDelayed({
            try {
                flushCharger(ctx)
            } catch (e: Exception) {
            }
        }, 1700L)
    }

    private fun flushCharger(ctx: Context) {
        val d = gate.flushCharger(System.currentTimeMillis(), lastPct)
        if (d is GateDecision.Say) {
            val kind = if (d.text.startsWith("Charger laga")) EventKind.CHARGER_ON else EventKind.CHARGER_OFF
            announce(ctx, DeviceEvent(kind), d.text, settings(ctx).speak)
        }
    }

    @Suppress("DEPRECATION")
    private fun btName(ctx: Context, intent: Intent): String {
        if (!btAllowed(ctx)) return ""
        return try {
            val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            d?.name ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun handlePackage(ctx: Context, intent: Intent) {
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return // update ko install mat samjho
        val name = intent.data?.schemeSpecificPart ?: return
        if (name == ctx.packageName) return
        val added = intent.action == Intent.ACTION_PACKAGE_ADDED
        val label = if (added) {
            try {
                ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(name, 0)).toString()
            } catch (e: Exception) {
                name
            }
        } else {
            name.substringAfterLast('.')
        }
        emit(ctx, DeviceEvent(if (added) EventKind.APP_INSTALLED else EventKind.APP_REMOVED, label))
    }

    @SuppressLint("MissingPermission")
    private fun announce(ctx: Context, ev: DeviceEvent, text: String, speak: Boolean) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvi phone events", NotificationManager.IMPORTANCE_LOW))
            }
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(text)
                .setAutoCancel(true)
                .setTimeoutAfter(10 * 60_000L)
                .build()
            NotificationManagerCompat.from(ctx).notify(7600 + ev.kind.ordinal, n)
        } catch (e: Exception) {
        }
        if (speak) {
            try {
                DrivingSpeaker.say(ctx, DeviceEventLogic.spoken(text))
            } catch (e: Exception) {
            }
        }
    }
}
