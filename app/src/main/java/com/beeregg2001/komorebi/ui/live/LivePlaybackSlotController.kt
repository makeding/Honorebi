package com.beeregg2001.komorebi.ui.live

import com.beeregg2001.komorebi.data.repository.LiveStreamSessionLease
import com.beeregg2001.komorebi.ui.player.PlayerRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.sse.EventSource

/**
 * Owns the resources of one live playback slot.  A generation is advanced before every
 * start and stop, therefore a cancelled/late creator can neither install nor tear down the
 * resources of a newer channel.
 *
 * Lease closing deliberately uses an application-owned scope, rather than the ViewModel
 * scope that may already have been cancelled during teardown.
 */
internal class LivePlaybackSlotController(
    private val label: String,
    private val releaseTimeoutMs: Long = 5_000L,
    private val log: (String) -> Unit = {},
) {
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var generation = 0L
    private var job: Job? = null
    private var recoveryJob: Job? = null
    private var runtime: PlayerRuntime? = null
    private var eventSource: EventSource? = null
    private var upstreamLease: LiveStreamSessionLease? = null
    /** A parked offline generation may finish a late resolver, but must not install it. */
    private var playbackBlocked = false

    fun currentRuntime(): PlayerRuntime? = synchronized(lock) { runtime }
    fun currentEventSource(): EventSource? = synchronized(lock) { eventSource }
    fun currentLease(): LiveStreamSessionLease? = synchronized(lock) { upstreamLease }

    fun begin(): Run {
        val previous: Resources
        val run: Run
        synchronized(lock) {
            generation += 1
            playbackBlocked = false
            previous = takeResourcesLocked()
            run = Run(generation)
            job = null
        }
        previous.release("replaced")
        return run
    }

    /** Cancel work and release resources without relying on a cancelled ViewModel scope. */
    fun stop(reason: String) {
        val previous = synchronized(lock) {
            generation += 1
            playbackBlocked = true
            takeResourcesLocked()
        }
        previous.release(reason)
    }

    /** Release playback while a generation-owned retry is still running. */
    fun releasePlayback(reason: String) {
        val previous = synchronized(lock) {
            Resources(null, null, runtime, eventSource, upstreamLease).also {
                runtime = null
                eventSource = null
                upstreamLease = null
            }
        }
        previous.release(reason)
    }

    fun isCurrent(run: Run): Boolean = synchronized(lock) { generation == run.epoch }

    internal inner class Run internal constructor(internal val epoch: Long) {
        val broadcastState = LiveBroadcastStreamState()

        /** A recovery is independent of startup, and duplicate errors cannot replace it. */
        fun launchRecovery(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit): Boolean {
            val candidate = scope.launch(start = CoroutineStart.LAZY, block = block)
            val accepted = synchronized(lock) {
                if (generation != epoch || recoveryJob?.let { !it.isCompleted } == true) false
                else { recoveryJob = candidate; true }
            }
            if (accepted) candidate.start() else candidate.cancel()
            return accepted
        }

        /** Queue one fenced recovery after an in-flight recovery has released this generation. */
        fun launchRecoveryWhenIdle(
            scope: CoroutineScope,
            block: suspend CoroutineScope.() -> Unit,
        ): Boolean {
            val activeRecovery = synchronized(lock) {
                if (generation != epoch) return false
                recoveryJob?.takeIf { !it.isCompleted }
            }
            if (activeRecovery == null) return launchRecovery(scope, block)
            activeRecovery.invokeOnCompletion {
                scope.launch {
                    if (isCurrent()) launchRecovery(scope, block)
                }
            }
            return true
        }

        fun isRecovering(): Boolean = synchronized(lock) {
            generation == epoch && recoveryJob?.let { !it.isCompleted } == true
        }

        fun isCurrent(): Boolean = this@LivePlaybackSlotController.isCurrent(this)

        /** Park this generation: late creators may release their result but cannot install it. */
        fun blockPlaybackInstallation() = synchronized(lock) {
            if (this@LivePlaybackSlotController.generation == epoch) playbackBlocked = true
        }

        /** Capture a late response before returning to the cancelled owner; transfer only after installation. */
        suspend fun <T> withCreatedResource(
            timeoutMs: Long = 30_000,
            create: suspend () -> T,
            leaseOf: (T) -> LiveStreamSessionLease?,
            install: suspend (T, () -> Boolean) -> Unit,
        ) {
            var unclaimed: LiveStreamSessionLease? = null
            try {
                val resource = withContext(NonCancellable) {
                    withTimeout(timeoutMs) {
                        create().also { unclaimed = leaseOf(it) }
                    }
                }
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) return
                install(resource) {
                    val lease = unclaimed
                    unclaimed = null
                    if (lease == null) isCurrent() else installLease(lease)
                }
            } finally {
                unclaimed?.let { releaseLease(it, "unclaimed_creation") }
            }
        }

        fun attachStartupJob(candidate: Job): Boolean {
            val stale = synchronized(lock) {
                if (this@LivePlaybackSlotController.generation != epoch) true else {
                    job?.cancel()
                    job = candidate
                    false
                }
            }
            if (stale) candidate.cancel()
            return !stale
        }

        fun launch(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit): Boolean {
            val candidate = scope.launch(start = CoroutineStart.LAZY, block = block)
            return attachStartupJob(candidate).also { installed -> if (installed) candidate.start() }
        }

        fun installRuntime(candidate: PlayerRuntime): Boolean {
            val stale = synchronized(lock) {
                if (this@LivePlaybackSlotController.generation != epoch || playbackBlocked ||
                    recoveryJob?.let { !it.isCompleted } == true) true else {
                    runtime?.release()
                    runtime = candidate
                    false
                }
            }
            if (stale) candidate.release()
            return !stale
        }

        fun installEventSource(candidate: EventSource): Boolean {
            val stale = synchronized(lock) {
                if (this@LivePlaybackSlotController.generation != epoch || playbackBlocked ||
                    recoveryJob?.let { !it.isCompleted } == true) true else {
                    eventSource?.cancel()
                    eventSource = candidate
                    false
                }
            }
            if (stale) candidate.cancel()
            return !stale
        }

        fun installLease(candidate: LiveStreamSessionLease): Boolean {
            val replaced: LiveStreamSessionLease?
            synchronized(lock) {
                if (this@LivePlaybackSlotController.generation != epoch || playbackBlocked ||
                    recoveryJob?.let { !it.isCompleted } == true) {
                    releaseLease(candidate, "late")
                    return false
                }
                replaced = upstreamLease
                upstreamLease = candidate
            }
            replaced?.let { releaseLease(it, "replaced") }
            return true
        }
    }

    private fun takeResourcesLocked(): Resources {
        val result = Resources(job, recoveryJob, runtime, eventSource, upstreamLease)
        recoveryJob = null
        job = null
        runtime = null
        eventSource = null
        upstreamLease = null
        return result
    }

    private inner class Resources(
        private val job: Job?,
        private val recoveryJob: Job?,
        private val runtime: PlayerRuntime?,
        private val eventSource: EventSource?,
        private val lease: LiveStreamSessionLease?,
    ) {
        fun release(reason: String) {
            recoveryJob?.cancel(CancellationException("$label recovery stopped: $reason"))
            job?.cancel(CancellationException("$label stopped: $reason"))
            try {
                eventSource?.cancel()
            } finally {
                try { runtime?.release() } finally { lease?.let { releaseLease(it, reason) } }
            }
        }
    }

    private fun releaseLease(lease: LiveStreamSessionLease, reason: String) {
        log("slot=$label generation release session=${lease.id} reason=$reason")
        releaseScope.launch {
            try {
                if (withTimeoutOrNull(releaseTimeoutMs) { lease.close() } == null) {
                    log("slot=$label session=${lease.id} release timed out")
                } else {
                    log("slot=$label session=${lease.id} released")
                }
            } catch (error: Exception) {
                log("slot=$label session=${lease.id} release failed: ${error.message}")
            }
        }
    }
}
