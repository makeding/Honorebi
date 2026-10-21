package com.beeregg2001.komorebi.ui.live

/** Fences a lookup result to the channel that requested it. */
internal class ChasePlaybackRequestFence {
    private var generation = 0L

    fun begin(channelId: String): Request = Request(++generation, channelId)

    fun invalidate() {
        generation++
    }

    fun isCurrent(request: Request, channelId: String): Boolean =
        request.generation == generation && request.channelId == channelId

    internal data class Request(val generation: Long, val channelId: String)
}
