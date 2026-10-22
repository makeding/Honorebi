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
}
