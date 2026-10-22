package com.beeregg2001.komorebi.ui.live

import com.beeregg2001.komorebi.ui.player.PlaybackLoadingNotice

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.beeregg2001.komorebi.data.PlayerClockMode
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.Genre
import com.beeregg2001.komorebi.data.model.Program
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
class LiveStatusOverlayTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun clockModesAndLoadingKeepTheirOwnSlotsAcrossProgramChanges() {
        val news = program(listOf(Genre("情報・ワイドショー", ""), Genre("ニュース・報道", "定時・総合")))
        val channel = mutableStateOf(channel(news))
        val mode = mutableStateOf(PlayerClockMode.ALWAYS_ON)
        val loading = mutableStateOf(true)
        composeRule.setContent {
            KomorebiTheme {
                Box(Modifier.size(960.dp, 540.dp)) {
                    StatusOverlay(channel.value, "", false,
                        now = { ZonedDateTime.parse("2026-10-13T19:00:00+09:00[Asia/Tokyo]") },
                        clockMode = mode.value)
                    PlaybackLoadingNotice("読み込み中…", loading.value)
                }
            }
        }
        val clock = composeRule.onNodeWithTag("live-wall-clock").getUnclippedBoundsInRoot()
        val card = composeRule.onNodeWithTag("live-channel-status").getUnclippedBoundsInRoot()
        val end = composeRule.onNodeWithTag("live-end-time").getUnclippedBoundsInRoot()
        val notice = composeRule.onNodeWithTag("playback-loading-notice").getUnclippedBoundsInRoot()
        assertEquals(16.dp, notice.top - clock.bottom)
        for (selected in PlayerClockMode.entries) {
            composeRule.runOnIdle { mode.value = selected }
            for (present in listOf(news, null, program(null), program(emptyList()), program(listOf(Genre("スポーツ", "スポーツニュース"))))) {
                composeRule.runOnIdle { channel.value = channel.value.copy(programPresent = present, programFollowing = news) }
                val show = selected == PlayerClockMode.ALWAYS_ON || (selected == PlayerClockMode.ONLY_NEWS && present == news)
                if (show) composeRule.onNodeWithText("19:00").assertIsDisplayed()
                else composeRule.onNodeWithText("19:00").assertDoesNotExist()
                if (present != null) {
                    composeRule.onNodeWithTag("live-end-time").assertTextEquals("20:00 終了予定")
                    assertEquals(end, composeRule.onNodeWithTag("live-end-time").getUnclippedBoundsInRoot())
                }
                assertEquals(card, composeRule.onNodeWithTag("live-channel-status").getUnclippedBoundsInRoot())
                assertEquals(notice, composeRule.onNodeWithTag("playback-loading-notice").getUnclippedBoundsInRoot())
            }
        }
        composeRule.runOnIdle { loading.value = false }
        assertEquals(notice.top, composeRule.onNodeWithTag("playback-loading-notice").getUnclippedBoundsInRoot().top)
    }

    @Test
    fun dualSlotLoadingUsesSameLaneAndFailureReplacesIt() {
        val error = mutableStateOf<String?>(null)
        composeRule.setContent {
            KomorebiTheme {
                Box(Modifier.size(480.dp, 270.dp)) {
                    LiveSlotStatus(error.value, true, "信号を待っています…", {})
                }
            }
        }
        val notice = composeRule.onNodeWithTag("playback-loading-notice").getUnclippedBoundsInRoot()
        assertEquals(24.dp, notice.left)
        assertEquals(96.dp, notice.top)
        composeRule.runOnIdle { error.value = "接続に失敗しました。再試行してください。" }
        composeRule.onNodeWithTag("playback-loading-notice").assertDoesNotExist()
        composeRule.onNodeWithText(error.value!!).assertIsDisplayed()
    }

    @Test
    fun liveTimelineUsesSharedSlotsAndNeverKeepsPreviousProgramTiming() {
        val original = program(null).copy(
            startTime = "2026-10-19T23:50:00+09:00", endTime = "2026-10-20T00:35:00+09:00")
        val channel = mutableStateOf(channel(original))
        var time = ZonedDateTime.parse("2026-10-20T00:26:00+09:00[Asia/Tokyo]")
        composeRule.setContent {
            KomorebiTheme {
                Box(Modifier.size(960.dp, 540.dp)) {
                    LiveOverlayUI(channel.value, channel.value.programPresent?.title ?: "番組情報なし",
                        "", false, false, false, rememberScrollState(), now = { time })
                }
            }
        }
        composeRule.onNodeWithTag("playback-time").assertTextEquals("36:00")
        composeRule.onNodeWithTag("playback-duration").assertTextEquals("45:00")
        val track = composeRule.onNodeWithTag("playback-track").getUnclippedBoundsInRoot()
        val title = composeRule.onNodeWithTag("live-title").getUnclippedBoundsInRoot()
        for (program in listOf(null, original.copy(endTime = "bad"), original.copy(title = "長い番組名".repeat(20)))) {
            composeRule.runOnIdle { channel.value = channel.value.copy(programPresent = program) }
            assertEquals(track, composeRule.onNodeWithTag("playback-track").getUnclippedBoundsInRoot())
            assertEquals(title, composeRule.onNodeWithTag("live-title").getUnclippedBoundsInRoot())
            composeRule.onNodeWithTag("playback-duration").assertTextEquals(if (program == null || program.endTime == "bad") "--:--" else "45:00")
        }
        composeRule.runOnIdle { time = time.plusMinutes(10) }
        composeRule.mainClock.advanceTimeBy(1_100)
        composeRule.onNodeWithTag("playback-time").assertTextEquals("--:--")
        composeRule.onNodeWithTag("playback-duration").assertTextEquals("--:--")
        assertEquals(track, composeRule.onNodeWithTag("playback-track").getUnclippedBoundsInRoot())
    }

    @Test
    fun endTimeRefreshesWithEpgAndClockStopsWhenWholeOverlayIsHidden() {
        val channel = mutableStateOf(channel(program(null)))
        val visible = mutableStateOf(true)
        var time = ZonedDateTime.parse("2026-10-13T19:00:00+09:00[Asia/Tokyo]")
        var reads = 0
        composeRule.setContent {
            KomorebiTheme {
                Box(Modifier.size(960.dp, 540.dp)) {
                    if (visible.value) StatusOverlay(channel.value, "", false, now = { reads++; time })
                }
            }
        }
        composeRule.onNodeWithTag("live-end-time").assertTextEquals("20:00 終了予定")
        val endBounds = composeRule.onNodeWithTag("live-end-time").getUnclippedBoundsInRoot()
        composeRule.runOnIdle { channel.value = channel.value.copy(programPresent = program(null).copy(endTime = "2026-10-13T20:30:00+09:00")) }
        composeRule.onNodeWithTag("live-end-time").assertTextEquals("20:30 終了予定")
        assertEquals(endBounds, composeRule.onNodeWithTag("live-end-time").getUnclippedBoundsInRoot())
        composeRule.runOnIdle { time = time.plusHours(2) }
        composeRule.mainClock.advanceTimeBy(1_100)
        composeRule.onNodeWithTag("live-end-time").assertTextEquals("終了時刻未定")
        composeRule.runOnIdle { visible.value = false }
        composeRule.waitForIdle()
        val hiddenReads = reads
        composeRule.mainClock.advanceTimeBy(3_000)
        assertEquals(hiddenReads, reads)
    }

    private fun channel(present: Program?) = Channel(
        id = "1", displayChannelId = "GR011", name = "NHK総合", channelNumber = "011",
        type = "GR", isWatchable = true, isDisplay = true,
        programPresent = present, programFollowing = null,
    )

    private fun program(genres: List<Genre>?) = Program(
        id = "program", title = "news", description = "", detail = null,
        startTime = "2026-10-13T19:00:00+09:00", endTime = "2026-10-13T20:00:00+09:00",
        duration = 3600, genres = genres,
    )
}
