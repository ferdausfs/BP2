package com.kb.blocker

import android.content.Context

object PinManager {

    private const val KEY_PIN = "pin_code"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(KeywordService.PREFS, Context.MODE_PRIVATE)

    fun isPinSet(ctx: Context): Boolean =
        !prefs(ctx).getString(KEY_PIN, "").isNullOrBlank()

    fun setPin(ctx: Context, pin: String) =
        prefs(ctx).edit().putString(KEY_PIN, pin).apply()

    fun clearPin(ctx: Context) =
        prefs(ctx).edit().remove(KEY_PIN).apply()

    fun verifyPin(ctx: Context, input: String): Boolean =
        prefs(ctx).getString(KEY_PIN, "") == input
}
