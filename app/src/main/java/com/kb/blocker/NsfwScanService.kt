package com.kb.blocker

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AI Screenshot Scanner
 *
 * ★ Crash-free design:
 * - একটাই scan চলবে একসাথে (AtomicBoolean lock)
 * - inference শেষ হওয়ার আগে নতুন scan শুরু হবে না
 * - সব bitmap সঠিকভাবে recycle হবে
 * - takeScreenshot main thread এ, inference bg thread এ
 */
object NsfwScanService {

    private const val TAG = "NsfwScan"

    // Scan interval — inference চলাকালীন skip হবে
    private const val SCHEDULE_MS   = 1_500L  // loop repeat
    private const val COOLDOWN_MS   = 3_000L  // block এর পরে rest

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var isRunning      = false
    @Volatile private var lastBlockTime  = 0L

    // ★ এই lock দিয়ে একসাথে একটাই scan চলবে
    private val scanInProgress = AtomicBoolean(false)

    // ── Start / Stop ───────────────────────────────────────────────────────────

    fun start(service: AccessibilityService) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
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

    // ── Main loop — main thread এ চলে ─────────────────────────────────────────

    private fun scheduleLoop(service: AccessibilityService) {
        mainHandler.postDelayed({
            if (!isRunning) return@postDelayed
            maybeCapture(service)
            scheduleLoop(service)
        }, SCHEDULE_MS)
    }

    private fun maybeCapture(service: AccessibilityService) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        // ★ আগের scan শেষ না হলে এই round skip
        if (!scanInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Previous scan still running — skip")
            return
        }

        val ctx = service.applicationContext

        if (!NsfwModelManager.isEnabled(ctx) || !NsfwModelManager.isModelLoaded()) {
            scanInProgress.set(false)
            return
        }

        if (System.currentTimeMillis() - lastBlockTime < COOLDOWN_MS) {
            scanInProgress.set(false)
            return
        }

        val pkg = KeywordService.currentForegroundPkg
        if (pkg.isBlank() || pkg == ctx.packageName) {
            scanInProgress.set(false)
            return
        }

        // শুধু browser + video app
        val isTarget = KeywordService.BROWSER_PACKAGES.any { pkg.contains(it) } ||
                       KeywordService.VIDEO_PACKAGES.any  { pkg.contains(it) }
        if (!isTarget) { scanInProgress.set(false); return }

        // Whitelist
        val svc = KeywordService.instance
        val whitelist = svc?.whitelistCache ?: KeywordService.loadWhitelistSet(ctx)
        if (whitelist.contains(pkg)) { scanInProgress.set(false); return }

        // ★ takeScreenshot — main thread এ call করা হচ্ছে ✓
        try {
            service.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                // callback executor — bg thread এ process করব
                java.util.concurrent.Executors.newSingleThreadExecutor(),
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(shot: AccessibilityService.ScreenshotResult) {
                        // bg thread এ inference
                        runInference(ctx, shot, pkg)
                    }
                    override fun onFailure(code: Int) {
                        Log.d(TAG, "Screenshot fail: $code")
                        scanInProgress.set(false)  // ★ lock release
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot error: ${e.message}")
            scanInProgress.set(false)  // ★ lock release
        }
    }

    // ── Inference — bg thread এ ────────────────────────────────────────────────

    private fun runInference(
        ctx: android.content.Context,
        shot: AccessibilityService.ScreenshotResult,
        pkg: String
    ) {
        var softBitmap: Bitmap? = null
        try {
            // HardwareBuffer → software bitmap
            val hw = shot.hardwareBuffer
            if (hw == null) { scanInProgress.set(false); return }

            val hardBitmap = Bitmap.wrapHardwareBuffer(hw, null)
            hw.close()

            if (hardBitmap == null) { scanInProgress.set(false); return }

            // Hardware bitmap → software (inference এর জন্য software লাগে)
            softBitmap = hardBitmap.copy(Bitmap.Config.ARGB_8888, false)
            hardBitmap.recycle()  // hard bitmap আর দরকার নেই

            if (softBitmap == null) { scanInProgress.set(false); return }

            // ★ scan — NsfwModelManager নিজেই resize করে
            val (isAdult, confidence) = NsfwModelManager.scan(ctx, softBitmap)

            if (isAdult) {
                val now = System.currentTimeMillis()
                if (now - lastBlockTime > COOLDOWN_MS) {
                    lastBlockTime = now
                    val pct = "%.0f".format(confidence * 100)
                    Log.d(TAG, "Adult detected in $pkg ($pct%)")
                    mainHandler.post {
                        KeywordService.instance?.triggerBlock(
                            pkg, "🤖 AI: Adult image detected ($pct%)"
                        )
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}")
        } finally {
            // ★ সবসময় bitmap recycle ও lock release
            try { softBitmap?.recycle() } catch (_: Exception) {}
            scanInProgress.set(false)
        }
    }
}
