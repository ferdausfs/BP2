package com.kb.blocker

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object NsfwScanService {

    private const val TAG         = "NsfwScan"
    private const val SCHEDULE_MS = 2_000L
    private const val COOLDOWN_MS = 4_000L

    private val mainHandler    = Handler(Looper.getMainLooper())
    // ★ একটাই executor — প্রতিবার নতুন তৈরি করা বন্ধ
    private val bgExecutor     = Executors.newSingleThreadExecutor()
    private val scanInProgress = AtomicBoolean(false)

    @Volatile private var isRunning     = false
    @Volatile private var lastBlockTime = 0L
    // ★ service reference weak রাখি
    @Volatile private var serviceRef: AccessibilityService? = null

    fun start(service: AccessibilityService) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        serviceRef = service
        if (isRunning) return
        isRunning = true
        scanInProgress.set(false)
        Log.d(TAG, "Started")
        scheduleLoop()
    }

    fun stop() {
        isRunning  = false
        serviceRef = null
        scanInProgress.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Stopped")
    }

    fun isRunning() = isRunning

    private fun scheduleLoop() {
        mainHandler.postDelayed({
            if (!isRunning) return@postDelayed
            val svc = serviceRef
            if (svc != null) tryScan(svc)
            scheduleLoop()
        }, SCHEDULE_MS)
    }

    private fun tryScan(service: AccessibilityService) {
        if (!scanInProgress.compareAndSet(false, true)) return

        val ctx = service.applicationContext

        if (!NsfwModelManager.isEnabled(ctx))   { scanInProgress.set(false); return }
        if (!NsfwModelManager.isModelLoaded())  { scanInProgress.set(false); return }
        if (System.currentTimeMillis() - lastBlockTime < COOLDOWN_MS) { scanInProgress.set(false); return }

        val pkg = KeywordService.currentForegroundPkg
        if (pkg.isBlank() || pkg == ctx.packageName) { scanInProgress.set(false); return }

        // শুধু browser + video
        val isTarget = KeywordService.BROWSER_PACKAGES.any { pkg.contains(it) } ||
                       KeywordService.VIDEO_PACKAGES.any  { pkg.contains(it) }
        if (!isTarget) { scanInProgress.set(false); return }

        // Whitelist
        val wl = KeywordService.instance?.whitelistCache ?: KeywordService.loadWhitelistSet(ctx)
        if (wl.contains(pkg)) { scanInProgress.set(false); return }

        try {
            // ★ takeScreenshot — main thread এ, callback bgExecutor এ
            service.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                bgExecutor,  // ★ একই executor reuse
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(shot: AccessibilityService.ScreenshotResult) {
                        processOnBgThread(ctx, shot, pkg)
                    }
                    override fun onFailure(code: Int) {
                        Log.d(TAG, "Screenshot fail: $code")
                        scanInProgress.set(false)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot error: ${e.message}")
            scanInProgress.set(false)
        }
    }

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
            if (hard == null) { scanInProgress.set(false); return }

            soft = hard.copy(Bitmap.Config.ARGB_8888, false)
            hard.recycle()
            if (soft == null || soft.isRecycled) { scanInProgress.set(false); return }

            val (isAdult, conf) = NsfwModelManager.scan(ctx, soft)

            if (isAdult) {
                val now = System.currentTimeMillis()
                if (now - lastBlockTime > COOLDOWN_MS) {
                    lastBlockTime = now
                    val pct = "%.0f".format(conf * 100)
                    Log.d(TAG, "Adult detected in $pkg ($pct%)")
                    mainHandler.post {
                        KeywordService.instance?.triggerBlock(
                            pkg, "🤖 AI: Adult image detected ($pct%)"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Process error: ${e.message}")
        } finally {
            try { soft?.recycle() } catch (_: Exception) {}
            scanInProgress.set(false)
        }
    }
}
