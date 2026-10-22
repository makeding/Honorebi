package com.beeregg2001.komorebi.ui.components

import com.beeregg2001.komorebi.data.model.RecordedProgram

fun RecordedProgram.isRecordingInProgress(): Boolean =
    isRecording || recordedVideo.status.equals("Recording", ignoreCase = true)

/**
 * ブラウズ画面 (グリッドカード/リスト行) でこの録画を再生できるか。
 * 録画中はストリーム再生の可能性があるため再生可能とする。
 * MainRootBackground 側の再生許可条件と同一規則で、グリッドとリストの有効/無効を一致させる。
 */
fun RecordedProgram.isPlayableForBrowse(): Boolean =
    isRecordingInProgress() || (recordedVideo.hasKeyFrames ?: true)

/** 録画データの更新で未生成サムネイルのキャッシュも更新する。完成済みの URL は維持する。 */
fun RecordedProgram.recordedThumbnailModel(thumbnailUrl: String?): String? {
    if (thumbnailUrl == null || !isRecordingInProgress()) return thumbnailUrl
    val revision = (recordedVideo.duration.coerceAtLeast(0.0) / 30.0).toLong()
    val fragment = thumbnailUrl.substringAfter('#', "")
    val base = thumbnailUrl.substringBefore('#')
    val separator = if ('?' in base) '&' else '?'
    return "$base${separator}recording_revision=$revision" +
        if (fragment.isEmpty()) "" else "#$fragment"
}

fun RecordedProgram.recordedThumbnailCacheKey(thumbnailUrl: String?): String? =
    recordedThumbnailModel(thumbnailUrl)
