package com.kb.blocker

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

object NsfwScanService {

    private const val TAG          = "NsfwScan"
    private const val SCHEDULE_MS  = 2_000L  // 2 সেকেন্ডে একবার চেষ্টা
    private const val COOLDOWN_MS  = 4_000L  // block এর পরে rest

    private val mainHandler    = Handler(Looper.getMainLooper())
    private val scanInProgress = AtomicBoolean(false)

    @Volatile private var isRunning     = false
    @Volatile private var lastBlockTime = 0L

    fun start(service: AccessibilityService) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.d(TAG, "Android 11+ required")
            return
        }
        if (isRunning) return
        isRunning = true
        scanInProgress.set(false)
        Log.d(TAG, "Started")
        scheduleLoop(service)
    }

    fun stop() {
        isRunning = false
        scanInProgress.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Stopped")
    }

    private fun scheduleLoop(service: AccessibilityService) {
        mainHandler.postDelayed({
            if (!isRunning) return@postDelayed
            tryScan(service)
            scheduleLoop(service)
        }, SCHEDULE_MS)
    }

    // ── Main thread: screenshot নাও ───────────────────────────────────────────

    private fun tryScan(service: AccessibilityService) {
        // আগের scan চললে skip
        if (!scanInProgress.compareAndSet(false, true)) return

        val ctx = service.applicationContext

        // সব condition check
        if (!NsfwModelManager.isEnabled(ctx)) { scanInProgress.set(false); return }
        if (!NsfwModelManager.isModelLoaded()) { scanInProgress.set(false); return }
        if (System.currentTimeMillis() - lastBlockTime < COOLDOWN_MS) { scanInProgress.set(false); return }

        val pkg = KeywordService.currentForegroundPkg
        if (pkg.isBlank() || pkg == ctx.packageName) { scanInProgress.set(false); return }

        // শুধু browser + video
        val isTarget = KeywordService.BROWSER_PACKAGES.any { pkg.contains(it) } ||
                       KeywordService.VIDEO_PACKAGES.any  { pkg.contains(it) }
        if (!isTarget) { scanInProgress.set(false); return }

        // Whitelist
        val wl = KeywordService.instance?.whitelistCache
            ?: KeywordService.loadWhitelistSet(ctx)
        if (wl.contains(pkg)) { scanInProgress.set(false); return }

        // ★ takeScreenshot — main thread এ ★
        try {
            service.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                java.util.concurrent.Executors.newSingleThreadExecutor(),
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(shot: AccessibilityService.ScreenshotResult) {
                        processOnBgThread(ctx, shot, pkg)
                    }
                    override fun onFailure(code: Int) {
                        Log.d(TAG, "fail: $code")
                        scanInProgress.set(false)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot: ${e.message}")
            scanInProgress.set(false)
        }
    }

    // ── Bg thread: inference ──────────────────────────────────────────────────

    private fun processOnBgThread(
        ctx: android.content.Context,
        shot: AccessibilityService.ScreenshotResult,
        pkg: String
    ) {
        var soft: Bitmap? = null
        try {
            val hw   = shot.hardwareBuffer ?: run { scanInProgress.set(false); return }
            val hard = Bitmap.wrapHardwareBuffer(hw, null)
            hw.close()
            hard ?: run { scanInProgress.set(false); return }

            // hardware → software (inference এর জন্য)
            soft = hard.copy(Bitmap.Config.ARGB_8888, false)
            hard.recycle()
            soft ?: run { scanInProgress.set(false); return }

            if (soft.isRecycled) { scanInProgress.set(false); return }

            val (isAdult, conf) = NsfwModelManager.scan(ctx, soft)

            if (isAdult) {
                val now = System.currentTimeMillis()
                if (now - lastBlockTime > COOLDOWN_MS) {
                    lastBlockTime = now
                    val pct = "%.0f".format(conf * 100)
                    Log.d(TAG, "Adult: $pkg ($pct%)")
                    mainHandler.post {
                        KeywordService.instance?.triggerBlock(
                            pkg, "🤖 AI: Adult image ($pct%)"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "process: ${e.message}")
        } finally {
            try { soft?.recycle() } catch (_: Exception) {}
            scanInProgress.set(false)  // ★ সবসময় release
        }
    }
}
