package com.beeregg2001.komorebi.data.api.interceptor

import com.beeregg2001.komorebi.data.api.KonomiApi
import com.beeregg2001.komorebi.data.api.LiveSessionBackendTarget
import com.beeregg2001.komorebi.data.model.LiveStreamSessionRequest
import com.beeregg2001.komorebi.data.repository.LiveStreamSessionLease
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class LiveSessionBackendTargetTest {
    @Test
    fun createAndReleaseStayOnOriginalBackendAfterSettingsChange() = runBlocking {
        val original = MockWebServer()
        val replacement = MockWebServer()
        original.start()
        replacement.start()
        var configured = original.url("/").toString()
        val client = OkHttpClient.Builder().addInterceptor(BackendOriginInterceptor { configured }).build()
        try {
            original.enqueue(MockResponse().setBody("""{"id":"session-1","stream_url":"/play","stream_type":"hls"}"""))
            original.enqueue(MockResponse().setResponseCode(204))
            val api = Retrofit.Builder().baseUrl(original.url("/"))
                .client(client).addConverterFactory(GsonConverterFactory.create()).build()
                .create(KonomiApi::class.java)
            val target = LiveSessionBackendTarget(configured)
            val response = api.createLiveStreamSession(LiveStreamSessionRequest("jellyfin-1"), target)
            val lease = LiveStreamSessionLease(response) { id ->
                assertTrue(api.closeLiveStreamSession(id, target).isSuccessful)
            }
            configured = replacement.url("/").toString()
            lease.close()
            lease.close()
            val created = original.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("POST", created.method)
            assertEquals("/api/streams/live/sessions", created.path)
            assertEquals("""{"channel_id":"jellyfin-1"}""", created.body.readUtf8())
            val released = original.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("DELETE", released.method)
            assertEquals("/api/streams/live/sessions/session-1", released.path)
            assertEquals(2, original.requestCount)
            assertEquals(0, replacement.requestCount)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            original.close()
            replacement.close()
        }
    }
}
