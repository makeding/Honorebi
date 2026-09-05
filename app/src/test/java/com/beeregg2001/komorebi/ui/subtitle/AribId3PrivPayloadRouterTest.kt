package com.beeregg2001.komorebi.ui.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AribId3PrivPayloadRouterTest {
    @Test
    fun captionAndSuperimposeUseIndependentRoutes() {
        assertEquals(
            AribId3PrivPayloadRouter.Route.Caption(render = true),
            AribId3PrivPayloadRouter.route(byteArrayOf(0x80.toByte(), 0xff.toByte(), 0x00), true),
        )
        assertEquals(
            AribId3PrivPayloadRouter.Route.Superimpose,
            AribId3PrivPayloadRouter.route(byteArrayOf(0x81.toByte(), 0xff.toByte(), 0x00), true),
        )
    }

    @Test
    fun hiddenCaptionsStillReachTheDecoderForManagementFiltering() {
        assertEquals(
            AribId3PrivPayloadRouter.Route.Caption(render = false),
            AribId3PrivPayloadRouter.route(byteArrayOf(0x80.toByte(), 0xff.toByte(), 0x00), false),
        )
        assertTrue(
            AribCaptionData.isManagementPacket(
                byteArrayOf(0x80.toByte(), 0xff.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
            )
        )
    }

    @Test
    fun invalidAndTruncatedPayloadsAreIgnored() {
        val ignore = AribId3PrivPayloadRouter.Route.Ignore
        assertEquals(ignore, AribId3PrivPayloadRouter.route(byteArrayOf(), true))
        assertEquals(ignore, AribId3PrivPayloadRouter.route(byteArrayOf(0x80.toByte()), true))
        assertEquals(ignore, AribId3PrivPayloadRouter.route(byteArrayOf(0x80.toByte(), 0x00, 0x00), true))
        assertEquals(ignore, AribId3PrivPayloadRouter.route(byteArrayOf(0x82.toByte(), 0xff.toByte(), 0x00), true))
    }

    @Test
    fun externalDiscontinuitiesResetBothDecoderTimelines() {
        assertFalse(
            AribId3PrivPayloadRouter.shouldResetForPositionDiscontinuity(
                AribId3PrivPayloadRouter.POSITION_DISCONTINUITY_REASON_INTERNAL
            )
        )
        assertTrue(AribId3PrivPayloadRouter.shouldResetForPositionDiscontinuity(1))
        assertTrue(AribId3PrivPayloadRouter.shouldResetForPositionDiscontinuity(4))
    }
}
