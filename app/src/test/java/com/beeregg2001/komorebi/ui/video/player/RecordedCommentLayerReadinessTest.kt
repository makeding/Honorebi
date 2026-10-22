package com.beeregg2001.komorebi.ui.video.player

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class RecordedCommentLayerReadinessTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lateDataMountsImmediatelyAndEmptyUpdatesKeepRendererUntilSessionChanges() {
        val session = mutableStateOf(Any())
        val hasComments = mutableStateOf(false)
        var mounts = 0
        var disposals = 0
        compose.setContent {
            if (rememberRecordedCommentLayerReady(session.value, hasComments.value)) {
                DisposableEffect(session.value) {
                    mounts++
                    onDispose { disposals++ }
                }
            }
        }
        compose.runOnIdle { assertEquals(0, mounts); hasComments.value = true }
        compose.runOnIdle { assertEquals(1, mounts); hasComments.value = false }
        compose.runOnIdle { assertEquals(1, mounts); assertEquals(0, disposals); hasComments.value = true }
        compose.runOnIdle { assertEquals(1, mounts); hasComments.value = false; session.value = Any() }
        compose.runOnIdle { assertEquals(1, disposals); assertEquals(1, mounts); hasComments.value = true }
        compose.runOnIdle { assertEquals(2, mounts) }
    }
}
