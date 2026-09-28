package com.beeregg2001.komorebi.ui.home

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import com.beeregg2001.komorebi.data.mapper.KonomiDataMapper
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.viewmodel.*

enum class HomeFocusTicket { NONE, TAB_BAR, CONTENT_TOP, HOME_RESTORE }

/**
 * ランチャー上部のタブ定義。タブ数とフォーカス要求リストのサイズを
 * 一致させるため、画面側と StateHolder 側でこの 1 箇所を共有する。
 */
internal val HOME_TAB_TITLES = listOf("ホーム", "ライブ", "アプリ", "ビデオ", "番組表", "録画予約")

/**
 * 設定でアプリタブを無効化したときに、ランチャーとルート画面で同じタブ構成を
 * 使うためのフィルタ。索引のズレを防ぐため必ずこの関数を経由する。
 */
internal fun visibleHomeTabs(hideAppsTab: Boolean): List<String> =
    if (hideAppsTab) HOME_TAB_TITLES.filterNot { it == "アプリ" } else HOME_TAB_TITLES

@Stable
class HomeFocusTicketManager {
    var currentTicket by mutableStateOf(HomeFocusTicket.NONE)
        private set
    var issueTime by mutableLongStateOf(0L)
        private set

    var targetSection by mutableStateOf<String?>(null)
        private set
    var targetItemId by mutableStateOf<String?>(null)
        private set

    fun issue(ticket: HomeFocusTicket) {
        currentTicket = ticket
        issueTime = System.currentTimeMillis()
        Log.i("KomorebiFocus", "🎟️ HomeTicket ISSUED: $ticket")
    }

    fun issueForHomeRestore(section: String, itemId: String) {
        targetSection = section
        targetItemId = itemId
        currentTicket = HomeFocusTicket.HOME_RESTORE
        issueTime = System.currentTimeMillis()
        Log.i(
            "KomorebiFocus",
            "🎟️ HomeTicket ISSUED: HOME_RESTORE (Section: $section, ItemID: $itemId)"
        )
    }

    fun consume(ticket: HomeFocusTicket) {
        if (currentTicket == ticket) {
            Log.i("KomorebiFocus", "🗑️ HomeTicket CONSUMED: $ticket")
            currentTicket = HomeFocusTicket.NONE
            targetSection = null
            targetItemId = null
        }
    }
}

@Composable
fun rememberHomeFocusTicketManager() = remember { HomeFocusTicketManager() }

/**
 * HomeLauncherScreen の UI状態とビジネスロジックを管理する State Holder
 */
