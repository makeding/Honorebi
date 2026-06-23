package com.beeregg2001.komorebi.viewmodel

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.mapper.KonomiDataMapper
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.repository.AppContentStore
import com.beeregg2001.komorebi.data.repository.ChannelLogoCache
import com.beeregg2001.komorebi.data.repository.WatchHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class ChannelViewModel @Inject constructor(
    private val watchHistoryRepository: WatchHistoryRepository,
    private val channelLogoCache: ChannelLogoCache,
    private val appContentStore: AppContentStore
) : ViewModel() {

    val isLoading: StateFlow<Boolean> = appContentStore.isChannelsLoading

    val liveRows: StateFlow<List<LiveRowState>> = appContentStore.liveRows

    val groupedChannels: StateFlow<Map<String, List<Channel>>> = appContentStore.groupedChannels

    private val baseballKeywords = listOf(
        "阪神", "タイガース", "広島", "カープ", "DeNA", "ベイスターズ",
        "巨人", "ジャイアンツ", "ヤクルト", "スワローズ", "中日", "ドラゴンズ",
        "オリックス", "バファローズ", "ロッテ", "マリーンズ", "ソフトバンク", "ホークス",
        "楽天", "イーグルス", "西武", "ライオンズ", "日本ハム", "ファイターズ", "プロ野球"
    )

    private val excludeKeywords = listOf(
        "プロ野球ニュース", "すぽると", "熱闘", "ダイジェスト", "ハイライト",
        "特集", "傑作選", "名勝負", "セレクション", "回顧", "伝説", "競馬"
    )

    private val matchKeywords = listOf(
        "ナイター", "デーゲーム", "ベースボール", "プロ野球中継", "実況中継",
        "ガオトラ", "オープン戦", "公式戦", "クライマックスシリーズ", "日本シリーズ"
    )

    private val versusSymbols = listOf("対", "×", "vs", "VS", "-", "ー")

    val baseballGroupedChannels: StateFlow<Map<String, List<Channel>>> =
        groupedChannels.map { grouped ->
            grouped.mapValues { (_, channels) ->
                channels.filter { ch ->
                    val presentTitle = ch.programPresent?.title ?: ""
                    val presentDesc = ch.programPresent?.description ?: ""
                    val followingTitle = ch.programFollowing?.title ?: ""
                    val followingDesc = ch.programFollowing?.description ?: ""

                    fun isBaseballGame(title: String, desc: String): Boolean {
                        if (title.isBlank()) return false

                        val fullText = "$title $desc"

                        val hasKeyword = baseballKeywords.any { keyword ->
                            fullText.contains(keyword)
                        }
                        if (!hasKeyword) return false

                        val isExcluded = excludeKeywords.any { keyword ->
                            title.contains(keyword)
                        }
                        if (isExcluded) return false

                        val hasVersusSymbol = versusSymbols.any { title.contains(it) }
                        val hasGenericLiveWord =
                            title.contains("中継") || title.contains("生") || title.contains(
                                "LIVE",
                                ignoreCase = true
                            )

                        if (hasVersusSymbol && hasGenericLiveWord) return true

                        val isStrongMatch = matchKeywords.any { keyword ->
                            title.contains(keyword, ignoreCase = true) || desc.contains(
                                keyword,
                                ignoreCase = true
                            )
                        }
                        if (isStrongMatch) return true

                        if (title.length <= 15 && title.contains("プロ野球") && hasGenericLiveWord) return true

                        return false
                    }

                    isBaseballGame(presentTitle, presentDesc) || isBaseballGame(
                        followingTitle,
                        followingDesc
                    )
                }
            }.filterValues { it.isNotEmpty() }

        }
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyMap()
            )

    val recentRecordings: StateFlow<List<RecordedProgram>> = appContentStore.recentRecordings

    val isRecordingLoading: StateFlow<Boolean> = appContentStore.isRecordingsLoading

    val connectionError: StateFlow<Boolean> = appContentStore.connectionError

    private var isPollingPaused = false

    val channelLogoUrls: StateFlow<Map<String, String>> = channelLogoCache.channelLogoUrls

    fun setPollingPaused(paused: Boolean) {
        if (isPollingPaused != paused) {
            isPollingPaused = paused
            appContentStore.setPollingPaused(paused)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun fetchChannels() {
        appContentStore.refreshChannels()
    }

    fun fetchRecentRecordings() {
        appContentStore.refreshRecentRecordings()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun startPolling() {
        appContentStore.setPollingPaused(false)
    }

    fun stopPolling() {
        appContentStore.setPollingPaused(true)
    }

    fun saveToHistory(program: RecordedProgram) {
        viewModelScope.launch {
            val entity = KonomiDataMapper.toEntity(program)
            watchHistoryRepository.saveToLocalHistory(entity)
        }
    }

    fun prefetchChannelLogoUrls(channels: Collection<Channel>) {
        viewModelScope.launch(Dispatchers.IO) {
            channelLogoCache.prefetchChannelLogoUrls(channels)
        }
    }

    suspend fun getChannelLogoUrl(channel: Channel): String =
        channelLogoCache.getChannelLogoUrl(channel)

    suspend fun getChannelLogoUrl(channelId: String): String =
        channelLogoCache.getChannelLogoUrl(channelId)
}
