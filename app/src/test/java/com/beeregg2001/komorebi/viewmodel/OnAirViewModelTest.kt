package com.beeregg2001.komorebi.viewmodel

import com.beeregg2001.komorebi.data.model.OnAirSeriesApiResponse
import com.beeregg2001.komorebi.data.model.RecordedApiResponse
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import com.beeregg2001.komorebi.data.model.SeriesProgram
import com.beeregg2001.komorebi.data.repository.OnAirProvider
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@RunWith(RobolectricTestRunner::class)
class OnAirViewModelTest {
    @Test
    fun refreshRejectsANonCooperativeLateListResponse() {
        val provider = FakeOnAirProvider()
        val configurations = MutableStateFlow(konomiConfiguration())
        val viewModel = OnAirViewModel(provider, configurations)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        viewModel.refresh()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        provider.resumeFirstList(seriesResponse(1, "古い結果"))
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals("新しい結果", viewModel.uiState.value.series.single().title)
    }

    @Test
    fun backendSwitchCancelsAndClearsTheOnAirSession() {
        val configurations = MutableStateFlow(konomiConfiguration())
        val viewModel = OnAirViewModel(FakeOnAirProvider(), configurations)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        configurations.value = OnAirBackendConfiguration("EDCB", "", "", "")
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertFalse(viewModel.uiState.value.backendSupported)
        assertTrue(viewModel.uiState.value.series.isEmpty())
        assertEquals("ON_AIR_UNSUPPORTED", (viewModel.uiState.value.listStatus as OnAirLoadState.Error).code)
    }

    @Test
    fun expansionPublishesTheMatrixOnlyAfterEveryPageArrives() {
        val provider = FakeOnAirProvider().apply {
            programPages = mapOf(
                1 to RecordedApiResponse(2, listOf(recording(10))),
                2 to RecordedApiResponse(2, listOf(recording(11))),
            )
        }
        val viewModel = OnAirViewModel(provider, MutableStateFlow(konomiConfiguration()))
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        viewModel.expandSeries(42)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val expanded = requireNotNull(viewModel.uiState.value.expanded)
        assertEquals(OnAirLoadState.Ready, expanded.programsStatus)
        assertEquals(listOf(10, 11), expanded.programs.map { it.id })
        assertEquals(listOf(1, 2), provider.requestedPages)
    }

    @Test
    fun duplicateOrIncompletePagesNeverPublishAPartialMatrix() {
        assertIncompletePages(
            mapOf(1 to RecordedApiResponse(2, listOf(recording(10))), 2 to RecordedApiResponse(2, listOf(recording(10)))),
        )
        assertIncompletePages(
            mapOf(1 to RecordedApiResponse(2, listOf(recording(10))), 2 to RecordedApiResponse(2, emptyList())),
        )
        assertIncompletePages(
            mapOf(1 to RecordedApiResponse(2, listOf(recording(10))), 2 to RecordedApiResponse(3, listOf(recording(11)))),
        )
    }

    @Test
    fun summaryRetryIsIndependentFromTheCompletedEpisodeMatrix() {
        val provider = FakeOnAirProvider().apply {
            summaryFailuresRemaining = 1
            programPages = mapOf(1 to RecordedApiResponse(1, listOf(recording(10))))
        }
        val viewModel = OnAirViewModel(provider, MutableStateFlow(konomiConfiguration()))
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        viewModel.expandSeries(42)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertTrue(viewModel.uiState.value.expanded!!.summaryStatus is OnAirLoadState.Error)
        assertEquals(OnAirLoadState.Ready, viewModel.uiState.value.expanded!!.programsStatus)
        viewModel.retrySummary()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals(OnAirLoadState.Ready, viewModel.uiState.value.expanded!!.summaryStatus)
        assertEquals(listOf(10), viewModel.uiState.value.expanded!!.programs.map { it.id })
    }

    @Test
    fun lateDetailForTheSameSeriesCannotOverwriteANewExpansion() {
        val provider = FakeOnAirProvider().apply { delayFirstSummary = true }
        val viewModel = OnAirViewModel(provider, MutableStateFlow(konomiConfiguration()))
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        viewModel.expandSeries(42)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        viewModel.collapseSeries()
        viewModel.expandSeries(42)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        provider.resumeFirstSummary(SeriesProgram(42, "古い概要"))
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals("summary", viewModel.uiState.value.expanded!!.summary!!.title)
    }

    private fun assertIncompletePages(pages: Map<Int, RecordedApiResponse>) {
        val provider = FakeOnAirProvider().apply { programPages = pages }
        val viewModel = OnAirViewModel(provider, MutableStateFlow(konomiConfiguration()))
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        viewModel.expandSeries(42)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        val expanded = requireNotNull(viewModel.uiState.value.expanded)
        assertTrue(expanded.programsStatus is OnAirLoadState.Error)
        assertTrue(expanded.programs.isEmpty())
    }

    private fun konomiConfiguration() = OnAirBackendConfiguration("KONOMITV", "tv.example", "7000", "auth")

    private fun seriesResponse(id: Int, title: String) = Gson().fromJson(
        """{"series_list":[{"id":$id,"title":"$title","thumbnail_recorded_program_ids":[],"channel_ids":[],"recorded_episodes_count":0,"missing_episodes_count":0,"partially_recorded_episodes_count":0,"weekday":0,"broadcast_time":"23:30","latest_broadcast_at":"2026-09-13T23:30:00+09:00"}]}""",
        OnAirSeriesApiResponse::class.java,
    )

    private fun recording(id: Int) = RecordedProgram(
        id = id, title = "録画$id", description = "", startTime = "2026-09-13T00:00:00+09:00",
        endTime = "2026-09-13T00:30:00+09:00", duration = 1800.0, isPartiallyRecorded = false,
        recordedVideo = RecordedVideo(id, "Recorded", "/recording/$id.ts", 1800.0, "MPEG-TS", "H.264", "AAC-LC"),
    )

    private class FakeOnAirProvider : OnAirProvider {
        private var firstListContinuation: Continuation<OnAirSeriesApiResponse>? = null
        private var listCalls = 0
        var programPages: Map<Int, RecordedApiResponse> = emptyMap()
        val requestedPages = mutableListOf<Int>()
        var summaryFailuresRemaining = 0
        var delayFirstSummary = false
        private var firstSummaryContinuation: Continuation<SeriesProgram>? = null

        override suspend fun getOnAirSeries(): OnAirSeriesApiResponse {
            listCalls++
            if (listCalls == 1) return suspendCoroutine { firstListContinuation = it }
            return seriesResponse(2, "新しい結果")
        }

        fun resumeFirstList(response: OnAirSeriesApiResponse) {
            firstListContinuation?.resume(response)
        }

        override suspend fun getSeriesSummary(seriesId: Int): SeriesProgram {
            if (summaryFailuresRemaining-- > 0) error("summary unavailable")
            if (delayFirstSummary) {
                delayFirstSummary = false
                return suspendCoroutine { firstSummaryContinuation = it }
            }
            return SeriesProgram(seriesId, "summary")
        }

        fun resumeFirstSummary(summary: SeriesProgram) {
            firstSummaryContinuation?.resume(summary)
        }

        override suspend fun getRecordedProgramsBySeries(seriesId: Int, page: Int, order: String): RecordedApiResponse {
            requestedPages += page
            return programPages[page] ?: RecordedApiResponse(0, emptyList())
        }
    }
}
