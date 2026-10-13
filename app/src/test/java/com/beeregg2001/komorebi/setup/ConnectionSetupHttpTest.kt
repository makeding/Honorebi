package com.beeregg2001.komorebi.setup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.*
import com.google.gson.GsonBuilder
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ConnectionSetupHttpTest {
    @Test fun actualHttpSaveReadbackAndScopedSessionUsePersistedRepository() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val repository = SettingsRepository(context)
        val gson = GsonBuilder().serializeNulls().create()
        var session: ConnectionSetupSession? = ConnectionSetupSession("test-token")
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing {
                connectionSetupRoutes({ session }, { repository.connectionSettings() },
                    { repository.saveConnectionSettings(it) },
                    { path -> context.assets.open(path).use { it.readBytes() } })
            }
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = OkHttpClient()
            fun request(path: String, snapshot: ConnectionSettingsSnapshot? = null, token: String = "test-token"): Pair<Int, String> {
                val builder = Request.Builder().url("http://127.0.0.1:$port$path").header("X-Setup-Token", token)
                if (snapshot != null) builder.post(gson.toJson(snapshot).toRequestBody("application/json".toMediaType()))
                return client.newCall(builder.build()).execute().use { it.code to it.body!!.string() }
            }
            val initial = repository.connectionSettings()
            val one = initial.copy(sources = initial.sources.map {
                it.copy(host = if (it.id == "EDCB") "192.168.1.20" else "")
            }, mainSystem = "KONOMITV")
            val first = request("/api/connections", one)
            assertEquals(first.second, 200, first.first)
            val saved = gson.fromJson(first.second, ConnectionSettingsSnapshot::class.java)
            assertEquals("EDCB", saved.mainSystem)
            assertEquals(saved, repository.connectionSettings())
            assertEquals("EDCB", repository.backendType.first())
            assertTrue(repository.isInitialized.first())
            val reloaded = SettingsRepository(context)
            assertEquals(saved, reloaded.connectionSettings())
            val readback = request("/api/connections")
            assertEquals(200, readback.first)
            assertEquals(saved, gson.fromJson(readback.second, ConnectionSettingsSnapshot::class.java))

            val two = saved.copy(sources = saved.sources.map {
                if (it.id == "KONOMITV") it.copy(host = "https://tv.example.org", port = "443") else it
            }, mainSystem = "EDCB")
            assertEquals(200, request("/api/connections", two).first)
            assertEquals("EDCB", repository.backendType.first())
            assertEquals(200, request("/api/connections", two.copy(mainSystem = "KONOMITV")).first)
            val current = repository.connectionSettings()
            val bad = current.copy(sources = current.sources.map { if (it.id == "EDCB") it.copy(port = "0") else it })
            val rejected = request("/api/connections", bad)
            assertEquals(400, rejected.first)
            assertTrue(rejected.second.contains("INVALID_CONNECTION"))
            assertEquals(current, repository.connectionSettings())
            assertEquals(401, request("/api/connections", token = "wrong").first)

            session = ConnectionSetupSession("test-token", "EDCB")
            val scopedRead = gson.fromJson(request("/api/connections").second, ConnectionSettingsSnapshot::class.java)
            assertEquals("EDCB", scopedRead.scope)
            val scoped = current.copy(sources = current.sources.map { if (it.id == "EDCB") it.copy(host = "192.168.1.21") else it })
            assertEquals(200, request("/api/connections", scoped).first)
            val outsideScope = scoped.copy(sources = scoped.sources.map { if (it.id == "KONOMITV") it.copy(host = "https://changed.example.org") else it })
            assertEquals(400, request("/api/connections", outsideScope).first)
            assertEquals(scoped, repository.connectionSettings())
            session = null
            assertEquals(401, request("/api/connections").first)
            assertEquals(401, request("/api/connections", one).first)

            val html = request("/connections/")
            assertEquals(html.second, 200, html.first)
            assertTrue(html.second.contains("/connections/assets/"))
            val assets = Regex("(?:src|href)=\"(/connections/assets/[^\"]+)\"").findAll(html.second).map { it.groupValues[1] }.toList()
            assertTrue(assets.isNotEmpty())
            assets.forEach { assertEquals(it, 200, request(it).first) }
        } finally { server.stop(0, 1000) }
    }

    @Test fun placeholderAndUnconfiguredSelectionCannotOverrideAConfiguredSource() {
        val configured = ConnectionSourcePolicy.configured(ConnectionSourcePolicy.KONOMI_PLACEHOLDER, "7000",
            "", "4510", "192.168.1.10", "40772")
        assertEquals(listOf(ConnectionSource.MIRAKURUN), configured)
        assertEquals("MIRAKURUN_ONLY", ConnectionSourcePolicy.resolve("KONOMITV", configured))
        assertEquals("KONOMITV", ConnectionSourcePolicy.resolve(null, emptyList()))
    }
}
