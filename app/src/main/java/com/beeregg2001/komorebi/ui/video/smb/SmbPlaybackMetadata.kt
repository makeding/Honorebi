package com.beeregg2001.komorebi.ui.video.smb


/**
 * Local-file presentation data. It deliberately has no recorded-program identity, provider
 * relation, EPG dates, channel, history, or stream-session fields.
 */
data class SmbPlaybackMetadata(
    val stableId: String,
    val title: String,
    val location: String,
    val thumbnailUrl: String?,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
) {
    companion object {
        fun from(item: SmbItem) = SmbPlaybackMetadata(
            stableId = "smb:${item.path}",
            title = item.name,
            location = item.path,
            thumbnailUrl = item.thumbnailUrl,
            sizeBytes = item.size,
            lastModifiedMs = item.lastModified,
        )
    }
}
