// app/src/main/java/com/guardian/shield/service/blocker/BlockingEngine.kt
package com.guardian.shield.service.blocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import com.guardian.shield.data.local.datastore.GuardianPreferences
import com.guardian.shield.domain.model.BlockReason
import com.guardian.shield.ui.overlay.BlockOverlayActivity
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BlockingEngine — orchestrates the user-visible block sequence.
 *
 * Flow when a violation is detected:
 *   1. Push offending app to background via GLOBAL_ACTION_HOME
 *   2. Launch BlockOverlayActivity — covers screen with the block UI
 *
 * Cooldown reduced from 3000ms → 1500ms so subsequent blocks aren't
 * silently swallowed when the user keeps trying to reopen the same app.
 */
@Singleton
class BlockingEngine @Inject constructor(
    private val prefs: GuardianPreferences
) {
    companion object {
        private const val TAG = "Guardian_Blocker"
        private const val BLOCK_COOLDOWN_MS = 1_500L
    }

    @Volatile private var lastBlockTime = 0L
    @Volatile private var cachedDelaySecs = 30

    suspend fun loadSettings() {
        try { cachedDelaySecs = prefs.delayUnlockSeconds.first() }
        catch (_: Exception) { cachedDelaySecs = 30 }
    }

    fun isCoolingDown(): Boolean =
        System.currentTimeMillis() - lastBlockTime < BLOCK_COOLDOWN_MS

    fun executeBlock(
        service: AccessibilityService,
        pkg: String,
        appName: String,
        reason: BlockReason,
        detail: String = ""
    ) {
        if (isCoolingDown()) return
        lastBlockTime = System.currentTimeMillis()

        Timber.w("$TAG BLOCK pkg=$pkg reason=$reason detail=$detail")

        // Step 1: kick offending app to background BEFORE showing the overlay,
        // otherwise on some Android versions the overlay activity briefly
        // composites underneath the offending app's surface.
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

        // Step 2: launch block UI
        try {
            val intent = Intent(service, BlockOverlayActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(BlockOverlayActivity.EXTRA_PKG, pkg)
                putExtra(BlockOverlayActivity.EXTRA_APP_NAME, appName)
                putExtra(BlockOverlayActivity.EXTRA_REASON, reason.name)
                putExtra(BlockOverlayActivity.EXTRA_DETAIL, detail)
                putExtra(BlockOverlayActivity.EXTRA_DELAY_SECS, cachedDelaySecs)
            }
            service.startActivity(intent)
        } catch (e: Exception) { Timber.e(e, "$TAG launch overlay") }
    }

    fun resetCooldown() { lastBlockTime = 0L }
}
