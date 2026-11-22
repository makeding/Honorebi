package com.beeregg2001.komorebi.data.previewchannels

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.SQLException
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreviewChannelPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Suppress("DEPRECATION")
    fun isSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
            context.packageManager.resolveContentProvider(TvContract.AUTHORITY, 0) != null

    @RequiresApi(Build.VERSION_CODES.O)
    fun findChannelId(kind: PreviewChannelKind): Long? {
        if (!isSupported()) return null
        val matchingIds = queryChannelIds(kind)
        if (matchingIds.isEmpty()) return null
        val sorted = matchingIds.sortedDescending()
        for (duplicateId in sorted.drop(1)) {
            runResolverSafely(Unit) {
                context.contentResolver.delete(
                    TvContract.buildChannelUri(duplicateId),
                    null,
                    null,
                )
            }
        }
        return sorted.first()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun ensureChannel(kind: PreviewChannelKind): Long? {
        if (!isSupported()) return null
        val values = toChannelContentValues(kind)
        val existingId = findChannelId(kind)
        if (existingId != null) {
            runResolverSafely(Unit) {
                context.contentResolver.update(
                    TvContract.buildChannelUri(existingId),
                    values,
                    null,
                    null,
                )
            }
            return existingId
        }

        val insertedUri = runResolverSafely<Uri?>(null) {
            context.contentResolver.insert(TvContract.Channels.CONTENT_URI, values)
        } ?: return null

        val channelId = runCatching { ContentUris.parseId(insertedUri) }
            .getOrNull()
            ?.takeIf { it > 0L }
            ?: return null

        writeChannelLogo(channelId)
        runResolverSafely(Unit) {
            TvContract.requestChannelBrowsable(context, channelId)
        }
        return channelId
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun deleteChannel(kind: PreviewChannelKind) {
        if (!isSupported()) return
        val ids = queryChannelIds(kind)
        for (id in ids) {
            runResolverSafely(Unit) {
                context.contentResolver.delete(
                    TvContract.buildChannelUri(id),
                    null,
                    null,
                )
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun clearAll() {
        if (!isSupported()) return
        for (kind in PreviewChannelKind.entries) {
            deleteChannel(kind)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun queryPrograms(channelId: Long): List<ExistingPreviewProgram> {
        if (!isSupported() || channelId <= 0L) return emptyList()
        val projection = arrayOf(
            TvContract.PreviewPrograms._ID,
            TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID,
            TvContract.PreviewPrograms.COLUMN_BROWSABLE,
        )
        return runResolverSafely(emptyList()) {
            context.contentResolver.query(
                TvContract.buildPreviewProgramsUriForChannel(channelId),
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(TvContract.PreviewPrograms._ID)
                val internalIdIdx =
                    cursor.getColumnIndex(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID)
                val browsableIdx = cursor.getColumnIndex(TvContract.PreviewPrograms.COLUMN_BROWSABLE)
                if (idIdx < 0 || internalIdIdx < 0) return@use emptyList()

                buildList {
                    while (cursor.moveToNext()) {
                        val rowId = cursor.getLong(idIdx)
                        val internalId = cursor.getString(internalIdIdx).orEmpty()
                        val browsable = if (browsableIdx < 0 || cursor.isNull(browsableIdx)) {
                            true
                        } else {
                            cursor.getInt(browsableIdx) != 0
                        }
                        add(
                            ExistingPreviewProgram(
                                rowId = rowId,
                                internalId = internalId,
                                browsable = browsable,
                            )
                        )
                    }
                }
            }.orEmpty()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun apply(
        channelId: Long,
        ops: List<PreviewProgramOp>,
        posterUris: Map<String, Uri?>,
    ) {
        if (!isSupported() || channelId <= 0L || ops.isEmpty()) return
        for (op in ops) {
            when (op) {
                is PreviewProgramOp.Insert -> {
                    runResolverSafely(Unit) {
                        val values = toProgramContentValues(
                            channelId = channelId,
                            candidate = op.candidate,
                            posterUri = posterUris[op.candidate.internalId],
                        )
                        context.contentResolver.insert(TvContract.PreviewPrograms.CONTENT_URI, values)
                    }
                }

                is PreviewProgramOp.Update -> {
                    runResolverSafely(Unit) {
                        val values = toProgramContentValues(
                            channelId = channelId,
                            candidate = op.candidate,
                            posterUri = posterUris[op.candidate.internalId],
                        )
                        context.contentResolver.update(
                            TvContract.buildPreviewProgramUri(op.rowId),
                            values,
                            null,
                            null,
                        )
                    }
                }

                is PreviewProgramOp.Delete -> {
                    runResolverSafely(Unit) {
                        context.contentResolver.delete(
                            TvContract.buildPreviewProgramUri(op.rowId),
                            null,
                            null,
                        )
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun queryChannelIds(kind: PreviewChannelKind): List<Long> {
        val projection = arrayOf(
            TvContract.Channels._ID,
            TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID,
        )
        return runResolverSafely(emptyList()) {
            context.contentResolver.query(
                TvContract.Channels.CONTENT_URI,
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(TvContract.Channels._ID)
                val internalIdIdx =
                    cursor.getColumnIndex(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID)
                if (idIdx < 0 || internalIdIdx < 0) return@use emptyList()

                buildList {
                    while (cursor.moveToNext()) {
                        val internalId = cursor.getString(internalIdIdx)
                        if (internalId == kind.internalProviderId) {
                            add(cursor.getLong(idIdx))
                        }
                    }
                }
            }.orEmpty()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun toChannelContentValues(kind: PreviewChannelKind): ContentValues {
        val launchIntent = context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)
            ?: context.packageManager.getLaunchIntentForPackage(context.packageName)
        return ContentValues().apply {
            put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW)
            put(TvContract.Channels.COLUMN_DISPLAY_NAME, kind.displayName)
            put(TvContract.Channels.COLUMN_DESCRIPTION, kind.description)
            put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, kind.internalProviderId)
            launchIntent?.toUri(Intent.URI_INTENT_SCHEME)?.let {
                put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI, it)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun writeChannelLogo(channelId: Long) {
        val bitmap = renderAppIconBitmap() ?: return
        try {
            runResolverSafely(Unit) {
                try {
                    context.contentResolver.openOutputStream(
                        TvContract.buildChannelLogoUri(channelId)
                    )?.use { stream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                        stream.flush()
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "Failed to write preview channel logo for id=$channelId: ${e.message}")
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun renderAppIconBitmap(): Bitmap? = try {
        val drawable = context.applicationInfo.loadIcon(context.packageManager)
        val width = drawable.intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(CHANNEL_LOGO_SIZE_PX)
            ?: CHANNEL_LOGO_SIZE_PX
        val height = drawable.intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(CHANNEL_LOGO_SIZE_PX)
            ?: CHANNEL_LOGO_SIZE_PX
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        bitmap
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to render preview channel logo bitmap: ${t.message}")
        null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun toProgramContentValues(
        channelId: Long,
        candidate: PreviewProgramCandidate,
        posterUri: Uri?,
    ): ContentValues {
        val intentUri = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(candidate.deepLink),
        )
            .setPackage(context.packageName)
            .toUri(Intent.URI_INTENT_SCHEME)

        return ContentValues().apply {
            put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, channelId)
            put(
                TvContract.PreviewPrograms.COLUMN_TYPE,
                when (candidate.kind) {
                    PreviewChannelKind.LIVE -> TvContract.PreviewPrograms.TYPE_CHANNEL
                    PreviewChannelKind.RECORDINGS -> TvContract.PreviewPrograms.TYPE_TV_EPISODE
                },
            )
            put(TvContract.PreviewPrograms.COLUMN_TITLE, candidate.title)
            if (!candidate.description.isNullOrBlank()) {
                put(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION, candidate.description)
            } else {
                putNull(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION)
            }

            when (candidate.kind) {
                PreviewChannelKind.LIVE -> {
                    put(TvContract.PreviewPrograms.COLUMN_LIVE, 1)
                    if (candidate.startMillis != null) {
                        put(
                            TvContract.PreviewPrograms.COLUMN_START_TIME_UTC_MILLIS,
                            candidate.startMillis,
                        )
                    } else {
                        putNull(TvContract.PreviewPrograms.COLUMN_START_TIME_UTC_MILLIS)
                    }
                    if (candidate.endMillis != null) {
                        put(
                            TvContract.PreviewPrograms.COLUMN_END_TIME_UTC_MILLIS,
                            candidate.endMillis,
                        )
                    } else {
                        putNull(TvContract.PreviewPrograms.COLUMN_END_TIME_UTC_MILLIS)
                    }
                    putNull(TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS)
                }

                PreviewChannelKind.RECORDINGS -> {
                    put(TvContract.PreviewPrograms.COLUMN_LIVE, 0)
                    putNull(TvContract.PreviewPrograms.COLUMN_START_TIME_UTC_MILLIS)
                    putNull(TvContract.PreviewPrograms.COLUMN_END_TIME_UTC_MILLIS)
                    if (candidate.durationMillis != null && candidate.durationMillis > 0L) {
                        put(
                            TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS,
                            candidate.durationMillis.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                        )
                    } else {
                        putNull(TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS)
                    }
                }
            }

            if (posterUri != null) {
                put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, posterUri.toString())
                put(
                    TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
                    TvContract.PreviewPrograms.ASPECT_RATIO_16_9,
                )
            } else {
                putNull(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI)
            }

            put(TvContract.PreviewPrograms.COLUMN_INTENT_URI, intentUri)
            put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, candidate.internalId)
            put(TvContract.PreviewPrograms.COLUMN_WEIGHT, candidate.weight)
        }
    }

    private inline fun <T> runResolverSafely(default: T, block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        Log.w(TAG, "PreviewChannels resolver security error: ${e.message}")
        default
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "PreviewChannels resolver argument error: ${e.message}")
        default
    } catch (e: IllegalStateException) {
        Log.w(TAG, "PreviewChannels resolver state error: ${e.message}")
        default
    } catch (e: UnsupportedOperationException) {
        Log.w(TAG, "PreviewChannels resolver unsupported error: ${e.message}")
        default
    } catch (e: SQLException) {
        Log.w(TAG, "PreviewChannels resolver SQL error: ${e.message}")
        default
    }

    private companion object {
        const val TAG = "PreviewChannels"
        const val CHANNEL_LOGO_SIZE_PX = 160
    }
}
