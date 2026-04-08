package com.kb.blocker

import android.content.Context
import android.content.SharedPreferences

/**
 * App Install Blocker
 * নতুন app install হওয়ার আগে PIN check করে।
 * PackageManager broadcast receiver দিয়ে detect করা হয়।
 */
object AppInstallBlocker {

    private const val PREFS  = "install_prefs"
    private const val KEY_ON = "install_block_enabled"

    private fun p(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context)          = p(ctx).getBoolean(KEY_ON, false)
    fun setEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean(KEY_ON, v).apply()
}
