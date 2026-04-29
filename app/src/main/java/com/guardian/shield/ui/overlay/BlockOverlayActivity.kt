// app/src/main/java/com/guardian/shield/ui/overlay/BlockOverlayActivity.kt
package com.guardian.shield.ui.overlay

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.guardian.shield.databinding.ActivityBlockOverlayBinding
import com.guardian.shield.domain.model.BlockReason
import com.guardian.shield.ui.unlock.DelayUnlockActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * BlockOverlayActivity — full-screen block UI shown after a violation.
 *
 * Improvements over the previous version:
 *   - setShowWhenLocked / setTurnScreenOn called via the API methods (not
 *     just window flags) so the activity reliably appears even when the
 *     device is in lock-screen / always-on-display state.
 *   - Excluded from recents AND background-task list so the user can't
 *     swipe back into the offending app from the recents UI.
 *   - Hardware nav keys are swallowed so power-users can't dismiss the
 *     block by pressing Back/Home/Recents.
 */
@AndroidEntryPoint
class BlockOverlayActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PKG        = "pkg"
        const val EXTRA_APP_NAME   = "app_name"
        const val EXTRA_REASON     = "reason"
        const val EXTRA_DETAIL     = "detail"
        const val EXTRA_DELAY_SECS = "delay_seconds"
    }

    private lateinit var binding: ActivityBlockOverlayBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Newer way (API 27+) — replaces the deprecated FLAG_SHOW_WHEN_LOCKED etc.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityBlockOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* swallow */ }
        })

        populateContent()
        setupButtons()
    }

    private fun populateContent() {
        val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: "App"
        val reason  = intent.getStringExtra(EXTRA_REASON) ?: BlockReason.APP_BLOCKED.name
        val detail  = intent.getStringExtra(EXTRA_DETAIL) ?: ""

        binding.tvBlockedApp.text = appName
        binding.tvBlockMessage.text = "Blocked for your protection"
        binding.tvBlockReason.text = when (reason) {
            BlockReason.APP_BLOCKED.name      -> "📵 This app is on your blocked list"
            BlockReason.KEYWORD_DETECTED.name -> "🔤 Blocked keyword detected: \"$detail\""
            BlockReason.AI_DETECTED.name      -> "🤖 Unsafe content detected ($detail)"
            else                              -> "⚠️ Content violation"
        }
    }

    private fun setupButtons() {
        binding.btnUnderstand.setOnClickListener {
            val delaySecs = intent.getIntExtra(EXTRA_DELAY_SECS, 30)
            startActivity(Intent(this, DelayUnlockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra(EXTRA_PKG,       intent.getStringExtra(EXTRA_PKG) ?: "")
                putExtra(EXTRA_APP_NAME,  intent.getStringExtra(EXTRA_APP_NAME) ?: "")
                putExtra("delay_seconds", delaySecs)
            })
            finish()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_MENU -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }

    /**
     * Pressing system-level home/recents may finish() this activity. We
     * re-launch ourselves so the block can't be silently dismissed.
     * (Limited — Android stops us if user invokes power button etc.)
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // No-op — the foreground service + accessibility re-block will
        // re-launch us if the user hits Home and reopens the offending app.
    }
}
