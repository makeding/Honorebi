package com.beeregg2001.komorebi.data.watchnext

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.beeregg2001.komorebi.navigation.KomorebiDeepLink
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchNextPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun isSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    @RequiresApi(Build.VERSION_CODES.O)
    fun queryExisting(): List<ExistingWatchNextRow> {
        if (!isSupported()) return emptyList()
        val projection = arrayOf(
            TvContract.WatchNextPrograms._ID,
            TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
            TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
        )
        return runResolverSafely(emptyList()) {
            context.contentResolver.query(
                TvContract.WatchNextPrograms.CONTENT_URI,
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(TvContract.WatchNextPrograms._ID)
                val internalIdIdx = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)
                val browsableIdx = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_BROWSABLE)
                val engagementIdx =
                    cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS)
                val typeIdx = cursor.getColumnIndex(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE)
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
                        val engagementMillis = if (engagementIdx < 0 || cursor.isNull(engagementIdx)) {
                            0L
                        } else {
                            cursor.getLong(engagementIdx)
                        }
                        val kind = if (typeIdx < 0 || cursor.isNull(typeIdx)) {
                            null
                        } else {
                            when (cursor.getInt(typeIdx)) {
                                TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE -> WatchNextKind.CONTINUE
                                TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEW -> WatchNextKind.NEW
                                else -> null
                            }
                        }
                        add(
                            ExistingWatchNextRow(
                                rowId = rowId,
                                internalId = internalId,
                                browsable = browsable,
                                engagementMillis = engagementMillis,
                                kind = kind,
                            )
                        )
                    }
                }
            }.orEmpty()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun apply(ops: List<WatchNextOp>, posterUris: Map<Int, Uri?>) {
        if (!isSupported() || ops.isEmpty()) return
        for (op in ops) {
            when (op) {
                is WatchNextOp.Insert -> {
                    runResolverSafely(Unit) {
                        val values = toContentValues(op.candidate, posterUris[op.candidate.recordedProgramId])
                        context.contentResolver.insert(TvContract.WatchNextPrograms.CONTENT_URI, values)
                    }
                }

                is WatchNextOp.Update -> {
                    runResolverSafely(Unit) {
                        val values = toContentValues(op.candidate, posterUris[op.candidate.recordedProgramId])
                        context.contentResolver.update(
                            TvContract.buildWatchNextProgramUri(op.rowId),
                            values,
                            null,
                            null,
                        )
                    }
                }

                is WatchNextOp.Delete -> {
                    runResolverSafely(Unit) {
                        context.contentResolver.delete(
                            TvContract.buildWatchNextProgramUri(op.rowId),
                            null,
                            null,
                        )
                    }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun clearAll() {
        if (!isSupported()) return
        val rows = queryExisting()
        for (row in rows) {
            if (!row.internalId.startsWith(WatchNextPlanner.INTERNAL_ID_PREFIX)) continue
            runResolverSafely(Unit) {
                context.contentResolver.delete(
                    TvContract.buildWatchNextProgramUri(row.rowId),
                    null,
                    null,
                )
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun toContentValues(candidate: WatchNextCandidate, posterUri: Uri?): ContentValues {
        val intentUri = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(KomorebiDeepLink.recordingUri(candidate.recordedProgramId)),
        )
            .setPackage(context.packageName)
            .toUri(Intent.URI_INTENT_SCHEME)

        return ContentValues().apply {
            put(TvContract.WatchNextPrograms.COLUMN_TYPE, TvContract.PreviewPrograms.TYPE_TV_EPISODE)
            put(
                TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
                when (candidate.kind) {
                    WatchNextKind.CONTINUE -> TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
                    WatchNextKind.NEW -> TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEW
                },
            )
            put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, candidate.engagementMillis)
            put(TvContract.WatchNextPrograms.COLUMN_TITLE, candidate.title)
            if (!candidate.description.isNullOrBlank()) {
                put(TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION, candidate.description)
            } else {
                putNull(TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION)
            }
            if (candidate.kind == WatchNextKind.CONTINUE &&
                candidate.durationMillis != null &&
                candidate.positionMillis != null
            ) {
                put(
                    TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
                    candidate.durationMillis.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                )
                put(
                    TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                    candidate.positionMillis.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                )
            } else {
                putNull(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS)
                putNull(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS)
            }
            if (posterUri != null) {
                put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, posterUri.toString())
                put(
                    TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
                    TvContract.PreviewPrograms.ASPECT_RATIO_16_9,
                )
            }
            put(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, candidate.internalId)
            put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, intentUri)
        }
    }

    private inline fun <T> runResolverSafely(default: T, block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        Log.w(TAG, "WatchNext resolver security error: ${e.message}")
        default
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "WatchNext resolver argument error: ${e.message}")
        default
    } catch (e: IllegalStateException) {
        Log.w(TAG, "WatchNext resolver state error: ${e.message}")
        default
    }

    private companion object {
        const val TAG = "WatchNext"
    }
}
