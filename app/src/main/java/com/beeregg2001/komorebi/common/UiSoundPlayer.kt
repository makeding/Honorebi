package com.beeregg2001.komorebi.common

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

internal enum class UiSoundEffect {
    MOVE,
    SELECT,
    TAB_SWITCH,
}

/**
 * Lightweight synthesized PCM micro-sound player for minimal console-style UI feedback.
 *
 * Uses static [AudioTrack] buffers with [AudioAttributes.USAGE_ASSISTANCE_SONIFICATION] so
 * no external audio assets are required and playback never blocks the UI thread.
 */
internal class UiSoundPlayer(
    private val nowMs: () -> Long = { SystemClock.uptimeMillis() },
) {
    @Volatile
    var enabled: Boolean = false

    private var lastMoveAtMs: Long = 0L
    private val tracks: Map<UiSoundEffect, AudioTrack?> by lazy(LazyThreadSafetyMode.NONE) {
        mapOf(
            UiSoundEffect.MOVE to buildStaticTrack(
                synthesizeTone(durationMs = 14, startHz = 540.0, endHz = 420.0, amplitude = 0.08),
            ),
            UiSoundEffect.SELECT to buildStaticTrack(
                synthesizeTone(durationMs = 26, startHz = 660.0, endHz = 880.0, amplitude = 0.11),
            ),
            UiSoundEffect.TAB_SWITCH to buildStaticTrack(
                synthesizeTone(durationMs = 34, startHz = 460.0, endHz = 620.0, amplitude = 0.10),
            ),
        )
    }

    fun play(effect: UiSoundEffect) {
        if (!enabled) return
        if (effect == UiSoundEffect.MOVE) {
            val now = nowMs()
            if (now - lastMoveAtMs < MOVE_COOLDOWN_MS) return
            lastMoveAtMs = now
        }
        val track = runCatching { tracks[effect] }.getOrNull() ?: return
        runCatching {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.stop()
            }
            track.reloadStaticData()
            track.play()
        }
    }

    fun release() {
        runCatching {
            tracks.values.forEach { track ->
                track?.run {
                    runCatching { stop() }
                    runCatching { release() }
                }
            }
        }
    }

    private fun buildStaticTrack(pcm: ShortArray): AudioTrack? = runCatching {
        val byteCount = pcm.size * 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(byteCount)
            .build()
        track.write(pcm, 0, pcm.size)
        track
    }.getOrNull()

    companion object {
        private const val SAMPLE_RATE_HZ = 24_000
        private const val MOVE_COOLDOWN_MS = 45L

        internal fun synthesizeTone(
            durationMs: Int,
            startHz: Double,
            endHz: Double,
            amplitude: Double,
        ): ShortArray {
            val sampleCount = (SAMPLE_RATE_HZ * durationMs / 1000).coerceAtLeast(1)
            val samples = ShortArray(sampleCount)
            var phase = 0.0
            val attackSamples = (sampleCount * 0.12).coerceAtLeast(1.0)
            for (i in 0 until sampleCount) {
                val progress = i.toDouble() / sampleCount
                val freq = startHz + (endHz - startHz) * progress
                phase += 2.0 * PI * freq / SAMPLE_RATE_HZ
                val attack = if (i < attackSamples) i / attackSamples else 1.0
                val decay = exp(-4.5 * progress)
                val value = sin(phase) * amplitude * attack * decay
                samples[i] = (value * Short.MAX_VALUE).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
            return samples
        }
    }
}

@Composable
internal fun rememberUiSoundPlayer(enabled: Boolean): UiSoundPlayer {
    val context = LocalContext.current
    val systemSoundEffectsEnabled = remember(context, enabled) {
        if (!enabled) {
            false
        } else {
            runCatching {
                Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.SOUND_EFFECTS_ENABLED,
                    1,
                ) != 0
            }.getOrDefault(true)
        }
    }
    val player = remember { UiSoundPlayer() }
    player.enabled = enabled && systemSoundEffectsEnabled
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    return player
}
