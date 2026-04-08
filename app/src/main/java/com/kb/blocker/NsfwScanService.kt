package com.kb.blocker

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object NsfwScanService {

    private const val TAG = "NsfwScan"
    private const val SCHEDULE_MS = 2_000L          // 2 সেকেন্ডে একবার চেষ্টা
    private const val COOLDOWN_MS = 4_000L          // block এর পরে rest
    private const val STRONG_BLOCK_THRESHOLD = 0.92f
    private const val CONFIRM_BLOCK_THRESHOLD = 0.72f
    private const val REQUIRED_CONSECUTIVE_HITS = 2

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanInProgress = AtomicBoolean(false)
    private val screenshotExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile private var isRunning = false
    @Volatile private var lastBlockTime = 0L

    private var positiveStreakPkg = ""
    private var positiveStreakCount = 0
    private var lastConfidence = 0f

    fun start(service: AccessibilityService) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.d(TAG, "Android 11+ required")
            return
        }
        if (isRunning) return
        isRunning = true
        scanInProgress.set(false)
        resetPositiveStreak()
        Log.d(TAG, "Started")
        scheduleLoop(service)
    }

    fun stop() {
        isRunning = false
        scanInProgress.set(false)
        resetPositiveStreak()
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
        if (!scanInProgress.compareAndSet(false, true)) return

        val ctx = service.applicationContext

        if (!NsfwModelManager.isEnabled(ctx)) { scanInProgress.set(false); return }
        if (!NsfwModelManager.isModelLoaded()) { scanInProgress.set(false); return }
        if (System.currentTimeMillis() - lastBlockTime < COOLDOWN_MS) { scanInProgress.set(false); return }

        val pkg = KeywordService.currentForegroundPkg
        if (pkg.isBlank() || pkg == ctx.packageName) { scanInProgress.set(false); return }

        val isTarget = KeywordService.BROWSER_PACKAGES.any { pkg.contains(it) } ||
            KeywordService.VIDEO_PACKAGES.any { pkg.contains(it) }
        if (!isTarget) { scanInProgress.set(false); return }

        val wl = KeywordService.instance?.whitelistCache ?: KeywordService.loadWhitelistSet(ctx)
        if (wl.contains(pkg)) { scanInProgress.set(false); return }

        try {
            service.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(shot: AccessibilityService.ScreenshotResult) {
                        processOnBgThread(ctx, shot, pkg)
                    }

                    override fun onFailure(code: Int) {
                        Log.d(TAG, "takeScreenshot fail: $code")
                        resetPositiveStreak()
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
        ctx: Context,
        shot: AccessibilityService.ScreenshotResult,
        pkg: String
    ) {
        var soft: Bitmap? = null
        try {
            val hw = shot.hardwareBuffer ?: run {
                resetPositiveStreak()
                scanInProgress.set(false)
                return
            }
            val hard = Bitmap.wrapHardwareBuffer(hw, null)
            hw.close()
            hard ?: run {
                resetPositiveStreak()
                scanInProgress.set(false)
                return
            }

            soft = hard.copy(Bitmap.Config.ARGB_8888, false)
            hard.recycle()
            soft ?: run {
                resetPositiveStreak()
                scanInProgress.set(false)
                return
            }

            if (soft.isRecycled) {
                resetPositiveStreak()
                scanInProgress.set(false)
                return
            }

            val (isAdult, conf) = NsfwModelManager.scan(ctx, soft)
            lastConfidence = conf

            if (!isAdult) {
                resetPositiveStreak()
                return
            }

            val shouldBlockNow = updatePositiveStreak(pkg, conf)
            if (!shouldBlockNow) return

            val now = System.currentTimeMillis()
            if (now - lastBlockTime > COOLDOWN_MS) {
                lastBlockTime = now
                val pct = "%.0f".format(conf * 100)
                Log.d(TAG, "Adult: $pkg ($pct%), streak=$positiveStreakCount")
                mainHandler.post {
                    KeywordService.instance?.triggerBlock(
                        pkg,
                        "🤖 AI: Adult image ($pct%)"
                    )
                }
                resetPositiveStreak()
            }
        } catch (e: Exception) {
            Log.e(TAG, "process: ${e.message}")
        } finally {
            try { soft?.recycle() } catch (_: Exception) {}
            scanInProgress.set(false)
        }
    }

    private fun updatePositiveStreak(pkg: String, confidence: Float): Boolean {
        if (confidence >= STRONG_BLOCK_THRESHOLD) {
            positiveStreakPkg = pkg
            positiveStreakCount = REQUIRED_CONSECUTIVE_HITS
            return true
        }

        if (confidence < CONFIRM_BLOCK_THRESHOLD) {
            resetPositiveStreak()
            return false
        }

        if (positiveStreakPkg == pkg) {
            positiveStreakCount += 1
        } else {
            positiveStreakPkg = pkg
            positiveStreakCount = 1
        }

        return positiveStreakCount >= REQUIRED_CONSECUTIVE_HITS
    }

    private fun resetPositiveStreak() {
        positiveStreakPkg = ""
        positiveStreakCount = 0
        lastConfidence = 0f
    }
}
