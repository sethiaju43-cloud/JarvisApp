package com.jarvis.app

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * 3D avatar chuno: kisi par dabao to wahi Jarvi ka chehra ban jata hai.
 * Tasveerein Microsoft Fluent Emoji (MIT license) ki hain.
 */
class AvatarActivity : ScreenActivity() {

    private var dirty = false

    override fun screenTitle() = "3D Avatar"

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun build() {
        val c = this
        container.addView(UiKit.section(c, "🎭 Jarvi ka chehra chuno"))
        container.addView(UiKit.text(c, "Kisi par dabao, wahi Jarvi ka chehra ban jayega (floating Jarvi, chat aur home sab jagah).", 12f, dim = true))

        val cols = 3
        var row: LinearLayout? = null
        for ((i, a) in AvatarLogic.all.withIndex()) {
            if (i % cols == 0) {
                val r = LinearLayout(c)
                r.orientation = LinearLayout.HORIZONTAL
                r.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                container.addView(r)
                row = r
            }
            row!!.addView(cell(a))
        }
        // Aakhri line ko barabar rakhne ke liye khali jagah
        val rest = AvatarLogic.all.size % cols
        if (rest != 0) {
            for (k in 0 until (cols - rest)) {
                val filler = View(c)
                filler.layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                row?.addView(filler)
            }
        }

        container.addView(UiKit.button(c, "↩️ Purana Jarvi wala chehra wapas") {
            Avatars.reset(c)
            dirty = true
            UiKit.toast(c, "Purana chehra wapas aa gaya")
        })
        container.addView(UiKit.text(c, "Apni photo lagani ho to Settings → Design mein DP chuno. Tasveerein: Microsoft Fluent Emoji (MIT license).", 11f, dim = true))
    }

    private fun cell(a: AvatarInfo): LinearLayout {
        val c = this
        val box = LinearLayout(c)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.setPadding(dp(4), dp(10), dp(4), dp(10))
        box.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

        val img = ImageView(c)
        img.layoutParams = LinearLayout.LayoutParams(dp(84), dp(84))
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        val bmp = Avatars.preview(c, a.id)
        if (bmp != null) img.setImageBitmap(bmp)
        box.addView(img)

        val label = UiKit.text(c, a.label, 12f)
        label.gravity = Gravity.CENTER
        box.addView(label)

        box.setOnClickListener {
            if (Avatars.apply(c, a.id)) {
                dirty = true
                UiKit.toast(c, "Ab Jarvi ka chehra: " + a.label)
            } else {
                UiKit.toast(c, "Ye avatar lag nahi paya")
            }
        }
        return box
    }

    override fun onStop() {
        super.onStop()
        if (dirty) {
            dirty = false
            Avatars.restartFloating(this)
        }
    }
}
