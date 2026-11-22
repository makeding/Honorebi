package com.beeregg2001.komorebi.data.watchnext

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

class WatchNextArtworkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? =
        if (parseArtworkId(uri.pathSegments) != null) MIME_TYPE_JPEG else null

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException("WatchNextArtworkProvider is read-only")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        throw UnsupportedOperationException("WatchNextArtworkProvider is read-only")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        throw UnsupportedOperationException("WatchNextArtworkProvider is read-only")
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") {
            throw FileNotFoundException("WatchNextArtworkProvider only supports read-only mode")
        }
        val id = parseArtworkId(uri.pathSegments)
            ?: throw FileNotFoundException("Invalid WatchNext artwork URI: $uri")
        val ctx = context ?: throw FileNotFoundException("Provider context unavailable")
        val rootDir = WatchNextArtworkCache.directory(ctx).canonicalFile
        val file = WatchNextArtworkCache.file(ctx, id).canonicalFile
        if (file.parentFile != rootDir || !file.isFile || file.length() <= 0L) {
            throw FileNotFoundException("Artwork not cached for id=$id")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    companion object {
        private const val MIME_TYPE_JPEG = "image/jpeg"
        private const val SEGMENT_REC = "rec"

        internal fun parseArtworkId(segments: List<String>): Int? {
            if (segments.size != 2) return null
            if (segments[0] != SEGMENT_REC) return null
            val rawId = segments[1]
            if (rawId.isEmpty() || !rawId.all { it in '0'..'9' }) return null
            return rawId.toIntOrNull()?.takeIf { it > 0 }
        }
    }
}
