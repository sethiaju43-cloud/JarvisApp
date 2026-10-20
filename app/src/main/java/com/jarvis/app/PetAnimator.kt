package com.jarvis.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView

object Poses {
    private val cache = HashMap<String, Bitmap?>()

    fun get(ctx: Context, name: String): Bitmap? {
        if (cache.containsKey(name)) return cache[name]
        val id = ctx.resources.getIdentifier("jarvi_$name", "drawable", ctx.packageName)
        var bmp: Bitmap? = null
        if (id != 0) {
            val o = BitmapFactory.Options()
            o.inScaled = false
            bmp = BitmapFactory.decodeResource(ctx.resources, id, o)
        }
        cache[name] = bmp
        return bmp
    }
}

class PetAnimator(
    private val ctx: Context,
    private val root: View,
    private val front: ImageView,
    private val back: ImageView
) {
    private val h = Handler(Looper.getMainLooper())
    private var token = 0
    private var cur = ""
    private var pending: Bitmap? = null
    private var sleeping = false
    private val animators = ArrayList<ObjectAnimator>()

    var busy = false
        private set

    private val mouth = listOf(
        "talk_closed", "talk_open", "talk_wide", "talk_open",
        "talk_o", "talk_open", "talk_wide", "talk_closed"
    )

    init {
        root.post {
            root.pivotX = root.width / 2f
            root.pivotY = root.height.toFloat()
        }
        val sway = ObjectAnimator.ofFloat(root, "rotation", -1f, 1.2f)
        val breathe = ObjectAnimator.ofFloat(root, "scaleY", 1f, 1.018f)
        sway.duration = 1700
        breathe.duration = 2300
        for (a in listOf(sway, breathe)) {
            a.repeatCount = ValueAnimator.INFINITE
            a.repeatMode = ValueAnimator.REVERSE
            a.interpolator = AccelerateDecelerateInterpolator()
            a.start()
            animators.add(a)
        }
        show("talk_closed")
    }

    fun isSleeping() = sleeping

    fun face(dir: Int) {
        val s = if (dir < 0) -1f else 1f
        front.scaleX = s
        back.scaleX = s
    }

    fun show(name: String) {
        if (name == cur) return
        val bmp = Poses.get(ctx, name) ?: return
        if (cur.isEmpty()) {
            front.setImageBitmap(bmp)
            cur = name
            return
        }
        cur = name
        back.animate().cancel()
        pending?.let { front.setImageBitmap(it) }
        pending = bmp
        back.setImageBitmap(bmp)
        back.alpha = 0f
        back.animate().alpha(1f).setDuration(90).withEndAction {
            front.setImageBitmap(bmp)
            back.alpha = 0f
            pending = null
        }.start()
    }

    private fun play(frames: List<String>, stepMs: Long, du
