package com.beeregg2001.komorebi.data.repository.tif

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowContextImpl

private const val INPUT = "test.input/.Service"

@Implements(TvInputManager::class)
class TestTvInputManager {
    @Implementation fun getTvInputList(): List<TvInputInfo> =
        listOf(Shadow.newInstanceOf(TvInputInfo::class.java))
}

@Implements(TvInputInfo::class)
class TestTvInputInfo {
    @Implementation fun getId() = INPUT
    @Implementation fun isPassthroughInput() = false
    @Implementation fun isHidden(context: Context) = false
    @Implementation fun loadCustomLabel(context: Context): CharSequence? = null
    @Implementation fun loadLabel(context: Context): CharSequence = "Test tuner"
}

class TestTvProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val columns = requireNotNull(projection)
        val cursor = MatrixCursor(columns)
        val now = System.currentTimeMillis()
        fun row(values: Map<String, Any?>) = cursor.addRow(columns.map { values[it] })
        when {
            uri.pathSegments.firstOrNull() == "channel" -> row(mapOf(
                "_id" to 42L, "input_id" to INPUT, "display_number" to "1",
                "display_name" to "Fixture", "original_network_id" to 1L,
                "transport_stream_id" to 2L, "service_id" to 3L, "type" to "TYPE_ISDB_T",
                "browsable" to 1, "searchable" to 1, "service_type" to "SERVICE_TYPE_AUDIO_VIDEO",
            ))
            uri.pathSegments.firstOrNull() == "program" -> {
                row(mapOf("_id" to 1L, "start_time_utc_millis" to now - 60_000,
                    "end_time_utc_millis" to now + 60_000, "title" to "Present",
                    "broadcast_genre" to "", "canonical_genre" to ""))
                row(mapOf("_id" to 2L, "start_time_utc_millis" to now + 60_000,
                    "end_time_utc_millis" to now + 120_000, "title" to "Following",
                    "broadcast_genre" to "", "canonical_genre" to ""))
            }
        }
        return cursor
    }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [TestTvInputManager::class, TestTvInputInfo::class])
class TifRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Before fun setup() {
        shadowOf(context).grantPermissions(TifInput.READ_LISTINGS_PERMISSION)
        Shadow.extract<ShadowContextImpl>(context.baseContext).setSystemService(
            Context.TV_INPUT_SERVICE, Shadow.newInstanceOf(TvInputManager::class.java),
        )
        ShadowContentResolver.registerProviderInternal(TvContract.AUTHORITY, TestTvProvider())
    }

    @Test fun tunerChannelAndProgramsKeepInputOwnership() = runBlocking {
        val result = TifRepository(context).getChannels()
        val channel = result.terrestrial.orEmpty().single()
        assertEquals(TifInput.channelId(INPUT, 42), channel.id)
        assertEquals("Fixture", channel.name)
        assertEquals("Present", channel.programPresent?.title)
        assertEquals("Following", channel.programFollowing?.title)
        val target = TifInput.resolveTarget(context, channel.id)
        assertEquals(INPUT, target?.inputId)
        assertEquals(TvContract.buildChannelUri(42), target?.channelUri)
        assertNull(TifInput.resolveTarget(context, TifInput.channelId("other/.Input", 42)))
    }
}
