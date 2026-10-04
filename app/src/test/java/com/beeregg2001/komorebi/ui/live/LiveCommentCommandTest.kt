package com.beeregg2001.komorebi.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveCommentCommandTest {
    @Test fun colorNamesAndAliasesKeepTheirBroadcastColors() {
        assertEquals("#FFEAEA", LiveJikkyoManager.getCommentColor("white"))
        assertEquals("#F02840", LiveJikkyoManager.getCommentColor("red"))
        assertEquals("#1E1310", LiveJikkyoManager.getCommentColor("black"))
        assertEquals("#CCCC99", LiveJikkyoManager.getCommentColor("niconicowhite"))
        assertEquals("#CC0033", LiveJikkyoManager.getCommentColor("truered"))
        assertEquals("#3399FF", LiveJikkyoManager.getCommentColor("marineblue"))
        assertEquals("#6633CC", LiveJikkyoManager.getCommentColor("nobleviolet"))
    }

    @Test fun hexColorsPassThroughAndInvalidCommandsRemainUnrecognized() {
        assertEquals("#aB12fF", LiveJikkyoManager.getCommentColor("#aB12fF"))
        for (command in listOf("", "RED", "184", "unknown", "#fff", "#1234567", "#GG0000")) {
            assertNull(LiveJikkyoManager.getCommentColor(command))
        }
    }

    @Test fun positionsAndSizesKeepTheirKnownTokensAndIgnoreOthers() {
        assertEquals("top", LiveJikkyoManager.getCommentPosition("ue"))
        assertEquals("right", LiveJikkyoManager.getCommentPosition("naka"))
        assertEquals("bottom", LiveJikkyoManager.getCommentPosition("shita"))
        assertNull(LiveJikkyoManager.getCommentPosition("top"))
        for (size in listOf("big", "medium", "small")) {
            assertEquals(size, LiveJikkyoManager.getCommentSize(size))
        }
        assertNull(LiveJikkyoManager.getCommentSize("unknown"))
    }
}
