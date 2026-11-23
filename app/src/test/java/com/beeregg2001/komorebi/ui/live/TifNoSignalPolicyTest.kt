package com.beeregg2001.komorebi.ui.live

import android.media.tv.TvInputManager
import com.beeregg2001.komorebi.data.TifNoSignalStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TifNoSignalPolicyTest {

    @Test
    fun videoUnavailableStatusClassifiesEveryReasonForPassthroughAndTuner() {
        val tuningPassthrough = videoUnavailableStatus(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING, isPassthrough = true)
        assertTrue(tuningPassthrough is TifVideoStatus.Transient)
        assertEquals("信号を検出しています…", tuningPassthrough.text)

        val tuningTuner = videoUnavailableStatus(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING, isPassthrough = false)
        assertTrue(tuningTuner is TifVideoStatus.Transient)
        assertEquals("選局しています…", tuningTuner.text)

        val weakPassthrough = videoUnavailableStatus(TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL, isPassthrough = true)
        assertTrue(weakPassthrough is TifVideoStatus.NoSignal)
        assertEquals("信号がありません", weakPassthrough.text)

        val weakTuner = videoUnavailableStatus(TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL, isPassthrough = false)
        assertTrue(weakTuner is TifVideoStatus.NoSignal)
        assertEquals("電波が弱いか信号がありません", weakTuner.text)

        val bufferingPassthrough = videoUnavailableStatus(TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING, isPassthrough = true)
        assertTrue(bufferingPassthrough is TifVideoStatus.Transient)
        assertEquals("読み込み中…", bufferingPassthrough.text)

        val bufferingTuner = videoUnavailableStatus(TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING, isPassthrough = false)
        assertTrue(bufferingTuner is TifVideoStatus.Transient)
        assertEquals("読み込み中…", bufferingTuner.text)

        val audioOnlyPassthrough = videoUnavailableStatus(TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY, isPassthrough = true)
        assertTrue(audioOnlyPassthrough is TifVideoStatus.Notice)
        assertEquals("音声のみの放送です", audioOnlyPassthrough.text)

        val audioOnlyTuner = videoUnavailableStatus(TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY, isPassthrough = false)
        assertTrue(audioOnlyTuner is TifVideoStatus.Notice)
        assertEquals("音声のみの放送です", audioOnlyTuner.text)

        val notConnectedPassthrough = videoUnavailableStatus(5, isPassthrough = true)
        assertTrue(notConnectedPassthrough is TifVideoStatus.NoSignal)
        assertEquals("入力機器が接続されていません", notConnectedPassthrough.text)

        val notConnectedTuner = videoUnavailableStatus(5, isPassthrough = false)
        assertTrue(notConnectedTuner is TifVideoStatus.NoSignal)
        assertEquals("入力機器が接続されていません", notConnectedTuner.text)

        val unknownPassthrough = videoUnavailableStatus(99, isPassthrough = true)
        assertTrue(unknownPassthrough is TifVideoStatus.NoSignal)
        assertEquals("映像信号がありません (reason=99)", unknownPassthrough.text)

        val unknownTuner = videoUnavailableStatus(99, isPassthrough = false)
        assertTrue(unknownTuner is TifVideoStatus.NoSignal)
        assertEquals("映像を利用できません (reason=99)", unknownTuner.text)
    }

    @Test
    fun resolveNoSignalRenderModeCoversAllFallbackBranches() {
        // Not ready yet -> always BANNER
        assertEquals(
            NoSignalRenderMode.BANNER,
            resolveNoSignalRenderMode(
                style = TifNoSignalStyle.DVD_BOUNCE,
                themedReady = false,
                suppressed = false,
                hardwareAccelerated = true,
                animationsEnabled = true,
                degraded = false,
                containerWidthDp = 960f,
            ),
        )

        // Suppressed (player screensaver active) -> BANNER
        assertEquals(
            NoSignalRenderMode.BANNER,
            resolveNoSignalRenderMode(
                style = TifNoSignalStyle.SCREENSAVER,
                themedReady = true,
                suppressed = true,
                hardwareAccelerated = true,
                animationsEnabled = true,
                degraded = false,
                containerWidthDp = 960f,
            ),
        )

        // Mini-player width < 480dp -> BANNER
        assertEquals(
            NoSignalRenderMode.BANNER,
            resolveNoSignalRenderMode(
                style = TifNoSignalStyle.HONOREBI_BOUNCE,
                themedReady = true,
                suppressed = false,
                hardwareAccelerated = true,
                animationsEnabled = true,
                degraded = false,
                containerWidthDp = 479f,
            ),
        )

        // Explicit BANNER style -> BANNER
        assertEquals(
            NoSignalRenderMode.BANNER,
            resolveNoSignalRenderMode(
                style = TifNoSignalStyle.BANNER,
                themedReady = true,
                suppressed = false,
                hardwareAccelerated = true,
                animationsEnabled = true,
                degraded = false,
                containerWidthDp = 960f,
            ),
        )

        // SCREENSAVER style -> SCREENSAVER (even when animations/hw accel off)
        assertEquals(
            NoSignalRenderMode.SCREENSAVER,
            resolveNoSignalRenderMode(
                style = TifNoSignalStyle.SCREENSAVER,
                themedReady = true,
                suppressed = false,
                hardwareAccelerated = false,
                animationsEnabled = false,
                degraded = true,
                containerWidthDp = 480f,
            ),
        )

        // Bounce styles succeed when hw accel + animations + not degraded
        for (bounceStyle in listOf(TifNoSignalStyle.DVD_BOUNCE, TifNoSignalStyle.HONOREBI_BOUNCE)) {
            assertEquals(
                NoSignalRenderMode.BOUNCE,
                resolveNoSignalRenderMode(
                    style = bounceStyle,
                    themedReady = true,
                    suppressed = false,
                    hardwareAccelerated = true,
                    animationsEnabled = true,
                    degraded = false,
                    containerWidthDp = 960f,
                ),
            )
            // Software rendering fallback -> BANNER
            assertEquals(
                NoSignalRenderMode.BANNER,
                resolveNoSignalRenderMode(
                    style = bounceStyle,
                    themedReady = true,
                    suppressed = false,
                    hardwareAccelerated = false,
                    animationsEnabled = true,
                    degraded = false,
                    containerWidthDp = 960f,
                ),
            )
            // Animators disabled fallback -> BANNER
            assertEquals(
                NoSignalRenderMode.BANNER,
                resolveNoSignalRenderMode(
                    style = bounceStyle,
                    themedReady = true,
                    suppressed = false,
                    hardwareAccelerated = true,
                    animationsEnabled = false,
                    degraded = false,
                    containerWidthDp = 960f,
                ),
            )
            // Jank degraded fallback -> BANNER
            assertEquals(
                NoSignalRenderMode.BANNER,
                resolveNoSignalRenderMode(
                    style = bounceStyle,
                    themedReady = true,
                    suppressed = false,
                    hardwareAccelerated = true,
                    animationsEnabled = true,
                    degraded = true,
                    containerWidthDp = 960f,
                ),
            )
        }

        // Static/low-motion effect styles resolve to EFFECT even when animations/hw accel are off
        val staticEffectStyles = listOf(
            TifNoSignalStyle.NOK_CALLSIGN,
            TifNoSignalStyle.ARIB_COLOR_BARS,
            TifNoSignalStyle.MINIMAL_CLOCK,
        )
        for (effectStyle in staticEffectStyles) {
            assertEquals(
                NoSignalRenderMode.EFFECT,
                resolveNoSignalRenderMode(
                    style = effectStyle,
                    themedReady = true,
                    suppressed = false,
                    hardwareAccelerated = false,
                    animationsEnabled = false,
                    degraded = true,
                    containerWidthDp = 480f,
                ),
            )
            // Still fall back to BANNER when not ready, suppressed, or narrow
            assertEquals(
                NoSignalRenderMode.BANNER,
                resolveNoSignalRenderMode(
                    style = effectStyle,
                    themedReady = false,
                    suppressed = false,
                    hardwareAccelerated = true,
                    animationsEnabled = true,
                    degraded = false,
                    containerWidthDp = 960f,
                ),
            )
            assertEquals(
                NoSignalRenderMode.BANNER,
                resolveNoSignalRenderMode(
                    style = effectStyle,
                    themedReady = true,
                    suppressed = true,
                    hardwareAccelerated = true,
                    animationsEnabled = true,
                    degraded = false,
                    containerWidthDp = 960f,
                ),
            )
            assertEquals(
                NoSignalRenderMode.BANNER,
                resolveNoSignalRenderMode(
                    style = effectStyle,
                    themedReady = true,
                    suppressed = false,
                    hardwareAccelerated = true,
                    animationsEnabled = true,
                    degraded = false,
                    containerWidthDp = 479f,
                ),
            )
        }

        // Animated effect styles succeed when hw accel + animations + not degraded, else BANNER
        val animatedEffectStyles = listOf(
            TifNoSignalStyle.ARIB_COLOR_BARS_SCROLL,
            TifNoSignalStyle.ANALOG_STATIC,
            TifNoSignalStyle.SIGNAL_WAVE,
            TifNoSignalStyle.AURORA_AMBIENT,
            TifNoSignalStyle.DOT_MATRIX,
            TifNoSignalStyle.STARFIELD,
            TifNoSignalStyle.CRAYON_TITLE,
            TifNoSignalStyle.PRECURE_SPARKLE,
            // TifNoSignalStyle.AIPRI_LIVE,
        )
        for (effectStyle in animatedEffectStyles) {
            assertEquals(
                NoSignalRenderMode.EFFECT,
                resolveNoSignalRenderMode(
                    style = effectStyle,
                    themedReady = true,
                    suppressed = false,
                    hardwareAccelerated = true,
                    animationsEnabled = true,
                    degraded = false,
                    containerWidthDp = 960f,
                ),
            )
            assertEquals(
                NoSignalRenderMode.BANNER,
                resolveNoSignalRenderMode(
                    style = effectStyle,
                    themedReady = true,
                    suppressed = false,
                    hardwareAccelerated = false,
                    animationsEnabled = true,
                    degraded = false,
                    containerWidthDp = 960f,
                ),
            )
            assertEquals(
                NoSignalRenderMode.BANNER,
                resolveNoSignalRenderMode(
                    style = effectStyle,
                    themedReady = true,
                    suppressed = false,
                    hardwareAccelerated = true,
                    animationsEnabled = false,
                    degraded = false,
                    containerWidthDp = 960f,
                ),
            )
            assertEquals(
                NoSignalRenderMode.BANNER,
                resolveNoSignalRenderMode(
                    style = effectStyle,
                    themedReady = true,
                    suppressed = false,
                    hardwareAccelerated = true,
                    animationsEnabled = true,
                    degraded = true,
                    containerWidthDp = 960f,
                ),
            )
            assertEquals(
                NoSignalRenderMode.BANNER,
                resolveNoSignalRenderMode(
                    style = effectStyle,
                    themedReady = false,
                    suppressed = false,
                    hardwareAccelerated = true,
                    animationsEnabled = true,
                    degraded = false,
                    containerWidthDp = 960f,
                ),
            )
            assertEquals(
                NoSignalRenderMode.BANNER,
                resolveNoSignalRenderMode(
                    style = effectStyle,
                    themedReady = true,
                    suppressed = true,
                    hardwareAccelerated = true,
                    animationsEnabled = true,
                    degraded = false,
                    containerWidthDp = 960f,
                ),
            )
            assertEquals(
                NoSignalRenderMode.BANNER,
                resolveNoSignalRenderMode(
                    style = effectStyle,
                    themedReady = true,
                    suppressed = false,
                    hardwareAccelerated = true,
                    animationsEnabled = true,
                    degraded = false,
                    containerWidthDp = 479f,
                ),
            )
        }
    }

    @Test
    fun previewNoSignalRenderModeMapsAllStyles() {
        val expected = mapOf(
            TifNoSignalStyle.BANNER to NoSignalRenderMode.BANNER,
            TifNoSignalStyle.DVD_BOUNCE to NoSignalRenderMode.BOUNCE,
            TifNoSignalStyle.HONOREBI_BOUNCE to NoSignalRenderMode.BOUNCE,
            TifNoSignalStyle.SCREENSAVER to NoSignalRenderMode.SCREENSAVER,
            TifNoSignalStyle.NOK_CALLSIGN to NoSignalRenderMode.EFFECT,
            TifNoSignalStyle.ARIB_COLOR_BARS to NoSignalRenderMode.EFFECT,
            TifNoSignalStyle.ARIB_COLOR_BARS_SCROLL to NoSignalRenderMode.EFFECT,
            TifNoSignalStyle.ANALOG_STATIC to NoSignalRenderMode.EFFECT,
            TifNoSignalStyle.SIGNAL_WAVE to NoSignalRenderMode.EFFECT,
            TifNoSignalStyle.MINIMAL_CLOCK to NoSignalRenderMode.EFFECT,
            TifNoSignalStyle.AURORA_AMBIENT to NoSignalRenderMode.EFFECT,
            TifNoSignalStyle.DOT_MATRIX to NoSignalRenderMode.EFFECT,
            TifNoSignalStyle.STARFIELD to NoSignalRenderMode.EFFECT,
            TifNoSignalStyle.CRAYON_TITLE to NoSignalRenderMode.EFFECT,
            TifNoSignalStyle.PRECURE_SPARKLE to NoSignalRenderMode.EFFECT,
            // TifNoSignalStyle.AIPRI_LIVE to NoSignalRenderMode.EFFECT,
        )
        assertEquals(16, TifNoSignalStyle.entries.size)
        assertEquals(TifNoSignalStyle.entries.toSet(), expected.keys)
        for ((style, expectedMode) in expected) {
            assertEquals(
                "previewNoSignalRenderMode($style)",
                expectedMode,
                previewNoSignalRenderMode(style),
            )
        }
    }
}
