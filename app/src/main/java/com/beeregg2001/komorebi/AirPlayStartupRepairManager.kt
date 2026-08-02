package com.beeregg2001.komorebi

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/** Repairs the MediaTek AirPlay boot race without touching Google Cast. */
object AirPlayStartupRepairManager {

    const val ACTION_REPAIR_AIRPLAY =
        "com.beeregg2001.Honorebi.action.REPAIR_AIRPLAY"

    private const val TAG = "HonorebiAirPlayRepair"
    private const val AIRPLAY_DAEMON_PACKAGE = "com.mediatek.airplaydaemon"
    private const val AIRPLAY_PERMISSION = "com.mediatek.permission.AirPlay.BroadCast"
    private const val ACTION_SYSTEM_READY = "Intent.airplay.airplaysystemready"
    private const val ACTION_SETUP_COMPLETE = "Intent.airplay.setupcomplete"
    private const val EXTRA_SETUP_COMPLETE = "isSetupComplete"
    private const val BOOT_RETRY_DELAY_MILLIS = 15_000L

    fun repair(context: Context) {
        runCatching {
            context.sendBroadcast(daemonIntent(ACTION_SYSTEM_READY), AIRPLAY_PERMISSION)
            context.sendBroadcast(
                daemonIntent(ACTION_SETUP_COMPLETE).putExtra(EXTRA_SETUP_COMPLETE, "true"),
                AIRPLAY_PERMISSION
            )
        }.onSuccess {
            Log.i(TAG, "Replayed MediaTek AirPlay ready and setup-complete signals")
        }.onFailure { error ->
            Log.e(TAG, "Failed to replay MediaTek AirPlay startup signals", error)
        }
    }

    fun scheduleBootRetry(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val retryIntent = Intent(context, AirPlayStartupRepairReceiver::class.java).apply {
            action = ACTION_REPAIR_AIRPLAY
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + BOOT_RETRY_DELAY_MILLIS,
            pendingIntent
        )
    }

    private fun daemonIntent(actionName: String) = Intent(actionName).apply {
        component = ComponentName(
            AIRPLAY_DAEMON_PACKAGE,
            "com.mediatek.airplaydaemon.BootupReceiver"
        )
        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
    }
}
