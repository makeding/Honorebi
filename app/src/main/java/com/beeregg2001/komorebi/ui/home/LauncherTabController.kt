package com.beeregg2001.komorebi.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/**
 * Owns the complete lifecycle of top-level launcher tabs:
 * selection -> coalescing -> mount/enter -> content-ready/active -> suspend/resume -> exit.
 *
 * Focus requesters are keyed by [LauncherTab] rather than positional list indices so toggling
 * tab visibility (e.g. hiding Apps) never shifts or aliases focus ownership.
 */
@Stable
class LauncherTabController(
    initialTab: LauncherTab,
    initialVisibleTabs: List<LauncherTab> = LauncherTab.entries,
    private val onLifecycleEvent: ((LauncherTab, LauncherTabLifecycleEvent) -> Unit)? = null,
) {
    var visibleTabs by mutableStateOf(initialVisibleTabs)
        private set

    /** Tab currently highlighted/selected in the top bar (updates immediately on D-pad move). */
    var selectedTab by mutableStateOf(initialTab)
        private set

    /** Tab whose content tree is currently mounted in the stage. */
    var activeTab by mutableStateOf(initialTab)
        private set

    /** Monotonic mount token; stale readiness callbacks from previous generations are ignored. */
    var generation by mutableLongStateOf(0L)
        private set

    /** Generation whose first content item has reported layout readiness, or -1L while mounting. */
    var readyGeneration by mutableLongStateOf(-1L)
        private set

    var phase by mutableStateOf(LauncherTabPhase.MOUNTING)
        private set

    var transitionDirection by mutableStateOf(TabTransitionDirection.NONE)
        private set

    var isSuspended by mutableStateOf(false)
        private set

    private var isDisposed = false

    private val tabRequesters: Map<LauncherTab, FocusRequester> =
        LauncherTab.entries.associateWith { FocusRequester() }

    private val contentRequesters: Map<LauncherTab, FocusRequester> =
        LauncherTab.entries.associateWith { FocusRequester() }

    init {
        emit(initialTab, LauncherTabLifecycleEvent.ENTER)
    }

    val selectedIndex: Int
        get() = visibleTabs.indexOf(selectedTab).coerceAtLeast(0)

    val activeIndex: Int
        get() = visibleTabs.indexOf(activeTab).coerceAtLeast(0)

    val activeSnapshot: TabContentSnapshot
        get() = TabContentSnapshot(activeTab, generation)

    val isActiveContentReady: Boolean
        get() = selectedTab == activeTab && readyGeneration == generation

    val shouldPollLiveChannels: Boolean
        get() = !isSuspended && activeTab.requiresLivePolling

    fun tabRequester(tab: LauncherTab): FocusRequester =
        tabRequesters[tab] ?: FocusRequester.Default

    fun contentRequester(tab: LauncherTab): FocusRequester =
        contentRequesters[tab] ?: FocusRequester.Default

    fun tabRequesterAt(index: Int): FocusRequester? =
        visibleTabs.getOrNull(index)?.let { tabRequesters[it] }

    fun contentRequesterAt(index: Int): FocusRequester =
        visibleTabs.getOrNull(index)?.let { contentRequesters[it] } ?: FocusRequester.Default

    fun isContentReadyFor(tab: LauncherTab): Boolean =
        activeTab == tab && readyGeneration == generation

    fun isContentReadyAt(index: Int): Boolean {
        val tab = visibleTabs.getOrNull(index) ?: return false
        return selectedTab == tab && isContentReadyFor(tab)
    }

    /**
     * Updates the visible tab list (e.g. when `hideAppsTab` changes).
     * Returns the new fallback tab if the current selection disappeared, or null if unchanged.
     */
    fun syncVisibleTabs(tabs: List<LauncherTab>): LauncherTab? {
        if (tabs.isEmpty()) return null
        visibleTabs = tabs
        if (selectedTab !in tabs) {
            val fallback = tabs.first()
            selectTab(fallback, immediate = true)
            return fallback
        }
        if (activeTab !in tabs) {
            commitPendingSelection()
        }
        return null
    }

    /**
     * Selects a tab in the top bar.
     * When [immediate] is false, the content stage waits [TAB_RENDER_COALESCE_MS] so rapid D-pad
     * scrubbing only mounts the final resting tab while the tab bar animates immediately.
     */
    fun selectTab(tab: LauncherTab, immediate: Boolean = false) {
        if (tab !in visibleTabs) return
        val direction = computeDirection(from = activeTab, to = tab)
        if (selectedTab == tab) {
            if (immediate && activeTab != tab) {
                commitPendingSelection()
            }
            return
        }
        selectedTab = tab
        transitionDirection = direction
        if (tab == activeTab) {
            // User scrubbed back to the already-mounted tab before the coalesce window elapsed.
            phase = currentSteadyPhase()
            return
        }
        if (immediate) {
            commitPendingSelection()
        } else if (!isSuspended) {
            phase = LauncherTabPhase.COALESCING
        }
    }

    fun selectIndex(index: Int, immediate: Boolean = false): LauncherTab {
        val safeIndex = index.coerceIn(0, (visibleTabs.size - 1).coerceAtLeast(0))
        val target = visibleTabs.getOrNull(safeIndex) ?: LauncherTab.HOME
        selectTab(target, immediate = immediate)
        return target
    }

    /**
     * Returns the next visible tab after [activeTab], or null if [activeTab] is already the last tab.
     */
    fun nextTab(): LauncherTab? {
        val currentIndex = visibleTabs.indexOf(activeTab)
        if (currentIndex == -1 || currentIndex >= visibleTabs.lastIndex) return null
        return visibleTabs[currentIndex + 1]
    }

    /**
     * Advances immediately to the next visible tab and returns it, or null if already at the last tab.
     */
    fun selectNextTab(): LauncherTab? {
        val target = nextTab() ?: return null
        selectTab(target, immediate = true)
        return target
    }

    /**
     * Returns the previous visible tab before [activeTab], or null if [activeTab] is already the first tab.
     */
    fun previousTab(): LauncherTab? {
        val currentIndex = visibleTabs.indexOf(activeTab)
        if (currentIndex <= 0) return null
        return visibleTabs[currentIndex - 1]
    }

    /**
     * Returns immediately to the previous visible tab and returns it, or null if already at the first tab.
     */
    fun selectPreviousTab(): LauncherTab? {
        val target = previousTab() ?: return null
        selectTab(target, immediate = true)
        return target
    }

    /**
     * Cancels an uncommitted D-pad scrub selection (e.g. when focus moves into the
     * bottom utility/settings footer or top-right status strip) so the stage and highlight
     * stay on [activeTab].
     */
    fun cancelPendingSelection() {
        if (selectedTab == activeTab) return
        selectedTab = activeTab
        transitionDirection = TabTransitionDirection.NONE
        phase = currentSteadyPhase()
    }

    /**
     * Commits [selectedTab] into the content stage, exiting the previous tab and advancing
     * [generation] so late callbacks from the departing tab cannot unlock focus.
     */
    fun commitPendingSelection() {
        val target = selectedTab
        if (activeTab == target) return
        val previous = activeTab
        transitionDirection = computeDirection(from = previous, to = target)
        emit(previous, LauncherTabLifecycleEvent.EXIT)
        activeTab = target
        generation += 1L
        readyGeneration = -1L
        phase = if (isSuspended) LauncherTabPhase.SUSPENDED else LauncherTabPhase.MOUNTING
        emit(target, LauncherTabLifecycleEvent.ENTER)
    }

    /**
     * Marks [forGeneration] as laid out and ready for DOWN focus entry.
     * Returns true only on the first valid readiness transition for the current generation.
     */
    fun markContentReady(forGeneration: Long): Boolean {
        if (forGeneration != generation) return false
        if (readyGeneration == forGeneration) return false
        readyGeneration = forGeneration
        if (!isSuspended && selectedTab == activeTab) {
            phase = LauncherTabPhase.ACTIVE
        }
        emit(activeTab, LauncherTabLifecycleEvent.READY)
        return true
    }

    /**
     * Suspends or resumes the active tab session (e.g. full-screen player/overlay or Activity pause).
     */
    fun updateSuspended(suspended: Boolean) {
        if (isSuspended == suspended) return
        isSuspended = suspended
        if (suspended) {
            phase = LauncherTabPhase.SUSPENDED
            emit(activeTab, LauncherTabLifecycleEvent.SUSPEND)
        } else {
            phase = currentSteadyPhase()
            emit(activeTab, LauncherTabLifecycleEvent.RESUME)
        }
    }

    fun dispose() {
        if (isDisposed) return
        isDisposed = true
        emit(activeTab, LauncherTabLifecycleEvent.EXIT)
    }

    private fun currentSteadyPhase(): LauncherTabPhase = when {
        isSuspended -> LauncherTabPhase.SUSPENDED
        selectedTab != activeTab -> LauncherTabPhase.COALESCING
        readyGeneration == generation -> LauncherTabPhase.ACTIVE
        else -> LauncherTabPhase.MOUNTING
    }

    private fun computeDirection(from: LauncherTab, to: LauncherTab): TabTransitionDirection {
        if (from == to) return TabTransitionDirection.NONE
        val fromIndex = visibleTabs.indexOf(from)
        val toIndex = visibleTabs.indexOf(to)
        if (fromIndex == -1 || toIndex == -1) return TabTransitionDirection.NONE
        return if (toIndex > fromIndex) TabTransitionDirection.FORWARD
        else TabTransitionDirection.BACKWARD
    }

    private fun emit(tab: LauncherTab, event: LauncherTabLifecycleEvent) {
        onLifecycleEvent?.invoke(tab, event)
    }
}

