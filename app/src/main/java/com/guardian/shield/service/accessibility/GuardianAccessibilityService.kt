// app/src/main/java/com/guardian/shield/service/accessibility/GuardianAccessibilityService.kt
package com.guardian.shield.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.guardian.shield.data.local.datastore.GuardianPreferences
import com.guardian.shield.data.repository.AppRuleRepository
import com.guardian.shield.data.repository.BlockEventRepository
import com.guardian.shield.data.repository.KeywordRepository
import com.guardian.shield.di.AccessibilityServiceEntryPoint
import com.guardian.shield.domain.model.BlockEvent
import com.guardian.shield.domain.model.BlockReason
import com.guardian.shield.domain.model.DetectionResult
import com.guardian.shield.service.blocker.BlockingEngine
import com.guardian.shield.service.blocker.GuardianForegroundService
import com.guardian.shield.service.blocker.PreemptiveBlurManager
import com.guardian.shield.service.detection.AiDetector
import com.guardian.shield.service.detection.RulesEngine
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import timber.log.Timber

private val SYSTEM_UI_SKIP = setOf(
    "android", "com.android.systemui",
    "com.google.android.inputmethod.latin",
    "com.samsung.android.honeyboard",
    "com.sec.android.inputmethod",
    "com.touchtype.swiftkey",
    "com.swiftkey.swiftkeyapp"
)

/**
 * Risky packages — these get a preemptive blur as soon as they come to the
 * foreground, hiding any content until the AI confirms the screen is safe.
 *
 * Blur shows for AT MOST one AI cycle (~400-600 ms) and is removed
 * immediately if the screen is verified safe.
 */
private val RISKY_PACKAGES = setOf(
    // Browsers
    "com.android.chrome", "org.mozilla.firefox", "com.opera.browser",
    "com.opera.mini.native", "com.brave.browser", "com.microsoft.emmx",
    "com.UCMobile.intl", "com.sec.android.app.sbrowser",
    "com.kiwibrowser.browser", "com.duckduckgo.mobile.android",
    "org.mozilla.focus", "com.vivaldi.browser",
    // Social / image-heavy
    "com.instagram.android", "com.snapchat.android", "com.zhiliaoapp.musically",
    "com.ss.android.ugc.trill", "com.twitter.android",
    "com.facebook.katana", "com.facebook.lite", "com.reddit.frontpage",
    "com.pinterest", "com.tumblr",
    // Galleries / file managers
    "com.google.android.apps.photos", "com.miui.gallery",
    "com.sec.android.gallery3d", "com.android.gallery3d",
    "com.mi.android.globalFileexplorer", "com.android.documentsui"
)

private val BROWSER_PACKAGES = setOf(
    "com.android.chrome", "org.mozilla.firefox", "com.opera.browser",
    "com.brave.browser", "com.microsoft.emmx", "com.UCMobile.intl",
    "com.sec.android.app.sbrowser", "com.kiwibrowser.browser"
)

class GuardianAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "Guardian_Service"
        const val ACTION_REFRESH_RULES = "com.guardian.shield.REFRESH_RULES"
        const val ACTION_RELOAD_MODEL  = "com.guardian.shield.RELOAD_MODEL"
        // Reduced from 700 → 250 ms: keyword detection is now near-instant.
        private const val TEXT_DEBOUNCE_MS = 250L
    }

    private lateinit var rulesEngine: RulesEngine
    private lateinit var blockingEngine: BlockingEngine
    private lateinit var aiDetector: AiDetector
    private lateinit var appRuleRepo: AppRuleRepository
    private lateinit var keywordRepo: KeywordRepository
    private lateinit var blockEventRepo: BlockEventRepository
    private lateinit var prefs: GuardianPreferences
    private var blurManager: PreemptiveBlurManager? = null

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile private var currentForegroundPkg = ""
    @Volatile private var aiEnabled = false
    @Volatile private var aiThreshold = 0.30f       // safer default
    @Volatile private var aiIntervalMs = 600L       // faster default
    @Volatile private var aiScanJob: Job? = null
    @Volatile private var aiBusy = false
    @Volatile private var isInjected = false
    @Volatile private var consecutiveSafeFrames = 0  // for blur removal stability

    private var textDebounceJob: Job? = null

    // ── Receiver ─────────────────────────────────────────────────────

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (!isInjected) return
            when (intent?.action) {
                ACTION_REFRESH_RULES -> serviceScope.launch {
                    loadRulesIntoEngine()
                    loadSettings()
                }
                ACTION_RELOAD_MODEL -> serviceScope.launch {
                    loadSettings()
                    reloadAiModel()
                }
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onServiceConnected() {
        Timber.d("$TAG onServiceConnected")
        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                AccessibilityServiceEntryPoint::class.java
            )
            rulesEngine    = entryPoint.rulesEngine()
            blockingEngine = entryPoint.blockingEngine()
            aiDetector     = entryPoint.aiDetector()
            appRuleRepo    = entryPoint.appRuleRepo()
            keywordRepo    = entryPoint.keywordRepo()
            blockEventRepo = entryPoint.blockEventRepo()
            prefs          = entryPoint.prefs()
            blurManager    = PreemptiveBlurManager(this)
            isInjected = true
        } catch (e: Exception) {
            Timber.e(e, "$TAG injection FAILED")
            return
        }

        serviceScope.launch {
            loadRulesIntoEngine()
            loadSettings()
            blockingEngine.loadSettings()
            if (aiEnabled) reloadAiModel()
        }
        registerReceivers()
        startForegroundWatchdog()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isInjected) return
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (!SYSTEM_UI_SKIP.contains(pkg) && !rulesEngine.isSystemUi(pkg)) {
                    val pkgChanged = currentForegroundPkg != pkg
                    currentForegroundPkg = pkg

                    if (pkgChanged) {
                        consecutiveSafeFrames = 0

                        // ★ KEY: drop blur from previous app immediately
                        blurManager?.hideBlur()

                        // ★ KEY: if entering a risky app & AI is on, show
                        //   preemptive blur INSTANTLY and trigger urgent scan.
                        if (aiEnabled && aiDetector.isLoaded() &&
                            isRiskyPackage(pkg) &&
                            !rulesEngine.isWhitelisted(pkg) &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

                            blurManager?.showBlur(pkg, "Scanning content…")
                            triggerImmediateAiScan()
                        }
                    }
                }
                handleAppEvent(pkg)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                debounceTextScan(pkg)

                // ★ While in a risky app, every content change triggers a
                //   fresh blur+scan because images can change without window
                //   state changing (e.g. swipe / scroll).
                if (aiEnabled && aiDetector.isLoaded() &&
                    isRiskyPackage(pkg) &&
                    !rulesEngine.isWhitelisted(pkg) &&
                    !blockingEngine.isCoolingDown() &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    triggerImmediateAiScan()
                }
            }
        }
    }

    override fun onInterrupt() { Timber.w("$TAG interrupted") }

    override fun onDestroy() {
        stopAiScanLoop()
        blurManager?.destroy()
        serviceScope.cancel()
        try { unregisterReceiver(refreshReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ── App + text handlers ─────────────────────────────────────────

    private fun handleAppEvent(pkg: String) {
        serviceScope.launch(Dispatchers.Default) {
            try {
                when (val r = rulesEngine.evaluateApp(pkg)) {
                    is DetectionResult.Block -> {
                        logAndBlock(pkg, getAppName(pkg), r.reason, r.detail)
                    }
                    else -> { /* allow */ }
                }
            } catch (e: Exception) { Timber.e(e, "$TAG handleAppEvent") }
        }
    }

    private fun debounceTextScan(pkg: String) {
        textDebounceJob?.cancel()
        textDebounceJob = serviceScope.launch {
            delay(TEXT_DEBOUNCE_MS)
            if (!isInjected) return@launch
            if (rulesEngine.isWhitelisted(pkg)) return@launch
            if (blockingEngine.isCoolingDown()) return@launch

            val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return@launch
            try {
                val text = collectAllText(root)
                if (text.isBlank()) return@launch

                val scanText = if (pkg in BROWSER_PACKAGES) {
                    val url = getUrlBarText(root)
                    if (url != null) "$text $url" else text
                } else text

                val r = rulesEngine.evaluateText(pkg, scanText)
                if (r is DetectionResult.Block) {
                    logAndBlock(pkg, getAppName(pkg), r.reason, r.detail)
                }
            } catch (e: Exception) { Timber.e(e, "$TAG textScan") }
            finally { try { root.recycle() } catch (_: Exception) {} }
        }
    }

    // ── AI model / scan loop ────────────────────────────────────────

    private suspend fun reloadAiModel() {
        try {
            aiEnabled    = prefs.isAiDetectionEnabled.first()
            aiThreshold  = prefs.aiThreshold.first()
            aiIntervalMs = prefs.aiIntervalMs.first()
        } catch (e: Exception) { Timber.e(e, "$TAG reloadAi settings") }

        if (!aiEnabled) {
            stopAiScanLoop()
            aiDetector.unload()
            blurManager?.hideBlur()
            return
        }
        if (!AiDetector.isModelAvailable(applicationContext)) {
            stopAiScanLoop()
            return
        }

        val ok = withContext(Dispatchers.IO) { aiDetector.reload() }
        if (ok && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startAiScanLoop()
        } else {
            stopAiScanLoop()
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun startAiScanLoop() {
        stopAiScanLoop()
        aiScanJob = serviceScope.launch {
            while (isActive) {
                delay(aiIntervalMs)
                if (!aiDetector.isLoaded()) continue
                if (rulesEngine.isWhitelisted(currentForegroundPkg)) continue
                if (blockingEngine.isCoolingDown()) continue
                if (aiBusy) continue
                if (currentForegroundPkg.isBlank()) continue
                captureAndAnalyze()
            }
        }
    }

    private fun stopAiScanLoop() {
        aiScanJob?.cancel()
        aiScanJob = null
    }

    /**
     * Trigger an immediate (non-throttled) AI scan — used when entering a
     * risky app or detecting a content change. Only one urgent scan can be
     * in-flight at a time (gated by aiBusy).
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun triggerImmediateAiScan() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (aiBusy) return
        if (!aiDetector.isLoaded()) return
        serviceScope.launch { captureAndAnalyze() }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    @android.annotation.SuppressLint("NewApi")
    private fun captureAndAnalyze() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        aiBusy = true
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY, mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        serviceScope.launch(Dispatchers.Default) { analyzeScreenshot(result) }
                    }
                    override fun onFailure(errorCode: Int) {
                        aiBusy = false
                        if (errorCode != 2) Timber.w("$TAG screenshot fail code=$errorCode")
                    }
                }
            )
        } catch (e: Exception) {
            aiBusy = false
            Timber.e(e, "$TAG captureAndAnalyze")
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    @android.annotation.SuppressLint("NewApi")
    private suspend fun analyzeScreenshot(result: ScreenshotResult) {
        var full: Bitmap? = null
        var cropped: Bitmap? = null
        try {
            val hb = result.hardwareBuffer ?: run { aiBusy = false; return }
            val hwBmp = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
            full = hwBmp?.copy(Bitmap.Config.ARGB_8888, false)
            hwBmp?.recycle()
            if (full == null) { aiBusy = false; return }

            val w = full.width; val h = full.height
            val topCut = (h * 0.07f).toInt()
            val botCut = (h * 0.09f).toInt()
            val cropH = h - topCut - botCut
            cropped = if (cropH > 100) Bitmap.createBitmap(full, 0, topCut, w, cropH) else full

            if (aiDetector.shouldSkipFrame(cropped)) {
                // Uniform / black screen — treat as "safe" for blur purposes
                onSafeFrame()
                aiBusy = false
                return
            }

            val r = aiDetector.classify(cropped, aiThreshold)
            val pkg = currentForegroundPkg
            Timber.d("$TAG AI $pkg: ${r.label}")

            if (r.isUnsafe) {
                consecutiveSafeFrames = 0
                val ev = rulesEngine.evaluateAiResult(pkg, r.unsafeScore, aiThreshold)
                if (ev is DetectionResult.Block) {
                    logAndBlock(pkg, getAppName(pkg), ev.reason, ev.detail)
                }
                // keep blur shown — block flow takes over
            } else {
                onSafeFrame()
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG analyzeScreenshot")
        } finally {
            try { result.hardwareBuffer?.close() } catch (_: Exception) {}
            if (cropped != null && cropped !== full) cropped.recycle()
            full?.recycle()
            aiBusy = false
        }
    }

    /**
     * Two consecutive safe verdicts → confidently lift the blur.
     * Single-verdict lifting can flicker when one frame happens to be
     * black/transitioning.
     */
    private fun onSafeFrame() {
        consecutiveSafeFrames++
        if (consecutiveSafeFrames >= 2) {
            blurManager?.hideBlur()
        }
    }

    // ── Block + log ─────────────────────────────────────────────────

    private suspend fun logAndBlock(
        pkg: String, appName: String, reason: BlockReason, detail: String
    ) {
        try {
            withContext(Dispatchers.IO) {
                blockEventRepo.logEvent(
                    BlockEvent(packageName = pkg, appName = appName, reason = reason, detail = detail)
                )
            }
        } catch (e: Exception) { Timber.e(e, "$TAG log") }

        withContext(Dispatchers.Main) {
            blockingEngine.executeBlock(this@GuardianAccessibilityService, pkg, appName, reason, detail)
        }
    }

    // ── Rule cache loading ──────────────────────────────────────────

    private suspend fun loadRulesIntoEngine() {
        try {
            rulesEngine.refreshBlockedApps(appRuleRepo.getBlockedPackages())
            rulesEngine.refreshWhitelistedApps(appRuleRepo.getWhitelistedPackages())
            rulesEngine.refreshKeywords(keywordRepo.getActiveKeywords())
        } catch (e: Exception) { Timber.e(e, "$TAG loadRules") }
    }

    private suspend fun loadSettings() {
        try {
            aiEnabled    = prefs.isAiDetectionEnabled.first()
            aiThreshold  = prefs.aiThreshold.first()
            aiIntervalMs = prefs.aiIntervalMs.first()
            val protectionOn = prefs.isProtectionEnabled.first()
            val keywordOn    = prefs.isKeywordDetectionEnabled.first()
            val strictMode   = prefs.isStrictMode.first()
            rulesEngine.setProtectionEnabled(protectionOn)
            rulesEngine.setKeywordDetectionEnabled(keywordOn)
            rulesEngine.setStrictMode(strictMode)
        } catch (e: Exception) { Timber.e(e, "$TAG loadSettings") }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun isRiskyPackage(pkg: String): Boolean = pkg in RISKY_PACKAGES

    private fun getAppName(pkg: String): String = try {
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    } catch (_: Exception) { pkg }

    private fun collectAllText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder(512)
        var nodeCount = 0
        val maxNodes = 200
        fun go(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 15 || nodeCount >= maxNodes) return
            nodeCount++
            n.text?.let { if (it.length < 500) sb.append(it).append(' ') }
            n.contentDescription?.let { if (it.length < 200) sb.append(it).append(' ') }
            val cc = minOf(n.childCount, 50)
            for (i in 0 until cc) {
                if (nodeCount >= maxNodes) break
                val c = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                go(c, depth + 1)
                try { c.recycle() } catch (_: Exception) {}
            }
        }
        go(node, 0)
        return sb.toString()
    }

    private fun getUrlBarText(root: AccessibilityNodeInfo): String? {
        val ids = listOf(
            "com.android.chrome:id/url_bar",
            "com.android.chrome:id/search_box_text",
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "com.brave.browser:id/url_bar"
        )
        for (id in ids) {
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                if (nodes.isNotEmpty()) {
                    val t = nodes[0].text?.toString()
                    nodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                    if (t != null) return t
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun startForegroundWatchdog() {
        try {
            val intent = Intent(this, GuardianForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(intent) else startService(intent)
        } catch (e: Exception) { Timber.e(e, "$TAG fgService") }
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(ACTION_REFRESH_RULES); addAction(ACTION_RELOAD_MODEL)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                registerReceiver(refreshReceiver, filter, RECEIVER_NOT_EXPORTED)
            else
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(refreshReceiver, filter)
        } catch (e: Exception) { Timber.e(e, "$TAG regReceiver") }
    }
}
