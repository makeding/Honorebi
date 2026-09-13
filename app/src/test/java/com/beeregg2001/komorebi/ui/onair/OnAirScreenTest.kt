package com.beeregg2001.komorebi.ui.onair

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import com.beeregg2001.komorebi.data.model.OnAirSeries
import com.beeregg2001.komorebi.data.model.OnAirSeriesApiResponse
import com.beeregg2001.komorebi.data.model.RecordedApiResponse
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import com.beeregg2001.komorebi.data.model.SeriesProgram
import com.beeregg2001.komorebi.data.repository.OnAirProvider
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.viewmodel.OnAirBackendConfiguration
import com.beeregg2001.komorebi.viewmodel.OnAirViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land")
class OnAirScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun weekdayCountsAndExpansionUseStableSeriesTags() {
        val vm = OnAirViewModel(FakeProvider(), MutableStateFlow(OnAirBackendConfiguration("KONOMITV", "tv", "7000", "")))
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        compose.setContent { KomorebiTheme { OnAirScreen("tv", "7000", "24H", {}, {}, {}, FocusRequester(), false, {}, vm) } }
        compose.onNodeWithTag("onair-day-0").assertIsDisplayed()
        compose.onNodeWithTag("onair-day-0").confirm()
        compose.onNodeWithTag("onair-series-1").confirm()
        compose.onNodeWithTag("onair-details").assertIsDisplayed()
    }

    @Test fun dpadDayChangesOnlyAfterConfirm_thenDetailBackRestoresCardFocus() {
        val vm = viewModel()
        compose.setContent { screen(vm) }
        compose.onNodeWithTag("onair-day-0").confirm().requestFocus().performKeyInput { keyDown(Key.DirectionRight); keyUp(Key.DirectionRight) }
        compose.onNodeWithTag("onair-day-1").assertIsFocused().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.onNodeWithTag("onair-series-2").assertIsDisplayed().requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.onNodeWithTag("onair-series-2").performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        compose.onNodeWithTag("onair-summary").assertIsFocused().performKeyInput { keyDown(Key.Back); keyUp(Key.Back) }
        compose.onNodeWithTag("onair-series-2").assertIsFocused()
    }

    @Test fun unsupportedAndEmptyStatesAreVisible() {
        val unsupported = OnAirViewModel(FakeProvider(), MutableStateFlow(OnAirBackendConfiguration("EDCB", "tv", "7000", "")))
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        compose.setContent { screen(unsupported) }
        compose.onNodeWithTag("onair-day-0").assertIsDisplayed()
        compose.onNodeWithTag("onair-grid").assertDoesNotExist()
    }

    @Test fun emptyAndRetryFailureStatesKeepRecoveryVisible() {
        val empty = OnAirViewModel(FakeProvider(empty = true), MutableStateFlow(OnAirBackendConfiguration("KONOMITV", "tv", "7000", "")))
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        compose.setContent { screen(empty) }
        compose.onNodeWithTag("onair-grid").assertDoesNotExist()
    }

    @Test fun playbackReturnRestoresSameCompositeEpisodeCell() {
        val vm = viewModel()
        val returning = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent { KomorebiTheme { OnAirScreen("tv", "7000", "24H", {}, {}, {}, FocusRequester(), returning.value, { returning.value = false }, vm) } }
        compose.onNodeWithTag("onair-day-0").confirm()
        compose.onNodeWithTag("onair-series-1").confirm()
        compose.onNodeWithTag("onair-series-1").performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        val key = "1:ch:episode:1:10"
        compose.onNodeWithTag("onair-episode-$key").requestFocus().confirm()
        compose.runOnIdle { returning.value = true }
        compose.onNodeWithTag("onair-episode-$key").assertIsFocused()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.confirm() =
        requestFocus().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }

    private fun viewModel() = OnAirViewModel(FakeProvider(), MutableStateFlow(OnAirBackendConfiguration("KONOMITV", "tv", "7000", ""))).also {
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    }
    @androidx.compose.runtime.Composable private fun screen(vm: OnAirViewModel) = KomorebiTheme { OnAirScreen("tv", "7000", "24H", {}, {}, {}, FocusRequester(), false, {}, vm) }

    private class FakeProvider(private val empty: Boolean = false) : OnAirProvider {
        override suspend fun getOnAirSeries() = OnAirSeriesApiResponse(listOf(
            OnAirSeries(1, "月曜作品", emptyList(), emptyList(), 0, 0, 0, 0, "23:30", "2026-09-13T23:30:00+09:00"),
            OnAirSeries(2, "火曜作品", emptyList(), emptyList(), 0, 0, 0, 1, "01:00", "2026-09-13T01:00:00+09:00"),
        ).takeUnless { empty } ?: emptyList())
        override suspend fun getSeriesSummary(seriesId: Int) = SeriesProgram(seriesId, "概要")
        override suspend fun getRecordedProgramsBySeries(seriesId: Int, page: Int, order: String) = if (seriesId == 1 && page == 1) RecordedApiResponse(1, listOf(program())) else RecordedApiResponse(0, emptyList())
        private fun program() = RecordedProgram(10, "第1話", seriesId = 1, episodeNumber = "1", description = "", startTime = "2026-09-13T00:00:00+09:00", endTime = "2026-09-13T00:30:00+09:00", duration = 1800.0, isPartiallyRecorded = false, channel = com.beeregg2001.komorebi.data.model.RecordedChannel("ch", displayChannelId = "ch", type = "GR", name = "局", channelNumber = "1"), recordedVideo = RecordedVideo(10, "Recorded", "/10.ts", null, null, 1800.0, "MPEG-TS", "H.264", "AAC"))
    }
}
