package com.beeregg2001.komorebi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AirPlayStartupRepairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        AirPlayStartupRepairManager.repair(context)
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            AirPlayStartupRepairManager.scheduleBootRetry(context)
        }
    }
}
