package com.beeregg2001.komorebi.ui.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener

/**
 * Owns one KonomiTV live-event connection.
 *
 * OkHttp invokes SSE callbacks on its own threads.  This wrapper serializes their observable
 * effects onto [scope], fences callbacks from superseded connections, and gives a transient
 * disconnect two reconnect attempts without touching the media player.
 */
internal class LiveStreamEventConnection(
    private val factory: EventSource.Factory,
    private val request: Request,
    private val scope: CoroutineScope,
    private val isCurrent: () -> Boolean,
    private val onEvent: (String) -> Unit,
    private val onExhausted: (Throwable?, Int?) -> Unit,
    private val log: (String) -> Unit = {},
    private val retryDelayMs: Long = 2_000L,
    private val healthyResetMs: Long = HEALTHY_RESET_MS,
) : EventSource {
    private val lock = Any()
    private var generation = 0L
    private var source: EventSource? = null
    private var retryJob: Job? = null
    private var healthyJob: Job? = null
    private var retryCount = 0
    private var started = false
    private var cancelled = false

    /** Starts the initial connection exactly once. */
    fun start() {
        val connectionGeneration = synchronized(lock) {
            if (started || cancelled) return
            started = true
            generation += 1
            generation
        }
        connect(connectionGeneration)
    }

    override fun request(): Request = request

    /** Invalidates all queued callbacks and prevents reconnecting after teardown. */
    override fun cancel() {
        val previous: EventSource?
        synchronized(lock) {
            if (cancelled) return
            cancelled = true
            generation += 1
            retryJob?.cancel()
            retryJob = null
            healthyJob?.cancel()
            healthyJob = null
            previous = source
            source = null
        }
        previous?.cancel()
    }

    private fun connect(connectionGeneration: Long) {
        if (!isActive(connectionGeneration)) return
        val candidate = factory.newEventSource(request, listener(connectionGeneration))
        val accepted = synchronized(lock) {
            if (cancelled || generation != connectionGeneration || !isCurrent()) false else {
                source = candidate
                true
            }
        }
        if (!accepted) candidate.cancel()
    }

    private fun listener(connectionGeneration: Long) = object : EventSourceListener() {
        override fun onClosed(eventSource: EventSource) {
            scope.launch { disconnected(connectionGeneration, eventSource, null, null) }
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            val statusCode = response?.code
            response?.close()
            scope.launch { disconnected(connectionGeneration, eventSource, t, statusCode) }
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            scope.launch {
                if (!owns(connectionGeneration, eventSource)) return@launch
                onEvent(data)
                // An open TCP connection alone is not useful: require a received SSE event
                // before a reconnect cycle earns a fresh retry budget.
                if (owns(connectionGeneration, eventSource)) {
                    scheduleHealthyReset(connectionGeneration, eventSource)
                }
            }
        }
    }

    private fun disconnected(
        connectionGeneration: Long,
        eventSource: EventSource,
        cause: Throwable?,
        statusCode: Int?,
    ) {
        val exhausted: Boolean
        synchronized(lock) {
            if (!ownsLocked(connectionGeneration, eventSource) || retryJob != null) return
            source = null
            healthyJob?.cancel()
            healthyJob = null
            if (retryCount >= MAX_RETRIES) {
                exhausted = true
            } else {
                exhausted = false
                retryCount += 1
                generation += 1
                val reconnectGeneration = generation
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    delay(retryDelayMs)
                    synchronized(lock) { retryJob = null }
                    connect(reconnectGeneration)
                }
                retryJob = job
                job.start()
            }
        }
        eventSource.cancel()
        if (exhausted) {
            log("SSE reconnect exhausted retries=$retryCount status=$statusCode cause=${cause?.message}")
            onExhausted(cause, statusCode)
        } else {
            log("SSE disconnected; reconnecting retry=$retryCount/$MAX_RETRIES status=$statusCode")
        }
    }

    private fun scheduleHealthyReset(connectionGeneration: Long, eventSource: EventSource) {
        synchronized(lock) {
            // Frequent events must not keep postponing a healthy connection's reset.
            if (healthyJob != null) return
            val job = scope.launch(start = CoroutineStart.LAZY) {
                delay(healthyResetMs)
                if (owns(connectionGeneration, eventSource)) {
                    retryCount = 0
                    log("SSE remained healthy for ${healthyResetMs}ms; retry budget reset")
                }
            }
            healthyJob = job
            job.start()
        }
    }

    private fun isActive(connectionGeneration: Long): Boolean = synchronized(lock) {
        !cancelled && generation == connectionGeneration && isCurrent()
    }

    private fun owns(connectionGeneration: Long, eventSource: EventSource): Boolean = synchronized(lock) {
        ownsLocked(connectionGeneration, eventSource)
    }

    private fun ownsLocked(connectionGeneration: Long, eventSource: EventSource): Boolean =
        !cancelled && generation == connectionGeneration && source === eventSource && isCurrent()

    private companion object {
        const val MAX_RETRIES = 2
        const val HEALTHY_RESET_MS = 10_000L
    }
}
