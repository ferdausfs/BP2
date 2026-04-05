package com.kb.blocker

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Device Admin Receiver — এটা register থাকলে Settings থেকে manually
 * deactivate না করা পর্যন্ত app uninstall করা যাবে না।
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        // Admin চালু হলে — কিছু করার নেই
    }

    override fun onDisabled(context: Context, intent: Intent) {
        // Admin বন্ধ হলে — কিছু করার নেই
    }
}
