package com.kb.blocker

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import java.util.concurrent.Executors

class KeywordService : AccessibilityService() {

    private lateinit var prefs: SharedPreferences
    private val handler  = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var lastBlockTime = 0L

    // Whitelist in-memory cache
    private var whitelistCache: Set<String> = emptySet()
    internal var whitelistCacheTime = 0L

    // ── Periodic video metadata scan ──────────────────────────────────────────
    // Video play-এর সময় accessibility event fire না হলেও এটা প্রতি 3 সেকেন্ডে
    // foreground video/browser app-এর metadata check করে।
    private val videoScanRunnable = object : Runnable {
        override fun run() {
            try {
                val pkg = currentForegroundPkg
                if (pkg.isNotBlank() && isEnabled(this@KeywordService) &&
                    !isPkgWhitelisted(pkg) &&
                    (isBrowser(pkg) || isVideoApp(pkg)) &&
                    isVideoMetaEnabled(this@KeywordService)
                ) {
                    val root = rootInActiveWindow
                    if (root != null) {
                        val meta = extractAllMetadata(root)
                        if (meta.isNotBlank()) {
                            val capturedPkg = pkg
                            val capturedMeta = meta
                            executor.execute {
                                if (VideoMetaDetector.isAdultMeta(capturedMeta) ||
                                    (isSoftAdultEnabled(this@KeywordService) &&
                                     SoftAdultDetector.isSoftAdultContent(capturedMeta))
                                ) {
                                    handler.post {
                                        val now = System.currentTimeMillis()
                                        if (now - lastBlockTime >= 1_500L) {
                                            lastBlockTime = now
                                            blockPkg(capturedPkg)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            handler.postDelayed(this, VIDEO_SCAN_INTERVAL_MS)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        prefs     = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        instance  = this
        isRunning = true
        refreshWhitelistCache()
        handler.postDelayed(videoScanRunnable, VIDEO_SCAN_INTERVAL_MS)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        executor.shutdown()
        instance  = null
        isRunning = false
    }

    // ── Whitelist cache ───────────────────────────────────────────────────────

    private fun refreshWhitelistCache() {
        val now = System.currentTimeMillis()
        if (now - whitelistCacheTime > 5_000L) {
            whitelistCache     = loadWhitelistSet(this)
            whitelistCacheTime = now
        }
    }

    private fun isPkgWhitelisted(pkg: String): Boolean {
        refreshWhitelistCache()
        return whitelistCache.contains(pkg)
    }

    // ── Main accessibility event ──────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (!isEnabled(this)) return

        // Foreground tracking
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (!isSystemPkg(pkg)) currentForegroundPkg = pkg
        }

        if (isPkgWhitelisted(pkg)) return

        val now = System.currentTimeMillis()
        if (now - lastBlockTime < 1_500L) return

        // ── Fast path 1: known adult app ──────────────────────────────────────
        if (isKnownAdultApp(pkg)) {
            lastBlockTime = now; blockPkg(pkg); return
        }

        val isBrowserOrVideo = isBrowser(pkg) || isVideoApp(pkg)
        val root = rootInActiveWindow

        // ── Fast path 2: URL check ────────────────────────────────────────────
        if (isBrowser(pkg)) {
            val url = extractUrl(root)
            if (!url.isNullOrBlank()) {
                if (isHardAdultUrl(url)) {
                    lastBlockTime = now; blockPkg(pkg); return
                }
                if (isSoftAdultEnabled(this) && SoftAdultDetector.isSoftAdultUrl(url)) {
                    lastBlockTime = now; blockPkg(pkg); return
                }
            }
        }

        // ── Collect text on main thread, match on background ──────────────────
        val screenText = buildString { collectText(root, this) }
        val metaText   = if (isBrowserOrVideo && isVideoMetaEnabled(this))
            extractAllMetadata(root) else ""

        if (screenText.isBlank() && metaText.isBlank()) return

        val capturedPkg  = pkg
        val capturedBV   = isBrowserOrVideo

        executor.execute {
            val block = runDetection(capturedPkg, capturedBV, screenText, metaText)
            if (block) {
                handler.post {
                    val t = System.currentTimeMillis()
                    if (t - lastBlockTime >= 1_500L) {
                        lastBlockTime = t
                        blockPkg(capturedPkg)
                    }
                }
            }
        }
    }

    /** Keyword + content detection — background thread */
    private fun runDetection(
        pkg: String,
        isBrowserOrVideo: Boolean,
        screenText: String,
        metaText: String
    ): Boolean {
        // Video metadata
        if (metaText.isNotBlank()) {
            if (isVideoMetaEnabled(this) && VideoMetaDetector.isAdultMeta(metaText)) return true
            if (isSoftAdultEnabled(this) && isBrowserOrVideo &&
                SoftAdultDetector.isSoftAdultContent(metaText)) return true
        }

        if (screenText.isBlank()) return false

        // User keywords — সব app
        val userKw = getUserKeywords()
        if (userKw.isNotEmpty()) {
            val lower = screenText.lowercase(Locale.getDefault())
            if (userKw.any { it.isNotBlank() && lower.contains(it.lowercase(Locale.getDefault())) })
                return true
        }

        // Hard adult — সব app
        if (isAdultTextDetectEnabled(this) && AdultContentDetector.isAdultContent(screenText))
            return true

        // Soft adult — browser + video only
        if (isSoftAdultEnabled(this) && isBrowserOrVideo &&
            SoftAdultDetector.isSoftAdultContent(screenText))
            return true

        return false
    }

    // ── Block ─────────────────────────────────────────────────────────────────

    fun blockPkg(pkg: String) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        BlockStatsManager.recordBlock(this, pkg)
        killPkg(pkg)
        handler.postDelayed({ killPkg(pkg) }, 300)
    }

    private fun killPkg(pkg: String) {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        try {
            am.appTasks.forEach { task ->
                if (task.taskInfo.baseActivity?.packageName == pkg)
                    task.finishAndRemoveTask()
            }
            am.killBackgroundProcesses(pkg)
        } catch (_: Exception) {}
    }

    // ── Video / Metadata extraction ───────────────────────────────────────────
    // Video play চলাকালীন screen-এ text দেখা না গেলেও accessibility tree-তে
    // title, description, tag থাকে। এখানে INVISIBLE node-সহ সব traverse করা হয়।

    /**
     * সব ধরনের metadata একসাথে collect করে — visible বা invisible যাই হোক।
     * Event-based ও periodic scan দুটোতেই call হয়।
     */
    private fun extractAllMetadata(root: AccessibilityNodeInfo?): String {
        root ?: return ""
        val sb = StringBuilder()
        // Pass 1: resource ID দিয়ে নির্দিষ্ট node খোঁজো
        collectMetaByResourceId(root, sb)
        // Pass 2: হ্যাশট্যাগ / মেনশন
        collectHashtagsAndMentions(root, sb)
        // Pass 3: content description (invisible node-সহ)
        collectContentDescriptions(root, sb)
        return sb.toString().trim()
    }

    private val META_IDS = setOf(
        "title", "video_title", "media_title", "item_title",
        "content_title", "player_title", "description",
        "video_description", "channel_name", "author_name",
        "tag", "hashtag", "caption", "subtitle",
        // YouTube-specific
        "engagement_panel_title", "collapsed_title",
        "watch_next_card_headline", "metadata_container",
        // TikTok-specific
        "desc", "author", "challenge_tag",
        // Facebook / Instagram
        "story_header", "inline_activities",
        // Generic video players
        "media_info", "info_container", "overlay_title"
    )

    private fun collectMetaByResourceId(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        node ?: return
        val resId = node.viewIdResourceName?.lowercase(Locale.getDefault()) ?: ""
        val text  = node.text?.toString() ?: ""
        val desc  = node.contentDescription?.toString() ?: ""

        if (META_IDS.any { resId.contains(it) }) {
            if (text.isNotBlank()) sb.append(text).append(' ')
            if (desc.isNotBlank()) sb.append(desc).append(' ')
        }
        for (i in 0 until node.childCount) collectMetaByResourceId(node.getChild(i), sb)
    }

    private fun collectHashtagsAndMentions(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        node ?: return
        val text = node.text?.toString() ?: ""
        if (text.contains('#') || text.startsWith('@')) sb.append(text).append(' ')
        for (i in 0 until node.childCount) collectHashtagsAndMentions(node.getChild(i), sb)
    }

    /**
     * সব node-এর contentDescription collect করো — visible না হলেও।
     * Video player-এ title প্রায়ই contentDescription হিসেবে থাকে।
     */
    private fun collectContentDescriptions(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        node ?: return
        val desc = node.contentDescription?.toString() ?: ""
        if (desc.length in 4..300) sb.append(desc).append(' ') // too short = icon desc, too long = skip
        for (i in 0 until node.childCount) collectContentDescriptions(node.getChild(i), sb)
    }

    // ── URL extraction ────────────────────────────────────────────────────────

    private fun extractUrl(node: AccessibilityNodeInfo?): String? {
        node ?: return null
        val resId = node.viewIdResourceName?.lowercase(Locale.getDefault()) ?: ""
        if (node.className?.contains("EditText") == true ||
            resId.contains("url") || resId.contains("address") ||
            resId.contains("omnibox") || resId.contains("search") ||
            resId.contains("location")
        ) {
            val text = node.text?.toString() ?: ""
            if (text.contains('.') && (
                text.startsWith("http") || text.contains("www.") ||
                text.matches(Regex(".*\\.[a-z]{2,}.*"))
            )) return text
        }
        for (i in 0 until node.childCount) {
            extractUrl(node.getChild(i))?.let { return it }
        }
        return null
    }

    private fun isHardAdultUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.getDefault())
        return ADULT_DOMAINS.any { lower.contains(it) } ||
               ADULT_KEYWORDS_URL.any { lower.contains(it) }
    }

    // ── Screen text collection ────────────────────────────────────────────────

    private fun collectText(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        node ?: return
        node.text?.let { sb.append(it).append(' ') }
        node.contentDescription?.let { sb.append(it).append(' ') }
        for (i in 0 until node.childCount) collectText(node.getChild(i), sb)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun getUserKeywords(): List<String> =
        (prefs.getString(KEY_WORDS, "") ?: "")
            .split(DELIMITER).filter { it.isNotBlank() }

    private fun isSystemPkg(pkg: String) =
        pkg == "android" || pkg.startsWith("com.android.systemui") ||
        pkg.contains("inputmethod") || pkg.contains("keyboard")

    private fun isBrowser(pkg: String)       = BROWSER_PACKAGES.any  { pkg.contains(it) }
    private fun isVideoApp(pkg: String)      = VIDEO_PACKAGES.any    { pkg.contains(it) }
    private fun isKnownAdultApp(pkg: String) = KNOWN_ADULT_APPS.any  { pkg.contains(it) }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        const val PREFS          = "kb_prefs"
        const val KEY_WORDS      = "keywords"
        const val KEY_ENABLED    = "enabled"
        const val KEY_WHITELIST  = "whitelist"
        const val KEY_ADULT_TEXT = "adult_text"
        const val KEY_SOFT_ADULT = "soft_adult"
        const val KEY_VIDEO_META = "video_meta"
        const val DELIMITER      = "|||"

        private const val VIDEO_SCAN_INTERVAL_MS = 3_000L  // 3 সেকেন্ডে একবার video check

        var instance             : KeywordService? = null
        var isRunning              = false
        var currentForegroundPkg   = ""

        // ── Package lists ─────────────────────────────────────────────────────

        val BROWSER_PACKAGES = setOf(
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
            "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix",
            "org.mozilla.focus", "org.mozilla.klar",
            "com.microsoft.emmx",
            "com.opera.browser", "com.opera.mini.native", "com.opera.gx",
            "com.brave.browser",
            "com.kiwibrowser.browser",
            "com.sec.android.app.sbrowser",
            "com.UCMobile.intl", "com.uc.browser.en",
            "com.mi.globalbrowser",
            "com.duckduckgo.mobile.android",
            "com.vivaldi.browser",
            "com.ecosia.android",
            "com.yandex.browser",
            "com.tor.browser", "org.torproject.torbrowser",
            "mark.via", "mark.via.gp",
            "com.puffin.browser",
            "acr.browser.lightning",
            "com.amazon.cloud9"
        )

        val VIDEO_PACKAGES = setOf(
            "com.google.android.youtube",
            "com.google.android.youtube.tv",
            "com.instagram.android",
            "com.facebook.katana", "com.facebook.lite",
            "com.twitter.android", "com.x.android",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.ss.android.ugc.aweme",
            "com.moj.app",
            "in.sharechat.app",
            "com.snapchat.android",
            "com.likee.video",
            "com.josh.fun",
            "com.mx.player", "com.mx.player.lite",
            "com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.j",
            "org.videolan.vlc",
            "com.reddit.frontpage",
            "com.pinterest",
            "tv.twitch.android.app",
            "com.dailymotion.dailymotion",
            "com.vimeo.android.videoapp",
            "com.whatsapp",
            "tv.periscope.android",
            "com.bigo.live",
            "com.nimo.live"
        )

        val KNOWN_ADULT_APPS = setOf(
            "pornhub", "xvideos", "xnxx", "xhamster",
            "redtube", "youporn", "tube8", "spankbang",
            "brazzers", "onlyfans", "faphouse", "fansly",
            "chaturbate", "stripchat", "bongacams",
            "badoo", "adultfriendfinder",
            "sexcam", "livejasmin", "camsoda",
            "hentai", "nhentai", "e-hentai",
            "beeg", "eporner", "drtuber",
            "myfreecams", "cam4", "flirt4free"
        )

        val ADULT_DOMAINS = setOf(
            "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com",
            "redtube.com", "youporn.com", "tube8.com", "spankbang.com",
            "eporner.com", "tnaflix.com", "beeg.com", "drtuber.com",
            "hqporner.com", "4tube.com", "porntrex.com", "porn.com",
            "brazzers.com", "bangbros.com", "naughtyamerica.com",
            "realitykings.com", "mofos.com", "kink.com",
            "onlyfans.com", "fansly.com", "manyvids.com",
            "chaturbate.com", "stripchat.com", "bongacams.com",
            "livejasmin.com", "camsoda.com", "cam4.com",
            "myfreecams.com", "flirt4free.com", "streamate.com",
            "rule34.xxx", "gelbooru.com", "e-hentai.org",
            "nhentai.net", "hentaihaven.xxx", "hentai2read.com"
        )

        val ADULT_KEYWORDS_URL = setOf(
            "/porn", "/sex", "/xxx", "/nude", "/naked",
            "/hentai", "/nsfw", "/adult", "porn", "xvideo", "xnxx"
        )

        // ── SharedPrefs helpers ───────────────────────────────────────────────

        private fun p(ctx: Context) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun saveWhitelist(ctx: Context, list: List<String>) {
            p(ctx).edit()
                .putString(KEY_WHITELIST, list.filter { it.isNotBlank() }.joinToString(DELIMITER))
                .apply()
            instance?.whitelistCacheTime = 0L
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

        fun saveKeywords(ctx: Context, list: List<String>) =
            p(ctx).edit()
                .putString(KEY_WORDS, list.filter { it.isNotBlank() }.joinToString(DELIMITER))
                .apply()

        fun loadKeywords(ctx: Context): MutableList<String> {
            val raw = p(ctx).getString(KEY_WORDS, "") ?: ""
            return if (raw.isBlank()) mutableListOf()
            else raw.split(DELIMITER).filter { it.isNotBlank() }.toMutableList()
        }

        fun setEnabled(ctx: Context, v: Boolean)  = p(ctx).edit().putBoolean(KEY_ENABLED, v).apply()
        fun isEnabled(ctx: Context)               = p(ctx).getBoolean(KEY_ENABLED, true)

        fun setAdultTextDetect(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean(KEY_ADULT_TEXT, v).apply()
        fun isAdultTextDetectEnabled(ctx: Context)        = p(ctx).getBoolean(KEY_ADULT_TEXT, true)

        fun setSoftAdult(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean(KEY_SOFT_ADULT, v).apply()
        fun isSoftAdultEnabled(ctx: Context)        = p(ctx).getBoolean(KEY_SOFT_ADULT, true)

        fun setVideoMeta(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean(KEY_VIDEO_META, v).apply()
        fun isVideoMetaEnabled(ctx: Context)        = p(ctx).getBoolean(KEY_VIDEO_META, true)
    }
}
