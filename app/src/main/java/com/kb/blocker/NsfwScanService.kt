package com.kb.blocker

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics

/**
 * NSFW AI Scanner
 *
 * AccessibilityService এর takeScreenshot() ব্যবহার করে
 * (Android 11+ / API 30+)
 *
 * প্রতি SCAN_INTERVAL_MS তে একবার screenshot নিয়ে
 * NsfwModelManager দিয়ে scan করে।
 *
 * Adult content detect হলে KeywordService কে জানায়।
 */
object NsfwScanService {

    private const val SCAN_INTERVAL_MS = 3_000L   // প্রতি ৩ সেকেন্ডে scan
    private const val SCAN_COOLDOWN_MS = 5_000L   // block এর পরে ৫ সেকেন্ড rest

    private val handler      = Handler(Looper.getMainLooper())
    private var isRunning    = false
    private var lastScanTime = 0L
    private var lastBlockTime = 0L

    // ── Start / Stop ──────────────────────────────────────────────────────────

    fun start(service: AccessibilityService) {
        if (isRunning) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        isRunning = true
        scheduleScan(service)
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
    }

    // ── Scan loop ─────────────────────────────────────────────────────────────

    private fun scheduleScan(service: AccessibilityService) {
        handler.postDelayed({
            if (!isRunning) return@postDelayed
            doScan(service)
            scheduleScan(service)
        }, SCAN_INTERVAL_MS)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun doScan(service: AccessibilityService) {
        val ctx = service.applicationContext

        // Feature enabled?
        if (!NsfwModelManager.isEnabled(ctx)) return
        if (!NsfwModelManager.isModelLoaded()) {
            NsfwModelManager.loadModel(ctx)
            if (!NsfwModelManager.isModelLoaded()) return
        }

        val now = System.currentTimeMillis()
        if (now - lastScanTime < SCAN_INTERVAL_MS) return
        if (now - lastBlockTime < SCAN_COOLDOWN_MS) return

        // Foreground pkg check
        val pkg = KeywordService.currentForegroundPkg
        if (pkg.isBlank() || pkg == ctx.packageName) return
        if (KeywordService.isWhitelisted(ctx, pkg)) return

        lastScanTime = now

        // takeScreenshot — API 30+
        try {
            service.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                ctx.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        processScreenshot(ctx, screenshot, service, pkg)
                    }
                    override fun onFailure(errorCode: Int) { /* ignore */ }
                }
            )
        } catch (_: Exception) { }
    }

    private fun processScreenshot(
        ctx: android.content.Context,
        screenshot: AccessibilityService.ScreenshotResult,
        service: AccessibilityService,
        pkg: String
    ) {
        try {
            val hwBuffer = screenshot.hardwareBuffer ?: return
            val bitmap   = Bitmap.wrapHardwareBuffer(hwBuffer, null)
                ?.copy(Bitmap.Config.ARGB_8888, false)
            hwBuffer.close()
            bitmap ?: return

            // Background thread এ inference চালাও
            Thread {
                try {
                    val (isAdult, confidence) = NsfwModelManager.scan(ctx, bitmap)
                    bitmap.recycle()

                    if (isAdult) {
                        handler.post {
                            val now = System.currentTimeMillis()
                            // Double check — ইতিমধ্যে block হয়নি তো?
                            if (now - lastBlockTime > SCAN_COOLDOWN_MS) {
                                lastBlockTime = now
                                val conf = "%.0f".format(confidence * 100)
                                KeywordService.instance?.triggerBlockPublic(
                                    pkg, "🤖 AI detected adult image ($conf%)"
                                )
                            }
                        }
                    }
                } catch (_: Exception) {
                    bitmap.recycle()
                }
            }.start()

        } catch (_: Exception) { }
    }
}
