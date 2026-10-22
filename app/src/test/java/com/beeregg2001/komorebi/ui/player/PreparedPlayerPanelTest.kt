package com.beeregg2001.komorebi.ui.player

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.layout
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
class PreparedPlayerPanelTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun preparationUsesSeparateComposeAndMeasureFrames_cancelsAndReusesPanel() {
        val leafCount = 500
        val oldCompositions = AtomicInteger()
        val newCompositions = AtomicInteger()
        val oldMeasures = AtomicInteger()
        val newMeasures = AtomicInteger()
        val oldEffects = AtomicInteger()
        val oldDisposals = AtomicInteger()
        val newEffects = AtomicInteger()
        val newDisposals = AtomicInteger()
        val visible = mutableStateOf(false)
        val prepare = mutableStateOf(false)
        val panelKey = mutableStateOf("old")
        val preparationFrames = Channel<Unit>(capacity = Channel.UNLIMITED)

        composeRule.setContent {
            val foregroundFocus = remember { FocusRequester() }
            val panelFocus = remember { FocusRequester() }
            val inputModeManager = LocalInputModeManager.current

            LaunchedEffect(visible.value) {
                inputModeManager.requestInputMode(InputMode.Keyboard)
                if (!visible.value) foregroundFocus.requestFocus()
            }
            Box(Modifier.size(960.dp, 540.dp)) {
                Box(
                    Modifier.fillMaxSize()
                        .testTag("foreground")
                        .focusRequester(foregroundFocus)
                        .focusable(),
                )
                val contentKey = panelKey.value
                PreparedPlayerPanel(
                    visible = visible.value,
                    prepare = prepare.value,
                    contentKey = contentKey,
                    modifier = Modifier.fillMaxSize(),
                    awaitPreparationFrame = { operation ->
                        preparationFrames.receive()
                        operation()
                    },
                ) { active ->
                    DisposableEffect(contentKey) {
                        if (contentKey == "old") oldEffects.incrementAndGet()
                        else newEffects.incrementAndGet()
                        onDispose {
                            if (contentKey == "old") oldDisposals.incrementAndGet()
                            else newDisposals.incrementAndGet()
                        }
                    }
                    LaunchedEffect(active) {
                        if (active) panelFocus.requestFocus()
                    }
                    Box(
                        Modifier.fillMaxSize()
                            .layout { measurable, constraints ->
                                if (contentKey == "old") oldMeasures.incrementAndGet()
                                else newMeasures.incrementAndGet()
                                val placeable = measurable.measure(constraints)
                                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                            }
                            .testTag("panel-$contentKey")
                            .focusRequester(panelFocus)
                            .focusable(),
                    ) {
                        repeat(leafCount) { index ->
                            key(index) {
                                CountingLeaf(
                                    onCompose = if (contentKey == "old") {
                                        { oldCompositions.incrementAndGet() }
                                    } else {
                                        { newCompositions.incrementAndGet() }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        composeRule.runOnIdle { prepare.value = true }
        composeRule.onRoot().captureToImage()

        // The first released frame composes, but does not measure, the panel.
        composeRule.runOnIdle { preparationFrames.trySend(Unit).getOrThrow() }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(leafCount, oldCompositions.get())
            assertEquals(0, oldMeasures.get())
            assertEquals(1, oldEffects.get())
            assertEquals(0, oldDisposals.get())
        }
        composeRule.onNodeWithTag("foreground").assertIsFocused()

        // Cancelling while the panel is waiting for its measure frame must
        // discard the old slot before a replacement key starts preparing.
        composeRule.runOnIdle { prepare.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(1, oldDisposals.get()) }
        composeRule.runOnIdle {
            panelKey.value = "new"
            prepare.value = true
        }
        composeRule.onRoot().captureToImage()
        composeRule.runOnIdle { preparationFrames.trySend(Unit).getOrThrow() }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(leafCount, newCompositions.get())
            assertEquals(0, newMeasures.get())
            assertEquals(1, newEffects.get())
            assertEquals(0, newDisposals.get())
        }

        // The next released frame measures the existing composition without
        // rebuilding it.
        composeRule.runOnIdle { preparationFrames.trySend(Unit).getOrThrow() }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(leafCount, newCompositions.get())
            assertEquals(1, newMeasures.get())
            assertEquals("premeasure must retain the applied composition", 1, newEffects.get())
            assertEquals("premeasure must not dispose the applied composition", 0, newDisposals.get())
        }
        composeRule.onNodeWithTag("panel-old").assertDoesNotExist()
        composeRule.onNodeWithTag("foreground").assertIsFocused()

        composeRule.runOnIdle { visible.value = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("panel-new").assertIsDisplayed().assertIsFocused()
        composeRule.runOnIdle {
            assertEquals(1, newEffects.get())
            assertEquals(0, newDisposals.get())
        }
    }
}

@Composable
private fun CountingLeaf(onCompose: () -> Unit) {
    onCompose()
    Box(Modifier.size(1.dp))
}
