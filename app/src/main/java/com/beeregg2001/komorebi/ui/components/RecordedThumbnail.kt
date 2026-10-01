package com.beeregg2001.komorebi.ui.components

import com.beeregg2001.komorebi.R
import com.beeregg2001.komorebi.data.model.RecordedProgram

private const val RECORDING_THUMBNAIL_CACHE_KEY = "komorebi:recording-thumbnail-placeholder:v1"

fun RecordedProgram.isRecordingInProgress(): Boolean =
    isRecording || recordedVideo.status.equals("Recording", ignoreCase = true)

/**
 * ブラウズ画面 (グリッドカード/リスト行) でこの録画を再生できるか。
 * 録画中はストリーム再生の可能性があるため再生可能とする。
 * MainRootBackground 側の再生許可条件と同一規則で、グリッドとリストの有効/無効を一致させる。
 */
fun RecordedProgram.isPlayableForBrowse(): Boolean =
    isRecordingInProgress() || (recordedVideo.hasKeyFrames ?: true)

fun RecordedProgram.recordedThumbnailModel(thumbnailUrl: String?): Any? =
    if (isRecordingInProgress()) R.drawable.dark_image else thumbnailUrl

fun RecordedProgram.recordedThumbnailCacheKey(thumbnailUrl: String?): String? =
    if (isRecordingInProgress()) RECORDING_THUMBNAIL_CACHE_KEY else thumbnailUrl
