package com.kb.blocker

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * VPN Detection
 * VPN চালু থাকলে block করার option
 */
object VpnDetector {

    private const val PREFS  = "vpn_prefs"
    private const val KEY_ON = "vpn_block_enabled"

    private fun p(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isBlockEnabled(ctx: Context)          = p(ctx).getBoolean(KEY_ON, false)
    fun setBlockEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean(KEY_ON, v).apply()

    fun isVpnActive(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps    = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (_: Exception) { false }
    }
}
