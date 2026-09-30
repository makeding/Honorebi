package com.beeregg2001.komorebi.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NHKExclusionModeTest {
    @Test
    fun classifiesReportedMirakurunAndKonomiNhkServiceNames() {
        val mirakurunNames = listOf(
            "ＮＨＫ総合１・東京", "ＮＨＫ総合２・東京", "ＮＨＫ携帯Ｇ・東京",
            "ＮＨＫ総合１・大阪", "ＮＨＫ総合２・大阪", "ＮＨＫ携帯Ｇ・大阪",
            "ＮＨＫ総合１・神戸", "ＮＨＫ総合２・神戸", "ＮＨＫ携帯Ｇ・神戸",
            "ＮＨＫＥテレ１東京", "ＮＨＫＥテレ２東京", "ＮＨＫＥテレ３東京", "ＮＨＫ携帯２",
            "ＮＨＫＥテレ１大阪", "ＮＨＫＥテレ２大阪", "ＮＨＫＥテレ３大阪", "ＮＨＫ携帯２",
            "ＮＨＫ　ＢＳ", "ＮＨＫ　ＢＳ", "ＮＨＫ　ＢＳＰ４Ｋ", "ＮＨＫ　ＢＳ８Ｋ",
        )
        val konomiNames = listOf(
            "NHK総合1・神戸", "NHK総合1・大阪", "NHK総合1・東京",
            "NHK総合2・神戸", "NHK総合2・大阪", "NHK総合2・東京",
            "NHKEテレ1大阪", "NHKEテレ1東京", "NHKEテレ2大阪", "NHKEテレ2東京",
            "NHKEテレ3大阪", "NHKEテレ3東京", "NHK BS", "NHK BS", "NHK BSP4K", "NHK BS8K",
        )

        assertEquals(21, mirakurunNames.size)
        assertEquals(16, konomiNames.size)
        assertTrue(mirakurunNames.all(NHKChannelClassifier::isNHKChannel))
        assertTrue(konomiNames.all(NHKChannelClassifier::isNHKChannel))
    }

    @Test
    fun doesNotClassifyNonNhkChannelsByProgramLikeNames() {
        listOf(null, "   ", "日本テレビ", "ＴＢＳテレビ", "テレビ朝日", "BS日テレ", "Eスポーツチャンネル", "").forEach {
            assertFalse(it, NHKChannelClassifier.isNHKChannel(it))
        }
        assertTrue(NHKChannelClassifier.isNHKChannel(" nhk bs "))
    }

    @Test
    fun persistedStateExpiresTemporaryOnlyAndPreservesPermanentMode() {
        val now = 1_000_000L
        val temporary = NHKExclusionState(NHKExclusionMode.TEMPORARY, now + 100, isLoaded = true)

        assertTrue(temporary.atTime(now + 99).isActive)
        assertEquals(NHKExclusionMode.OFF, temporary.atTime(now + 100).mode)
        assertEquals(null, temporary.atTime(now + 100).expiresAtMillis)
        assertEquals(
            NHKExclusionState(NHKExclusionMode.ON, isLoaded = true),
            NHKExclusionState(NHKExclusionMode.ON, isLoaded = true).atTime(now + 100),
        )
    }

    @Test
    fun temporaryEnableRetriggersExpiryWithoutDowngradingPermanentMode() {
        val now = 2_000_000L
        val temporary = NHKExclusionState(NHKExclusionMode.TEMPORARY, now - 1, isLoaded = true)
            .temporarilyEnabled(now)

        assertEquals(NHKExclusionMode.TEMPORARY, temporary.mode)
        assertEquals(now + TEMPORARY_NHK_HIDE_MILLIS, temporary.expiresAtMillis)
        assertEquals(
            NHKExclusionState(NHKExclusionMode.ON, isLoaded = true),
            NHKExclusionState(NHKExclusionMode.ON, isLoaded = true).temporarilyEnabled(now),
        )
    }
}
