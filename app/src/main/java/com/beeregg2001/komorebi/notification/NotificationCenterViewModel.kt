package com.beeregg2001.komorebi.notification

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class NotificationCenterViewModel @Inject constructor(
    private val repository: NotificationCenterRepository,
) : ViewModel() {

    val uiState: StateFlow<NotificationCenterUiState> = combine(
        repository.accessStatus,
        repository.isAccessGranted,
        repository.notifications,
        repository.externalMediaSessions,
    ) { accessStatus, isAccessGranted, notifications, externalMediaSessions ->
        NotificationCenterUiState(
            accessStatus = accessStatus,
            isAccessGranted = isAccessGranted,
            notifications = notifications,
            externalMediaSessions = externalMediaSessions,
            adbGrantCommand = repository.adbGrantCommand,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = NotificationCenterUiState(
            accessStatus = repository.accessStatus.value,
            isAccessGranted = repository.isAccessGranted.value,
            notifications = repository.notifications.value,
            externalMediaSessions = repository.externalMediaSessions.value,
            adbGrantCommand = repository.adbGrantCommand,
        ),
    )

    fun refreshAccessStatus() {
        repository.refreshAccessStatus()
    }

    fun dismissNotification(key: String) {
        repository.dismissNotification(key)
    }

    fun clearAllClearable() {
        repository.clearAllClearable()
    }

    fun openNotification(
        item: SystemNotificationItem,
        onBeforeLaunch: () -> Unit = {},
    ): Boolean = repository.openNotification(item, onBeforeLaunch)

    fun sendMediaAction(
        session: ExternalMediaSessionState,
        action: MediaTransportAction,
    ): Boolean {
        val token = session.sessionToken
        return if (token != null) {
            repository.sendMediaAction(token, action)
        } else {
            repository.sendMediaAction(session.sessionKey, action)
        }
    }

    fun openMediaApp(
        session: ExternalMediaSessionState,
        onBeforeLaunch: () -> Unit = {},
    ): Boolean = repository.openMediaApp(session, onBeforeLaunch)

    fun openNotificationListenerSettings(
        context: Context,
        onBeforeLaunch: () -> Unit = {},
    ): NotificationSettingsOpenResult =
        repository.openNotificationListenerSettings(context, onBeforeLaunch)
}
