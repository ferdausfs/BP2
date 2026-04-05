package com.kb.blocker

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import java.util.concurrent.Executors

class KeywordService : AccessibilityService() {

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var lastBlockTime = 0L

    // ── Whitelist in-memory cache ──────────────────────────────────────────
    private var whitelistCache: Set<String> = emptySet()
    internal var whitelistCacheTime = 0L

    // ── Foreground tracking ────────────────────────────────────────────────
    // pkg → last seen timestamp, যাতে false positive কমে
    private val recentPkgs = LinkedHashMap<String, Long>(16, 0.75f, true)

    override fun onServiceConnected() {
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        instance = this
        isRunning = true
        refreshWhitelistCache()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        executor.shutdown()
        instance = null
        isRunning = false
    }

    // ── Cache ──────────────────────────────────────────────────────────────

    private fun refreshWhitelistCache() {
        val now = System.currentTimeMillis()
        if (now - whitelistCacheTime > 3_000L) {
            whitelistCache = loadWhitelistSet(this)
            whitelistCacheTime = now
        }
    }

    private fun isPkgWhitelisted(pkg: String): Boolean {
        refreshWhitelistCache()
        return whitelistCache.contains(pkg)
    }

    // ── Accessibility Events ───────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (!isEnabled(this)) return

        // Foreground tracking — window state changed এ update
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (!isSystemPkg(pkg)) {
                currentForegroundPkg = pkg
                recentPkgs[pkg] = System.currentTimeMillis()
            }
        }

        // ★ WHITELIST — সবার আগে, দুইভাবে চেক ★
        // event pkg এবং currentForegroundPkg দুটোই check
        if (isPkgWhitelisted(pkg)) return
        if (isPkgWhitelisted(currentForegroundPkg)) return

        val now = System.currentTimeMillis()
        if (now - lastBlockTime < 1500L) return

        // ── 1. Known adult app ─────────────────────────────────────────────
        if (isKnownAdultApp(pkg)) {
            lastBlockTime = now; closeAndKillPkg(pkg); return
        }

        val isBrowserOrVideo = isBrowser(pkg) || isVideoApp(pkg)
        val root = rootInActiveWindow

        // ── 2. Browser URL check ───────────────────────────────────────────
        if (isBrowser(pkg)) {
            val url = extractUrl(root)
            if (!url.isNullOrBlank()) {
                if (isHardAdultUrl(url)) {
                    lastBlockTime = now; closeAndKillPkg(pkg); return
                }
                if (isSoftAdultEnabled(this) && SoftAdultDetector.isSoftAdultUrl(url)) {
                    lastBlockTime = now; closeAndKillPkg(pkg); return
                }
            }
        }

        // ── 3. Video metadata — title, tag, description ────────────────────
        if (isBrowserOrVideo && isVideoMetaEnabled(this)) {
            val meta = extractVideoMetadata(root)
            if (meta.isNotBlank() && VideoMetaDetector.isAdultMeta(meta)) {
                lastBlockTime = now; closeAndKillPkg(pkg); return
            }
        }

        // ── 4. Full screen text collect ────────────────────────────────────
        val screenText = buildString { collectText(root, this) }
        if (screenText.isBlank()) return

        // ── 5. User keywords — সব app ─────────────────────────────────────
        val userKeywords = getUserKeywords()
        if (userKeywords.isNotEmpty()) {
            val lower = screenText.lowercase(Locale.getDefault())
            if (userKeywords.any {
                it.isNotBlank() && lower.contains(it.lowercase(Locale.getDefault()))
            }) {
                lastBlockTime = now; closeAndKillPkg(pkg); return
            }
        }

        // ── 6. Hard adult text ─────────────────────────────────────────────
        if (isAdultTextDetectEnabled(this) && AdultContentDetector.isAdultContent(screenText)) {
            lastBlockTime = now; closeAndKillPkg(pkg); return
        }

        // ── 7. Soft adult — browser + video only ───────────────────────────
        if (isSoftAdultEnabled(this) && isBrowserOrVideo &&
            SoftAdultDetector.isSoftAdultContent(screenText)
        ) {
            lastBlockTime = now; closeAndKillPkg(pkg)
        }
    }

    // ── Video Metadata Deep Extraction ────────────────────────────────────────
    // YouTube, TikTok, Instagram, Facebook এ video title/tag screen node এ থাকে
    // কিন্তু collectText এ miss হয় কারণ এগুলো sometimes hidden/overlaid node

    private fun extractVideoMetadata(root: AccessibilityNodeInfo?): String {
        root ?: return ""
        val sb = StringBuilder()
        val targetIds = setOf(
            "title", "video_title", "media_title", "item_title",
            "content_title", "player_title", "description",
            "video_description", "channel_name", "author_name",
            "tag", "hashtag", "caption", "subtitle", "label",
            // YouTube specific
            "watch_title", "engagement_panel_title",
            // Instagram specific
            "media_caption", "reel_title",
            // TikTok specific
            "video_desc", "author_username",
            // Facebook
            "story_title", "feed_title"
        )
        collectMetaDeep(root, targetIds, sb, depth = 0)
        return sb.toString()
    }

    private fun collectMetaDeep(
        node: AccessibilityNodeInfo?,
        targetIds: Set<String>,
        sb: StringBuilder,
        depth: Int
    ) {
        if (node == null || depth > 12) return

        val resId = node.viewIdResourceName?.lowercase(Locale.getDefault()) ?: ""
        val text  = node.text?.toString() ?: ""
        val desc  = node.contentDescription?.toString() ?: ""

        val isTarget = targetIds.any { resId.contains(it) }

        if (isTarget) {
            if (text.isNotBlank()) sb.append(text).append(" ")
            if (desc.isNotBlank()) sb.append(desc).append(" ")
        }

        // Hashtag সব node থেকে নাও
        if (text.contains("#") || text.startsWith("@")) {
            sb.append(text).append(" ")
        }

        // Long text যা title হতে পারে (30+ char, video description ধরনের)
        if (!isTarget && text.length in 10..300 && depth <= 5) {
            sb.append(text).append(" ")
        }

        for (i in 0 until node.childCount) {
            collectMetaDeep(node.getChild(i), targetIds, sb, depth + 1)
        }
    }

    // ── URL extraction ────────────────────────────────────────────────────────

    private fun extractUrl(node: AccessibilityNodeInfo?): String? {
        node ?: return null
        val resId = node.viewIdResourceName?.lowercase(Locale.getDefault()) ?: ""
        if (node.className?.contains("EditText") == true ||
            resId.contains("url") || resId.contains("address") ||
            resId.contains("omnibox") || resId.contains("search")
        ) {
            val text = node.text?.toString() ?: ""
            if (text.contains(".") && (
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

    // ── Block ─────────────────────────────────────────────────────────────────

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
        handler.postDelayed(::kill, 300)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun collectText(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        node ?: return
        node.text?.let { sb.append(it).append(' ') }
        node.contentDescription?.let { sb.append(it).append(' ') }
        for (i in 0 until node.childCount) collectText(node.getChild(i), sb)
    }

    private fun getUserKeywords(): List<String> =
        (prefs.getString(KEY_WORDS, "") ?: "")
            .split(DELIMITER).filter { it.isNotBlank() }

    private fun isSystemPkg(pkg: String) =
        pkg == "android" || pkg.startsWith("com.android.systemui") ||
        pkg.contains("inputmethod") || pkg.contains("keyboard")

    private fun isBrowser(pkg: String)      = BROWSER_PACKAGES.any { pkg.contains(it) }
    private fun isVideoApp(pkg: String)     = VIDEO_PACKAGES.any  { pkg.contains(it) }
    private fun isKnownAdultApp(pkg: String) = KNOWN_ADULT_APPS.any { pkg.contains(it) }

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

        var instance           : KeywordService? = null
        var isRunning            = false
        var currentForegroundPkg = ""

        val BROWSER_PACKAGES = setOf(
            "com.android.chrome", "org.mozilla.firefox",
            "org.mozilla.firefox_beta", "com.microsoft.emmx",
            "com.opera.browser", "com.opera.mini.native",
            "com.brave.browser", "com.kiwibrowser.browser",
            "com.sec.android.app.sbrowser", "com.UCMobile.intl",
            "com.uc.browser.en", "com.mi.globalbrowser",
            "com.duckduckgo.mobile.android", "com.vivaldi.browser",
            "com.ecosia.android", "com.yandex.browser"
        )

        val VIDEO_PACKAGES = setOf(
            "com.google.android.youtube",
            "com.instagram.android",
            "com.facebook.katana",
            "com.twitter.android",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.reddit.frontpage",
            "com.pinterest",
            "com.snapchat.android",
            "tv.twitch.android.app",
            "com.mx.player",
            "org.videolan.vlc",
            "com.mxtech.videoplayer.ad",
            "com.dailymotion.dailymotion",
            "com.vimeo.android.videoapp"
        )

        val KNOWN_ADULT_APPS = setOf(
            "pornhub", "xvideos", "xnxx", "xhamster",
            "redtube", "youporn", "tube8", "spankbang",
            "brazzers", "onlyfans", "faphouse",
            "chaturbate", "stripchat", "bongacams",
            "badoo", "adultfriendfinder",
            "sexcam", "livejasmin", "camsoda",
            "hentai", "nhentai", "e-hentai"
        )

        val ADULT_DOMAINS = setOf(
            "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com",
            "redtube.com", "youporn.com", "tube8.com", "spankbang.com",
            "eporner.com", "tnaflix.com", "beeg.com", "drtuber.com",
            "hqporner.com", "4tube.com", "porntrex.com",
            "brazzers.com", "bangbros.com", "naughtyamerica.com",
            "realitykings.com", "mofos.com", "kink.com",
            "onlyfans.com", "fansly.com", "manyvids.com",
            "chaturbate.com", "stripchat.com", "bongacams.com",
            "livejasmin.com", "camsoda.com", "cam4.com",
            "myfreecams.com", "flirt4free.com",
            "rule34.xxx", "gelbooru.com", "e-hentai.org",
            "nhentai.net", "hentaihaven.xxx"
        )

        val ADULT_KEYWORDS_URL = setOf(
            "/porn", "/sex", "/xxx", "/nude", "/naked",
            "/hentai", "/nsfw", "/adult", "porn", "xvideo", "xnxx"
        )

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

        fun isWhitelisted(ctx: Context, pkg: String): Boolean =
            loadWhitelistSet(ctx).contains(pkg)

        fun saveKeywords(ctx: Context, list: List<String>) =
            p(ctx).edit().putString(KEY_WORDS,
                list.filter { it.isNotBlank() }.joinToString(DELIMITER)).apply()

        fun loadKeywords(ctx: Context): MutableList<String> {
            val raw = p(ctx).getString(KEY_WORDS, "") ?: ""
            return if (raw.isBlank()) mutableListOf()
            else raw.split(DELIMITER).filter { it.isNotBlank() }.toMutableList()
        }

        fun setEnabled(ctx: Context, v: Boolean) =
            p(ctx).edit().putBoolean(KEY_ENABLED, v).apply()
        fun isEnabled(ctx: Context) =
            p(ctx).getBoolean(KEY_ENABLED, true)

        fun setAdultTextDetect(ctx: Context, v: Boolean) =
            p(ctx).edit().putBoolean(KEY_ADULT_TEXT, v).apply()
        fun isAdultTextDetectEnabled(ctx: Context) =
            p(ctx).getBoolean(KEY_ADULT_TEXT, true)

        fun setSoftAdult(ctx: Context, v: Boolean) =
            p(ctx).edit().putBoolean(KEY_SOFT_ADULT, v).apply()
        fun isSoftAdultEnabled(ctx: Context) =
            p(ctx).getBoolean(KEY_SOFT_ADULT, true)

        fun setVideoMeta(ctx: Context, v: Boolean) =
            p(ctx).edit().putBoolean(KEY_VIDEO_META, v).apply()
        fun isVideoMetaEnabled(ctx: Context) =
            p(ctx).getBoolean(KEY_VIDEO_META, true)
    }
}
