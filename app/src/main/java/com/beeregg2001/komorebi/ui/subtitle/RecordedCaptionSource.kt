package com.beeregg2001.komorebi.ui.subtitle

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Fences both queued work and results returning from a decoder worker. */
class RecordedCaptionSource(private val isCurrent: () -> Boolean) {
    private val active = AtomicBoolean(true)
    private val epoch = AtomicLong()
    fun token(): Long = epoch.get()
    fun accepts(token: Long = token()): Boolean =
        active.get() && token == epoch.get() && isCurrent()
    fun reset() { epoch.incrementAndGet() }
    fun retire() { active.set(false); reset() }
}
