@file:OptIn(UnstableApi::class)

package com.beeregg2001.komorebi.ui.player.live

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.BackendConfig
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.data.model.StreamSource
import com.beeregg2001.komorebi.data.repository.LiveProvider
import com.beeregg2001.komorebi.ui.player.PlayerRuntime
import com.beeregg2001.komorebi.util.TsReadExDataSourceFactory
import com.beeregg2001.komorebi.util.mmts.B60DataBroadcastingCallback
import com.beeregg2001.komorebi.util.mmts.B62SubtitleSample
import com.beeregg2001.komorebi.util.mmts.RawMmtsLayerController
import com.beeregg2001.komorebi.util.mmts.TlvExtractorsFactory
import com.beeregg2001.komorebi.util.playbackHttpDataSourceFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Resolves live-source URLs and MediaSources without owning an ExoPlayer.
 *
 * A caller supplies one TS factory per playback slot, preserving independent
 * direct-TS state for the main and dual players.
 */
class LivePlaybackSourceResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val liveProvider: LiveProvider,
    private val settingsRepository: SettingsRepository
) {
    data class Request(
        val url: String,
        val source: StreamSource,
        val isEdcbDirect: Boolean,
        val quality: StreamQuality,
        val config: BackendConfig
    ) {
        val apiQuality: String
            get() = if (quality.isRawMmts) StreamQuality.RAW_MMTS_PRIMARY_VALUE else quality.value
    }

    suspend fun resolve(
        channel: Channel,
        requestedSource: StreamSource,
        requestedIsEdcbDirect: Boolean,
        requestedQuality: StreamQuality,
        streamNumber: Int,
        factory: TsReadExDataSourceFactory
    ): Request {
        val isRawMmts = channel.type.equals("BS4K", ignoreCase = true)
        val quality = if (isRawMmts) {
            StreamQuality.rawMmtsQualities(channel)
                .firstOrNull { it.value == requestedQuality.value }
                ?: StreamQuality.rawMmtsQualities(channel).first()
        } else if (requestedQuality.isRawMmts) {
            val savedQuality = settingsRepository.liveQuality.first()
            StreamQuality(label = savedQuality, value = savedQuality)
        } else {
            requestedQuality
        }
        val source = if (isRawMmts) resolveRawMmtsSource(requestedSource) else requestedSource
        val isEdcbDirect = source == StreamSource.EDCB && requestedIsEdcbDirect
        val config = settingsRepository.getBackendConfig(source)
        val url = when (source) {
            StreamSource.EDCB -> {
                if (!isEdcbDirect) {
                    val hlsUrl = liveProvider.getLiveStreamUrl(channel.id, quality.value, streamNumber)
                    if (hlsUrl.isBlank()) throw IOException("HLSトランスコードの開始に失敗しました")
                    return Request(hlsUrl, source, false, quality, config)
                }
                val ip = config.ip.ifBlank { "127.0.0.1" }
                val port = config.port.ifBlank { "4510" }
                val parts = channel.id.split("_")
                val isEdcbFormat = parts.size >= 4 && parts[0].startsWith("edcb", ignoreCase = true)
                val onid = if (isEdcbFormat) parts[1] else channel.networkId.toString()
                val tsid = if (isEdcbFormat) parts[2] else channel.transportStreamId
                    .takeIf { it != 0L }?.toString() ?: channel.networkId.toString()
                val sid = if (isEdcbFormat) parts[3] else channel.serviceId.toString()
                factory.tsArgs = directTsArgs(sid)
                "edcb://$ip:$port/live?onid=$onid&tsid=$tsid&sid=$sid"
            }

            StreamSource.MIRAKURUN -> if (config.isValid) {
                if (quality.isRawMmts) {
                    UrlBuilder.getMirakurunRawMmtsStreamUrl(
                        config.ip, config.port, channel.networkId, channel.serviceId
                    )
                } else {
                    factory.tsArgs = directTsArgs(channel.serviceId.toString())
                    UrlBuilder.getMirakurunStreamUrl(
                        config.ip, config.port, channel.networkId, channel.serviceId
                    )
                }
            } else ""

            StreamSource.KONOMITV -> UrlBuilder.getKonomiTvLiveStreamUrl(
                config.ip,
                config.port,
                channel.displayChannelId,
                if (quality.isRawMmts) StreamQuality.RAW_MMTS_PRIMARY_VALUE else quality.value
            )
        }
        if (url.isBlank()) throw IOException("ストリーミングソースの設定が不完全です: $source")
        return Request(url, source, isEdcbDirect, quality, config)
    }

    fun createMediaSource(
        request: Request,
        factory: TsReadExDataSourceFactory,
        onSubtitleDataReceived: (Long, ByteArray) -> Unit,
        onB62SubtitleDataReceived: (B62SubtitleSample) -> Unit,
        dataBroadcastingCallback: B60DataBroadcastingCallback? = null,
        rawMmtsLayerController: RawMmtsLayerController? = null,
        acceptsCurrentSession: () -> Boolean = { true }
    ): MediaSource {
        val mediaItem = MediaItem.fromUri(request.url)
        return when {
            request.quality.isRawMmts -> {
                rawMmtsLayerController?.reset()
                val httpDataSourceFactory = playbackHttpDataSourceFactory(context)
                if (request.source == StreamSource.MIRAKURUN) {
                    httpDataSourceFactory.setDefaultRequestProperties(mapOf("X-Mirakurun-Priority" to "0"))
                }
                ProgressiveMediaSource.Factory(
                    httpDataSourceFactory,
                    TlvExtractorsFactory(
                        preferredVideoPacketId = request.quality.videoPacketId,
                        onTracksChanged = { if (acceptsCurrentSession()) rawMmtsLayerController?.updateTracks(it) },
                        onSubtitleDataReceived = onB62SubtitleDataReceived,
                        dataBroadcastingCallback = dataBroadcastingCallback
                    )
                ).createMediaSource(mediaItem)
            }

            request.source == StreamSource.MIRAKURUN ||
                (request.source == StreamSource.EDCB && request.isEdcbDirect) -> {
                val extractorsFactory = ExtractorsFactory {
                    arrayOf(
                        TsExtractor(
                            TsExtractor.MODE_SINGLE_PMT,
                            TimestampAdjuster(C.TIME_UNSET),
                            DirectSubtitlePayloadReaderFactory(onSubtitleDataReceived = onSubtitleDataReceived),
                            TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
                        )
                    )
                }
                ProgressiveMediaSource.Factory(factory, extractorsFactory).createMediaSource(mediaItem)
            }

            request.source == StreamSource.EDCB -> {
                val ctok = Uri.parse(request.url).getQueryParameter("ctok") ?: ""
                val httpDataSourceFactory = playbackHttpDataSourceFactory(context)
                    .setDefaultRequestProperties(mapOf("Cookie" to "ctok=$ctok"))
                HlsMediaSource.Factory(httpDataSourceFactory)
                    .setAllowChunklessPreparation(false)
                    .createMediaSource(mediaItem)
            }

            else -> DefaultMediaSourceFactory(context)
                .setDataSourceFactory(playbackHttpDataSourceFactory(context))
                .createMediaSource(mediaItem)
        }
    }

    fun attachRawMmtsLayerSelection(
        runtime: PlayerRuntime,
        request: Request,
        rawMmtsLayerController: RawMmtsLayerController,
        acceptsCurrentSession: () -> Boolean
    ) {
        if (!request.quality.isRawMmts) return
        val player = runtime.player
        lateinit var unregister: () -> Unit
        unregister = runtime.addListener(object : androidx.media3.common.Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (!acceptsCurrentSession()) return
                val parameters = rawMmtsLayerController.buildLayerSelection(
                    player,
                    request.quality.videoPacketId,
                    mainAudio = true
                ) ?: return
                unregister()
                player.trackSelectionParameters = parameters
            }
        })
    }

    private suspend fun resolveRawMmtsSource(requestedSource: StreamSource): StreamSource {
        if (requestedSource != StreamSource.EDCB && settingsRepository.getBackendConfig(requestedSource).isValid) {
            return requestedSource
        }
        if (settingsRepository.getBackendConfig(StreamSource.KONOMITV).isValid) return StreamSource.KONOMITV
        if (settingsRepository.getBackendConfig(StreamSource.MIRAKURUN).isValid) return StreamSource.MIRAKURUN
        throw IOException("BS4K/BS8K Raw MMTS には HonomiTV または Mirakurun の設定が必要です")
    }

    private fun directTsArgs(serviceId: String): Array<String> = arrayOf(
        "-x", "18/38/39", "-n", serviceId, "-a", "13", "-b", "4", "-c", "5", "-u", "1", "-d", "13"
    )
}
