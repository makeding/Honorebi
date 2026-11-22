package com.beeregg2001.komorebi.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.ui.home.components.VideoCompactHero
import com.beeregg2001.komorebi.ui.home.components.VideoHeroPreview
import com.beeregg2001.komorebi.ui.home.components.VideoRecentRecordCard
import com.beeregg2001.komorebi.ui.home.components.VideoWatchHistoryCard
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land")
class RecordedCardConsistencyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun focusAndProgressUpdatesKeepEachCardAndItsNeighboursInPlace() {
        var position by mutableStateOf(0.0)
        var program by mutableStateOf(RecordedProgram(id = 1, title = "番組", description = "", startTime = "2026-10-13T12:00:00+09:00",
            endTime = "2026-10-13T12:30:00+09:00", duration = 1800.0, isPartiallyRecorded = false,
            recordedVideo = RecordedVideo(1, "Recorded", "/1.ts", null, null, 1800.0, "MPEG-TS", "H.264", "AAC-LC")))
        compose.setContent {
            KomorebiTheme {
                val history = KonomiHistoryProgram(KonomiProgram("1", "番組", "", null,
                    program.startTime, program.endTime, ""), position, program.startTime)
                Column(Modifier.size(900.dp, 500.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        VideoRecentRecordCard(program, history, "", "", "KonomiTV", {}, {},
                            Modifier.testTag("recent"), focusRequester = remember { FocusRequester() },
                            timeFormat = "24h", allowNetworkImages = false)
                        VideoWatchHistoryCard(history, program, "", "", "KonomiTV", {}, {},
                            Modifier.testTag("history"), remember { FocusRequester() }, "24h", false)
                    }
                    RecordedCard(program.copy(playbackPosition = position), "KonomiTV", "", "", {},
                        Modifier.width(280.dp).testTag("grid"), isScrolling = { true })
                }
            }
        }
        val tags = listOf("recent", "history", "grid")
        val bounds = tags.associateWith { compose.onNodeWithTag(it).getUnclippedBoundsInRoot() }
        for (tag in tags) {
            compose.onNodeWithTag(tag).requestFocus().assertIsFocused()
            compose.runOnIdle {
                position += 60.0
                program = program.copy(recordedVideo = program.recordedVideo.copy(
                    status = if (tag == "grid") "Recorded" else "Recording",
                    duration = position,
                ))
            }
            compose.onNodeWithTag(tag).assertIsFocused()
            for (other in tags) assertEquals(bounds[other], compose.onNodeWithTag(other).getUnclippedBoundsInRoot())
        }
    }

    @Test fun singleLineAndMultiLineTitlesAlignThirdLineMetadataVertically() {
        val shortTitle = "短い番組タイトル"
        val longTitle = "とても長い番組タイトルで二行目まで確実に折り返して三行目以降が省略されるはずのテスト番組タイトル"
        val channelA = RecordedChannel(
            id = "gr01",
            displayChannelId = "gr01",
            type = "GR",
            name = "放送局A",
            channelNumber = "011",
        )
        val channelB = channelA.copy(id = "gr02", displayChannelId = "gr02", name = "放送局B")
        val shortProgram = RecordedProgram(
            id = 10,
            title = shortTitle,
            description = "",
            startTime = "2026-10-13T12:00:00+09:00",
            endTime = "2026-10-13T12:30:00+09:00",
            duration = 1800.0,
            isPartiallyRecorded = false,
            channel = channelA,
            recordedVideo = RecordedVideo(10, "Recorded", "/10.ts", null, null, 1800.0, "MPEG-TS", "H.264", "AAC-LC"),
        )
        val longProgram = shortProgram.copy(id = 11, title = longTitle, channel = channelB)

        compose.setContent {
            KomorebiTheme {
                val longHistory = KonomiHistoryProgram(
                    KonomiProgram("11", longTitle, "", null, longProgram.startTime, longProgram.endTime, "gr02"),
                    120.0,
                    longProgram.startTime,
                )
                Row(
                    modifier = Modifier.size(900.dp, 300.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    VideoRecentRecordCard(
                        program = shortProgram,
                        history = null,
                        konomiIp = "",
                        konomiPort = "",
                        backendType = "KonomiTV",
                        onClick = {},
                        onFocus = {},
                        modifier = Modifier.testTag("short_recent"),
                        focusRequester = remember { FocusRequester() },
                        timeFormat = "24H",
                        allowNetworkImages = false,
                    )
                    VideoRecentRecordCard(
                        program = longProgram,
                        history = null,
                        konomiIp = "",
                        konomiPort = "",
                        backendType = "KonomiTV",
                        onClick = {},
                        onFocus = {},
                        modifier = Modifier.testTag("long_recent"),
                        focusRequester = remember { FocusRequester() },
                        timeFormat = "24H",
                        allowNetworkImages = false,
                    )
                    VideoWatchHistoryCard(
                        historyItem = longHistory,
                        matchedProgram = longProgram.copy(channel = channelB.copy(name = "放送局C")),
                        konomiIp = "",
                        konomiPort = "",
                        backendType = "KonomiTV",
                        onClick = {},
                        onFocus = {},
                        modifier = Modifier.testTag("long_history"),
                        focusRequester = remember { FocusRequester() },
                        timeFormat = "24H",
                        allowNetworkImages = false,
                    )
                }
            }
        }

        val shortCardBounds = compose.onNodeWithTag("short_recent").getUnclippedBoundsInRoot()
        val shortMetaBounds = compose.onNodeWithText("放送局A · ", substring = true, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val longMetaBounds = compose.onNodeWithText("放送局B · ", substring = true, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val historyMetaBounds = compose.onNodeWithText("放送局C · ", substring = true, useUnmergedTree = true).getUnclippedBoundsInRoot()

        assertEquals(shortMetaBounds.top, longMetaBounds.top)
        assertEquals(shortMetaBounds.top, historyMetaBounds.top)
        assert(shortMetaBounds.bottom <= shortCardBounds.bottom) {
            "Expected metadata bottom (${shortMetaBounds.bottom}) <= card bottom (${shortCardBounds.bottom})"
        }
    }

    @Test fun videoCompactHeroKeepsTitleFullWidthBelowStatusSlot() {
        compose.setContent {
            KomorebiTheme {
                Column(Modifier.width(900.dp)) {
                    VideoCompactHero(
                        state = VideoHeroPreview(
                            title = "ヒーロータイトル（メタあり）",
                            metaLine = "ABCテレビ1 · 2026/11/22(日) 08:30 · 30:00",
                            description = "説明テキスト",
                        ),
                        modifier = Modifier.testTag("hero_with_meta"),
                    )
                    VideoCompactHero(
                        state = VideoHeroPreview(title = "ヒーロータイトル（メタなし）"),
                        modifier = Modifier.testTag("hero_no_meta"),
                    )
                }
            }
        }

        val heroWithMetaBounds = compose.onNodeWithTag("hero_with_meta").getUnclippedBoundsInRoot()
        val heroNoMetaBounds = compose.onNodeWithTag("hero_no_meta").getUnclippedBoundsInRoot()
        val metaBounds = compose.onNodeWithText("ABCテレビ1", substring = true, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val titleWithMetaBounds = compose.onNodeWithText("ヒーロータイトル（メタあり）").getUnclippedBoundsInRoot()
        val titleNoMetaBounds = compose.onNodeWithText("ヒーロータイトル（メタなし）").getUnclippedBoundsInRoot()

        assertEquals(heroWithMetaBounds.right, titleWithMetaBounds.right)
        assert(titleWithMetaBounds.top > metaBounds.bottom) {
            "Expected title top (${titleWithMetaBounds.top}) > meta bottom (${metaBounds.bottom})"
        }
        assertEquals(
            titleWithMetaBounds.top - heroWithMetaBounds.top,
            titleNoMetaBounds.top - heroNoMetaBounds.top,
        )
    }
}
