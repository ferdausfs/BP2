package com.kb.blocker

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

object NsfwScanService {

    private const val TAG              = "NsfwScanService"
    private const val SCAN_INTERVAL_MS = 4_000L
    private const val SCAN_COOLDOWN_MS = 6_000L
    private const val MIN_SCAN_GAP_MS  = 3_000L

    private val mainHandler  = Handler(Looper.getMainLooper())
    private val bgExecutor   = Executors.newSingleThreadExecutor()

    @Volatile private var isRunning    = false
    @Volatile private var lastScanTime = 0L
    @Volatile private var lastBlockTime = 0L

    fun start(service: AccessibilityService) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (isRunning) return
        isRunning = true
        Log.d(TAG, "NsfwScanService started")
        scheduleScan(service)
    }

    fun stop() {
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        Log.d(TAG, "NsfwScanService stopped")
    }

    private fun scheduleScan(service: AccessibilityService) {
        mainHandler.postDelayed({
            if (!isRunning) return@postDelayed
            tryTakeScreenshot(service)
            scheduleScan(service)
        }, SCAN_INTERVAL_MS)
    }

    private fun tryTakeScreenshot(service: AccessibilityService) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val ctx = service.applicationContext

        // Feature enabled?
        if (!NsfwModelManager.isEnabled(ctx)) return

        // Model loaded? Background thread এ load করো
        if (!NsfwModelManager.isModelLoaded()) {
            bgExecutor.execute {
                NsfwModelManager.loadModel(ctx)
            }
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastScanTime < MIN_SCAN_GAP_MS) return
        if (now - lastBlockTime < SCAN_COOLDOWN_MS) return

        val pkg = KeywordService.currentForegroundPkg
        if (pkg.isBlank() || pkg == ctx.packageName) return
        if (KeywordService.isWhitelisted(ctx, pkg)) return

        lastScanTime = now

        try {
            service.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                bgExecutor,  // ← bg executor এ callback — main thread block হবে না
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        processInBackground(ctx, screenshot, service, pkg)
                    }
                    override fun onFailure(errorCode: Int) {
                        Log.d(TAG, "Screenshot failed: $errorCode")
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot error: ${e.message}")
        }
    }

    private fun processInBackground(
        ctx: android.content.Context,
        screenshot: AccessibilityService.ScreenshotResult,
        service: AccessibilityService,
        pkg: String
    ) {
        var bitmap: Bitmap? = null
        try {
            val hwBuffer = screenshot.hardwareBuffer ?: return
            bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, null)
                ?.copy(Bitmap.Config.ARGB_8888, false)
            hwBuffer.close()
            bitmap ?: return

            val (isAdult, confidence) = NsfwModelManager.scan(ctx, bitmap)

            if (isAdult) {
                val now = System.currentTimeMillis()
                if (now - lastBlockTime > SCAN_COOLDOWN_MS) {
                    lastBlockTime = now
                    val conf = "%.0f".format(confidence * 100)
                    Log.d(TAG, "Adult detected in $pkg: $conf%")

                    mainHandler.post {
                        KeywordService.instance?.triggerBlock(
                            pkg, "🤖 AI: Adult image detected ($conf%)"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Process error: ${e.message}")
        } finally {
            try { bitmap?.recycle() } catch (_: Exception) {}
        }
    }
}
