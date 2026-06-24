package com.beeregg2001.komorebi.ui.components

import com.beeregg2001.komorebi.R
import com.beeregg2001.komorebi.data.model.RecordedProgram

private const val RECORDING_THUMBNAIL_CACHE_KEY = "komorebi:recording-thumbnail-placeholder:v1"

fun RecordedProgram.isRecordingInProgress(): Boolean =
    isRecording || recordedVideo.status.equals("Recording", ignoreCase = true)

fun RecordedProgram.recordedThumbnailModel(thumbnailUrl: String?): Any? =
    if (isRecordingInProgress()) R.drawable.dark_image else thumbnailUrl

fun RecordedProgram.recordedThumbnailCacheKey(thumbnailUrl: String?): String? =
    if (isRecordingInProgress()) RECORDING_THUMBNAIL_CACHE_KEY else thumbnailUrl
