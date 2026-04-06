package com.kb.blocker

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import java.util.concurrent.Executors

class KeywordService : AccessibilityService() {

    private lateinit var prefs: SharedPreferences
    private val handler   = Handler(Looper.getMainLooper())
    private val bgPool    = Executors.newSingleThreadExecutor()
    private var lastBlockTime = 0L

    // ── Whitelist cache ────────────────────────────────────────────────────────
    private var whitelistCache    : Set<String> = emptySet()
    internal var whitelistCacheTime = 0L

    // ── Usage tracking ─────────────────────────────────────────────────────────
    private var fgPkg       = ""
    private var fgStartTime = 0L

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        prefs    = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        instance = this
        isRunning = true
        refreshWhitelistCache()

        // AI scan — background thread এ model load
        if (NsfwModelManager.isEnabled(this)) {
            bgPool.execute {
                if (NsfwModelManager.loadModel(this)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        handler.post { NsfwScanService.start(this) }
                    }
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        flushUsage()
        NsfwScanService.stop()
        NsfwModelManager.unloadModel()
        handler.removeCallbacksAndMessages(null)
        instance  = null
        isRunning = false
    }

    // ── Whitelist cache ────────────────────────────────────────────────────────

    internal fun refreshWhitelistCache() {
        val now = System.currentTimeMillis()
        if (now - whitelistCacheTime > 3_000L) {
            whitelistCache    = loadWhitelistSet(this)
            whitelistCacheTime = now
        }
    }

    /**
     * ★ CORE WHITELIST CHECK ★
     * pkg: event এর package
     * currentForegroundPkg: screen এ যে app দেখা যাচ্ছে
     *
     * যেকোনো একটা whitelisted হলে block করব না।
     * এটা দিয়ে YouTube whitelisted থাকলে YouTube এর
     * background system event এও block হবে না।
     */
    private fun shouldBlock(eventPkg: String): Boolean {
        refreshWhitelistCache()
        // foreground app whitelisted? → block করব না
        if (whitelistCache.contains(currentForegroundPkg)) return false
        // event sender whitelisted? → block করব না
        if (whitelistCache.contains(eventPkg)) return false
        return true
    }

    // ── Accessibility Events ───────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (!isEnabled(this)) return

        // Foreground tracking
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (!isSystemPkg(pkg)) {
                if (fgPkg != pkg) {
                    flushUsage()
                    fgPkg       = pkg
                    fgStartTime = System.currentTimeMillis()
                }
                currentForegroundPkg = pkg
            }
        }

        // ★ WHITELIST — foreground app check সবার আগে ★
        if (!shouldBlock(pkg)) return

        val now = System.currentTimeMillis()
        if (now - lastBlockTime < 1500L) return

        // ── 1. Known adult app ─────────────────────────────────────────────────
        if (isKnownAdultApp(pkg)) {
            block(pkg, "🔞 Known adult app"); return
        }

        val isBrowserOrVideo = isBrowser(pkg) || isVideoApp(pkg)
        val root = rootInActiveWindow

        // ── 2. Schedule check ──────────────────────────────────────────────────
        if (isBrowserOrVideo && !ScheduleManager.isCurrentlyAllowed(this)) {
            block(pkg, "⏰ সময়সীমার বাইরে"); return
        }

        // ── 3. Usage limit ─────────────────────────────────────────────────────
        if (UsageLimitManager.isLimitExceeded(this, pkg)) {
            block(pkg, "⏱️ দৈনিক সীমা শেষ"); return
        }

        // ── 4. Browser URL check ───────────────────────────────────────────────
        if (isBrowser(pkg)) {
            val url = extractUrl(root)
            if (!url.isNullOrBlank()) {
                if (isHardAdultUrl(url)) { block(pkg, "🔞 Adult site"); return }
                if (isSoftAdultEnabled(this) && SoftAdultDetector.isSoftAdultUrl(url)) {
                    block(pkg, "🌶️ Suggestive search"); return
                }
            }
        }

        // ── 5. Video metadata ──────────────────────────────────────────────────
        if (isBrowserOrVideo && isVideoMetaEnabled(this)) {
            val meta = extractVideoMetadata(root)
            if (meta.isNotBlank() && VideoMetaDetector.isAdultMeta(meta)) {
                block(pkg, "🎬 Adult video content"); return
            }
        }

        // ── 6. Screen text ─────────────────────────────────────────────────────
        val screenText = buildString { collectText(root, this) }
        if (screenText.isBlank()) return

