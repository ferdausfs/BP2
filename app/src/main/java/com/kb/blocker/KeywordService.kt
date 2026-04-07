package com.kb.blocker

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import java.util.Locale
import java.util.concurrent.Executors

class KeywordService : AccessibilityService() {

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    internal val bgPool = Executors.newSingleThreadExecutor()

    private var lastBlockTime   = 0L
    private var lastContentScan = 0L      // OPT1: debounce
    private var cachedUrl       : String? = null  // OPT3: URL cache
    private var cachedUrlPkg    = ""

    internal var whitelistCache     : Set<String> = emptySet()
    internal var whitelistCacheTime = 0L

    private var fgPkg       = ""
    private var fgStartTime = 0L

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        prefs     = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        instance  = this
        isRunning = true
        refreshWhitelistCache()
        showServiceNotification()

        if (NsfwModelManager.isEnabled(this)) {
            bgPool.execute {
                if (NsfwModelManager.loadModel(this)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                        handler.post { NsfwScanService.start(this) }
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        flushUsage()
        stopServiceNotification()
        NsfwScanService.stop()
        NsfwModelManager.unloadModel()
        handler.removeCallbacksAndMessages(null)
        instance  = null
        isRunning = false
    }

    // ── Whitelist ──────────────────────────────────────────────────────────────

    internal fun refreshWhitelistCache() {
        val now = System.currentTimeMillis()
        if (now - whitelistCacheTime > 30_000L) {
            whitelistCache     = loadWhitelistSet(this)
            whitelistCacheTime = now
        }
    }

    // ── Events ────────────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (!isEnabled(this)) return

        // ── Foreground tracking ────────────────────────────────────────────────
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (!isSystemPkg(pkg)) {
                if (fgPkg != pkg) {
                    flushUsage()
                    fgPkg        = pkg
                    fgStartTime  = System.currentTimeMillis()
                    cachedUrl    = null  // OPT3: new window → cache clear
                    cachedUrlPkg = ""
                }
                currentForegroundPkg = pkg
            }
        }

        // OPT1: debounce CONTENT_CHANGED — 150ms
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val now2 = System.currentTimeMillis()
            if (now2 - lastContentScan < 150L) return
            lastContentScan = now2
        }

        // ★ WHITELIST — foreground app check সবার আগে ★
        refreshWhitelistCache()
        if (whitelistCache.contains(currentForegroundPkg)) return
        if (whitelistCache.contains(pkg)) return

        val now = System.currentTimeMillis()
        if (now - lastBlockTime < 1500L) return

        // 1. Known adult app
        if (isKnownAdultApp(pkg)) { block(pkg, "🔞 Known adult app"); return }

        val isBrowserOrVideo = isBrowser(pkg) || isVideoApp(pkg)
        val root = rootInActiveWindow

        // 2. Schedule
        if (isBrowserOrVideo && !ScheduleManager.isCurrentlyAllowed(this)) {
            block(pkg, "⏰ সময়সীমার বাইরে"); return
        }

        // 3. Usage limit
        if (UsageLimitManager.isLimitExceeded(this, pkg)) {
            block(pkg, "⏱️ দৈনিক সীমা শেষ"); return
        }

        // 4. Browser URL
        if (isBrowser(pkg)) {
            val url = getUrl(root)
            if (!url.isNullOrBlank()) {
                if (isHardAdultUrl(url)) { block(pkg, "🔞 Adult site"); return }
                if (isSoftAdultEnabled(this) && SoftAdultDetector.isSoftAdultUrl(url)) {
                    block(pkg, "🌶️ Suggestive search"); return
                }
            }
        }

        // 5. Video metadata
        if (isBrowserOrVideo && isVideoMetaEnabled(this)) {
            val meta = extractVideoMetadata(root)
            if (meta.isNotBlank() && VideoMetaDetector.isAdultMeta(meta)) {
                block(pkg, "🎬 Adult video content"); return
            }
        }

        // 6. Screen text
        val screenText = buildString { collectText(root, this) }
        if (screenText.isBlank()) return

        val userKeywords = getUserKeywords()
        if (userKeywords.isNotEmpty()) {
            val lower = screenText.lowercase(Locale.getDefault())
            val hit   = userKeywords.firstOrNull { it.isNotBlank() && lower.contains(it.lowercase()) }
            if (hit != null) { block(pkg, "🚫 Keyword: \"$hit\""); return }
        }

        if (isAdultTextDetectEnabled(this) && AdultContentDetector.isAdultContent(screenText)) {
            block(pkg, "🔞 Adult text"); return
        }

