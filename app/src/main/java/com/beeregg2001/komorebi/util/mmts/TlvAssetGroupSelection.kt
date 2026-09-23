package com.beeregg2001.komorebi.util.mmts

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.beeregg2001.komorebi.data.model.StreamQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
    val audioMainComponent: Boolean = false,
    val trackId: Long = 0L,
    val contextId: Long = 0L
)

internal data class TlvLayerSelection(
    val videoPacketId: Int,
    val audioPacketId: Int
)

internal data class TlvLayerPair(
    val preferred: TlvLayerSelection,
    val fallback: TlvLayerSelection
)

/** Resolve only a complete A/V pair from tracks in the current MPT. */
internal fun resolveCurrentTlvLayerPair(
    tracks: List<TlvTrackInfo>,
    selectedAudioPacketId: Int? = null
): TlvLayerPair? {
    val videos = tracks.filter { it.kind == TlvTrackKind.VIDEO }
        .sortedBy { video -> video.assetGroups.minOfOrNull { it.selectionLevel } ?: 0 }
    val preferred = videos.firstOrNull() ?: return null
    val preferredGroup = preferred.assetGroups.minByOrNull { it.selectionLevel }
    val preferredLevel = preferredGroup?.selectionLevel ?: 0
    val fallback = videos.filter { video ->
        video.contextId == preferred.contextId && video.packetId != preferred.packetId &&
            video.assetGroups.any { group ->
                (preferredGroup == null || group.groupIdentification == preferredGroup.groupIdentification) &&
                    group.selectionLevel > preferredLevel
            }
    }.minByOrNull { video -> video.assetGroups.filter { group ->
        (preferredGroup == null || group.groupIdentification == preferredGroup.groupIdentification) &&
            group.selectionLevel > preferredLevel
    }.minOf { it.selectionLevel } } ?: return null
    val fallbackLevel = fallback.assetGroups.filter {
        (preferredGroup == null || it.groupIdentification == preferredGroup.groupIdentification) &&
            it.selectionLevel > preferredLevel
    }.minOf { it.selectionLevel }
    val audio = tracks.filter { it.kind == TlvTrackKind.AUDIO && it.contextId == preferred.contextId }
    val selectedAudio = audio.firstOrNull { it.packetId == selectedAudioPacketId }
    val audioGroupIds = selectedAudio?.assetGroups?.map { it.groupIdentification }.orEmpty() +
        audio.filter { it.audioMainComponent }.flatMap { it.assetGroups.map(TlvAssetGroup::groupIdentification) }
    for (groupId in audioGroupIds.distinct()) {
        val primaryAudio = audio.firstOrNull { track -> track.assetGroups.any {
            it.groupIdentification == groupId && it.selectionLevel == preferredLevel
        } } ?: continue
        val rainAudio = audio.firstOrNull { track -> track.assetGroups.any {
            it.groupIdentification == groupId && it.selectionLevel == fallbackLevel
        } } ?: continue
        return TlvLayerPair(
            TlvLayerSelection(preferred.packetId, primaryAudio.packetId),
            TlvLayerSelection(fallback.packetId, rainAudio.packetId)
        )
    }
    return null
}

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
    private val _qualities = MutableStateFlow(autoQualities())
    val qualities: StateFlow<List<StreamQuality>> = _qualities
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var demuxer: NativeTlvDemuxer? = null
    private var player: ExoPlayer? = null
    private var isCurrent: () -> Boolean = { false }
    private var onNotice: (String) -> Unit = {}
    private var generation = 0
    private var firstFrameRendered = false
    private var playbackClock: Runnable? = null
    private var pendingSwitch: TlvLayerSelection? = null
    private var pendingTimeout: Runnable? = null
    private var layerModeVideoPacketId: Int? = null

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            firstFrameRendered = true
        }

        override fun onTracksChanged(tracks: Tracks) {
            val pending = pendingSwitch
            if (pending == null) {
                demuxer?.setLayerMode(layerModeVideoPacketId,
                    tracks.selectedPacketId(C.TRACK_TYPE_VIDEO),
                    tracks.selectedPacketId(C.TRACK_TYPE_AUDIO))
                return
            }
            if (tracks.selectedPacketId(C.TRACK_TYPE_VIDEO) == pending.videoPacketId &&
                tracks.selectedPacketId(C.TRACK_TYPE_AUDIO) == pending.audioPacketId) {
                pendingSwitch = null
                pendingTimeout?.let(mainHandler::removeCallbacks)
                pendingTimeout = null
                demuxer?.completeLayerSwitch(true)
                onNotice(if (pending.videoPacketId == resolveCurrentTlvLayerPair(this@RawMmtsLayerController.tracks)?.fallback?.videoPacketId) {
                    "降雨放送へ切り替えました"
                } else {
                    "通常放送へ戻りました"
                })
            }
        }
    }

    fun attachDemuxer(value: NativeTlvDemuxer) {
        demuxer = value
        value.setLayerMode(layerModeVideoPacketId, null, null)
    }

    fun attachPlayer(value: ExoPlayer, acceptsCurrentSession: () -> Boolean, notice: (String) -> Unit) {
        player?.removeListener(listener)
        player = value
        isCurrent = acceptsCurrentSession
        onNotice = notice
        firstFrameRendered = false
        value.addListener(listener)
        val attachedGeneration = generation
        val tick = object : Runnable {
            override fun run() {
                if (attachedGeneration != generation || !isCurrent()) return
                if (value.isPlaying && value.playbackState == Player.STATE_READY) {
                    demuxer?.setPlaybackPosition(value.currentPosition.coerceAtLeast(0L) * 1_000L,
                        firstFrameRendered)
                }
                mainHandler.postDelayed(this, 250L)
            }
        }
        playbackClock = tick
        mainHandler.post(tick)
    }

    fun setLayerMode(videoPacketId: Int?, audioPacketId: Int?) {
        layerModeVideoPacketId = videoPacketId
        demuxer?.setLayerMode(videoPacketId,
            player?.currentTracks?.selectedPacketId(C.TRACK_TYPE_VIDEO),
            audioPacketId ?: player?.currentTracks?.selectedPacketId(C.TRACK_TYPE_AUDIO))
    }

    fun requestAutomaticSwitch(videoPacketId: Int, audioPacketId: Int) {
        val requestedGeneration = generation
        mainHandler.post {
            val activePlayer = player
            val pair = resolveCurrentTlvLayerPair(tracks,
                activePlayer?.currentTracks?.selectedPacketId(C.TRACK_TYPE_AUDIO))
            val target = TlvLayerSelection(videoPacketId, audioPacketId)
            if (requestedGeneration != generation || !isCurrent() || activePlayer == null ||
                !activePlayer.playWhenReady ||
                layerModeVideoPacketId != null || pendingSwitch != null ||
                (pair?.preferred != target && pair?.fallback != target)) {
                demuxer?.completeLayerSwitch(false)
                return@post
            }
            val parameters = buildExactLayerSelection(activePlayer, target)
            if (parameters == null) {
                demuxer?.completeLayerSwitch(false)
                onNotice("切り替え先の放送画質が利用できません。画質を自動で選び直してください")
                return@post
            }
            pendingSwitch = target
            val previousParameters = activePlayer.trackSelectionParameters
            activePlayer.trackSelectionParameters = parameters
            val timeout = Runnable {
                if (pendingSwitch == target && requestedGeneration == generation) {
                    pendingSwitch = null
                    activePlayer.trackSelectionParameters = previousParameters
                    demuxer?.completeLayerSwitch(false)
                    onNotice("放送画質を切り替えられませんでした。画質を自動で選び直してください")
                }
            }
            pendingTimeout = timeout
            mainHandler.postDelayed(timeout, 2_000L)
        }
    }

    fun updateTracks(value: List<TlvTrackInfo>) {
        tracks = value
        val pair = resolveCurrentTlvLayerPair(value)
        _qualities.value = if (pair == null) autoQualities() else autoQualities() + listOf(
            StreamQuality("TLV パススルー（通常放送）", StreamQuality.RAW_MMTS_PREFERRED_VALUE,
                isRawMmts = true, videoPacketId = pair.preferred.videoPacketId),
            StreamQuality("TLV パススルー（降雨放送）", StreamQuality.RAW_MMTS_SECONDARY_VALUE,
                isRawMmts = true, videoPacketId = pair.fallback.videoPacketId)
        )
    }

    fun reset() {
        generation++
        playbackClock?.let(mainHandler::removeCallbacks)
        pendingTimeout?.let(mainHandler::removeCallbacks)
        player?.removeListener(listener)
        player = null
        demuxer = null
        pendingSwitch = null
        pendingTimeout = null
        playbackClock = null
        layerModeVideoPacketId = null
        firstFrameRendered = false
        tracks = emptyList()
        _qualities.value = autoQualities()
    }

    private fun autoQualities() = listOf(StreamQuality(
        "TLV パススルー（自動）", StreamQuality.RAW_MMTS_PRIMARY_VALUE, isRawMmts = true
    ))

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

    private fun buildExactLayerSelection(
        player: ExoPlayer,
        selection: TlvLayerSelection
    ): TrackSelectionParameters? {
        val videoGroup = player.currentTracks.groupFor(C.TRACK_TYPE_VIDEO, selection.videoPacketId)
            ?: return null
        val audioGroup = player.currentTracks.groupFor(C.TRACK_TYPE_AUDIO, selection.audioPacketId)
            ?: return null
        return player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .addOverride(TrackSelectionOverride(videoGroup.mediaTrackGroup, 0))
            .addOverride(TrackSelectionOverride(audioGroup.mediaTrackGroup, 0))
            .build()
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
