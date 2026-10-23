package com.jarvis.app

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.app.databinding.ActivityFactsBinding

/** Jarvi ko aapke baare mein jo baatein yaad hain, unhe dekho aur hatao */
class FactsActivity : AppCompatActivity() {

    private lateinit var b: ActivityFactsBinding

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityFactsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }
        b.btnClearAll.setOnClickListener {
            GeminiClient.clearFacts(this)
            render()
        }
        render()
    }

    private fun render() {
        b.container.removeAllViews()
        val facts = GeminiClient.listFacts(this)
        b.tvEmpty.visibility = if (facts.isEmpty()) View.VISIBLE else View.GONE
        b.btnClearAll.visibility = if (facts.isEmpty()) View.GONE else View.VISIBLE

        for (i in facts.indices) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(8), 0, dp(8))

            val tv = TextView(this)
            tv.text = facts[i]
            tv.setTextColor(ContextCompat.getColor(this, R.color.text_white))
            tv.textSize = 15f
            row.addView(
                tv,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )

            val del = TextView(this)
            del.text = "Hatao"
            del.setTextColor(0xFFFF6B6B.toInt())
            del.textSize = 14f
            del.setPadding(dp(12), dp(8), dp(4), dp(8))
            del.setOnClickListener {
                GeminiClient.removeFact(this, i)
                render()
            }
            row.addView(
                del,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            b.container.addView(row)
        }
    }
}
