package com.kb.blocker

import android.content.Context
import android.content.SharedPreferences

/**
 * Safe Search Enforcement
 *
 * Browser URL এ Google/YouTube/Bing safe search parameter inject করে।
 * যদি URL এ safe search disabled থাকে → block করে।
 *
 * Google:  &safe=active
 * YouTube: &safe=active  (Restricted Mode)
 * Bing:    &adlt=strict
 * DuckDuckGo: &kp=1
 */
object SafeSearchManager {

    private const val PREFS   = "safesearch_prefs"
    private const val KEY_ON  = "safesearch_enabled"

    private fun p(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context)          = p(ctx).getBoolean(KEY_ON, false)
    fun setEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean(KEY_ON, v).apply()

    /**
     * URL টা safe search bypass করছে কিনা check করো।
     * return true → block করো
     */
    fun isSafeSearchBypassed(url: String): Boolean {
        val lower = url.lowercase()

        // Google search
        if (lower.contains("google.") && lower.contains("/search")) {
            if (lower.contains("safe=off") || lower.contains("safe=images"))
                return true
            // safe parameter নেই — এটাও unsafe
            if (!lower.contains("safe=active") && !lower.contains("safe=strict"))
                return true
        }

        // YouTube
        if (lower.contains("youtube.com") || lower.contains("youtu.be")) {
            if (lower.contains("disable_polymer=1") || lower.contains("has_verified=1"))
                return false // normal YouTube — ok
        }

        // Bing search
        if (lower.contains("bing.com/search")) {
            if (lower.contains("adlt=off") || lower.contains("adlt=moderate"))
                return true
        }

        // DuckDuckGo
        if (lower.contains("duckduckgo.com") && lower.contains("?q=")) {
            if (lower.contains("kp=-1") || lower.contains("kp=-2"))
                return true
        }

        return false
    }

    /**
     * Safe search URL তৈরি করো — redirect করার জন্য
     * (এটা future use এর জন্য — এখন শুধু block করি)
     */
    fun makeSafeUrl(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains("google.") && lower.contains("/search") ->
                if (!url.contains("safe=")) "$url&safe=active" else url
            lower.contains("bing.com/search") ->
                if (!url.contains("adlt=")) "$url&adlt=strict" else url
            lower.contains("duckduckgo.com") ->
                if (!url.contains("kp=")) "$url&kp=1" else url
            else -> url
        }
    }
}
