package com.guardian.shield.service.detection

import com.guardian.shield.domain.model.BlockReason
import com.guardian.shield.domain.model.DetectionResult
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RulesEngine — the single authority for ALL block decisions.
 *
 * Priority order (MUST NOT be changed):
 *   1. OWN package → always allow
 *   2. SYSTEM UI → always allow
 *   3. WHITELIST → always allow (overrides everything)
 *   4. SCHEDULE active → block if app in schedule
 *   5. BLOCKED app list → block
 *   6. KEYWORD match → block
 *   7. AI detection → block
 *   8. Default → allow
 */
@Singleton
class RulesEngine @Inject constructor() {

    companion object {
        const val OUR_PACKAGE = "com.guardian.shield"
        private const val TAG = "Guardian_Rules"

        // System packages that should never be interfered with
        private val SYSTEM_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.sec.android.inputmethod",
            "com.touchtype.swiftkey",
            "com.android.settings.intelligence",
            "com.miui.msa.global"
        )

        private val SYSTEM_PREFIXES = arrayOf(
            "com.android.systemui.",
            "com.oneplus.",
            "com.nothing.launcher",
            "com.samsung.android.app.taskbar"
        )

        // Suspicious URL/text patterns
        private val SUSPICIOUS_PATTERNS = listOf(
            PatternRule("nsfw_url", "(?i)(porn|xxx|sex|nude|nsfw)\\.(com|net|org|xyz|tv)".toRegex(), "nsfw"),
            PatternRule("adult_keywords", "(?i)\\b(porn|xxx|nudity|hentai)\\b".toRegex(), "adult"),
            PatternRule("gambling", "(?i)\\b(casino|betting|gambling|poker)\\b".toRegex(), "gambling")
        )
    }

    // In-memory caches — refreshed via refreshCaches()
    @Volatile private var blockedPackages: Set<String>    = emptySet()
    @Volatile private var whitelistedPackages: Set<String> = emptySet()
    @Volatile private var activeKeywords: List<String>    = emptyList()
    @Volatile private var isKeywordDetectionOn: Boolean   = true
    @Volatile private var isProtectionEnabled: Boolean    = true
    @Volatile private var isStrictMode: Boolean           = false

    // Schedule-based blocking
    @Volatile private var scheduleBlockedApps: Set<String> = emptySet()
    @Volatile private var scheduleBlockAll: Boolean        = false
    @Volatile private var activeScheduleName: String       = ""

    // ── Cache refresh (called by service on startup + DB change) ──────

    fun refreshBlockedApps(packages: Set<String>) {
        blockedPackages = packages
        Timber.d("$TAG blocked list refreshed: ${packages.size} apps")
    }

    fun refreshWhitelistedApps(packages: Set<String>) {
        whitelistedPackages = packages
        Timber.d("$TAG whitelist refreshed: ${packages.size} apps")
    }

    fun refreshKeywords(keywords: List<String>) {
        activeKeywords = keywords.map { it.lowercase().trim() }
        Timber.d("$TAG keywords refreshed: ${keywords.size} keywords")
    }

    fun setKeywordDetectionEnabled(enabled: Boolean) { isKeywordDetectionOn = enabled }
    fun setProtectionEnabled(enabled: Boolean)        { isProtectionEnabled  = enabled }
    fun setStrictMode(enabled: Boolean)               { isStrictMode         = enabled }

    /**
     * Update active schedule info from ScheduleManager
     */
    fun updateActiveSchedule(
        scheduleName: String,
        blockedApps: Set<String>,
        blockAll: Boolean
    ) {
        activeScheduleName = scheduleName
        scheduleBlockedApps = blockedApps
        scheduleBlockAll = blockAll
        Timber.d("$TAG schedule updated: '$scheduleName' blockAll=$blockAll apps=${blockedApps.size}")
    }

    fun clearActiveSchedule() {
        activeScheduleName = ""
        scheduleBlockedApps = emptySet()
        scheduleBlockAll = false
    }

    // ── Main evaluation ───────────────────────────────────────────────

    /**
     * Evaluate foreground app package.
     * Returns DetectionResult.Allow, .Whitelist, or .Block
     */
    fun evaluateApp(packageName: String): DetectionResult {
        if (!isProtectionEnabled) return DetectionResult.Allow

        // Rule 1: Own package — never block ourselves
        if (packageName == OUR_PACKAGE) return DetectionResult.Allow

        // Rule 2: System UI — never block
        if (isSystemPackage(packageName)) return DetectionResult.Allow

        // Rule 3: WHITELIST — highest priority, overrides everything
        if (packageName in whitelistedPackages) {
            Timber.d("$TAG ALLOW (whitelist): $packageName")
            return DetectionResult.Whitelist
        }

        // Rule 4: Active schedule blocking
        if (activeScheduleName.isNotEmpty()) {
            if (scheduleBlockAll || packageName in scheduleBlockedApps) {
                Timber.d("$TAG BLOCK (schedule '$activeScheduleName'): $packageName")
                return DetectionResult.Block(
                    BlockReason.SCHEDULE_BLOCKED,
                    "Schedule: $activeScheduleName"
                )
            }
        }

        // Rule 5: Blocked app list
        if (packageName in blockedPackages) {
            Timber.d("$TAG BLOCK (app list): $packageName")
            return DetectionResult.Block(BlockReason.APP_BLOCKED, packageName)
        }

        return DetectionResult.Allow
    }

    /**
     * Evaluate screen text for keyword matches.
     */
    fun evaluateText(packageName: String, text: String): DetectionResult {
        if (!isProtectionEnabled) return DetectionResult.Allow
        if (!isKeywordDetectionOn) return DetectionResult.Allow
        if (packageName == OUR_PACKAGE) return DetectionResult.Allow
        if (packageName in whitelistedPackages) return DetectionResult.Whitelist

        val lower = text.lowercase()
        val hit = activeKeywords.firstOrNull { kw ->
            lower.contains(kw)
        }

        return if (hit != null) {
            Timber.d("$TAG BLOCK (keyword '$hit'): $packageName")
            DetectionResult.Block(BlockReason.KEYWORD_DETECTED, hit)
        } else {
            DetectionResult.Allow
        }
    }

    /**
     * Evaluate AI model result.
     */
    fun evaluateAiResult(packageName: String, unsafeScore: Float, threshold: Float): DetectionResult {
        if (!isProtectionEnabled) return DetectionResult.Allow
        if (packageName == OUR_PACKAGE) return DetectionResult.Allow
        if (packageName in whitelistedPackages) return DetectionResult.Whitelist

        return if (unsafeScore >= threshold) {
            Timber.d("$TAG BLOCK (AI score=${unsafeScore}): $packageName")
            DetectionResult.Block(BlockReason.AI_DETECTED, "${(unsafeScore * 100).toInt()}% unsafe")
        } else {
            DetectionResult.Allow
        }
    }

    /**
     * NEW: Check keywords - used by AiDetector
     */
    fun checkKeywords(text: String): KeywordMatch? {
        if (text.isBlank()) return null
        val lower = text.lowercase()
        val hit = activeKeywords.firstOrNull { kw -> lower.contains(kw) }
        return hit?.let { KeywordMatch(it, "keyword") }
    }

    /**
     * NEW: Check suspicious patterns - used by AiDetector
     */
    fun checkPatterns(text: String): PatternRule? {
        if (text.isBlank()) return null
        return SUSPICIOUS_PATTERNS.firstOrNull { rule ->
            rule.regex.containsMatchIn(text)
        }
    }

    /**
     * Quick whitelist check
     */
    fun isWhitelisted(packageName: String): Boolean =
        packageName == OUR_PACKAGE || packageName in whitelistedPackages

    fun isSystemUi(pkg: String): Boolean = isSystemPackage(pkg)

    fun isProtectionActive(): Boolean = isProtectionEnabled

    fun hasActiveSchedule(): Boolean = activeScheduleName.isNotEmpty()

    fun getActiveScheduleName(): String = activeScheduleName

    private fun isSystemPackage(pkg: String): Boolean {
        if (pkg in SYSTEM_PACKAGES) return true
        SYSTEM_PREFIXES.forEach { if (pkg.startsWith(it)) return true }
        return false
    }

    // ── Data classes for matches ─────────────────────────────────────

    data class KeywordMatch(
        val keyword: String,
        val category: String
    )

    data class PatternRule(
        val name: String,
        val regex: Regex,
        val category: String
    )
}