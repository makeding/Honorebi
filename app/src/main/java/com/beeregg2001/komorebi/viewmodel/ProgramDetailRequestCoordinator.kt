package com.beeregg2001.komorebi.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debounces focus-driven detail loads while fencing results from superseded or cleared requests.
 * A completed request is deliberately not cached: recording metadata may change between focuses.
 */
internal class ProgramDetailRequestCoordinator<T>(
    private val scope: CoroutineScope,
    private val load: suspend (Int) -> Result<T>,
    private val onRequestAccepted: (Int) -> Unit,
    private val onSuccess: (T) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMillis: Long = 300L,
) {
    private data class RequestToken(val generation: Long, val videoId: Int)

    private val lock = Any()
    private var generation = 0L
    private var activeRequest: RequestToken? = null
    private var requestJob: Job? = null

    /** Returns false when the same video already has a pending or running request. */
    fun request(videoId: Int): Boolean {
        val previousJob: Job?
        val nextJob: Job
        synchronized(lock) {
            if (activeRequest?.videoId == videoId) return false

            previousJob = requestJob
            val token = RequestToken(++generation, videoId)
            activeRequest = token
            requestJob = null
            onRequestAccepted(videoId)
            nextJob = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
                try {
                    if (debounceMillis > 0) delay(debounceMillis)
                    val result = try {
                        load(videoId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Result.failure(error)
                    }
                    publishIfCurrent(token, result)
                } finally {
                    releaseIfCurrent(token)
                }
            }
            requestJob = nextJob
        }
        // The replacement token is already visible before cancellation can run callbacks.
        previousJob?.cancel()
        nextJob.start()
        return true
    }

    /** Invalidates before cancelling, so a provider that ignores cancellation cannot publish later. */
    fun cancel(onInvalidated: () -> Unit = {}) {
        val cancelledJob: Job?
        synchronized(lock) {
            generation += 1
            activeRequest = null
            cancelledJob = requestJob
            requestJob = null
            onInvalidated()
        }
        cancelledJob?.cancel()
    }

    private fun publishIfCurrent(token: RequestToken, result: Result<T>) {
        synchronized(lock) {
            if (activeRequest != token) return
            activeRequest = null
            requestJob = null
            result.fold(onSuccess = onSuccess, onFailure = onFailure)
        }
    }

    private fun releaseIfCurrent(token: RequestToken) {
        synchronized(lock) {
            if (activeRequest != token) return
            activeRequest = null
            requestJob = null
        }
    }
}
