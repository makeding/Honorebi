package com.beeregg2001.komorebi.ui.home

import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay

/** A one-shot focus command; click identities remain in HomeViewModel. */
data class HomeFocusRequest(
    val id: Long,
    val tab: Int,
    val section: String? = null,
    val itemId: String? = null,
    val contentTop: Boolean = false,
)

@Stable
class FocusMemory {
    var request by mutableStateOf<HomeFocusRequest?>(null)
        private set
    var completedId by mutableStateOf(0L)
        private set
    var preparedId by mutableStateOf<Long?>(null)
        private set
    fun prepare(id: Long) {
        if (request?.id == id) preparedId = id
    }
    private var nextId = 0L
    val requestId get() = request?.id

    fun remember(tab: Int, section: String? = null, itemId: String? = null) {
        request = HomeFocusRequest(++nextId, tab, section, itemId)
    }

    fun requestContent(tab: Int) {
        request = HomeFocusRequest(++nextId, tab, contentTop = true)
    }

    fun consume(id: Long) {
        if (request?.id == id) {
            request = null
            completedId = id
        }
    }

    fun clear() {
        request = null
    }
}

@Composable
fun rememberFocusMemory() = remember { FocusMemory() }

/** Player/AI restoration owns screen entry; later overlay dismissal still restores focus. */
@Composable
internal fun HomeOverlayReturnFocusEffect(
    isFullScreen: Boolean,
    returnInProgress: Boolean,
    onInitialEntry: (() -> Unit)? = null,
    onRestore: () -> Unit,
) {
    val enteredWithReturn = remember { returnInProgress }
    var previousFullScreen by remember { mutableStateOf<Boolean?>(null) }
    val currentReturning by rememberUpdatedState(returnInProgress)
    val initialEntry by rememberUpdatedState(onInitialEntry)
    val restore by rememberUpdatedState(onRestore)
    LaunchedEffect(isFullScreen) {
        val previous = previousFullScreen
        previousFullScreen = isFullScreen
        if (isFullScreen || currentReturning || (previous == null && enteredWithReturn)) {
            return@LaunchedEffect
        }
        if (!currentReturning) {
            if (previous == null) (initialEntry ?: restore)() else restore()
        }
    }
}

@Composable
internal fun HomeEntryFocusEffect(
    memory: FocusMemory,
    tabRequester: (Int) -> FocusRequester?,
    contentRequester: (Int) -> FocusRequester,
    contentReadyGeneration: Long = 0L,
    isContentReady: (Int) -> Boolean = { true },
    contentFallbackTimeoutMs: Long = 2_000L,
) {
    LaunchedEffect(memory.requestId, contentReadyGeneration) {
        val request = memory.request ?: return@LaunchedEffect
        if (request.section != null) return@LaunchedEffect
        if (request.contentTop && !isContentReady(request.tab)) {
            if (contentFallbackTimeoutMs <= 0L) return@LaunchedEffect
            delay(contentFallbackTimeoutMs)
            val stillPending = memory.request
            if (stillPending == null || stillPending.id != request.id) return@LaunchedEffect
            if (!isContentReady(request.tab)) {
                tabRequester(request.tab)?.safeRequestFocusWithRetry("HomeFocus_EntryFallback")
                memory.consume(request.id)
            }
            return@LaunchedEffect
        }
        val requester = if (request.contentTop) contentRequester(request.tab) else tabRequester(request.tab)
        val focused = requester?.safeRequestFocusWithRetry(
            tag = "HomeFocus_Entry",
            maxRetries = 8,
            delayMillis = 40L,
            isCurrent = { memory.request?.id == request.id },
        ) ?: false
        if (!focused && request.contentTop) {
            tabRequester(request.tab)?.safeRequestFocusWithRetry(
                tag = "HomeFocus_EntryFallback",
                isCurrent = { memory.request?.id == request.id },
            )
        }
        memory.consume(request.id)
    }
}
