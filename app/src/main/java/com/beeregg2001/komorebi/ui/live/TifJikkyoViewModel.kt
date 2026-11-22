package com.beeregg2001.komorebi.ui.live

import androidx.lifecycle.ViewModel
import com.beeregg2001.komorebi.data.model.Channel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class TifJikkyoViewModel @Inject constructor(private val manager: LiveJikkyoManager) : ViewModel() {
    private val sessions = LiveChannelSessionCoordinator()
    val comments = manager.liveComments

    fun start(channel: Channel): LiveChannelSessionToken {
        val token = sessions.begin(LivePlaybackSlot.MAIN, channel.id)
        manager.startJikkyo(channel, null, token, sessions::isCurrent)
        return token
    }

    fun stop() {
        sessions.end(LivePlaybackSlot.MAIN)
        manager.stopJikkyo()
    }
}
