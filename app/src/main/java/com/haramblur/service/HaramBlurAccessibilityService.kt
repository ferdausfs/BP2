package com.haramblur.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.haramblur.ml.NsfwClassifier
import com.haramblur.overlay.BlurOverlayManager
import com.haramblur.utils.Constants
import com.haramblur.utils.Settings
import kotlinx.coroutines.*

@RequiresApi(Build.VERSION_CODES.R)
class HaramBlurAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "HaramBlur.Service"
        var instance: HaramBlurAccessibilityService? = null

        private val NSFW_KEYWORDS = setOf(
            "porn", "xxx", "nude", "naked", "sex", "adult", "nsfw",
            "hentai", "erotic", "onlyfans", "escort",
            "xvideos", "pornhub", "xnxx", "xhamster", "redtube"
        )

        private val IMAGE_CLASSES = setOf(
            "ImageView", "PhotoView", "DraweeView", "FrescoImageView"
        )
    }

    private lateinit var settings: Settings
    private lateinit var classifier: NsfwClassifier
    private lateinit var overlay: BlurOverlayManager

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastShotTime = 0L
    private var classifyJob: Job? = null
    private var consecutiveSfwCount = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance   = this
        settings   = Settings(this)
        classifier = NsfwClassifier(this)
        overlay    = BlurOverlayManager(this)

        // Enable window content retrieval for View Hierarchy scanning
        val info = serviceInfo
        info.flags = info.flags or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        serviceInfo = info

        scope.launch {
            classifier.init()
            Log.d(TAG, "Classifier ready=${classifier.isReady()}")
        }
    }

    override fun onInterrupt() { Log.d(TAG, "Interrupted") }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        overlay.destroy()
        classifier.close()
        scope.cancel()
    }

    // ── event handling ────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!settings.isEnabled) { overlay.hideBlur(); return }

        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (settings.isWhitelisted(pkg)) { overlay.hideBlur(); return }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                consecutiveSfwCount = 0
                scheduleCapture(priority = true)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val root = event.source
                val hasKeyword = root != null && scanKeywords(root)
                val hasImage   = root != null && scanImages(root)
                root?.recycle()

                when {
                    hasKeyword -> scheduleCapture(priority = true)
                    hasImage   -> scheduleCapture(priority = false)
                    overlay.isVisible() -> scheduleCapture(priority = false)
                }
            }
        }
    }

    // ── View Hierarchy scan ──────────────────────────────────────────────────

    private fun scanKeywords(node: AccessibilityNodeInfo): Boolean =
        walkTree(node, 0) { n ->
            val t = "${n.text ?: ""}${n.contentDescription ?: ""}".lowercase()
            NSFW_KEYWORDS.any { t.contains(it) }
        }

    private fun scanImages(node: AccessibilityNodeInfo): Boolean =
        walkTree(node, 0) { n ->
            val cls = n.className?.toString() ?: ""
            if (IMAGE_CLASSES.any { cls.endsWith(it) }) {
                val r = Rect(); n.getBoundsInScreen(r)
                r.width() > 60 && r.height() > 60
            } else false
        }

    /** DFS tree walk; returns true as soon as predicate matches */
    private fun walkTree(
        node: AccessibilityNodeInfo,
        depth: Int,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): Boolean {
        if (depth > 8) return false
        if (predicate(node)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = walkTree(child, depth + 1, predicate)
            child.recycle()
            if (found) return true
        }
        return false
    }

    // ── capture + classify ───────────────────────────────────────────────────

    private fun scheduleCapture(priority: Boolean) {
        val now = System.currentTimeMillis()
        val debounce = if (priority) 150L else Constants.SCREENSHOT_DEBOUNCE_MS
        if (now - lastShotTime < debounce) return
        lastShotTime = now

        classifyJob?.cancel()
        classifyJob = scope.launch {
            if (!classifier.isReady()) return@launch

            val bmp = captureScreen() ?: return@launch

            if (NsfwClassifier.isBlackFrame(bmp)) {
                bmp.recycle()
                Log.d(TAG, "Black frame — skip")
                return@launch
            }

            val result = classifier.classify(bmp, settings.strictness)
            bmp.recycle()

            withContext(Dispatchers.Main) {
                if (result?.isNsfw == true) {
                    consecutiveSfwCount = 0
                    Log.d(TAG, "NSFW: ${result.topClass} ${result.confidence}")
                    overlay.showBlur()
                } else {
                    consecutiveSfwCount++
                    // Require 3 consecutive clean frames before hiding
                    // — prevents flicker when user scrolls through feed
                    if (consecutiveSfwCount >= 3) {
                        overlay.hideBlur()
                    }
                }
            }
        }
    }

    private suspend fun captureScreen(): Bitmap? =
        suspendCancellableCoroutine { cont ->
            try {
                takeScreenshot(0, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(r: ScreenshotResult) {
                        val hw  = r.hardwareBuffer
                        val bmp = Bitmap.wrapHardwareBuffer(hw, r.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        hw.close()
                        cont.resume(bmp) {}
                    }
                    override fun onFailure(code: Int) {
                        Log.w(TAG, "Screenshot fail=$code")
                        cont.resume(null) {}
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "takeScreenshot: ${e.message}")
                cont.resume(null) {}
            }
        }
}
