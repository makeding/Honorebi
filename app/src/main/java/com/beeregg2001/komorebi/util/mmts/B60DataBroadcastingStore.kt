package com.beeregg2001.komorebi.util.mmts

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

data class B60ApplicationStatus(
    val generation: Long = 0,
    val channelId: String? = null,
    val contextId: Long? = null,
    val application: B60ApplicationInformation? = null,
    val declaredEntryPath: String? = null,
    val transportUrls: List<String> = emptyList(),
    val entryPath: String? = null,
    val collectionState: Int = APPLICATION_STATE_DISCOVERED,
    val resourceCount: Long = 0,
    val declaredEntryReady: Boolean = false,
    val entryReady: Boolean = false,
    val broadcastClock: B60BroadcastClock? = null,
    val presentEvent: B60EventInfo? = null,
    val followingEvent: B60EventInfo? = null
) {
    companion object {
        const val APPLICATION_STATE_DISCOVERED = 0
    }
}

data class B60ApplicationInformation(
    val applicationType: Int,
    val organizationId: Int,
    val applicationId: Long,
    val controlCode: Int,
    val autostartPriority: Int
)

data class B60BroadcastClock(
    val mediaTimeValue: Long,
    val mediaTimeTimescale: Long,
    val broadcastTimeValue: Long,
    val broadcastTimeTimescale: Long,
    val inputOffset: Long,
    val discontinuity: Boolean
)

data class B60EventInfo(
    val contextId: Long,
    val tableId: Int,
    val currentNext: Boolean,
    val sectionNumber: Int,
    val serviceId: Int,
    val tlvStreamId: Int,
    val originalNetworkId: Int,
    val eventId: Int,
    val startTimeUnixMilliseconds: Long?,
    val durationSeconds: Long?,
    val runningStatus: Int,
    val freeCaMode: Boolean,
    val language: String,
    val title: String,
    val description: String
)

data class B60ApplicationResource(
    val contextId: Long,
    val path: String,
    val contentType: String,
    val data: ByteArray,
    val version: Int
)

data class B60ResourceChange(
    val generation: Long,
    val path: String,
    val updated: Boolean
)

interface B60DataBroadcastingCallback {
    fun onBroadcastClock(clock: B60BroadcastClock) = Unit
    fun onEventInfo(event: B60EventInfo) = Unit
    fun onApplicationState(
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
    ) = Unit

    fun onApplicationResource(resource: B60ApplicationResource) = Unit
    fun onApplicationResourcesReset() = Unit
}

/**
 * Extractor スレッドと WebView の request thread の間で B60 carousel を受け渡す。
 * WebView が少し先にファイルを要求した場合は、同じ session の到着を短時間待てる。
 */
class B60DataBroadcastingStore : B60DataBroadcastingCallback {
    private data class ResourceKey(val contextId: Long, val path: String)

    private val monitor = Object()
    private val generationCounter = AtomicLong(0)
    private val resources = linkedMapOf<ResourceKey, B60ApplicationResource>()

    private val _status = MutableStateFlow(B60ApplicationStatus())
    val status: StateFlow<B60ApplicationStatus> = _status.asStateFlow()

    private val _resourceChanges = MutableSharedFlow<B60ResourceChange>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val resourceChanges: SharedFlow<B60ResourceChange> = _resourceChanges.asSharedFlow()

    fun beginSession(channelId: String) {
        synchronized(monitor) {
            resources.clear()
            _status.value = B60ApplicationStatus(
                generation = generationCounter.incrementAndGet(),
                channelId = channelId
            )
            monitor.notifyAll()
        }
    }

    override fun onBroadcastClock(clock: B60BroadcastClock) {
        _status.value = _status.value.copy(broadcastClock = clock)
    }

