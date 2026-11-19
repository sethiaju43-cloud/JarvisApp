package com.jarvis.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.FileOutputStream

/**
 * 3D avatar (Microsoft Fluent Emoji, MIT license) ko Jarvi ka chehra (DP) banata hai.
 * Chuna hua avatar theme ke rang ke chamakte background par rakhkar dp.jpg mein save hota hai,
 * isliye floating Jarvi, chat aur home sab jagah wahi dikhta hai.
 */
object Avatars {

    private val cache = HashMap<String, Bitmap>()

    /** Chhota preview (grid ke liye) */
    @Synchronized
    fun preview(ctx: Context, id: String): Bitmap? {
        cache[id]?.let { return it }
        return try {
            val b = ctx.applicationContext.assets.open("avatars/$id.webp").use { BitmapFactory.decodeStream(it) }
            if (b != null) cache[id] = b
            b
        } catch (e: Exception) {
            null
        }
    }

    private fun mix(a: Int, b: Int, f: Float): Int {
        val r = (Color.red(a) * (1 - f) + Color.red(b) * f).toInt()
        val g = (Color.green(a) * (1 - f) + Color.green(b) * f).toInt()
        val bl = (Color.blue(a) * (1 - f) + Color.blue(b) * f).toInt()
        return Color.rgb(r, g, bl)
    }

    /** Avatar ko DP bana do. true = ho gaya. */
    fun apply(ctx: Context, id: String): Boolean {
        return try {
            val app = ctx.applicationContext
            val src = preview(app, id) ?: return false
            val size = 512
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val cv = Canvas(out)
            val accent = AppTheme.current(app).accent
            val bg = Paint(Paint.ANTI_ALIAS_FLAG)
            bg.shader = RadialGradient(
                size / 2f, size * 0.42f, size * 0.8f,
                mix(accent, Color.WHITE, 0.25f), mix(accent, Color.BLACK, 0.55f), Shader.TileMode.CLAMP
            )
            cv.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bg)
            val pad = 52
            cv.drawBitmap(src, null, Rect(pad, pad, size - pad, size - pad), Paint(Paint.FILTER_BITMAP_FLAG))
            FileOutputStream(AppTheme.dpFile(app)).use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            out.recycle()
            AppTheme.setAvatarMode(app, 1)
            AppTheme.bump(app)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Purana Jarvi wala chehra wapas */
    fun reset(ctx: Context) {
        val app = ctx.applicationContext
        AppTheme.setAvatarMode(app, 0)
        AppTheme.bump(app)
    }

    /** Floating Jarvi ko naya chehra dikhane ke liye ek baar band-chalu karo */
    fun restartFloating(ctx: Context) {
        val app = ctx.applicationContext
        if (!Prefs.sp(app).getBoolean("floating", true) || !Settings.canDrawOverlays(app)) return
        app.stopService(Intent(app, FloatingService::class.java))
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                ContextCompat.startForegroundService(app, Intent(app, FloatingService::class.java))
            } catch (e: Exception) {
            }
        }, 600)
    }
}
