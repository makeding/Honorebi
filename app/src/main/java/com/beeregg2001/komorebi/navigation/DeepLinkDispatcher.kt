package com.beeregg2001.komorebi.navigation

import android.content.Intent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeepLinkDispatcher @Inject constructor() {
    private val channel = Channel<PlaybackOpenRequest>(Channel.BUFFERED)
    val requests: Flow<PlaybackOpenRequest> = channel.receiveAsFlow()

    fun dispatch(intent: Intent?): Boolean {
        val request = KomorebiDeepLink.parse(intent) ?: return false
        return channel.trySend(request).isSuccess
    }
}
