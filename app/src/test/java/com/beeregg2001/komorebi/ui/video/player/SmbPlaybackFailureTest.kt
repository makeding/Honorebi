package com.beeregg2001.komorebi.ui.video.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class SmbPlaybackFailureTest {
    @Test fun networkFailureIsSafeAndActionable() {
        val error = PlaybackException(
            "smb://user:secret@server/private/video.ts",
            null,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        )

        val failure = smbPlaybackFailure(error)

        assertTrue(failure.message.contains("接続を確認"))
        assertEquals("SMB_ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", failure.code)
        assertFalse(failure.message.contains("secret"))
        assertFalse(failure.code.contains("server"))
    }

    @Test fun unsupportedContainerHasSpecificReason() {
        val error = PlaybackException(
            "raw parser detail",
            null,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        )

        val failure = smbPlaybackFailure(error)

        assertTrue(failure.message.contains("ファイル形式"))
        assertEquals("SMB_ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED", failure.code)
    }
}
