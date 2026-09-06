package com.beeregg2001.komorebi.ui.epg

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.beeregg2001.komorebi.ui.epg.engine.EpgAnimValues
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class EpgDrawAnimationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun animationDrawsIntermediateFramesWithoutRecomposingOrMovingItsSurface() {
        val target = mutableFloatStateOf(0f)
        var compositions = 0
        val frames = mutableListOf<EpgAnimValues>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val values = epgDrawAnimation(target.floatValue, -target.floatValue,
                target.floatValue, target.floatValue, 40f + target.floatValue, false)
            SideEffect { compositions++ }
            Box(Modifier.size(300.dp).testTag("grid").drawBehind {
                frames.add(values())
                drawRect(Color.Black)
            })
        }
        val bounds = compose.onNodeWithTag("grid").getUnclippedBoundsInRoot()
        compose.runOnIdle { target.floatValue = 100f }
        // The target invalidation and the first animation frame are distinct with a
        // manual test clock. Drain both before establishing the no-recomposition
        // baseline; later frames must remain draw-only.
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        val afterTargetChange = compositions
        repeat(30) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        assertEquals(afterTargetChange, compositions)
        assertTrue(frames.any { it.scrollX > 0f && it.scrollX < 100f })
        assertEquals(100f, frames.last().scrollX, 0.1f)
        assertEquals(-100f, frames.last().scrollY, 0.1f)
        assertEquals(140f, frames.last().animH, 0.1f)
        assertEquals(bounds, compose.onNodeWithTag("grid").getUnclippedBoundsInRoot())
    }
}
