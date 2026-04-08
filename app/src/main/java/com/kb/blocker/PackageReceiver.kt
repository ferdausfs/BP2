package com.kb.blocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Package install/uninstall receiver
 * নতুন app install detect করে PIN block করে
 */
class PackageReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_INSTALL -> {
                if (!AppInstallBlocker.isEnabled(context)) return
                if (!PinManager.isPinSet(context)) return

                val pkg = intent.data?.schemeSpecificPart ?: return
                if (pkg == context.packageName) return

                // PIN verify screen দেখাও
                val pinIntent = Intent(context, PinActivity::class.java).apply {
                    putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_VERIFY)
                    putExtra("block_reason", "নতুন app install: $pkg")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(pinIntent)
            }
        }
    }
}
