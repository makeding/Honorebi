package com.beeregg2001.komorebi.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structural guardrails for the live/recorded player convergence. */
class UnifiedPlayerArchitectureTest {

    private val sourceRoot: File
        get() {
            val fromApp = File("src/main")
            return if (fromApp.isDirectory) fromApp else File("app/src/main")
        }

    private fun sourceFiles(): List<File> = sourceRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    private fun read(relativePath: String): String =
        File(sourceRoot, relativePath).readText()

    @Test
    fun exoPlayerBuilder_hasOneCanonicalOwner() {
        val builderOwners = sourceFiles().filter { file ->
            Regex("\\bExoPlayer\\.Builder\\s*\\(").containsMatchIn(file.readText())
        }
        assertEquals(listOf(File(sourceRoot, "java/com/beeregg2001/komorebi/ui/player/PlayerFactory.kt")), builderOwners)
    }

    @Test
    fun mainRootState_doesNotRemainAPlaybackDelegate() {
        val source = read("java/com/beeregg2001/komorebi/ui/main/MainRootState.kt")
        assertFalse("MainRootState must not contain forwarding getters", Regex("get\\(\\)\\s*=\\s*playbackState\\.").containsMatchIn(source))
        assertFalse("MainRootState must not contain forwarding setters", Regex("set\\(value\\)\\s*\\{[^}]*playbackState\\.").containsMatchIn(source))
        assertFalse("MainRootState must not instantiate the superseded state", source.contains("MainRootPlaybackState()"))
    }

    @Test
    fun supersededPlayerEntrypoints_areRemoved() {
        assertFalse(File(sourceRoot, "java/com/beeregg2001/komorebi/ui/live/LivePlayerFactory.kt").exists())
        assertFalse(File(sourceRoot, "java/com/beeregg2001/komorebi/ui/video/CustomPlayerManager.kt").exists())
        assertFalse(File(sourceRoot, "java/com/beeregg2001/komorebi/ui/live/DataBroadcastingCompatibility.kt").exists())
        assertFalse(File(sourceRoot, "java/com/beeregg2001/komorebi/ui/live/DataBroadcastingLayout.kt").exists())
        assertFalse(File(sourceRoot, "java/com/beeregg2001/komorebi/ui/live/DataBroadcastingDirectionShortcut.kt").exists())
        assertFalse(File(sourceRoot, "java/com/beeregg2001/komorebi/ui/live/LiveCommentOverlay.kt").exists())
        assertFalse(File(sourceRoot, "java/com/beeregg2001/komorebi/ui/subtitle/RecordedCaptionSource.kt").exists())
        assertFalse(File(sourceRoot, "java/com/beeregg2001/komorebi/ui/live/DataBroadcastingWebViewOverlay.kt").exists())
        assertFalse(File(sourceRoot, "java/com/beeregg2001/komorebi/ui/live/LivePlayerSubtitleLogic.kt").exists())
        assertTrue(File(sourceRoot, "java/com/beeregg2001/komorebi/ui/player/DataBroadcastingWebViewOverlay.kt").exists())
        assertTrue(File(sourceRoot, "java/com/beeregg2001/komorebi/ui/player/live/DirectSubtitlePayloadReader.kt").exists())
    }

    @Test
    fun recordedPlayer_doesNotDependOnLivePlayerState() {
        val recordedFiles = File(sourceRoot, "java/com/beeregg2001/komorebi/ui/video/player")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        assertTrue(recordedFiles.isNotEmpty())
        recordedFiles.forEach { file ->
            val source = file.readText()
            assertFalse("${file.name} imports LivePlayerState", source.contains("LivePlayerState"))
            assertFalse("${file.name} imports rememberLivePlayerState", source.contains("rememberLivePlayerState"))
        }
    }

    @Test
    fun smbPlayback_hasAnIndependentProgramlessEntryPoint() {
        val source = read("java/com/beeregg2001/komorebi/ui/main/MainRootPlaybackHost.kt")
        val smbBranch = source.substringAfter("is PlaybackTarget.Smb")
            .substringBefore("PlaybackTarget.None")
        assertTrue("SMB must pass a dedicated metadata model", smbBranch.contains("smbMetadata"))
        assertTrue("SMB must enter without a RecordedProgram", Regex("program\\s*=\\s*null").containsMatchIn(smbBranch))
        assertFalse("SMB must not use a synthetic base program", Regex("\\b(baseProgram|dummyProgram)\\b").containsMatchIn(smbBranch))
    }
}