        if (isSoftAdultEnabled(this) && isBrowserOrVideo &&
            SoftAdultDetector.isSoftAdultContent(screenText)) {
            block(pkg, "🌶️ Suggestive content")
        }
    }

    // ── Block ──────────────────────────────────────────────────────────────────

    internal fun triggerBlock(pkg: String, reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastBlockTime < 1500L) return
        block(pkg, reason)
    }

    private fun block(pkg: String, reason: String) {
        lastBlockTime = System.currentTimeMillis()
        BlockLogManager.log(this, pkg, reason)
        closeAndKillPkg(pkg)
        BlockedActivity.launch(this, getAppLabel(pkg), reason)
    }

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

    // ── Notification ───────────────────────────────────────────────────────────

    internal fun showServiceNotification() {
        try {
            val ch = "blocker_service"
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(ch, "Content Blocker Service",
                        NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Service চালু আছে"
                        setShowBadge(false)
                    }
                )
            }
            val pi = PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

            nm.notify(9001, NotificationCompat.Builder(this, ch)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("🛡️ Content Blocker চালু আছে")
                .setContentText("Screen monitor করছে — tap করে settings খোলো")
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .build())
        } catch (_: Exception) {}
    }

    internal fun stopServiceNotification() {
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(9001) }
        catch (_: Exception) {}
    }

    // ── URL (OPT3 cached) ─────────────────────────────────────────────────────

    private fun getUrl(root: AccessibilityNodeInfo?): String? {
        if (cachedUrlPkg == currentForegroundPkg && cachedUrl != null) return cachedUrl
        val result   = scanUrlFromTree(root)
        cachedUrl    = result
        cachedUrlPkg = currentForegroundPkg
        return result
    }

    private fun scanUrlFromTree(node: AccessibilityNodeInfo?): String? {
        node ?: return null
        val resId = node.viewIdResourceName?.lowercase(Locale.getDefault()) ?: ""
        if (node.className?.contains("EditText") == true ||
            resId.contains("url") || resId.contains("address") ||
            resId.contains("omnibox") || resId.contains("search")) {
            val t = node.text?.toString() ?: ""
            if (t.contains(".") && (t.startsWith("http") || t.contains("www.") ||
                    t.matches(Regex(".*\\.[a-z]{2,}.*")))) return t
        }
        for (i in 0 until node.childCount)
            scanUrlFromTree(node.getChild(i))?.let { return it }
        return null
    }

    private fun isHardAdultUrl(url: String): Boolean {
        val l = url.lowercase(Locale.getDefault())
        return ADULT_DOMAINS.any { l.contains(it) } || ADULT_KEYWORDS_URL.any { l.contains(it) }
    }

    // ── Video metadata ─────────────────────────────────────────────────────────

    private fun extractVideoMetadata(root: AccessibilityNodeInfo?): String {
        root ?: return ""
        val sb  = StringBuilder()
        val ids = setOf("title","video_title","media_title","item_title","content_title",
            "player_title","description","video_description","channel_name","author_name",
            "tag","hashtag","caption","subtitle","watch_title","media_caption","reel_title",
            "video_desc","author_username","story_title")
        collectMeta(root, ids, sb, 0)
        return sb.toString()
    }

    private fun collectMeta(node: AccessibilityNodeInfo?, ids: Set<String>, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 10) return
        val resId = node.viewIdResourceName?.lowercase(Locale.getDefault()) ?: ""
        val text  = node.text?.toString() ?: ""
        val desc  = node.contentDescription?.toString() ?: ""
        if (ids.any { resId.contains(it) }) {
            if (text.isNotBlank()) sb.append(text).append(" ")
            if (desc.isNotBlank()) sb.append(desc).append(" ")
        }
        if (text.contains("#") || text.startsWith("@")) sb.append(text).append(" ")
        if (text.length in 10..200 && depth <= 4) sb.append(text).append(" ")
        for (i in 0 until node.childCount) collectMeta(node.getChild(i), ids, sb, depth + 1)
    }

    // ── Text collect (OPT2: depth+size limit) ─────────────────────────────────

    private fun collectText(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int = 0) {
        if (node == null || depth > 8 || sb.length > 3000) return
        node.text?.let { sb.append(it).append(' ') }
        node.contentDescription?.let { sb.append(it).append(' ') }
        for (i in 0 until node.childCount) collectText(node.getChild(i), sb, depth + 1)
    }

    // ── Usage ──────────────────────────────────────────────────────────────────

    private fun flushUsage() {
        if (fgPkg.isNotBlank() && fgStartTime > 0) {
            val secs = (System.currentTimeMillis() - fgStartTime) / 1000
            if (secs > 0) UsageLimitManager.addUsage(this, fgPkg, secs)
            fgStartTime = 0L
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun getUserKeywords() =
        (prefs.getString(KEY_WORDS, "") ?: "").split(DELIMITER).filter { it.isNotBlank() }

    private fun getAppLabel(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun isSystemPkg(pkg: String) =
        pkg == "android" || pkg.startsWith("com.android.systemui") ||
        pkg.contains("inputmethod") || pkg.contains("keyboard")

    private fun isBrowser(pkg: String)        = BROWSER_PACKAGES.any { pkg.contains(it) }
    private fun isVideoApp(pkg: String)       = VIDEO_PACKAGES.any  { pkg.contains(it) }
    private fun isKnownAdultApp(pkg: String)  = KNOWN_ADULT_APPS.any { pkg.contains(it) }

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

        var instance            : KeywordService? = null
        var isRunning             = false
        var currentForegroundPkg  = ""

        val BROWSER_PACKAGES = setOf(
            "com.android.chrome","org.mozilla.firefox","org.mozilla.firefox_beta",
            "com.microsoft.emmx","com.opera.browser","com.opera.mini.native",
            "com.brave.browser","com.kiwibrowser.browser","com.sec.android.app.sbrowser",
            "com.UCMobile.intl","com.uc.browser.en","com.mi.globalbrowser",
            "com.duckduckgo.mobile.android","com.vivaldi.browser",
            "com.ecosia.android","com.yandex.browser"
        )
        val VIDEO_PACKAGES = setOf(
            "com.google.android.youtube","com.instagram.android","com.facebook.katana",
            "com.twitter.android","com.zhiliaoapp.musically","com.ss.android.ugc.trill",
            "com.reddit.frontpage","com.pinterest","com.snapchat.android",
            "tv.twitch.android.app","com.mx.player","org.videolan.vlc",
            "com.mxtech.videoplayer.ad","com.dailymotion.dailymotion","com.vimeo.android.videoapp"
        )
        val KNOWN_ADULT_APPS = setOf(
            "pornhub","xvideos","xnxx","xhamster","redtube","youporn","tube8","spankbang",
            "brazzers","onlyfans","faphouse","chaturbate","stripchat","bongacams",
            "badoo","adultfriendfinder","sexcam","livejasmin","camsoda","hentai","nhentai","e-hentai"
        )
        val ADULT_DOMAINS = setOf(
            "pornhub.com","xvideos.com","xnxx.com","xhamster.com","redtube.com","youporn.com",
            "tube8.com","spankbang.com","eporner.com","tnaflix.com","beeg.com","drtuber.com",
            "hqporner.com","4tube.com","porntrex.com","brazzers.com","bangbros.com",
            "naughtyamerica.com","realitykings.com","mofos.com","kink.com","onlyfans.com",
            "fansly.com","manyvids.com","chaturbate.com","stripchat.com","bongacams.com",
            "livejasmin.com","camsoda.com","cam4.com","myfreecams.com","flirt4free.com",
            "rule34.xxx","gelbooru.com","e-hentai.org","nhentai.net","hentaihaven.xxx"
        )
        val ADULT_KEYWORDS_URL = setOf(
            "/porn","/sex","/xxx","/nude","/naked","/hentai","/nsfw","/adult","porn","xvideo","xnxx"
        )

        private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun saveWhitelist(ctx: Context, list: List<String>) {
            p(ctx).edit().putString(KEY_WHITELIST,
                list.filter { it.isNotBlank() }.joinToString(DELIMITER)).apply()
            instance?.let { it.whitelistCacheTime = 0L; it.refreshWhitelistCache() }
        }
        fun loadWhitelist(ctx: Context): MutableList<String> {
            val raw = p(ctx).getString(KEY_WHITELIST,"") ?: ""
            return if (raw.isBlank()) mutableListOf()
            else raw.split(DELIMITER).filter { it.isNotBlank() }.toMutableList()
        }
        fun loadWhitelistSet(ctx: Context): Set<String> {
            val raw = p(ctx).getString(KEY_WHITELIST,"") ?: ""
            return if (raw.isBlank()) emptySet()
            else raw.split(DELIMITER).filter { it.isNotBlank() }.toHashSet()
        }
        fun isWhitelisted(ctx: Context, pkg: String) = loadWhitelistSet(ctx).contains(pkg)

        fun saveKeywords(ctx: Context, list: List<String>) =
            p(ctx).edit().putString(KEY_WORDS, list.filter { it.isNotBlank() }.joinToString(DELIMITER)).apply()
        fun loadKeywords(ctx: Context): MutableList<String> {
            val raw = p(ctx).getString(KEY_WORDS,"") ?: ""
            return if (raw.isBlank()) mutableListOf()
            else raw.split(DELIMITER).filter { it.isNotBlank() }.toMutableList()
        }

        fun setEnabled(ctx: Context, v: Boolean)         = p(ctx).edit().putBoolean(KEY_ENABLED, v).apply()
        fun isEnabled(ctx: Context)                      = p(ctx).getBoolean(KEY_ENABLED, true)
        fun setAdultTextDetect(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean(KEY_ADULT_TEXT, v).apply()
        fun isAdultTextDetectEnabled(ctx: Context)       = p(ctx).getBoolean(KEY_ADULT_TEXT, true)
        fun setSoftAdult(ctx: Context, v: Boolean)       = p(ctx).edit().putBoolean(KEY_SOFT_ADULT, v).apply()
        fun isSoftAdultEnabled(ctx: Context)             = p(ctx).getBoolean(KEY_SOFT_ADULT, true)
        fun setVideoMeta(ctx: Context, v: Boolean)       = p(ctx).edit().putBoolean(KEY_VIDEO_META, v).apply()
        fun isVideoMetaEnabled(ctx: Context)             = p(ctx).getBoolean(KEY_VIDEO_META, true)
    }
}

// ── Extensions ────────────────────────────────────────────────────────────────

fun KeywordService.onNsfwModelChanged() {
    if (NsfwModelManager.isEnabled(this)) {
        if (!NsfwModelManager.isModelLoaded()) {
            bgPool.execute {
                if (NsfwModelManager.loadModel(this)) {
                    Handler(Looper.getMainLooper()).post {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                            NsfwScanService.start(this)
                    }
                }
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) NsfwScanService.start(this)
        }
    } else {
        NsfwScanService.stop()
    }
}

fun KeywordService.requestTestScan() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        android.widget.Toast.makeText(this, "Android 11+ দরকার", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val svc = this
    Handler(Looper.getMainLooper()).post {
        try {
            svc.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, svc.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(shot: AccessibilityService.ScreenshotResult) {
                        Thread {
                            var bmp: android.graphics.Bitmap? = null
                            try {
                                val hw = shot.hardwareBuffer ?: return@Thread
                                bmp = android.graphics.Bitmap.wrapHardwareBuffer(hw, null)
                                    ?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                                hw.close()
                                bmp ?: return@Thread
                                val (isAdult, conf) = NsfwModelManager.scan(svc, bmp)
                                // scanDetailed এ recycled bitmap দেওয়া safe — আমরা false দিয়ে scale করেছি
                                val detailed = if (!bmp.isRecycled) NsfwModelManager.scanDetailed(svc, bmp) else null
                                Handler(Looper.getMainLooper()).post {
                                    val thresh = NsfwModelManager.getThreshold(svc)
                                    val msg = buildString {
                                        append("${if (isAdult) "🔴 ADULT" else "🟢 SAFE"}\n")
                                        append("Score: ${"%.1f".format(conf*100)}%  |  Threshold: ${"%.0f".format(thresh*100)}%\n\n")
                                        detailed?.entries?.sortedByDescending { it.value }?.forEach { (lbl, score) ->
                                            val bar = "█".repeat((score * 20).toInt().coerceIn(0, 20))
                                            append("$lbl: ${"%.1f".format(score*100)}% $bar\n")
                                        }
                                    }
                                    androidx.appcompat.app.AlertDialog.Builder(svc)
                                        .setTitle("🔬 AI Test Result")
                                        .setMessage(msg)
                                        .setPositiveButton("OK", null)
                                        .show()
                                }
                            } catch (e: Exception) {
                                Handler(Looper.getMainLooper()).post {
                                    android.widget.Toast.makeText(svc, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } finally { try { bmp?.recycle() } catch (_: Exception) {} }
                        }.start()
                    }
                    override fun onFailure(code: Int) {
                        android.widget.Toast.makeText(svc, "Screenshot failed ($code)", android.widget.Toast.LENGTH_SHORT).show()
                    }
                })
        } catch (e: Exception) {
            android.widget.Toast.makeText(svc, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
