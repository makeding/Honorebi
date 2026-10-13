package com.beeregg2001.komorebi.ui.screensaver

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.data.repository.NHKExclusionRepository
import com.beeregg2001.komorebi.data.repository.ReserveProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

data class UpcomingRecording(
    val startsAt: Instant,
    val channelName: String,
    val title: String,
)

sealed interface UpcomingRecordingsState {
    data object Loading : UpcomingRecordingsState
    data class Content(val recordings: List<UpcomingRecording>) : UpcomingRecordingsState
    data object Empty : UpcomingRecordingsState
    data class Error(val detail: String) : UpcomingRecordingsState
}

/** Returns future, enabled reservations in actual start-time order. */
internal fun selectUpcomingRecordings(
    reservations: List<ReserveItem>,
    now: Instant = Instant.now(),
): List<UpcomingRecording> = reservations.mapNotNull { reservation ->
    if (!reservation.recordSettings.isEnabled) return@mapNotNull null
    val start = parseReservationStart(reservation.program.startTime) ?: return@mapNotNull null
    if (!start.isAfter(now)) return@mapNotNull null
    UpcomingRecording(start, reservation.channel.name, reservation.program.title)
}.sortedBy(UpcomingRecording::startsAt).take(3)

private fun parseReservationStart(value: String): Instant? = runCatching {
    OffsetDateTime.parse(value).toInstant()
}.recoverCatching {
    Instant.parse(value)
}.getOrNull()

/** Shared source for the in-app screensaver, preview, and Android Dream. */
@Singleton
class UpcomingRecordingsSource @Inject constructor(
    private val reserveProvider: ReserveProvider,
    private val nhkExclusionRepository: NHKExclusionRepository,
) {
    private val _state = MutableStateFlow<UpcomingRecordingsState>(UpcomingRecordingsState.Loading)
    val state: StateFlow<UpcomingRecordingsState> = _state.asStateFlow()

    suspend fun refresh() {
        try {
            nhkExclusionRepository.state.filter { it.isLoaded }.first()
            reserveProvider.getReserves().fold(
                onSuccess = { reservations ->
                    val visible = reservations.filterNot(nhkExclusionRepository::isExcluded)
                    val upcoming = selectUpcomingRecordings(visible)
                    _state.value = if (upcoming.isEmpty()) UpcomingRecordingsState.Empty
                    else UpcomingRecordingsState.Content(upcoming)
                },
                onFailure = { failure ->
                    _state.value = UpcomingRecordingsState.Error(failure.safeUpcomingDetail())
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            _state.value = UpcomingRecordingsState.Error(failure.safeUpcomingDetail())
        }
    }
}

private fun Throwable.safeUpcomingDetail(): String = when (this) {
    is java.net.UnknownHostException,
    is java.net.ConnectException,
    is java.net.SocketTimeoutException -> "ネットワークに接続できません"
    is UnsupportedOperationException -> "このバックエンドでは録画予定を利用できません"
    is retrofit2.HttpException -> when (code()) {
        401, 403 -> "録画予定の取得に必要な認証を確認してください (HTTP ${code()})"
        else -> "録画予定を取得できません (HTTP ${code()})"
    }
    else -> "録画予定を取得できません"
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface UpcomingRecordingsEntryPoint {
    fun upcomingRecordingsSource(): UpcomingRecordingsSource
}

@Composable
fun rememberUpcomingRecordingsSource(): UpcomingRecordingsSource? {
    val appContext = LocalContext.current.applicationContext
    return remember(appContext) {
        runCatching {
            EntryPointAccessors.fromApplication(
                appContext,
                UpcomingRecordingsEntryPoint::class.java,
            ).upcomingRecordingsSource()
        }.getOrNull()
    }
}

/** Fetches only while the optional panel is both enabled and visible. */
@Composable
fun rememberUpcomingRecordings(
    enabled: Boolean,
    visible: Boolean,
    source: UpcomingRecordingsSource? = rememberUpcomingRecordingsSource(),
): UpcomingRecordingsState? {
    if (!enabled || !visible) return null
    if (source == null) return UpcomingRecordingsState.Error("録画予定を利用できません")
    val state by source.state.collectAsState()
    LaunchedEffect(source, enabled, visible) {
        while (enabled && visible) {
            source.refresh()
            delay(60_000)
        }
    }
    return state
}

@Composable
internal fun UpcomingRecordingsPanel(
    state: UpcomingRecordingsState,
    modifier: Modifier = Modifier,
) {
    val contentColor = androidx.compose.ui.graphics.Color.White
    var clock by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(Instant.now()) }
    LaunchedEffect(state) {
        while (true) {
            clock = Instant.now()
            delay(1_000)
        }
    }
    val renderedState = when (state) {
        is UpcomingRecordingsState.Content -> state.recordings
            .filter { it.startsAt.isAfter(clock) }
            .let { if (it.isEmpty()) UpcomingRecordingsState.Empty else UpcomingRecordingsState.Content(it) }
        else -> state
    }
    Column(
        modifier = modifier
            .testTag("screensaver-upcoming-panel")
            .width(420.dp)
            .height(270.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.58f))
            .padding(horizontal = 28.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Event, contentDescription = null, tint = contentColor)
            Text(
                "これからの録画",
                modifier = Modifier.padding(start = 10.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
            )
        }
        when (renderedState) {
            UpcomingRecordingsState.Loading -> Text("録画予定を読み込み中", style = MaterialTheme.typography.bodyMedium, color = contentColor)
            UpcomingRecordingsState.Empty -> Text("これからの録画予定はありません", style = MaterialTheme.typography.bodyMedium, color = contentColor)
            is UpcomingRecordingsState.Error -> Text(
                "${renderedState.detail}。接続を確認してください",
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )
            is UpcomingRecordingsState.Content -> {
                renderedState.recordings.forEach { recording ->
                    val local = recording.startsAt.atZone(ZoneId.systemDefault())
                    val start = local.format(DateTimeFormatter.ofPattern("M/d (EEE) HH:mm"))
                    Column(Modifier.height(54.dp)) {
                        Text(
                            "$start  ${recording.channelName}",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = contentColor.copy(alpha = 0.85f),
                        )
                        Text(
                            recording.title,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = contentColor,
                        )
                    }
                }
                repeat(3 - renderedState.recordings.size) { Spacer(Modifier.height(54.dp)) }
            }
        }
    }
}