@Stable
class HomeLauncherState(
    initialTabIndex: Int,
) {
    // --- 内部UI状態 ---
    var selectedTabIndex by mutableIntStateOf(initialTabIndex)
    var internalLastPlayerChannelId by mutableStateOf<String?>(null)
    var isEpgJumping by mutableStateOf(false)
    var topNavHasFocus by mutableStateOf(false)
    var isCurrentTabContentReady by mutableStateOf(false)

    // --- データ保持 ---
    var watchHistory by mutableStateOf<List<KonomiHistoryProgram>>(emptyList())
    var lastChannels by mutableStateOf<List<Channel>>(emptyList())
    var recentRecordings by mutableStateOf<List<RecordedProgram>>(emptyList())
    var isLoadingInitial by mutableStateOf(false)
    var isLoadingMore by mutableStateOf(false)
    var reserves by mutableStateOf<List<ReserveItem>>(emptyList())

    var hotChannels by mutableStateOf<List<UiChannelState>>(emptyList())
    var upcomingReserves by mutableStateOf<List<ReserveItem>>(emptyList())
    var genrePickup by mutableStateOf<List<Pair<EpgProgram, String>>>(emptyList())
    var pickupGenreLabel by mutableStateOf("アニメ")
    var genrePickupTimeSlot by mutableStateOf("夜")

    var epgUiState by mutableStateOf<EpgUiState>(EpgUiState.Loading)
    var logoUrls by mutableStateOf<List<String>>(emptyList())

    var openedSeriesTitle by mutableStateOf<String?>(null)
    var isSeriesListOpen by mutableStateOf(false)

    // タブ定義とサイズを一致させ、魔法の数字(10)による索引ズレを防ぐ。
    val tabFocusRequesters = List(HOME_TAB_TITLES.size) { FocusRequester() }
    val contentFirstItemRequesters = List(HOME_TAB_TITLES.size) { FocusRequester() }
    val settingsFocusRequester = FocusRequester()

    val safeHouseRequester = FocusRequester()

    val watchHistoryPrograms: List<RecordedProgram>
        @RequiresApi(Build.VERSION_CODES.O) get() = watchHistory.map {
            KonomiDataMapper.toDomainModel(it)
        }

    @RequiresApi(Build.VERSION_CODES.O)
    fun onTabSelected(
        index: Int,
        onTabChange: (Int) -> Unit,
        homeViewModel: HomeViewModel,
    ) {
        onTabChange(index)
        isCurrentTabContentReady = false
        homeViewModel.clearFocusMemory()
    }

    fun isFullScreen(
        selectedChannel: Channel?,
        selectedProgram: RecordedProgram?,
        epgSelectedProgram: EpgProgram?,
        isSettingsOpen: Boolean,
        isRecordListOpen: Boolean,
        isReserveOverlayOpen: Boolean
    ): Boolean {
        return selectedChannel != null || selectedProgram != null || epgSelectedProgram != null ||
                isSettingsOpen || isRecordListOpen || isReserveOverlayOpen || isSeriesListOpen
    }

    fun handleBackNavigation(
        onTabChange: (Int) -> Unit,
        onFinalBack: () -> Unit,
        onBackTriggered: () -> Unit,
        requestTopNavFocus: () -> Unit,
        escapeToSafeHouse: () -> Unit
    ) {
        if (!topNavHasFocus) {
            escapeToSafeHouse()
            requestTopNavFocus()
            onBackTriggered()
        } else if (selectedTabIndex != 0) {
            escapeToSafeHouse()
            selectedTabIndex = 0
            onTabChange(0)
            requestTopNavFocus()
            onBackTriggered()
        } else {
            onFinalBack()
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun rememberHomeLauncherState(
    initialTabIndex: Int,
    channelViewModel: ChannelViewModel,
    homeViewModel: HomeViewModel,
    epgViewModel: EpgViewModel,
    recordViewModel: RecordViewModel,
    reserveViewModel: ReserveViewModel
): HomeLauncherState {

    val state = rememberSaveable(
        saver = Saver(
            save = {
                listOf(
                    it.selectedTabIndex,
                    it.internalLastPlayerChannelId,
                    it.openedSeriesTitle,
                    it.isSeriesListOpen
                )
            },
            restore = {
                @Suppress("UNCHECKED_CAST")
                val list = it as List<Any?>
                HomeLauncherState(list[0] as Int).apply {
                    internalLastPlayerChannelId = list[1] as String?
                    openedSeriesTitle = list[2] as String?
                    isSeriesListOpen = list[3] as Boolean
                }
            }
        )
    ) {
        HomeLauncherState(initialTabIndex)
    }

    LaunchedEffect(initialTabIndex) {
        state.selectedTabIndex = initialTabIndex
    }

    state.watchHistory = homeViewModel.watchHistory.collectAsState().value
    state.lastChannels = homeViewModel.lastWatchedChannelFlow.collectAsState().value
    state.recentRecordings = recordViewModel.recentRecordings.collectAsState().value
    state.isLoadingInitial = recordViewModel.isRecordingLoading.collectAsState().value
    state.isLoadingMore = recordViewModel.isLoadingMore.collectAsState().value
    state.reserves = reserveViewModel.reserves.collectAsState().value

    val liveRows by channelViewModel.liveRows.collectAsState()
    state.hotChannels = remember(liveRows) { homeViewModel.getHotChannels(liveRows) }
    state.upcomingReserves =
        remember(state.reserves) { homeViewModel.getUpcomingReserves(state.reserves) }
    state.genrePickup = homeViewModel.genrePickupPrograms.collectAsState().value
    state.pickupGenreLabel = homeViewModel.pickupGenreLabel.collectAsState().value
    state.genrePickupTimeSlot = homeViewModel.genrePickupTimeSlot.collectAsState().value

    state.epgUiState = epgViewModel.uiState
    state.logoUrls = remember(state.epgUiState) {
        val eData = state.epgUiState
        if (eData is EpgUiState.Success) eData.logoUrls else emptyList()
    }

    return state
}
