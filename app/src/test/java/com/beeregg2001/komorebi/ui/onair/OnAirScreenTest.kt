package com.beeregg2001.komorebi.ui.onair

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.beeregg2001.komorebi.data.model.OnAirSeries
import com.beeregg2001.komorebi.data.model.OnAirSeriesApiResponse
import com.beeregg2001.komorebi.data.model.RecordedApiResponse
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
class OnAirScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun weekdayCountsAndExpansionUseStableSeriesTags() {
        val vm = OnAirViewModel(FakeProvider(), MutableStateFlow(OnAirBackendConfiguration("KONOMITV", "tv", "7000", "")))
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        compose.setContent { KomorebiTheme { OnAirScreen("tv", "7000", "24H", {}, {}, {}, FocusRequester(), false, {}, vm) } }
        compose.onNodeWithTag("onair-day-0").assertIsDisplayed()
        compose.onNodeWithTag("onair-series-1").performClick()
        compose.onNodeWithTag("onair-details").assertIsDisplayed()
    }

    private class FakeProvider : OnAirProvider {
        override suspend fun getOnAirSeries() = OnAirSeriesApiResponse(listOf(
            OnAirSeries(1, "月曜作品", emptyList(), emptyList(), 0, 0, 0, 0, "23:30", "2026-09-13T23:30:00+09:00"),
            OnAirSeries(2, "火曜作品", emptyList(), emptyList(), 0, 0, 0, 1, "01:00", "2026-09-13T01:00:00+09:00"),
        ))
        override suspend fun getSeriesSummary(seriesId: Int) = SeriesProgram(seriesId, "概要")
        override suspend fun getRecordedProgramsBySeries(seriesId: Int, page: Int, order: String) = RecordedApiResponse(0, emptyList())
    }
}
