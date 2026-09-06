package com.beeregg2001.komorebi.ui.video.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
class ThumbnailTimelineLayoutTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun sceneTimeline_spansScreenBetweenMargins() = checkTimeline(chapters = false)

    @Test fun chapterTimeline_spansScreenBetweenMargins() = checkTimeline(chapters = true)

    private fun checkTimeline(chapters: Boolean) {
        val program = RecordedProgram(
            id = 1, title = "番組", description = "説明",
            startTime = "2026-09-07T12:00:00+09:00", endTime = "2026-09-07T12:30:00+09:00",
            duration = 1800.0, isPartiallyRecorded = false,
            recordedVideo = RecordedVideo(
                id = 1, status = "Recorded", filePath = "/recorded.ts", duration = 1800.0,
                containerFormat = "MPEG-TS", videoCodec = "H.264", audioCodec = "AAC"
            )
        )
        composeRule.setContent {
            KomorebiTheme {
                Box(Modifier.fillMaxSize().testTag("preview-root")) {
                    if (chapters) {
                        ChapterListOverlay(program, emptyList(), null, 0L, {}, {})
                    } else {
                        SceneSearchOverlay(program, null, 0L, {}, {})
                    }
                }
            }
        }
        val root = composeRule.onNodeWithTag("preview-root").getUnclippedBoundsInRoot()
        val timeline = composeRule.onNodeWithTag("thumbnail-timeline").getUnclippedBoundsInRoot()
        assertEquals(root.left + 48.dp, timeline.left)
        assertEquals(root.right - 48.dp, timeline.right)
        composeRule.mainClock.advanceTimeBy(500)
        assertEquals(timeline, composeRule.onNodeWithTag("thumbnail-timeline").getUnclippedBoundsInRoot())
    }
}
