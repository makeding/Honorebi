package com.beeregg2001.komorebi.ui.player

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Rejects queued decode work and late decoder results after a source replacement, seek, or
 * disposal. The caller-provided predicate carries the owning playback-slot/session check.
 */
open class CaptionGenerationFence(private val isCurrent: () -> Boolean) {
    private val active = AtomicBoolean(true)
    private val generation = AtomicLong()

    fun token(): Long = generation.get()

    fun accepts(token: Long = token()): Boolean =
        active.get() && token == generation.get() && isCurrent()

    fun reset() {
        generation.incrementAndGet()
    }

    fun retire() {
        active.set(false)
        reset()
    }
}
