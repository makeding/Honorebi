package com.beeregg2001.komorebi.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KomorebiDeepLinkTest {

    @Test
    fun parseRecording_absentPosition_returnsNullPosition() {
        assertEquals(
            PlaybackOpenRequest.Recording(
                recordedProgramId = 123,
                positionSeconds = null,
                source = PlaybackOpenSource.DeepLink,
            ),
            KomorebiDeepLink.parse("komorebi://recording/123"),
        )
    }

    @Test
    fun parseRecording_explicitZeroPosition_isDistinctFromAbsent() {
        assertEquals(
            PlaybackOpenRequest.Recording(
                recordedProgramId = 123,
                positionSeconds = 0.0,
                source = PlaybackOpenSource.DeepLink,
            ),
            KomorebiDeepLink.parse("komorebi://recording/123?position=0"),
        )
    }

    @Test
    fun parseRecording_fractionalPosition_parsesDouble() {
        assertEquals(
            PlaybackOpenRequest.Recording(
                recordedProgramId = 456,
                positionSeconds = 12.5,
                source = PlaybackOpenSource.DeepLink,
            ),
            KomorebiDeepLink.parse("komorebi://recording/456?position=12.5"),
        )
    }

    @Test
    fun parseRecording_uppercaseSchemeAndHost_isAccepted() {
        assertEquals(
            PlaybackOpenRequest.Recording(
                recordedProgramId = 789,
                positionSeconds = 30.0,
                source = PlaybackOpenSource.DeepLink,
            ),
            KomorebiDeepLink.parse("KOMOREBI://RECORDING/789?position=30"),
        )
    }

    @Test
    fun parseRecording_invalidIds_returnNull() {
        assertNull(KomorebiDeepLink.parse("komorebi://recording/abc"))
        assertNull(KomorebiDeepLink.parse("komorebi://recording/0"))
        assertNull(KomorebiDeepLink.parse("komorebi://recording/-1"))
        assertNull(KomorebiDeepLink.parse("komorebi://recording/+1"))
        assertNull(KomorebiDeepLink.parse("komorebi://recording/999999999999999"))
        assertNull(KomorebiDeepLink.parse("komorebi://recording/"))
        assertNull(KomorebiDeepLink.parse("komorebi://recording"))
    }

    @Test
    fun parseRecording_invalidPositionValues_rejectWholeLink() {
        assertNull(KomorebiDeepLink.parse("komorebi://recording/123?position=NaN"))
        assertNull(KomorebiDeepLink.parse("komorebi://recording/123?position=-1"))
        assertNull(KomorebiDeepLink.parse("komorebi://recording/123?position=-0.0"))
        assertNull(KomorebiDeepLink.parse("komorebi://recording/123?position=Infinity"))
        assertNull(KomorebiDeepLink.parse("komorebi://recording/123?position=-Infinity"))
        assertNull(KomorebiDeepLink.parse("komorebi://recording/123?position="))
        assertNull(KomorebiDeepLink.parse("komorebi://recording/123?position"))
        assertNull(KomorebiDeepLink.parse("komorebi://recording/123?position=abc"))
        assertNull(KomorebiDeepLink.parse("komorebi://recording/123?position=10&position=20"))
    }

    @Test
    fun parse_invalidStructureHostOrScheme_returnsNull() {
        assertNull(KomorebiDeepLink.parse(null as String?))
        assertNull(KomorebiDeepLink.parse(""))
        assertNull(KomorebiDeepLink.parse("   "))
        assertNull(KomorebiDeepLink.parse("https://recording/123"))
        assertNull(KomorebiDeepLink.parse("http://live/gr011"))
        assertNull(KomorebiDeepLink.parse("komorebi://unknown/123"))
        assertNull(KomorebiDeepLink.parse("komorebi://recording/123/extra"))
        assertNull(KomorebiDeepLink.parse("komorebi://recording/123/"))
        assertNull(KomorebiDeepLink.parse("komorebi://recording//123"))
        assertNull(KomorebiDeepLink.parse("komorebi://live/gr011/extra"))
        assertNull(KomorebiDeepLink.parse("komorebi://recording:8080/123"))
        assertNull(KomorebiDeepLink.parse("komorebi://user@recording/123"))
    }

    @Test
    fun parseLive_validAndEncodedIds_roundTripViaLiveUri() {
        val simpleUri = KomorebiDeepLink.liveUri("gr011")
        assertEquals("komorebi://live/gr011", simpleUri)
        assertEquals(
            PlaybackOpenRequest.Live("gr011", PlaybackOpenSource.DeepLink),
            KomorebiDeepLink.parse(simpleUri),
        )

        val specialId = "tif:ch/01+HD テスト"
        val encodedUri = KomorebiDeepLink.liveUri(specialId)
        assertEquals(
            PlaybackOpenRequest.Live(specialId, PlaybackOpenSource.DeepLink),
            KomorebiDeepLink.parse(encodedUri),
        )
    }

    @Test
    fun parseLive_blankMalformedOrOverlong_returnsNull() {
        assertNull(KomorebiDeepLink.parse("komorebi://live/"))
        assertNull(KomorebiDeepLink.parse("komorebi://live"))
        assertNull(KomorebiDeepLink.parse("komorebi://live/%20%20"))
        assertNull(KomorebiDeepLink.parse("komorebi://live/bad%2"))
        assertNull(KomorebiDeepLink.parse("komorebi://live/bad%ZZ"))
        assertNull(KomorebiDeepLink.parse("komorebi://live/${"a".repeat(129)}"))

        val maxLenId = "a".repeat(128)
        assertEquals(
            PlaybackOpenRequest.Live(maxLenId, PlaybackOpenSource.DeepLink),
            KomorebiDeepLink.parse("komorebi://live/$maxLenId"),
        )
    }

    @Test
    fun recordingUri_roundTripsWithoutPositionZeroAndFractional() {
        val noPos = KomorebiDeepLink.recordingUri(42)
        assertEquals("komorebi://recording/42", noPos)
        assertEquals(
            PlaybackOpenRequest.Recording(42, null, PlaybackOpenSource.DeepLink),
            KomorebiDeepLink.parse(noPos),
        )

        val zeroPos = KomorebiDeepLink.recordingUri(42, 0.0)
        assertEquals("komorebi://recording/42?position=0", zeroPos)
        assertEquals(
            PlaybackOpenRequest.Recording(42, 0.0, PlaybackOpenSource.DeepLink),
            KomorebiDeepLink.parse(zeroPos),
        )

        val fracPos = KomorebiDeepLink.recordingUri(42, 12.5)
        assertEquals("komorebi://recording/42?position=12.5", fracPos)
        assertEquals(
            PlaybackOpenRequest.Recording(42, 12.5, PlaybackOpenSource.DeepLink),
            KomorebiDeepLink.parse(fracPos),
        )
    }
}
