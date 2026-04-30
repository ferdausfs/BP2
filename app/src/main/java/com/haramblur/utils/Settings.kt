package com.haramblur.utils

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    var isEnabled: Boolean
        get() = prefs.getBoolean(Constants.KEY_ENABLED, true)
        set(v) = prefs.edit().putBoolean(Constants.KEY_ENABLED, v).apply()

    var strictness: Float
        get() = prefs.getFloat(Constants.KEY_STRICTNESS, Constants.DEFAULT_STRICTNESS)
        set(v) = prefs.edit().putFloat(Constants.KEY_STRICTNESS, v).apply()

    var blurImages: Boolean
        get() = prefs.getBoolean(Constants.KEY_BLUR_IMAGES, true)
        set(v) = prefs.edit().putBoolean(Constants.KEY_BLUR_IMAGES, v).apply()

    var blurFemale: Boolean
        get() = prefs.getBoolean(Constants.KEY_BLUR_FEMALE, true)
        set(v) = prefs.edit().putBoolean(Constants.KEY_BLUR_FEMALE, v).apply()

    var blurMale: Boolean
        get() = prefs.getBoolean(Constants.KEY_BLUR_MALE, false)
        set(v) = prefs.edit().putBoolean(Constants.KEY_BLUR_MALE, v).apply()

    var blurAmount: Int
        get() = prefs.getInt(Constants.KEY_BLUR_AMOUNT, Constants.DEFAULT_BLUR_AMOUNT)
        set(v) = prefs.edit().putInt(Constants.KEY_BLUR_AMOUNT, v).apply()

    // Whitelist: set of package names
    var whitelist: Set<String>
        get() {
            val json = prefs.getString(Constants.KEY_WHITELIST, "[]") ?: "[]"
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            } catch (e: Exception) { emptySet() }
        }
        set(v) {
            val arr = JSONArray(v.toList())
            prefs.edit().putString(Constants.KEY_WHITELIST, arr.toString()).apply()
        }

    fun addToWhitelist(pkg: String)      { whitelist = whitelist + pkg }
    fun removeFromWhitelist(pkg: String) { whitelist = whitelist - pkg }
    fun isWhitelisted(pkg: String)       = whitelist.contains(pkg)
}
