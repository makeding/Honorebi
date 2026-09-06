package com.beeregg2001.komorebi.ui.video.player

import com.beeregg2001.komorebi.ui.main.RecordedPlaybackToken
import com.beeregg2001.komorebi.util.mmts.B62SubtitleSample
import com.beeregg2001.komorebi.util.mmts.B60ApplicationResource
import com.beeregg2001.komorebi.util.mmts.B60BroadcastClock
import com.beeregg2001.komorebi.util.mmts.B60DataBroadcastingCallback
import com.beeregg2001.komorebi.util.mmts.B60EventInfo

class RecordedPlaybackFence(
    private val token: RecordedPlaybackToken?,
    private val isCurrent: (RecordedPlaybackToken) -> Boolean,
    val identity: Any,
) {
    fun accepts(): Boolean = token?.let(isCurrent) ?: true

    fun accepts(candidate: RecordedPlaybackToken?): Boolean =
        candidate == token && accepts()

    fun tokenOrNull(): RecordedPlaybackToken? = token
}

internal data class FencedB62SubtitleSample(
    val token: RecordedPlaybackToken?,
    val sample: B62SubtitleSample,
    val epoch: Long = 0L,
)

internal class FencedB60DataBroadcastingCallback(
    private val delegate: B60DataBroadcastingCallback,
    private val fence: RecordedPlaybackFence,
) : B60DataBroadcastingCallback {
    override fun onBroadcastClock(clock: B60BroadcastClock) { if (fence.accepts()) delegate.onBroadcastClock(clock) }
    override fun onEventInfo(event: B60EventInfo) { if (fence.accepts()) delegate.onEventInfo(event) }
    override fun onLayoutConfiguration(contextId: Long, backgroundColorRgb: Int?) { if (fence.accepts()) delegate.onLayoutConfiguration(contextId, backgroundColorRgb) }
    override fun onApplicationState(contextId: Long, applicationType: Int, organizationId: Int, applicationId: Long, controlCode: Int, applicationPriority: Int, entryPath: String, transportUrls: List<String>, collectionState: Int, resourceCount: Long, entryReady: Boolean) {
        if (fence.accepts()) delegate.onApplicationState(contextId, applicationType, organizationId, applicationId, controlCode, applicationPriority, entryPath, transportUrls, collectionState, resourceCount, entryReady)
    }
    override fun onApplicationResource(resource: B60ApplicationResource) { if (fence.accepts()) delegate.onApplicationResource(resource) }
    override fun onApplicationResourcesReset() { if (fence.accepts()) delegate.onApplicationResourcesReset() }
}
