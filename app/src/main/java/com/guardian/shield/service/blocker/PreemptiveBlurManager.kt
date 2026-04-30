// app/src/main/java/com/guardian/shield/service/blocker/PreemptiveBlurManager.kt
package com.guardian.shield.service.blocker

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * PreemptiveBlurManager — instantly draws an opaque/blur overlay on top of
 * the current screen the moment a potentially-risky app opens, BEFORE the
 * AI inference has finished. Once the AI confirms the screen is safe, the
 * overlay is removed. If unsafe is detected, the full BlockOverlayActivity
 * takes over.
 *
 * Why: previously the app waited 2.5s between AI scans, meaning the user
 * could see explicit content for up to 2.5s before the block kicked in.
 * With preemptive blur:
 *   t=0ms     → user opens browser/social app
 *   t=0ms     → black/blur overlay drawn instantly via WindowManager
 *   t=~400ms  → AI verdict ready
 *   t=~400ms  → overlay removed (safe) OR full block shown (unsafe)
 *
 * Implementation note: We use TYPE_ACCESSIBILITY_OVERLAY which is granted
 * automatically with the accessibility permission — no SYSTEM_ALERT_WINDOW
 * runtime grant required.
 */
class PreemptiveBlurManager(
    private val service: AccessibilityService
) {
    companion object {
        private const val TAG = "Guardian_Blur"
        // How long to keep the blur up if no AI verdict arrives (safety net).
        private const val MAX_BLUR_DURATION_MS = 6_000L
    }

    private val wm: WindowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var overlayView: View? = null
    @Volatile private var currentPkg: String = ""

    private val autoRemoveRunnable = Runnable {
        Timber.d("$TAG auto-remove triggered (AI didn't respond in time)")
        hideBlur()
    }

    /**
     * Show preemptive blur immediately. Idempotent — multiple calls for the
     * same package are no-ops.
     */
    @Synchronized
    fun showBlur(packageName: String, message: String = "Scanning content…") {
        if (overlayView != null && currentPkg == packageName) return

        // Different package — remove old overlay first
        if (overlayView != null) hideBlurInternal()

        currentPkg = packageName

        // BUG FIX: Set a sentinel on the main thread BEFORE posting, so that
        // a second synchronized call arriving while the post is queued sees
        // currentPkg already set and returns early (prevents duplicate addView).
        val view = buildOverlayView(message)
        val params = buildLayoutParams()

        mainHandler.post {
            // Double-check: another call may have hidden the blur between the
            // synchronized block above and this post executing.
            if (currentPkg != packageName) return@post

            try {
                if (overlayView != null) {
                    try { wm.removeViewImmediate(overlayView!!) } catch (_: Exception) {}
                    overlayView = null
                }
                wm.addView(view, params)
                overlayView = view

                // Safety: never let blur stay up forever
                mainHandler.removeCallbacks(autoRemoveRunnable)
                mainHandler.postDelayed(autoRemoveRunnable, MAX_BLUR_DURATION_MS)

                Timber.d("$TAG showBlur for $packageName")
            } catch (e: Exception) {
                Timber.e(e, "$TAG showBlur failed")
                overlayView = null
            }
        }
    }

    /**
     * Hide the blur — called after the AI confirms the screen is safe.
     */
    @Synchronized
    fun hideBlur() {
        mainHandler.removeCallbacks(autoRemoveRunnable)
        if (overlayView == null) return
        mainHandler.post { hideBlurInternal() }
    }

    @Synchronized
    private fun hideBlurInternal() {
        try {
            overlayView?.let { wm.removeViewImmediate(it) }
        } catch (e: Exception) {
            Timber.w(e, "$TAG removeView failed")
        } finally {
            overlayView = null
            currentPkg = ""
        }
    }

    /**
     * Force-remove on service destroy.
     */
    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)
        hideBlurInternal()
    }

    // ── Builders ──────────────────────────────────────────────────────

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        val flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
            or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            // On Android 12+ apply real-time window blur if supported
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                blurBehindRadius = 60
                this.flags = this.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                dimAmount = 0.85f
            } else {
                dimAmount = 1.0f
            }
        }
    }

    private fun buildOverlayView(message: String): View {
        val ctx = service
        val root = FrameLayout(ctx).apply {
            // Solid near-black (works on every Android version, even when
            // hardware blur isn't available).
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2000000"))
            }
            isClickable = true   // swallow taps so user can't bypass
            isFocusable = false
        }

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.CENTER }
            layoutParams = lp
        }

        val shield = TextView(ctx).apply {
            text = "🛡️"
            textSize = 64f
            gravity = Gravity.CENTER
        }
        val title = TextView(ctx).apply {
            text = "Guardian Shield"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }
        val subtitle = TextView(ctx).apply {
            text = message
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(32, 8, 32, 0)
        }
        val progress = ProgressBar(ctx).apply {
            val pp = LinearLayout.LayoutParams(96, 96).also {
                it.topMargin = 32
                it.gravity = Gravity.CENTER
            }
            layoutParams = pp
            isIndeterminate = true
        }

        column.addView(shield)
        column.addView(title)
        column.addView(subtitle)
        column.addView(progress)
        root.addView(column)
        return root
    }
}
