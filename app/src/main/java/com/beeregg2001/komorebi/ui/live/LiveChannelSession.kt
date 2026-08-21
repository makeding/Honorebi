package com.beeregg2001.komorebi.ui.live

import com.beeregg2001.komorebi.util.mmts.B60ApplicationResource
import com.beeregg2001.komorebi.util.mmts.B60BroadcastClock
import com.beeregg2001.komorebi.util.mmts.B60DataBroadcastingCallback
import com.beeregg2001.komorebi.util.mmts.B60EventInfo

enum class LivePlaybackSlot {
    MAIN,
    DUAL
}

data class LiveChannelSessionToken(
    val slot: LivePlaybackSlot,
    val epoch: Long,
    val channelId: String
)

class LiveChannelSessionCoordinator {
    private val lock = Any()
    private val epochs = mutableMapOf<LivePlaybackSlot, Long>()
    private val currentTokens = mutableMapOf<LivePlaybackSlot, LiveChannelSessionToken>()

    fun begin(slot: LivePlaybackSlot, channelId: String): LiveChannelSessionToken = synchronized(lock) {
        val token = LiveChannelSessionToken(slot, (epochs[slot] ?: 0) + 1, channelId)
        epochs[slot] = token.epoch
        currentTokens[slot] = token
        token
    }

    fun current(slot: LivePlaybackSlot): LiveChannelSessionToken? = synchronized(lock) {
        currentTokens[slot]
    }

    fun isCurrent(token: LiveChannelSessionToken): Boolean = synchronized(lock) {
        currentTokens[token.slot] == token
    }

    fun end(slot: LivePlaybackSlot) {
        synchronized(lock) {
            currentTokens.remove(slot)
        }
    }
}

class LiveSessionDataBroadcastingCallback(
    private val token: LiveChannelSessionToken,
    private val sessions: LiveChannelSessionCoordinator,
    private val delegate: B60DataBroadcastingCallback
) : B60DataBroadcastingCallback {
    override fun onBroadcastClock(clock: B60BroadcastClock) {
        if (sessions.isCurrent(token)) delegate.onBroadcastClock(clock)
    }

    override fun onEventInfo(event: B60EventInfo) {
        if (sessions.isCurrent(token)) delegate.onEventInfo(event)
    }

    override fun onLayoutConfiguration(contextId: Long, backgroundColorRgb: Int?) {
        if (sessions.isCurrent(token)) {
            delegate.onLayoutConfiguration(contextId, backgroundColorRgb)
        }
    }

    override fun onApplicationState(
        contextId: Long,
        applicationType: Int,
        organizationId: Int,
        applicationId: Long,
        controlCode: Int,
        applicationPriority: Int,
        entryPath: String,
        transportUrls: List<String>,
        collectionState: Int,
        resourceCount: Long,
        entryReady: Boolean
    ) {
        if (sessions.isCurrent(token)) {
            delegate.onApplicationState(
                contextId, applicationType, organizationId, applicationId, controlCode,
                applicationPriority, entryPath, transportUrls, collectionState, resourceCount,
                entryReady
            )
        }
    }

    override fun onApplicationResource(resource: B60ApplicationResource) {
        if (sessions.isCurrent(token)) delegate.onApplicationResource(resource)
    }

    override fun onApplicationResourcesReset() {
        if (sessions.isCurrent(token)) delegate.onApplicationResourcesReset()
    }
}
