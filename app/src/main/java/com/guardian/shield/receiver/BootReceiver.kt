package com.guardian.shield.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.guardian.shield.service.blocker.GuardianForegroundService
import timber.log.Timber

/**
 * BootReceiver — restart the foreground service after device boot, package
 * upgrade, or an in-process restart broadcast.
 *
 * Hardened against background-start restrictions on Android 12+ by wrapping
 * the start in try/catch — if the OS denies the start (e.g. the app was
 * force-stopped), we simply log and exit instead of crashing the receiver.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val accepted =
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "android.intent.action.LOCKED_BOOT_COMPLETED" ||
            action == "android.intent.action.MY_PACKAGE_REPLACED" ||
            action == "com.guardian.shield.RESTART_SERVICE"
        if (!accepted) return

        Timber.d("BootReceiver: $action — starting service")
        try {
            val serviceIntent = Intent(context, GuardianForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            // Android 12+ background-start restriction can throw here.
            // Nothing we can do — service will start on next user launch.
            Timber.e(e, "BootReceiver: failed to start service")
        }
    }
}
