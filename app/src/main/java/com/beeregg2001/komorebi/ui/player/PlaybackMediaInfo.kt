package com.beeregg2001.komorebi.ui.player

import com.beeregg2001.komorebi.data.model.CmSection
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.ThumbnailInfo
import com.beeregg2001.komorebi.ui.video.smb.SmbPlaybackMetadata

/** Display data shared by controls without assigning SMB a recorded-program identity. */
data class PlaybackMediaInfo(
    val stableId: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val thumbnailInfo: ThumbnailInfo? = null,
    val cmSections: List<CmSection> = emptyList(),
) {
    companion object {
        fun recorded(program: RecordedProgram) = PlaybackMediaInfo(
            stableId = "recorded:${program.id}",
            title = program.title,
            thumbnailUrl = program.directThumbnailUrl ?: program.apiThumbnailUrl,
            thumbnailInfo = program.recordedVideo.thumbnailInfo,
            cmSections = program.recordedVideo.cmSections.orEmpty(),
        )

        fun smb(metadata: SmbPlaybackMetadata) = PlaybackMediaInfo(
            stableId = metadata.stableId,
            title = metadata.title,
            thumbnailUrl = metadata.thumbnailUrl,
        )
    }
}
