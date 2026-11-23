package com.beeregg2001.komorebi.ui.reserve

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
// ★ 追加・変更: 標準の Lazy系のインポート。items も明示的にインポートすることで Argument type mismatch を防ぎます
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.ReservationCondition
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.ui.components.KeywordConditionCard
import com.beeregg2001.komorebi.ui.components.ReserveCard
import com.beeregg2001.komorebi.viewmodel.ReserveViewModel
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.viewmodel.ChannelViewModel
import kotlinx.coroutines.launch

private const val TAG = "ReserveListScreen"

enum class ReserveFocusTicket { NONE, TARGET_RESERVE_ID, TARGET_CONDITION_ID, CONTENT_TOP }

@Stable
class ReserveFocusTicketManager {
    var currentTicket by mutableStateOf(ReserveFocusTicket.NONE)
        private set
    var issueTime by mutableLongStateOf(0L)
        private set
    var targetReserveId by mutableStateOf<Int?>(null)
        private set
    var targetConditionId by mutableStateOf<Int?>(null)
        private set

    fun issueForReserve(reserveId: Int) {
        targetReserveId = reserveId
        currentTicket = ReserveFocusTicket.TARGET_RESERVE_ID
        issueTime = System.currentTimeMillis()
        Log.i("KomorebiFocus", "🎟️ ReserveTicket ISSUED: TARGET_RESERVE_ID ($reserveId)")
    }

    fun issueForCondition(conditionId: Int) {
        targetConditionId = conditionId
        currentTicket = ReserveFocusTicket.TARGET_CONDITION_ID
        issueTime = System.currentTimeMillis()
        Log.i("KomorebiFocus", "🎟️ ReserveTicket ISSUED: TARGET_CONDITION_ID ($conditionId)")
    }

    fun issueForTop() {
        currentTicket = ReserveFocusTicket.CONTENT_TOP
        issueTime = System.currentTimeMillis()
        Log.i("KomorebiFocus", "🎟️ ReserveTicket ISSUED: CONTENT_TOP (Fallback)")
    }

    fun consume(ticket: ReserveFocusTicket) {
        if (currentTicket == ticket) {
            Log.i("KomorebiFocus", "🗑️ ReserveTicket CONSUMED: $currentTicket")
            currentTicket = ReserveFocusTicket.NONE
            targetReserveId = null
            targetConditionId = null
        }
    }
}

