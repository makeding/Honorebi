package com.beeregg2001.komorebi.data.repository

import com.beeregg2001.komorebi.data.model.LiveStreamSessionResponse
import java.util.concurrent.atomic.AtomicBoolean

/** Ownership travels from the create operation to exactly one playback slot. */
class LiveStreamSessionLease(
    val response: LiveStreamSessionResponse,
    private val release: suspend (String) -> Unit
) {
    val id: String get() = response.id
    private val closed = AtomicBoolean(false)
    suspend fun close() {
        if (closed.compareAndSet(false, true)) release(id)
    }
}
