package com.beeregg2001.komorebi.ui.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
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
    fun currentNewsGenreHidesOnlyClockAndStopsReadsUntilProgramChanges() {
        val channel = mutableStateOf(channel(program(listOf(Genre("スポーツ", "スポーツニュース")))))
        var time = ZonedDateTime.parse("2026-10-13T19:00:00+09:00[Asia/Tokyo]")
        var reads = 0
        composeRule.setContent {
            KomorebiTheme {
                Box(Modifier.size(960.dp, 540.dp)) {
                    StatusOverlay(channel.value, "", false, now = { reads++; time })
                }
            }
        }
        composeRule.onNodeWithText("19:00").assertIsDisplayed()
        val clockBounds = composeRule.onNodeWithTag("live-wall-clock").getUnclippedBoundsInRoot()
        val cardBounds = composeRule.onNodeWithTag("live-channel-status").getUnclippedBoundsInRoot()
        val news = program(listOf(Genre("情報・ワイドショー", ""), Genre("ニュース・報道", "定時・総合")))
        composeRule.runOnIdle { channel.value = channel.value.copy(programPresent = news) }
        composeRule.onNodeWithTag("live-wall-clock").assertDoesNotExist()
        composeRule.onNodeWithTag("live-channel-status").assertIsDisplayed()
        assertEquals(cardBounds, composeRule.onNodeWithTag("live-channel-status").getUnclippedBoundsInRoot())
        val hiddenReads = reads
        composeRule.mainClock.advanceTimeBy(3_000)
        assertEquals(hiddenReads, reads)

        // Unknown metadata and non-news genres keep the clock, including news-like titles
        // and a following news program. Only the current program's major genre owns this rule.
        for (present in listOf(null, program(null), program(emptyList()), program(listOf(Genre("スポーツ", "スポーツニュース"))))) {
            composeRule.runOnIdle {
                time = time.plusMinutes(1)
                channel.value = channel.value.copy(programPresent = present, programFollowing = news)
            }
            composeRule.mainClock.advanceTimeBy(1_100)
            composeRule.onNodeWithText(time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")))
                .assertIsDisplayed()
            assertEquals(clockBounds, composeRule.onNodeWithTag("live-wall-clock").getUnclippedBoundsInRoot())
            assertEquals(cardBounds, composeRule.onNodeWithTag("live-channel-status").getUnclippedBoundsInRoot())
        }
        // A channel change to news applies the same rule immediately.
        composeRule.runOnIdle { channel.value = channel(news).copy(id = "2", name = "別チャンネル") }
        composeRule.onNodeWithTag("live-wall-clock").assertDoesNotExist()
        composeRule.onNodeWithTag("live-channel-name").assertTextEquals("別チャンネル").assertIsDisplayed()
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