    override fun onEventInfo(event: B60EventInfo) {
        if (event.tableId != 0x8b || !event.currentNext) return
        _status.value = when (event.sectionNumber) {
            0 -> _status.value.copy(presentEvent = event)
            1 -> _status.value.copy(followingEvent = event)
            else -> _status.value
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
        val declaredEntryPath = entryPath.normalizeBroadcastPath().takeIf { it.isNotBlank() }
        val normalizedTransportUrls = transportUrls.mapNotNull {
            it.normalizeBroadcastPath().takeIf(String::isNotBlank)
        }
        synchronized(monitor) {
            val resolvedEntryPath = if (entryReady && declaredEntryPath != null) {
                resolveEntryPathLocked(contextId, declaredEntryPath, normalizedTransportUrls)
            } else {
                null
            }
            _status.value = _status.value.copy(
                contextId = contextId,
                application = B60ApplicationInformation(
                    applicationType = applicationType,
                    organizationId = organizationId,
                    applicationId = applicationId,
                    controlCode = controlCode,
                    autostartPriority = applicationPriority
                ),
                declaredEntryPath = declaredEntryPath,
                transportUrls = normalizedTransportUrls,
                entryPath = resolvedEntryPath,
                collectionState = collectionState,
                resourceCount = resourceCount,
                declaredEntryReady = entryReady,
                entryReady = resolvedEntryPath != null
            )
            monitor.notifyAll()
        }
    }

    override fun onApplicationResource(resource: B60ApplicationResource) {
        val normalized = resource.copy(path = resource.path.normalizeBroadcastPath())
        if (normalized.path.isBlank()) return
        val status = _status.value
        val key = ResourceKey(normalized.contextId, normalized.path)
        val updated: Boolean
        synchronized(monitor) {
            updated = resources.put(key, normalized) != null
            refreshResolvedEntryLocked(normalized.contextId)
            monitor.notifyAll()
        }
        _resourceChanges.tryEmit(
            B60ResourceChange(
                generation = status.generation,
                path = normalized.path,
                updated = updated
            )
        )
    }

    override fun onApplicationResourcesReset() {
        val channelId = _status.value.channelId
        synchronized(monitor) {
            resources.clear()
            _status.value = B60ApplicationStatus(
                generation = generationCounter.incrementAndGet(),
                channelId = channelId
            )
            monitor.notifyAll()
        }
    }

    fun waitForResource(path: String, timeoutMillis: Long = 30_000L): B60ApplicationResource? {
        val requestedPath = path.normalizeBroadcastPath()
        if (requestedPath.isBlank()) return null
        val expectedGeneration = _status.value.generation
        val deadline = System.currentTimeMillis() + timeoutMillis.coerceAtLeast(0L)
        synchronized(monitor) {
            while (true) {
                findResourceLocked(requestedPath)?.let { return it }
                if (_status.value.generation != expectedGeneration) return null
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) return null
                monitor.wait(remaining)
            }
        }
    }

    private fun findResourceLocked(path: String): B60ApplicationResource? {
        val contextId = _status.value.contextId ?: return null
        resources[ResourceKey(contextId, path)]?.let { return it }

        val basename = path.substringAfterLast('/')
        if (basename.isBlank()) return null
        val candidates = resources
            .asSequence()
            .filter { (key, _) ->
                key.contextId == contextId &&
                    (key.path == basename || key.path.endsWith("/$basename"))
            }
            .map { it.value }
            .take(2)
            .toList()
        return candidates.singleOrNull()
    }

    private fun refreshResolvedEntryLocked(contextId: Long) {
        val current = _status.value
        val declaredEntryPath = current.declaredEntryPath ?: return
        if (current.contextId != contextId || !current.declaredEntryReady) return
        val resolvedEntryPath = resolveEntryPathLocked(
            contextId,
            declaredEntryPath,
            current.transportUrls
        )
        if (resolvedEntryPath != current.entryPath) {
            _status.value = current.copy(
                entryPath = resolvedEntryPath,
                entryReady = resolvedEntryPath != null
            )
        }
    }

    private fun resolveEntryPathLocked(
        contextId: Long,
        declaredEntryPath: String,
        transportUrls: List<String>
    ): String? {
        val candidates = buildList {
            add(declaredEntryPath)
            transportUrls.forEach { transportUrl ->
                add("$transportUrl/$declaredEntryPath".normalizeBroadcastPath())
            }
        }
        val aitEntry = candidates.firstOrNull {
            resources.containsKey(ResourceKey(contextId, it))
        } ?: return null
        return resolveTransparentStartupCompatibilityEntryLocked(contextId, aitEntry) ?: aitEntry
    }

    /**
     * Some B60 services publish a transparent AIT startup page which only waits for the D key.
     * If that same carousel exposes one unambiguous top-page entry, open it directly. The mount
     * and path both come from collected resources; broadcaster directory names are not assumed.
     */
    private fun resolveTransparentStartupCompatibilityEntryLocked(
        contextId: Long,
        aitEntry: String
    ): String? {
        if (aitEntry.split('/').none { it.equals("startup", ignoreCase = true) }) return null
        val mount = aitEntry.substringBefore('/', missingDelimiterValue = "")
        if (mount.isBlank()) return null
        val candidates = resources.keys.asSequence()
            .filter { key ->
                key.contextId == contextId &&
                    key.path.startsWith("$mount/") &&
                    B60_TOP_ENTRY_PATH.containsMatchIn(key.path)
            }
            .map(ResourceKey::path)
            .take(2)
            .toList()
        return candidates.singleOrNull()
    }
}

private fun String.normalizeBroadcastPath(): String =
    replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." && it != ".." }
        .joinToString("/")

private val B60_TOP_ENTRY_PATH = Regex(
    "/top/(?:[^/]+/)*index(?:4k|8k)?\\.html$",
    RegexOption.IGNORE_CASE
)