@Composable
fun rememberReserveFocusTicketManager() = remember { ReserveFocusTicketManager() }

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun ReserveListScreen(
    onBack: () -> Unit,
    onProgramClick: (ReserveItem) -> Unit,
    onConditionClick: (ReservationCondition) -> Unit = {},
    konomiIp: String,
    konomiPort: String,
    groupedChannels: Map<String, List<Channel>> = emptyMap(),
    contentFirstItemRequester: FocusRequester? = null,
    topNavFocusRequester: FocusRequester? = null,
    topBarFocusRequester: FocusRequester? = null,
    isReserveOverlayOpen: Boolean = false,
    isTopNavFocused: Boolean = false,
    isReturningFromPlayer: Boolean = false,
    onReturnFocusConsumed: () -> Unit = {},
    viewModel: ReserveViewModel = hiltViewModel(),
    channelViewModel: ChannelViewModel = hiltViewModel(),
    timeFormat: String = "24H",
    // ★ 追加(Step3): AIコンシェルジュからの復帰シグナル
    aiFocusReturnTick: Int = 0,
    onAiReturnConsumed: () -> Unit = {},
    onContentReady: () -> Unit = {},
) {
    val reserves by viewModel.reserves.collectAsState()
    val normalReserves by viewModel.normalReserves.collectAsState()
    val conditions by viewModel.conditions.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val colors = KomorebiTheme.colors

    val selectedTabIndex by viewModel.selectedTabIndex.collectAsState()
    val tabs = listOf("全ての予約", "通常予約", "キーワード自動予約")

    val listFocusRequester = remember { FocusRequester() }
    val tabFocusRequesters = remember { List(tabs.size) { FocusRequester() } }

    val ticketManager = rememberReserveFocusTicketManager()

    val scope = rememberCoroutineScope()

    // ★ 変更: rememberTvLazyListState -> rememberLazyListState
    val allListState = rememberLazyListState()
    val normalListState = rememberLazyListState()
    val conditionListState = rememberLazyListState()

    var previousOverlayOpen by remember { mutableStateOf(isReserveOverlayOpen) }
    var lastFocusedIndex by remember { mutableIntStateOf(0) }
    var listAreaHasFocus by remember { mutableStateOf(false) }

    val currentIds = remember(selectedTabIndex, reserves, normalReserves, conditions) {
        when (selectedTabIndex) {
            0 -> reserves.map { it.id }
            1 -> normalReserves.map { it.id }
            else -> conditions.map { it.id }
        }
    }
    val showBlockingSpinner = isLoading && currentIds.isEmpty()

    LaunchedEffect(Unit) {
        viewModel.refreshAll()
    }

    // ★ 追加(Step3): AIコンシェルジュから戻ってきた時のフォーカス復元（チケット発行）
    LaunchedEffect(aiFocusReturnTick) {
        if (aiFocusReturnTick > 0) {
            Log.i("KomorebiFocus", "[ReserveList] AIコンシェルジュから復帰。チケットを発行します。")
            val conditionId = viewModel.lastClickedConditionId
            val reserveId = viewModel.lastClickedReserveId
            if (conditionId != null) {
                ticketManager.issueForCondition(conditionId)
            } else if (reserveId != null) {
                ticketManager.issueForReserve(reserveId)
            } else {
                ticketManager.issueForTop()
            }
            onAiReturnConsumed()
        }
    }

    LaunchedEffect(isReturningFromPlayer) {
        if (isReturningFromPlayer) {
            Log.i("KomorebiFocus", "[ReserveList] プレイヤーから復帰しました。チケットを発行します。")
            val conditionId = viewModel.lastClickedConditionId
            val reserveId = viewModel.lastClickedReserveId
            if (conditionId != null) {
                ticketManager.issueForCondition(conditionId)
            } else if (reserveId != null) {
                ticketManager.issueForReserve(reserveId)
            } else {
                contentFirstItemRequester?.safeRequestFocusWithRetry("ReserveListFallback")
            }
            onReturnFocusConsumed()
        }
    }

    LaunchedEffect(isReserveOverlayOpen) {
        if (previousOverlayOpen && !isReserveOverlayOpen) {
            Log.i("KomorebiFocus", "[ReserveList] オーバーレイが閉じました。チケットを発行します。")
            withFrameNanos { }
            val conditionId = viewModel.lastClickedConditionId
            val reserveId = viewModel.lastClickedReserveId
            if (conditionId != null) {
                ticketManager.issueForCondition(conditionId)
            } else if (reserveId != null) {
                ticketManager.issueForReserve(reserveId)
            } else {
                tabFocusRequesters[selectedTabIndex].safeRequestFocusWithRetry("ReserveListOverlayFallback")
            }
        }
        previousOverlayOpen = isReserveOverlayOpen
    }

    var wasBlockingLoading by remember { mutableStateOf(showBlockingSpinner) }

    LaunchedEffect(showBlockingSpinner) {
        if (isReserveOverlayOpen) {
            wasBlockingLoading = showBlockingSpinner
            return@LaunchedEffect
        }
        if (wasBlockingLoading && !showBlockingSpinner && !isTopNavFocused && ticketManager.currentTicket == ReserveFocusTicket.NONE) {
            val condId = viewModel.lastClickedConditionId
            val resId = viewModel.lastClickedReserveId
            when {
                condId != null -> ticketManager.issueForCondition(condId)
                resId != null -> ticketManager.issueForReserve(resId)
                else -> ticketManager.issueForTop()
            }
        }
        wasBlockingLoading = showBlockingSpinner
    }

    // 録画完了などでフォーカス中の予約アイテムがリストから消えた場合、即座に近傍アイテムへフォーカスを救済する
    LaunchedEffect(currentIds, selectedTabIndex) {
        if (showBlockingSpinner || isTopNavFocused || isReserveOverlayOpen) return@LaunchedEffect
        if (ticketManager.currentTicket != ReserveFocusTicket.NONE) return@LaunchedEffect
        val trackedId = if (selectedTabIndex == 2) viewModel.lastClickedConditionId else viewModel.lastClickedReserveId
        if (trackedId != null && trackedId !in currentIds) {
            Log.w(
                "KomorebiFocus",
                "[ReserveList] フォーカス中のアイテム(id=$trackedId)がリストから消滅したため近傍へ復帰します"
            )
            if (currentIds.isNotEmpty()) {
                val fallbackId = currentIds[lastFocusedIndex.coerceIn(0, currentIds.lastIndex)]
                if (selectedTabIndex == 2) {
                    viewModel.lastClickedConditionId = fallbackId
                    ticketManager.issueForCondition(fallbackId)
                } else {
                    viewModel.lastClickedReserveId = fallbackId
                    ticketManager.issueForReserve(fallbackId)
                }
            } else {
                viewModel.clearFocusMemory()
                withFrameNanos { }
                tabFocusRequesters[selectedTabIndex].safeRequestFocusWithRetry("ReserveListEmptyFallback")
            }
        } else if (listAreaHasFocus && currentIds.isEmpty()) {
            withFrameNanos { }
            tabFocusRequesters[selectedTabIndex].safeRequestFocusWithRetry("ReserveListClearedFallback")
        }
    }

    LaunchedEffect(
        ticketManager.currentTicket,
        ticketManager.issueTime,
        showBlockingSpinner,
        reserves.size,
        normalReserves.size,
        conditions.size
    ) {
        if (showBlockingSpinner) return@LaunchedEffect

        when (ticketManager.currentTicket) {
            ReserveFocusTicket.TARGET_RESERVE_ID -> {
                val targetId = ticketManager.targetReserveId
                if (targetId != null) {
                    var isFound = false
                    val activeList = when (selectedTabIndex) {
                        0 -> reserves
                        1 -> normalReserves
                        else -> emptyList()
                    }
                    when (selectedTabIndex) {
                        0 -> {
                            val index = reserves.indexOfFirst { it.id == targetId }
                            if (index != -1) {
                                allListState.scrollToItem(maxOf(0, index - 1))
                                isFound = true
                            }
                        }

                        1 -> {
                            val index = normalReserves.indexOfFirst { it.id == targetId }
                            if (index != -1) {
                                normalListState.scrollToItem(maxOf(0, index - 1))
                                isFound = true
                            }
                        }
                    }
                    if (!isFound) {
                        if (activeList.isNotEmpty()) {
                            val fallbackItem = activeList[lastFocusedIndex.coerceIn(0, activeList.lastIndex)]
                            Log.w(
                                "KomorebiFocus",
                                "[ReserveList] 対象の予約が見つかりません(削除/録画終了)。近傍(id=${fallbackItem.id})へ復帰します。"
                            )
                            viewModel.lastClickedReserveId = fallbackItem.id
                            ticketManager.issueForReserve(fallbackItem.id)
                        } else {
                            Log.w(
                                "KomorebiFocus",
                                "[ReserveList] 対象の予約が見つかりません(リスト空)。タブへ戻ります。"
                            )
                            ticketManager.issueForTop()
                        }
                    }
                }
            }

            ReserveFocusTicket.TARGET_CONDITION_ID -> {
                val targetId = ticketManager.targetConditionId
                if (targetId != null) {
                    var isFound = false
                    when (selectedTabIndex) {
                        2 -> {
                            val index = conditions.indexOfFirst { it.id == targetId }
                            if (index != -1) {
                                conditionListState.scrollToItem(maxOf(0, index - 1))
                                isFound = true
                            }
                        }
                    }
                    if (!isFound) {
                        if (conditions.isNotEmpty()) {
                            val fallbackCond = conditions[lastFocusedIndex.coerceIn(0, conditions.lastIndex)]
                            Log.w(
                                "KomorebiFocus",
                                "[ReserveList] 対象の条件が見つかりません(削除済)。近傍(id=${fallbackCond.id})へ復帰します。"
                            )
                            viewModel.lastClickedConditionId = fallbackCond.id
                            ticketManager.issueForCondition(fallbackCond.id)
                        } else {
                            Log.w(
                                "KomorebiFocus",
                                "[ReserveList] 対象の条件が見つかりません(リスト空)。タブへ戻ります。"
                            )
                            ticketManager.issueForTop()
                        }
                    }
                }
            }

            ReserveFocusTicket.CONTENT_TOP -> {
                if (isTopNavFocused) {
                    ticketManager.consume(ReserveFocusTicket.CONTENT_TOP)
                    return@LaunchedEffect
                }
                Log.i(
                    "KomorebiFocus",
                    "[ReserveList] CONTENT_TOP へのスクロールとフォーカスを実行します。"
                )
                when (selectedTabIndex) {
                    0 -> allListState.scrollToItem(0)
                    1 -> normalListState.scrollToItem(0)
                    2 -> conditionListState.scrollToItem(0)
                }
                withFrameNanos { }
                val focused = listFocusRequester.safeRequestFocusWithRetry("ReserveContentTop")
                if (!focused) {
                    tabFocusRequesters[selectedTabIndex].safeRequestFocusWithRetry("ReserveContentTopFallback")
                }
                ticketManager.consume(ReserveFocusTicket.CONTENT_TOP)
                viewModel.clearFocusMemory()
            }

            else -> {}
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 32.dp, end = 32.dp, top = 68.dp, bottom = 20.dp)
            .onKeyEvent { event ->
                if (event.key == Key.Back) {
                    if (event.type == KeyEventType.KeyDown) return@onKeyEvent true
                    if (event.type == KeyEventType.KeyUp) {
                        Log.i(
                            "KomorebiFocus",
                            "[ReserveList] 戻るキーを処理 -> onBack() へ委譲し、記憶をリセット"
                        )

                        viewModel.clearFocusMemory()
                        onBack()
                        return@onKeyEvent true
                    }
                }
                false
            }
    ) {
        if (contentFirstItemRequester != null) {
            Box(
                modifier = Modifier
                    .size(1.dp)
                    .alpha(0f)
                    .focusRequester(contentFirstItemRequester)
                    .onFocusChanged {
                        if (it.isFocused) {
                            Log.i(
                                "KomorebiFocus",
                                "[ReserveList] TopNavからDOWN -> 内部タブへフォーカスを転送します"
                            )
                            scope.launch {
                                tabFocusRequesters[selectedTabIndex].safeRequestFocusWithRetry("TopNavToTab")
                            }
                        }
                    }
                    .focusable()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .focusGroup()
                .focusProperties {
                    enter = { tabFocusRequesters[selectedTabIndex] }
                },
            contentAlignment = Alignment.Center
        ) {
            TabRow(
                selectedTabIndex = selectedTabIndex,
                indicator = { tabPositions, doesTabRowHaveFocus ->
                    TabRowDefaults.UnderlinedIndicator(
                        currentTabPosition = tabPositions[selectedTabIndex],
                        doesTabRowHaveFocus = doesTabRowHaveFocus,
                        activeColor = colors.accent,
                        inactiveColor = Color.Transparent
                    )
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = index == selectedTabIndex,
                        onFocus = {
                            viewModel.updateTabIndex(index)
                            viewModel.clearFocusMemory()
                        },
                        onClick = {
                            viewModel.updateTabIndex(index)
                            viewModel.clearFocusMemory()
                        },
                        modifier = Modifier
                            .focusRequester(tabFocusRequesters[index])
                            .then(
                                if (index == selectedTabIndex) {
                                    Modifier.onGloballyPositioned { onContentReady() }
                                } else Modifier
                            )
                            .focusProperties {
                                up = topBarFocusRequester ?: topNavFocusRequester ?: FocusRequester.Default
                                down = if (!showBlockingSpinner) listFocusRequester else FocusRequester.Cancel
                                if (index == 0) left = topNavFocusRequester ?: FocusRequester.Cancel
                                if (index == tabs.lastIndex) right = FocusRequester.Cancel
                            }
                            .onKeyEvent { event ->
                                if (event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown) {
                                    Log.i(
                                        "KomorebiFocus",
                                        "[ReserveList] 内部タブから上キー -> TopBar/TopNavへ強制移動"
                                    )
                                    (topBarFocusRequester ?: topNavFocusRequester)?.requestFocus()
                                    viewModel.clearFocusMemory()
                                    return@onKeyEvent true
                                }
                                false
                            },
                        colors = TabDefaults.underlinedIndicatorTabColors(
                            contentColor = colors.textSecondary,
                            selectedContentColor = colors.accent,
                            focusedContentColor = colors.textPrimary,
                            focusedSelectedContentColor = colors.textPrimary
                        )
                    ) {
                        Text(
                            text = title,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (showBlockingSpinner) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.textPrimary)
            }
        } else {
            AnimatedContent(
                targetState = selectedTabIndex,
                label = "ReserveTabContent"
            ) { targetIndex ->
                when (targetIndex) {
                    0 -> {
                        if (reserves.isEmpty()) {
                            EmptyMessage(
                                message = "現在、予約されている番組はありません。",
                                listFocusRequester = listFocusRequester,
                                targetTabRequester = tabFocusRequesters[0],
                                leftEdgeFocusRequester = topNavFocusRequester ?: FocusRequester.Cancel,
                            )
                        } else {
                            // ★ 変更: TvLazyColumn -> LazyColumn
                            LazyColumn(
                                state = allListState,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 48.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .onFocusChanged { listAreaHasFocus = it.hasFocus }
                                    .focusGroup()
                            ) {
                                itemsIndexed(reserves, key = { _, it -> "all_res_${it.id}" }) { index, program ->
                                    val specificRequester = remember { FocusRequester() }
                                    LaunchedEffect(
                                        ticketManager.currentTicket,
                                        ticketManager.issueTime
                                    ) {
                                        if (ticketManager.currentTicket == ReserveFocusTicket.TARGET_RESERVE_ID && program.id == ticketManager.targetReserveId) {
                                            withFrameNanos { }
                                            specificRequester.safeRequestFocusWithRetry("TargetReserve_All")
                                            ticketManager.consume(ReserveFocusTicket.TARGET_RESERVE_ID)
                                        }
                                    }

                                    ReserveCard(
                                        item = program,
                                        konomiIp = konomiIp,
                                        konomiPort = konomiPort,
                                        onClick = {
                                            lastFocusedIndex = index
                                            viewModel.lastClickedReserveId = program.id
                                            viewModel.lastClickedConditionId = null
                                            onProgramClick(program)
                                        },
                                        modifier = Modifier
                                            .focusRequester(specificRequester)
                                            .then(
                                                if (index == 0) Modifier.focusRequester(listFocusRequester)
                                                else Modifier
                                            )
                                            .focusProperties {
                                                if (index == 0) up = tabFocusRequesters[0]
                                                left = topNavFocusRequester ?: FocusRequester.Cancel
                                                right = FocusRequester.Cancel
                                            }
                                            .onFocusChanged {
                                                if (it.isFocused || it.hasFocus) {
                                                    lastFocusedIndex = index
                                                    viewModel.lastClickedReserveId = program.id
                                                    viewModel.lastClickedConditionId = null
                                                }
                                            },
                                        timeFormat = timeFormat,
                                        getLogoUrl = { displayId ->
                                            channelViewModel.getChannelLogoUrl(
                                                displayId
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }

                    1 -> {
                        if (normalReserves.isEmpty()) {
                            EmptyMessage(
                                message = "現在、手動で予約されている番組はありません。",
                                listFocusRequester = listFocusRequester,
                                targetTabRequester = tabFocusRequesters[1],
                                leftEdgeFocusRequester = topNavFocusRequester ?: FocusRequester.Cancel,
                            )
                        } else {
                            // ★ 変更: TvLazyColumn -> LazyColumn
                            LazyColumn(
                                state = normalListState,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 48.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .onFocusChanged { listAreaHasFocus = it.hasFocus }
                                    .focusGroup()
                            ) {
                                itemsIndexed(normalReserves, key = { _, it -> "norm_${it.id}" }) { index, program ->
                                    val specificRequester = remember { FocusRequester() }
                                    LaunchedEffect(
                                        ticketManager.currentTicket,
                                        ticketManager.issueTime
                                    ) {
                                        if (ticketManager.currentTicket == ReserveFocusTicket.TARGET_RESERVE_ID && program.id == ticketManager.targetReserveId) {
                                            withFrameNanos { }
                                            specificRequester.safeRequestFocusWithRetry("TargetReserve_Normal")
                                            ticketManager.consume(ReserveFocusTicket.TARGET_RESERVE_ID)
                                        }
                                    }

                                    ReserveCard(
                                        item = program,
                                        konomiIp = konomiIp,
                                        konomiPort = konomiPort,
                                        onClick = {
                                            lastFocusedIndex = index
                                            viewModel.lastClickedReserveId = program.id
                                            viewModel.lastClickedConditionId = null
                                            onProgramClick(program)
                                        },
                                        modifier = Modifier
                                            .focusRequester(specificRequester)
                                            .then(
                                                if (index == 0) Modifier.focusRequester(listFocusRequester)
                                                else Modifier
                                            )
                                            .focusProperties {
                                                if (index == 0) up = tabFocusRequesters[1]
                                                left = topNavFocusRequester ?: FocusRequester.Cancel
                                                right = FocusRequester.Cancel
                                            }
                                            .onFocusChanged {
                                                if (it.isFocused || it.hasFocus) {
                                                    lastFocusedIndex = index
                                                    viewModel.lastClickedReserveId = program.id
                                                    viewModel.lastClickedConditionId = null
                                                }
                                            },
                                        timeFormat = timeFormat,
                                        getLogoUrl = { displayId ->
                                            channelViewModel.getChannelLogoUrl(
                                                displayId
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }

                    2 -> {
                        if (conditions.isEmpty()) {
                            EmptyMessage(
                                message = "キーワード自動予約の条件は登録されていません。\n番組表の番組詳細から「EPG予約」が可能です。",
                                listFocusRequester = listFocusRequester,
                                targetTabRequester = tabFocusRequesters[2],
                                leftEdgeFocusRequester = topNavFocusRequester ?: FocusRequester.Cancel,
                            )
                        } else {
                            // ★ 変更: TvLazyColumn -> LazyColumn
                            LazyColumn(
                                state = conditionListState,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 48.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .onFocusChanged { listAreaHasFocus = it.hasFocus }
                                    .focusGroup()
                            ) {
                                itemsIndexed(conditions, key = { _, it -> "cond_${it.id}" }) { index, condition ->
                                    val specificRequester = remember { FocusRequester() }
                                    LaunchedEffect(
                                        ticketManager.currentTicket,
                                        ticketManager.issueTime
                                    ) {
                                        if (ticketManager.currentTicket == ReserveFocusTicket.TARGET_CONDITION_ID && condition.id == ticketManager.targetConditionId) {
                                            withFrameNanos { }
                                            specificRequester.safeRequestFocusWithRetry("TargetCondition_Tab2")
                                            ticketManager.consume(ReserveFocusTicket.TARGET_CONDITION_ID)
                                        }
                                    }

                                    KeywordConditionCard(
                                        condition = condition,
                                        onClick = {
                                            lastFocusedIndex = index
                                            viewModel.lastClickedConditionId = condition.id
                                            viewModel.lastClickedReserveId = null
                                            onConditionClick(condition)
                                        },
                                        konomiIp = konomiIp,
                                        konomiPort = konomiPort,
                                        groupedChannels = groupedChannels,
                                        reserves = reserves,
                                        modifier = Modifier
                                            .focusRequester(specificRequester)
                                            .then(
                                                if (index == 0) Modifier.focusRequester(listFocusRequester)
                                                else Modifier
                                            )
                                            .focusProperties {
                                                if (index == 0) up = tabFocusRequesters[2]
                                                left = topNavFocusRequester ?: FocusRequester.Cancel
                                                right = FocusRequester.Cancel
                                            }
                                            .onFocusChanged {
                                                if (it.isFocused || it.hasFocus) {
                                                    lastFocusedIndex = index
                                                    viewModel.lastClickedConditionId = condition.id
                                                    viewModel.lastClickedReserveId = null
                                                }
                                            },
                                        getLogoUrl = { displayId ->
                                            channelViewModel.getChannelLogoUrl(
                                                displayId
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun EmptyMessage(
    message: String,
    listFocusRequester: FocusRequester,
    targetTabRequester: FocusRequester,
    leftEdgeFocusRequester: FocusRequester = FocusRequester.Cancel,
) {
    val colors = KomorebiTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(listFocusRequester)
            .focusProperties {
                up = targetTabRequester
                left = leftEdgeFocusRequester
                right = FocusRequester.Cancel
            }
            .focusable(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            color = colors.textSecondary,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
