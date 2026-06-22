package com.beeregg2001.komorebi.viewmodel

import androidx.lifecycle.ViewModel
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.ReserveItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID
import javax.inject.Inject

sealed class AiConciergeAction {
    data class PlayLive(val channelId: String) : AiConciergeAction()
    data class PlayRecorded(val videoId: Int) : AiConciergeAction()

    data class SearchEpg(
        val keyword: String,
        val genre: String,
        val date: String,
        val isLiveOnly: Boolean,
        val channelName: String
    ) : AiConciergeAction()

    data class SearchRecord(
        val keyword: String,
        val genre: String
    ) : AiConciergeAction()

    data class ReqEpgSearch(
        val keyword: String,
        val genre: String,
        val date: String,
        val isLiveOnly: Boolean,
        val channelName: String
    ) : AiConciergeAction()

    data class ReqRecSearch(
        val keyword: String,
        val genre: String
    ) : AiConciergeAction()

    data class ReserveSingle(val programId: String) : AiConciergeAction()
    data class ReserveAuto(val keyword: String) : AiConciergeAction()
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val isUser: Boolean,
    val text: String,
    val isThinking: Boolean = false,
    val isHidden: Boolean = false
)

data class AiContextData(
    val liveChannels: Map<String, List<Channel>>,
    val groupedSeries: Map<String, List<SeriesInfo>>
)

@HiltViewModel
class AiConciergeViewModel @Inject constructor() : ViewModel() {
    private val disabledMessage = ChatMessage(
        isUser = false,
        text = "AI機能はHonorebiでは無効化されています。"
    )

    private val _chatHistory = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatHistory: StateFlow<List<ChatMessage>> = _chatHistory

    private val _pendingAction = MutableSharedFlow<AiConciergeAction>()
    val pendingAction = _pendingAction.asSharedFlow()

    fun sendTextWithContext(
        userInput: String,
        liveChannels: Map<String, List<Channel>>,
        recentRecordings: List<RecordedProgram>,
        groupedSeries: Map<String, List<SeriesInfo>>,
        activeReserves: List<ReserveItem>
    ) {
        _chatHistory.value = listOf(disabledMessage)
    }

    fun sendAudioWithContext(
        audioBytes: ByteArray,
        liveChannels: Map<String, List<Channel>>,
        recentRecordings: List<RecordedProgram>,
        groupedSeries: Map<String, List<SeriesInfo>>,
        activeReserves: List<ReserveItem>
    ) {
        _chatHistory.value = listOf(disabledMessage)
    }

    fun submitSilentSearchResult(keyword: String, results: List<UiSearchResultItem>) = Unit

    fun submitSilentRecordSearchResult(keyword: String, results: List<RecordedProgram>) = Unit

    fun resetState() {
        _chatHistory.value = emptyList()
    }
}
