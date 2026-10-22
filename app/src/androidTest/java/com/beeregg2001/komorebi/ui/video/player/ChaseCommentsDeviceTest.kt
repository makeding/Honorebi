package com.beeregg2001.komorebi.ui.video.player

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.beeregg2001.komorebi.data.jikkyo.JikkyoClient
import com.beeregg2001.komorebi.data.model.ArchivedJikkyoResponse
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap

/** Opt-in real HTTP/WS validation. Does not start media playback or change viewing history. */
@RunWith(AndroidJUnit4::class)
class ChaseCommentsDeviceTest {
    @Test fun realHistoryBackfillCoversArchivedRecordingAndSchedulesIt() {
        val args = InstrumentationRegistry.getArguments()
        val base = args.getString("chaseBackend")
        val video = args.getString("chaseVideo")
        val watch = args.getString("chaseWatch")
        assumeNotNull(base, video, watch)
        val http = OkHttpClient()
        fun read(path: String): String = http.newCall(Request.Builder().url("$base$path").build()).execute().use {
            assertEquals(200, it.code)
            it.body!!.string()
        }
        val gson = Gson()
        val program = gson.fromJson(read("/api/videos/$video"), RecordedProgram::class.java)
        val archive = gson.fromJson(read("/api/videos/$video/jikkyo"), ArchivedJikkyoResponse::class.java)
        assertTrue(archive.is_success)
        assertTrue("Need more than one history page", archive.comments.size > 500)
        val start = program.recordingStartUnixOrNull()!!
        val received = ConcurrentHashMap<String, com.beeregg2001.komorebi.data.model.ArchivedComment>()
        // Match content/time only: archive and live command styling may differ.
        fun key(c: com.beeregg2001.komorebi.data.model.ArchivedComment) =
            "${kotlin.math.round(c.time * 1000).toLong()}:${c.text}"
        val expected = archive.comments.map(::key).toSet()
        val client = JikkyoClient(watch!!)
        try {
            client.startChase(start.toDouble()) { raw ->
                parseChaseWsArchivedComment(raw, start)?.let { received[key(it)] = it }
            }
            val deadline = SystemClock.elapsedRealtime() + 45_000L
            while (SystemClock.elapsedRealtime() < deadline && !received.keys.containsAll(expected)) {
                Thread.sleep(100)
            }
            val matched = expected.count(received::containsKey)
            Log.i("ChaseDeviceTest", "video=$video archive=${expected.size} websocket=${received.size} matched=$matched")
            assertEquals("Real multi-page backfill must cover archive", expected.size, matched)
            val sorted = received.values.sortedBy { it.time }
            val target = archive.comments.first { it.time > 100.0 }
            assertTrue(ArchivedCommentSchedule().next(sorted, target.time).comments.any { key(it) == key(target) })
        } finally { client.stop() }
    }
}