private const val HOME_REFRESH_MIN_INTERVAL_MS = 300_000L

/**
 * Connects [LauncherTabController] to Compose coalescing, Android lifecycle pause/resume,
 * full-screen suspension, and active-tab side effects (polling, EPG preload, home refresh).
 */
@Composable
internal fun LauncherTabLifecycleHost(
    controller: LauncherTabController,
    isFullScreenMode: Boolean,
    networkAvailable: Boolean,
    onEnsureEpgLoaded: () -> Unit,
    onPollingChanged: (Boolean) -> Unit,
    onRefreshHomeData: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isActivityPaused by remember { mutableStateOf(false) }
    var lastHomeRefreshTime by remember { mutableLongStateOf(0L) }

    val latestEnsureEpgLoaded by rememberUpdatedState(onEnsureEpgLoaded)
    val latestPollingChanged by rememberUpdatedState(onPollingChanged)
    val latestRefreshHomeData by rememberUpdatedState(onRefreshHomeData)

    // 1. Coalesce rapid D-pad scrubs before mounting the new tab content tree.
    LaunchedEffect(controller.selectedTab, controller.activeTab) {
        if (controller.selectedTab != controller.activeTab) {
            delay(TAB_RENDER_COALESCE_MS)
            controller.commitPendingSelection()
        }
    }

    // 2. Track Android Activity pause/resume so backgrounded launcher suspends active tab work.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> isActivityPaused = true
                Lifecycle.Event.ON_RESUME -> isActivityPaused = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 3. Synchronize controller suspension with full-screen overlays and Activity pause.
    LaunchedEffect(isFullScreenMode, isActivityPaused) {
        controller.updateSuspended(isFullScreenMode || isActivityPaused)
    }

    // 4. Drive tab-scoped side effects cleanly from the active tab + suspension + network state.
    LaunchedEffect(controller.activeTab, controller.isSuspended, networkAvailable) {
        val activeTab = controller.activeTab

        // Offline mode can still render cached EPG data, so establish the lazy subscription
        // before checking network availability.
        if (activeTab.requiresEpgData) {
            latestEnsureEpgLoaded()
        }

        val shouldPoll = !controller.isSuspended && networkAvailable && activeTab.requiresLivePolling
        latestPollingChanged(shouldPoll)

        if (!controller.isSuspended && networkAvailable && activeTab.refreshesHomeDataOnActivate) {
            val now = System.currentTimeMillis()
            if (now - lastHomeRefreshTime > HOME_REFRESH_MIN_INTERVAL_MS) {
                latestRefreshHomeData()
                lastHomeRefreshTime = now
            }
        }
    }

    // 5. Cleanly stop active side effects and emit final EXIT when leaving composition.
    DisposableEffect(controller) {
        onDispose {
            latestPollingChanged(false)
            controller.dispose()
        }
    }
}
