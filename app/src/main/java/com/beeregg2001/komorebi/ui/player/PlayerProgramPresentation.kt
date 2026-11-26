package com.beeregg2001.komorebi.ui.player

import com.beeregg2001.komorebi.ui.video.smb.SmbPlaybackMetadata
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val smbModified12hFormat = DateTimeFormatter.ofPattern("yyyy/MM/dd a h:mm", Locale.JAPAN)
private val smbModified24hFormat = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.JAPAN)

/**
 * Content for the fixed [PlayerProgramPanel].  Sources supply only facts they own;
 * in particular SMB never invents an EPG programme or broadcast schedule.
 */
data class PlayerProgramPresentation(
    val channelName: String,
    val logoUrl: String = "",
    val shouldCropLogo: Boolean = false,
    val title: String,
    val description: String?,
    val detail: Map<String, String>? = null,
    val metadata: List<Pair<String, String>>,
) {
    companion object {
        fun smb(metadata: SmbPlaybackMetadata, timeFormat: String): PlayerProgramPresentation {
            val formatter = if (timeFormat == "12h") smbModified12hFormat else smbModified24hFormat
            val modified = metadata.lastModifiedMs.takeIf { it > 0L }?.let {
                Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(formatter)
            } ?: "記録されていません"
            return PlayerProgramPresentation(
                channelName = "ローカルファイル",
                title = metadata.title,
                description = "ローカルファイルです。放送番組情報はありません。",
                metadata = listOf(
                    "場所" to metadata.location,
                    "サイズ" to "%,d bytes".format(Locale.JAPAN, metadata.sizeBytes.coerceAtLeast(0L)),
                    "更新日時" to modified,
                ),
            )
        }
    }
}
