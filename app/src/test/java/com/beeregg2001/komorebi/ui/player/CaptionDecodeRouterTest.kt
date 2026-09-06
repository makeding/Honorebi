package com.beeregg2001.komorebi.ui.player

import com.beeregg2001.komorebi.ui.subtitle.AribId3PrivPayloadRouter
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptionDecodeRouterTest {
    @Test
    fun `B24 routing keeps hidden caption management and superimpose separate`() {
        assertEquals(
            AribId3PrivPayloadRouter.Route.Caption(render = false),
            AribId3PrivPayloadRouter.route(byteArrayOf(0x80.toByte(), 0xff.toByte(), 0), false),
        )
        assertEquals(
            AribId3PrivPayloadRouter.Route.Superimpose,
            AribId3PrivPayloadRouter.route(byteArrayOf(0x81.toByte(), 0xff.toByte(), 0), false),
        )
    }

    @Test
    fun `hidden B62 captions only suppress rendering while superimpose remains visible`() {
        assertEquals(
            false,
            CaptionDecodeRouter.shouldRenderB62(
                type = com.beeregg2001.komorebi.ui.subtitle.NativeCaptionDecoder.TYPE_CAPTION,
                captionsEnabled = false,
            ),
        )
        assertEquals(
            true,
            CaptionDecodeRouter.shouldRenderB62(
                type = com.beeregg2001.komorebi.ui.subtitle.NativeCaptionDecoder.TYPE_SUPERIMPOSE,
                captionsEnabled = false,
            ),
        )
    }
}
