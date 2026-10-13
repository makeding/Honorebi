package com.beeregg2001.komorebi.ui.video.player

import com.beeregg2001.komorebi.data.model.SeekPositionResponse
import com.beeregg2001.komorebi.ui.video.player.policy.RecordedTsRecoveryPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecordedSeekRecoveryTest {
    @Test fun recording2835OldKeyframeIsPassedWithoutProportionalFallback() = runBlocking {
        val resolver = RecordedSeekResolver(2835, true) { _, time ->
            if (time < 20) SeekPositionResponse(4888188, 4.371033333333333)
            else SeekPositionResponse(17950728, 18.802122222222224)
        }
        val policy = RecordedTsRecoveryPolicy()
        val position = 4818L
        var landed: Long? = null
        var calls = 0
        while (landed == null) {
            val target = policy.nextTarget(position, 1_808_523) ?: break
            calls++
            resolver.resolveForRecovery(target)?.takeIf { it > position + 500 }?.let { landed = it }
        }
        assertEquals(3, calls)
        assertEquals(18802L, landed)
        assertEquals(17950728L, resolver.resolutionRef.get()!!.position)
    }

    @Test fun unavailableSeekIndexDoesNotAutomaticallyGuessByteOffset() = runBlocking {
        val resolver = RecordedSeekResolver(2835, true) { _, _ -> null }
        assertNull(resolver.resolveForRecovery(20_000))
        assertNull(resolver.resolutionRef.get())
        assertEquals(20_000L, resolver.resolve(20_000))
    }
}
