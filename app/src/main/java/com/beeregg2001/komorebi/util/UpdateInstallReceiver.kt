package com.beeregg2001.komorebi.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class UpdateInstallReceiver : BroadcastReceiver() {
    @Inject lateinit var updater: AppUpdater

    override fun onReceive(context: Context, intent: Intent) {
        updater.handleInstallResult(intent)
    }
}
