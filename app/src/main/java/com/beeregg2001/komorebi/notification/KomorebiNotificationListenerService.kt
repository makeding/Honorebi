package com.beeregg2001.komorebi.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

private const val TAG = "KomorebiNotifListener"

@AndroidEntryPoint
class KomorebiNotificationListenerService : NotificationListenerService() {

    @Inject
    lateinit var repository: NotificationCenterRepository

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (::repository.isInitialized) {
            repository.onListenerConnected(this)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (::repository.isInitialized) {
            repository.onListenerDisconnected(this)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (::repository.isInitialized) {
            repository.onNotificationsChanged(safeActiveNotifications())
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (::repository.isInitialized) {
            repository.onNotificationsChanged(safeActiveNotifications())
        }
    }

    override fun onDestroy() {
        if (::repository.isInitialized) {
            repository.onListenerDisconnected(this)
        }
        super.onDestroy()
    }

    internal fun safeActiveNotifications(): Array<StatusBarNotification>? =
        runCatching { activeNotifications }.getOrElse { error ->
            Log.w(TAG, "Unable to read activeNotifications", error)
            null
        }
}