        // User keywords
        val userKeywords = getUserKeywords()
        if (userKeywords.isNotEmpty()) {
            val lower = screenText.lowercase(Locale.getDefault())
            val hit   = userKeywords.firstOrNull {
                it.isNotBlank() && lower.contains(it.lowercase(Locale.getDefault()))
            }
            if (hit != null) { block(pkg, "🚫 Keyword: \"$hit\""); return }
        }

        // Hard adult text
        if (isAdultTextDetectEnabled(this) && AdultContentDetector.isAdultContent(screenText)) {
            block(pkg, "🔞 Adult text"); return
        }

        // Soft adult — browser/video only
        if (isSoftAdultEnabled(this) && isBrowserOrVideo &&
            SoftAdultDetector.isSoftAdultContent(screenText)) {
            block(pkg, "🌶️ Suggestive content")
        }
    }

    // ── Block trigger ──────────────────────────────────────────────────────────

    internal fun triggerBlock(pkg: String, reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastBlockTime < 1500L) return
        lastBlockTime = now
        block(pkg, reason)
    }

    private fun block(pkg: String, reason: String) {
        lastBlockTime = System.currentTimeMillis()
        val label = getAppLabel(pkg)
        BlockLogManager.log(this, pkg, reason)
        closeAndKillPkg(pkg)
        BlockedActivity.launch(this, label, reason)
    }

    // ── Kill app ───────────────────────────────────────────────────────────────

    fun closeAndKillPkg(pkg: String) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        fun kill() = try {
            am.appTasks.forEach { task ->
                if (task.taskInfo.baseActivity?.packageName == pkg)
                    task.finishAndRemoveTask()
            }
            am.killBackgroundProcesses(pkg)
        } catch (_: Exception) {}
        kill()
        handler.postDelayed(::kill, 400)
    }

    // ── Video metadata ─────────────────────────────────────────────────────────

    private fun extractVideoMetadata(root: AccessibilityNodeInfo?): String {
        root ?: return ""
        val sb = StringBuilder()
        val ids = setOf(
            "title", "video_title", "media_title", "item_title",
            "content_title", "player_title", "description",
            "video_description", "channel_name", "author_name",
            "tag", "hashtag", "caption", "subtitle",
            "watch_title", "media_caption", "reel_title",
            "video_desc", "author_username", "story_title"
        )
        collectMetaDeep(root, ids, sb, 0)
        return sb.toString()
    }

    private fun collectMetaDeep(
        node: AccessibilityNodeInfo?, ids: Set<String>,
        sb: StringBuilder, depth: Int
    ) {
        if (node == null || depth > 10) return
        val resId = node.viewIdResourceName?.lowercase(Locale.getDefault()) ?: ""
        val text  = node.text?.toString() ?: ""
        val desc  = node.contentDescription?.toString() ?: ""

        if (ids.any { resId.contains(it) }) {
            if (text.isNotBlank()) sb.append(text).append(" ")
            if (desc.isNotBlank()) sb.append(desc).append(" ")
        }
        if (text.contains("#") || text.startsWith("@"))
            sb.append(text).append(" ")
        if (text.length in 10..200 && depth <= 4)
            sb.append(text).append(" ")

        for (i in 0 until node.childCount)
            collectMetaDeep(node.getChild(i), ids, sb, depth + 1)
    }

    // ── URL extraction ─────────────────────────────────────────────────────────

    private fun extractUrl(node: AccessibilityNodeInfo?): String? {
        node ?: return null
        val resId = node.viewIdResourceName?.lowercase(Locale.getDefault()) ?: ""
        if (node.className?.contains("EditText") == true ||
            resId.contains("url") || resId.contains("address") ||
            resId.contains("omnibox") || resId.contains("search")) {
            val text = node.text?.toString() ?: ""
            if (text.contains(".") && (text.startsWith("http") ||
                text.contains("www.") || text.matches(Regex(".*\\.[a-z]{2,}.*"))))
                return text
        }
        for (i in 0 until node.childCount)
            extractUrl(node.getChild(i))?.let { return it }
        return null
    }

    private fun isHardAdultUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.getDefault())
        return ADULT_DOMAINS.any { lower.contains(it) } ||
               ADULT_KEYWORDS_URL.any { lower.contains(it) }
    }

    // ── Usage tracking ─────────────────────────────────────────────────────────

    private fun flushUsage() {
        if (fgPkg.isNotBlank() && fgStartTime > 0) {
            val secs = (System.currentTimeMillis() - fgStartTime) / 1000
            if (secs > 0) UsageLimitManager.addUsage(this, fgPkg, secs)
            fgStartTime = 0L
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun collectText(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        node ?: return
        node.text?.let { sb.append(it).append(' ') }
        node.contentDescription?.let { sb.append(it).append(' ') }
        for (i in 0 until node.childCount) collectText(node.getChild(i), sb)
    }

    private fun getUserKeywords(): List<String> =
        (prefs.getString(KEY_WORDS, "") ?: "")
            .split(DELIMITER).filter { it.isNotBlank() }

    private fun getAppLabel(pkg: String) = try {
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    } catch (_: Exception) { pkg }

    private fun isSystemPkg(pkg: String) =
        pkg == "android" || pkg.startsWith("com.android.systemui") ||
        pkg.contains("inputmethod") || pkg.contains("keyboard")

    private fun isBrowser(pkg: String)       = BROWSER_PACKAGES.any { pkg.contains(it) }
    private fun isVideoApp(pkg: String)      = VIDEO_PACKAGES.any  { pkg.contains(it) }
    private fun isKnownAdultApp(pkg: String) = KNOWN_ADULT_APPS.any { pkg.contains(it) }

    // ── Companion ──────────────────────────────────────────────────────────────

    companion object {
        const val PREFS          = "kb_prefs"
        const val KEY_WORDS      = "keywords"
        const val KEY_ENABLED    = "enabled"
        const val KEY_WHITELIST  = "whitelist"
        const val KEY_ADULT_TEXT = "adult_text"
        const val KEY_SOFT_ADULT = "soft_adult"
        const val KEY_VIDEO_META = "video_meta"
        const val DELIMITER      = "|||"

        var instance           : KeywordService? = null
        var isRunning            = false
        var currentForegroundPkg = ""

        val BROWSER_PACKAGES = setOf(
            "com.android.chrome", "org.mozilla.firefox", "org.mozilla.firefox_beta",
            "com.microsoft.emmx", "com.opera.browser", "com.opera.mini.native",
            "com.brave.browser", "com.kiwibrowser.browser", "com.sec.android.app.sbrowser",
            "com.UCMobile.intl", "com.uc.browser.en", "com.mi.globalbrowser",
            "com.duckduckgo.mobile.android", "com.vivaldi.browser",
            "com.ecosia.android", "com.yandex.browser"
        )
        val VIDEO_PACKAGES = setOf(
            "com.google.android.youtube", "com.instagram.android",
            "com.facebook.katana", "com.twitter.android",
            "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
            "com.reddit.frontpage", "com.pinterest", "com.snapchat.android",
            "tv.twitch.android.app", "com.mx.player", "org.videolan.vlc",
            "com.mxtech.videoplayer.ad", "com.dailymotion.dailymotion",
            "com.vimeo.android.videoapp"
        )
        val KNOWN_ADULT_APPS = setOf(
            "pornhub", "xvideos", "xnxx", "xhamster", "redtube", "youporn",
            "tube8", "spankbang", "brazzers", "onlyfans", "faphouse",
            "chaturbate", "stripchat", "bongacams", "badoo", "adultfriendfinder",
            "sexcam", "livejasmin", "camsoda", "hentai", "nhentai", "e-hentai"
        )
        val ADULT_DOMAINS = setOf(
            "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com",
            "redtube.com", "youporn.com", "tube8.com", "spankbang.com",
            "eporner.com", "tnaflix.com", "beeg.com", "drtuber.com",
            "hqporner.com", "4tube.com", "porntrex.com", "brazzers.com",
            "bangbros.com", "naughtyamerica.com", "realitykings.com",
            "mofos.com", "kink.com", "onlyfans.com", "fansly.com",
            "manyvids.com", "chaturbate.com", "stripchat.com", "bongacams.com",
            "livejasmin.com", "camsoda.com", "cam4.com", "myfreecams.com",
            "flirt4free.com", "rule34.xxx", "gelbooru.com", "e-hentai.org",
            "nhentai.net", "hentaihaven.xxx"
        )
        val ADULT_KEYWORDS_URL = setOf(
            "/porn", "/sex", "/xxx", "/nude", "/naked",
            "/hentai", "/nsfw", "/adult", "porn", "xvideo", "xnxx"
        )

        private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun saveWhitelist(ctx: Context, list: List<String>) {
            p(ctx).edit().putString(KEY_WHITELIST,
                list.filter { it.isNotBlank() }.joinToString(DELIMITER)).apply()
            instance?.let {
                it.whitelistCacheTime = 0L
                it.refreshWhitelistCache()  // ← সাথে সাথে refresh
            }
        }
        fun loadWhitelist(ctx: Context): MutableList<String> {
            val raw = p(ctx).getString(KEY_WHITELIST, "") ?: ""
            return if (raw.isBlank()) mutableListOf()
            else raw.split(DELIMITER).filter { it.isNotBlank() }.toMutableList()
        }
        fun loadWhitelistSet(ctx: Context): Set<String> {
            val raw = p(ctx).getString(KEY_WHITELIST, "") ?: ""
            return if (raw.isBlank()) emptySet()
            else raw.split(DELIMITER).filter { it.isNotBlank() }.toHashSet()
        }
        fun isWhitelisted(ctx: Context, pkg: String) = loadWhitelistSet(ctx).contains(pkg)

        fun saveKeywords(ctx: Context, list: List<String>) =
            p(ctx).edit().putString(KEY_WORDS,
                list.filter { it.isNotBlank() }.joinToString(DELIMITER)).apply()
        fun loadKeywords(ctx: Context): MutableList<String> {
            val raw = p(ctx).getString(KEY_WORDS, "") ?: ""
            return if (raw.isBlank()) mutableListOf()
            else raw.split(DELIMITER).filter { it.isNotBlank() }.toMutableList()
        }

        fun setEnabled(ctx: Context, v: Boolean)      = p(ctx).edit().putBoolean(KEY_ENABLED, v).apply()
        fun isEnabled(ctx: Context)                   = p(ctx).getBoolean(KEY_ENABLED, true)
        fun setAdultTextDetect(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean(KEY_ADULT_TEXT, v).apply()
        fun isAdultTextDetectEnabled(ctx: Context)    = p(ctx).getBoolean(KEY_ADULT_TEXT, true)
        fun setSoftAdult(ctx: Context, v: Boolean)    = p(ctx).edit().putBoolean(KEY_SOFT_ADULT, v).apply()
        fun isSoftAdultEnabled(ctx: Context)          = p(ctx).getBoolean(KEY_SOFT_ADULT, true)
        fun setVideoMeta(ctx: Context, v: Boolean)    = p(ctx).edit().putBoolean(KEY_VIDEO_META, v).apply()
        fun isVideoMetaEnabled(ctx: Context)          = p(ctx).getBoolean(KEY_VIDEO_META, true)
    }
}

// ── Extension functions ────────────────────────────────────────────────────────

fun KeywordService.onNsfwModelChanged() {
    val svc = this
    if (NsfwModelManager.isEnabled(svc)) {
        if (!NsfwModelManager.isModelLoaded()) {
            val bgPool = java.util.concurrent.Executors.newSingleThreadExecutor()
            bgPool.execute {
                if (NsfwModelManager.loadModel(svc)) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                            NsfwScanService.start(svc)
                    }
                }
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                NsfwScanService.start(svc)
        }
    } else {
        NsfwScanService.stop()
    }
}

fun KeywordService.requestTestScan() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        android.widget.Toast.makeText(this,
            "Test scan needs Android 11+", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val svc = this
    // ★ takeScreenshot MUST be called on main thread ★
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        try {
            svc.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                svc.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(shot: AccessibilityService.ScreenshotResult) {
                        // Process in background
                        Thread {
                            var bitmap: android.graphics.Bitmap? = null
                            try {
                                val hw = shot.hardwareBuffer ?: return@Thread
                                bitmap = android.graphics.Bitmap
                                    .wrapHardwareBuffer(hw, null)
                                    ?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                                hw.close()
                                bitmap ?: return@Thread

                                val (isAdult, conf) = NsfwModelManager.scan(svc, bitmap)
                                val detailed        = NsfwModelManager.scanDetailed(svc, bitmap)

                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    val msg = buildString {
                                        append("🔬 Test Result\n")
                                        append("Adult: ${if (isAdult) "✅ YES" else "❌ NO"}\n")
                                        append("Score: ${"%.1f".format(conf * 100)}%\n\n")
                                        detailed?.entries?.sortedByDescending { it.value }
                                            ?.forEach { (lbl, score) ->
                                                append("$lbl: ${"%.1f".format(score * 100)}%\n")
                                            }
                                    }
                                    android.widget.Toast.makeText(
                                        svc, msg, android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            } catch (e: Exception) {
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    android.widget.Toast.makeText(
                                        svc, "Test failed: ${e.message}",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } finally {
                                try { bitmap?.recycle() } catch (_: Exception) {}
                            }
                        }.start()
                    }
                    override fun onFailure(code: Int) {
                        android.widget.Toast.makeText(
                            svc, "Screenshot failed (code $code)",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                svc, "takeScreenshot error: ${e.message}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
}
