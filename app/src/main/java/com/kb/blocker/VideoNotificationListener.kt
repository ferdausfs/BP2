package com.kb.blocker

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Locale

/**
 * Notification Listener — YouTube, Facebook, Instagram ইত্যাদি
 * video চলার সময় notification এ title/description পাঠায়।
 * সেখান থেকে adult content ধরা যায় যা screen এ text হিসেবে নেই।
 *
 * Permission: BIND_NOTIFICATION_LISTENER_SERVICE
 * User কে Settings → Notification Access দিতে হবে।
 */
class VideoNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return

        // শুধু video/browser app এর notification চেক করব
        val isTarget = KeywordService.VIDEO_PACKAGES.any { pkg.contains(it) } ||
                       KeywordService.BROWSER_PACKAGES.any { pkg.contains(it) }
        if (!isTarget) return

        // Whitelisted হলে skip
        if (KeywordService.isWhitelisted(this, pkg)) return
        if (!KeywordService.isEnabled(this)) return
        if (!KeywordService.isVideoMetaEnabled(this)) return

        val notif   = sbn.notification ?: return
        val extras  = notif.extras    ?: return

        // Notification এর title + text একসাথে
        val title   = extras.getString("android.title") ?: ""
        val text    = extras.getString("android.text")  ?: ""
        val bigText = extras.getString("android.bigText") ?: ""
        val subText = extras.getString("android.subText") ?: ""

        val combined = "$title $text $bigText $subText"
        if (combined.isBlank()) return

        // Adult check
        if (VideoMetaDetector.isAdultMeta(combined)) {
            KeywordService.instance?.let { svc ->
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                handler.post {
                    // BUG 4 fix: BlockedActivity দেখাও
                    svc.triggerBlock(pkg, "📱 Adult notification detected")
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
