package com.kb.blocker

import android.content.Context

/**
 * Block statistics — কতবার কোন app block হলো SharedPrefs-এ রাখে।
 */
object BlockStatsManager {

    private const val PREFS_STATS  = "kb_stats"
    private const val KEY_TOTAL    = "total_blocks"
    private const val PREFIX_PKG   = "pkg_"

    private fun p(ctx: Context) =
        ctx.getSharedPreferences(PREFS_STATS, Context.MODE_PRIVATE)

    fun recordBlock(ctx: Context, pkg: String) {
        val pr = p(ctx)
        pr.edit()
            .putInt(KEY_TOTAL, pr.getInt(KEY_TOTAL, 0) + 1)
            .putInt(PREFIX_PKG + pkg, pr.getInt(PREFIX_PKG + pkg, 0) + 1)
            .apply()
    }

    fun getTotalBlocks(ctx: Context): Int =
        p(ctx).getInt(KEY_TOTAL, 0)

    fun getTopBlocked(ctx: Context, limit: Int = 5): List<Pair<String, Int>> =
        p(ctx).all
            .filter { it.key.startsWith(PREFIX_PKG) }
            .map { it.key.removePrefix(PREFIX_PKG) to (it.value as? Int ?: 0) }
            .sortedByDescending { it.second }
            .take(limit)

    fun clearStats(ctx: Context) =
        p(ctx).edit().clear().apply()
}
