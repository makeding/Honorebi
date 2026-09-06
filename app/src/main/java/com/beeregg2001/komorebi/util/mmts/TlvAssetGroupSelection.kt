package com.beeregg2001.komorebi.util.mmts

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer

data class TlvAssetGroup(
    val groupIdentification: Int,
    val selectionLevel: Int
)

enum class TlvTrackKind {
    VIDEO,
    AUDIO
}

data class TlvTrackInfo(
    val packetId: Int,
    val kind: TlvTrackKind,
    val assetGroups: List<TlvAssetGroup>,
    val audioMainComponent: Boolean = false
)

internal data class TlvLayerSelection(
    val videoPacketId: Int,
    val audioPacketId: Int
)

internal fun isAudioLayerCompatible(
    videoSelectionLevels: Set<Int>,
    audioSelectionLevels: IntArray
): Boolean =
    videoSelectionLevels.isEmpty() ||
        audioSelectionLevels.isEmpty() ||
        audioSelectionLevels.any(videoSelectionLevels::contains)

internal fun selectTlvLayer(
    tracks: List<TlvTrackInfo>,
    requestedVideoPacketId: Int?,
    selectedAudioPacketId: Int?,
    preferredMainAudio: Boolean? = null
): TlvLayerSelection? {
    val videoTracks = tracks.filter { it.kind == TlvTrackKind.VIDEO }
    val video = if (requestedVideoPacketId != null) {
        videoTracks.firstOrNull { it.packetId == requestedVideoPacketId }
    } else {
        videoTracks.firstOrNull { track ->
            track.assetGroups.isEmpty() || track.assetGroups.any { it.selectionLevel == 0 }
        }
    } ?: return null

    val videoLevels = video.assetGroups.mapTo(linkedSetOf()) { it.selectionLevel }
    val compatibleAudio = tracks.filter { track ->
        track.kind == TlvTrackKind.AUDIO &&
            isAudioLayerCompatible(
                videoLevels,
                track.assetGroups.map { it.selectionLevel }.toIntArray()
            )
    }
    if (compatibleAudio.isEmpty()) return null

    val selectedAudio = tracks.firstOrNull {
        it.kind == TlvTrackKind.AUDIO && it.packetId == selectedAudioPacketId
    }
    val selectedGroupIds = selectedAudio?.assetGroups
        ?.mapTo(linkedSetOf()) { it.groupIdentification }
        .orEmpty()
    val audio = when (preferredMainAudio) {
        null -> compatibleAudio.firstOrNull { candidate ->
            candidate.assetGroups.any { it.groupIdentification in selectedGroupIds }
        }
        else -> compatibleAudio.firstOrNull { it.audioMainComponent == preferredMainAudio }
    } ?: if (preferredMainAudio == null) {
        compatibleAudio.firstOrNull { it.audioMainComponent } ?: compatibleAudio.first()
    } else {
        return null
    }

    return TlvLayerSelection(video.packetId, audio.packetId)
}

class RawMmtsLayerController {
    @Volatile
    private var tracks: List<TlvTrackInfo> = emptyList()

    fun updateTracks(value: List<TlvTrackInfo>) {
        tracks = value
    }

    fun reset() {
        tracks = emptyList()
    }

    fun buildLayerSelection(
        player: ExoPlayer,
        requestedVideoPacketId: Int?,
        mainAudio: Boolean
    ): TrackSelectionParameters? = buildSelection(
        player = player,
        requestedVideoPacketId = requestedVideoPacketId,
        preferredMainAudio = mainAudio
    )

    fun buildAudioSelection(
        player: ExoPlayer,
        mainAudio: Boolean
    ): TrackSelectionParameters? {
        val selectedVideoPacketId = player.currentTracks.selectedPacketId(C.TRACK_TYPE_VIDEO)
            ?: return null
        return buildSelection(
            player = player,
            requestedVideoPacketId = selectedVideoPacketId,
            preferredMainAudio = mainAudio
        )
    }

    private fun buildSelection(
        player: ExoPlayer,
        requestedVideoPacketId: Int?,
        preferredMainAudio: Boolean?
    ): TrackSelectionParameters? {
        val currentTracks = player.currentTracks
        val selection = selectTlvLayer(
            tracks = tracks,
            requestedVideoPacketId = requestedVideoPacketId,
            selectedAudioPacketId = currentTracks.selectedPacketId(C.TRACK_TYPE_AUDIO),
            preferredMainAudio = preferredMainAudio
        ) ?: return null
        val videoGroup = currentTracks.groupFor(C.TRACK_TYPE_VIDEO, selection.videoPacketId)
            ?: return null
        val audioGroup = currentTracks.groupFor(C.TRACK_TYPE_AUDIO, selection.audioPacketId)
            ?: return null
        return player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .addOverride(TrackSelectionOverride(videoGroup.mediaTrackGroup, 0))
            .addOverride(TrackSelectionOverride(audioGroup.mediaTrackGroup, 0))
            .build()
    }
}

private fun Tracks.selectedPacketId(trackType: Int): Int? = groups
    .firstOrNull { group -> group.type == trackType && group.isTrackSelected(0) }
    ?.packetId()

private fun Tracks.groupFor(trackType: Int, packetId: Int): Tracks.Group? = groups
    .firstOrNull { group -> group.type == trackType && group.packetId() == packetId }

private fun Tracks.Group.packetId(): Int? = mediaTrackGroup
    .getFormat(0)
    .id
    ?.substringAfterLast('/')
    ?.toIntOrNull()
