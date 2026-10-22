package com.beeregg2001.komorebi.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
class MiniPlayerTransformLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun animationKeepsEndpointBoundsWithoutMeasuringContentOnEveryFrame() {
        var isMini by mutableStateOf(false)
        val measureCount = AtomicInteger(0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(960.dp, 540.dp)) {
                    MiniPlayerTransformLayout(
                        isMiniPlayerMode = isMini,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .layout { measurable, constraints ->
                                    measureCount.incrementAndGet()
                                    val placeable = measurable.measure(constraints)
                                    layout(placeable.width, placeable.height) {
                                        placeable.place(0, 0)
                                    }
                                }
                                .testTag("mini-player-content"),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        val fullBounds = compose.onNodeWithTag("mini-player-content").getUnclippedBoundsInRoot()
        assertEquals(960.dp, (fullBounds.right - fullBounds.left))
        assertEquals(540.dp, (fullBounds.bottom - fullBounds.top))

        compose.runOnIdle { isMini = true }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val measuresAfterTransitionStarted = measureCount.get()

        repeat(10) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
        assertEquals(measuresAfterTransitionStarted, measureCount.get())

        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        val miniBounds = compose.onNodeWithTag("mini-player-content").getUnclippedBoundsInRoot()
        assertEquals(320.dp, (miniBounds.right - miniBounds.left))
        assertEquals(180.dp, (miniBounds.bottom - miniBounds.top))
        assertEquals(608.dp, miniBounds.left)
        assertEquals(328.dp, miniBounds.top)
    }

    @Test
    fun reversedTransitionReturnsToTheLatestEndpointWithoutPerFrameRemeasure() {
        var isMini by mutableStateOf(false)
        val measureCount = AtomicInteger(0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(960.dp, 540.dp)) {
                    MiniPlayerTransformLayout(
                        isMiniPlayerMode = isMini,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .layout { measurable, constraints ->
                                    measureCount.incrementAndGet()
                                    val placeable = measurable.measure(constraints)
                                    layout(placeable.width, placeable.height) {
                                        placeable.place(0, 0)
                                    }
                                }
                                .testTag("mini-player-content"),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        compose.runOnIdle { isMini = true }
        compose.mainClock.advanceTimeBy(200)
        compose.runOnIdle { isMini = false }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val measuresAfterReverseStarted = measureCount.get()

        repeat(10) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
        assertEquals(measuresAfterReverseStarted, measureCount.get())

        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        val fullBounds = compose.onNodeWithTag("mini-player-content").getUnclippedBoundsInRoot()
        assertEquals(0.dp, fullBounds.left)
        assertEquals(0.dp, fullBounds.top)
        assertEquals(960.dp, (fullBounds.right - fullBounds.left))
        assertEquals(540.dp, (fullBounds.bottom - fullBounds.top))
    }
}
