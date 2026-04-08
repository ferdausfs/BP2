package com.kb.blocker

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

object ThemeManager {

    private const val PREFS     = "theme_prefs"
    private const val KEY_THEME = "theme_mode"

    const val THEME_DARK   = 0
    const val THEME_LIGHT  = 1
    const val THEME_SYSTEM = 2

    private fun p(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getTheme(ctx: Context): Int = p(ctx).getInt(KEY_THEME, THEME_DARK)

    fun setTheme(ctx: Context, mode: Int) {
        p(ctx).edit().putInt(KEY_THEME, mode).apply()
        apply(mode)
    }

    fun apply(mode: Int) {
        val nightMode = when (mode) {
            THEME_LIGHT  -> AppCompatDelegate.MODE_NIGHT_NO
            THEME_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else         -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    fun applyFromPrefs(ctx: Context) = apply(getTheme(ctx))
}
