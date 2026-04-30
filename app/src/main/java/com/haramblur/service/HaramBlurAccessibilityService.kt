package com.haramblur.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.haramblur.ml.NsfwClassifier
import com.haramblur.overlay.BlurOverlayManager
import com.haramblur.utils.Constants
import com.haramblur.utils.Settings
import kotlinx.coroutines.*

@RequiresApi(Build.VERSION_CODES.R)   // Android 11+ for takeScreenshot()
class HaramBlurAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "HaramBlur.Service"
        var instance: HaramBlurAccessibilityService? = null
    }

    private lateinit var settings: Settings
    private lateinit var classifier: NsfwClassifier
    private lateinit var overlay: BlurOverlayManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastShotTime = 0L
    private var classifyJob: Job? = null

    // ---------- lifecycle ----------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        settings   = Settings(this)
        classifier = NsfwClassifier(this)
        overlay    = BlurOverlayManager(this)

        scope.launch {
            classifier.init()
            Log.d(TAG, "Service connected, classifier ready=${classifier.isReady()}")
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        overlay.destroy()
        classifier.close()
        scope.cancel()
    }

    // ---------- event handling ----------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!settings.isEnabled) { overlay.hideBlur(); return }

        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return                       // skip our own app
        if (settings.isWhitelisted(pkg)) { overlay.hideBlur(); return }

        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            scheduleCapture()
        }
    }

    // ---------- capture + classify ----------

    private fun scheduleCapture() {
        val now = System.currentTimeMillis()
        if (now - lastShotTime < Constants.SCREENSHOT_DEBOUNCE_MS) return
        lastShotTime = now

        classifyJob?.cancel()
        classifyJob = scope.launch {
            if (!classifier.isReady()) return@launch

            val bmp = captureScreen() ?: return@launch

            // DRM / blank frame check
            if (NsfwClassifier.isBlackFrame(bmp)) {
                Log.d(TAG, "Black frame — DRM content, skip")
                bmp.recycle()
                return@launch
            }

            val result = classifier.classify(bmp, settings.strictness)
            bmp.recycle()

            withContext(Dispatchers.Main) {
                if (result?.isNsfw == true) {
                    Log.d(TAG, "NSFW: ${result.topClass} conf=${result.confidence}")
                    overlay.showBlur()
                } else {
                    overlay.hideBlur()
                }
            }
        }
    }

    /**
     * AccessibilityService.takeScreenshot() — Android 11+
     * No extra permission prompt beyond enabling the accessibility service.
     */
    private suspend fun captureScreen(): Bitmap? =
        suspendCancellableCoroutine { cont ->
            try {
                takeScreenshot(
                    0,               // DEFAULT_DISPLAY
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            val hw  = result.hardwareBuffer
                            val bmp = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                            hw.close()
                            cont.resume(bmp) {}
                        }
                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "Screenshot fail code=$errorCode")
                            cont.resume(null) {}
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "takeScreenshot exception: ${e.message}")
                cont.resume(null) {}
            }
        }
}
