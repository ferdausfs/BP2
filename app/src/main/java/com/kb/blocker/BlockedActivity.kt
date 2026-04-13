package com.kb.blocker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity  // ← FIX: was Activity

/**
 * Block screen — content block হলে এই screen দেখায়।
 * FIX: Must extend AppCompatActivity — app uses AppCompat theme.
 *
 * 5 সেকেন্ড countdown → auto dismiss, অথবা OK চাপলে dismiss।
 */
class BlockedActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APP    = "app_label"
        const val EXTRA_REASON = "reason"

        fun launch(ctx: Context, appLabel: String, reason: String) {
            ctx.startActivity(Intent(ctx, BlockedActivity::class.java).apply {
                putExtra(EXTRA_APP, appLabel)
                putExtra(EXTRA_REASON, reason)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                         Intent.FLAG_ACTIVITY_CLEAR_TOP or
                         Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Lock screen-এর উপরেও দেখাবে
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON  or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        setContentView(R.layout.activity_blocked)

        val appLabel = intent.getStringExtra(EXTRA_APP)    ?: "App"
        val reason   = intent.getStringExtra(EXTRA_REASON) ?: "Adult content"

        findViewById<TextView>(R.id.tvBlockedApp).text    = appLabel
        findViewById<TextView>(R.id.tvBlockedReason).text = reason

        val tvCountdown = findViewById<TextView>(R.id.tvCountdown)
        var countdown   = 5

        val countRunnable = object : Runnable {
            override fun run() {
                tvCountdown.text = "${countdown}s এ বন্ধ হবে..."
                if (--countdown > 0) {
                    handler.postDelayed(this, 1000)
                } else {
                    handler.postDelayed({ finish() }, 1000)
                }
            }
        }
        handler.post(countRunnable)

        findViewById<Button>(R.id.btnOk).setOnClickListener {
            handler.removeCallbacksAndMessages(null)
            finish()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // Back button disable — screen দেখাতেই হবে
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* intentionally blocked */ }
}
