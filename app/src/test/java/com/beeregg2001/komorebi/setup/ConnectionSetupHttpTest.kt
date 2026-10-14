package com.beeregg2001.komorebi.setup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.*
import com.google.gson.GsonBuilder
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.routing.post
import io.ktor.server.response.respondText
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
    @Test fun accountPairingPollsOnTheTvAndAllAccountRoutesRequireAnOpenQrSession() = runBlocking {
        val active = ConnectionSetupSession()
        var user: String? = null
        val paired = java.util.concurrent.CountDownLatch(1)
        var creates = 0
        var logouts = 0
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing {
                connectionAccountRoutes({ active }, { ConnectionAccountSettings(user) }, { "https://tv.example.org:443" },
                    {
                        creates++
                        com.beeregg2001.komorebi.data.model.DeviceAuthRequest("private-device-code", "USERCODE",
                            "https://tv.example.org/device-auth", 30, 1)
                    }, { _, session ->
                        assertTrue(session.accepts(active.token))
                        user = "huggy"; paired.countDown(); ConnectionAccountSettings(user)
                    }, { user = null; logouts++ })
            }
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val client = OkHttpClient()
            fun request(path: String, post: Boolean = false, token: String = active.token): Pair<Int, String> {
                val builder = Request.Builder().url("http://127.0.0.1:$port/api/connections/account$path")
                    .header("X-Setup-Token", token)
                if (post) builder.post("{}".toRequestBody("application/json".toMediaType()))
                return client.newCall(builder.build()).execute().use { it.code to it.body!!.string() }
            }
            assertEquals(401, request("", token = "").first)
            assertEquals(401, request("/pair", true, "wrong").first)
            assertEquals(401, request("/logout", true, "wrong").first)
            assertEquals(0, creates); assertEquals(0, logouts)
            val start = request("/pair", true)
            assertEquals(start.second, 200, start.first)
            assertTrue(start.second.contains("https://tv.example.org/device-auth"))
            assertFalse(start.second.contains("private-device-code"))
            assertEquals(200, request("/pair", true).first)
            assertEquals(1, creates)
            // No browser status polling: the TV still obtains and saves the provider session.
            assertTrue(paired.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val status = request("/pair")
            assertEquals(200, status.first); assertTrue(status.second.contains("success")); assertTrue(status.second.contains("huggy"))
            assertEquals(200, request("/logout", true).first); assertNull(user); assertEquals(1, logouts)
            active.revoke()
            assertEquals(401, request("").first)
            assertEquals(401, request("/pair", true).first)
            assertEquals(401, request("/logout", true).first)
            assertEquals(1, creates); assertEquals(1, logouts)
        } finally { active.revoke(); server.stop(0, 1000) }
    }

    @Test fun actualHttpSaveReadbackAndSessionLifetimeUsePersistedRepository() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val repository = SettingsRepository(context)
        val gson = GsonBuilder().serializeNulls().create()
        var session: ConnectionSetupSession? = ConnectionSetupSession("test-token")
        val browserPreview = System.getenv("HONOREBI_SETUP_BROWSER_PREVIEW") == "1"
        val browserFinished = java.util.concurrent.CountDownLatch(1)
        val server = embeddedServer(CIO, host = "127.0.0.1", port = if (browserPreview) 8081 else 0) {
            routing {
                connectionSetupRoutes({ session }, { repository.connectionSettings() },
                    { repository.saveConnectionSettings(it) },
                    { path -> context.assets.open(path).use { it.readBytes() } })
                if (browserPreview) {
                    connectionAccountRoutes({ session }, { ConnectionAccountSettings() }, { "https://changed.example.org:443" },
                        { error("Browser acceptance does not contact a provider") }, { _, _ -> ConnectionAccountSettings() }, {})
                    post("/__test__/finish") {
                        if (session?.accepts(call.request.headers["X-Setup-Token"]) == true) {
                            browserFinished.countDown()
                            call.respondText("finished")
                        } else call.respondText("unauthorized", status = io.ktor.http.HttpStatusCode.Unauthorized)
                    }
                }
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
            // Browser saveBody deliberately omits read-only account/labels/presence flags and unchanged secrets.
            val browserBody = gson.toJsonTree(saved).asJsonObject.apply {
                remove("account")
                getAsJsonArray("sources").forEach { it.asJsonObject.remove("label") }
                getAsJsonObject("cloudflareAccess").apply { remove("clientSecret"); remove("secretConfigured") }
            }
            val browserRequest = Request.Builder().url("http://127.0.0.1:$port/api/connections")
                .header("X-Setup-Token", "test-token")
                .post(gson.toJson(browserBody).toRequestBody("application/json".toMediaType())).build()
            client.newCall(browserRequest).execute().use {
                assertEquals(it.body!!.string(), 200, it.code)
            }
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
            val expanded = repository.connectionSettings().copy(
                preferredSource = "MIRAKURUN", edcbRecordPlayMethod = "API",
                cloudflareAccess = ConnectionAccessSettings("access-id", "access-secret"),
                smbServers = listOf(ConnectionSmbSettings("nas", "NAS", "192.168.1.50", "445", "user", "smb-secret")),
            )
            val full = request("/api/connections", expanded)
            assertEquals(full.second, 200, full.first)
            assertFalse(full.second.contains("access-secret")); assertFalse(full.second.contains("smb-secret"))
            val redacted = gson.fromJson(full.second, ConnectionSettingsSnapshot::class.java)
            assertTrue(redacted.cloudflareAccess.secretConfigured)
            assertTrue(redacted.smbServers.single().passwordConfigured)
            assertEquals("MIRAKURUN", redacted.preferredSource); assertEquals("API", redacted.edcbRecordPlayMethod)
            assertEquals(200, request("/api/connections", redacted.copy(smbServers = redacted.smbServers.map { it.copy(name = "Renamed") })).first)
            assertTrue(repository.connectionSettings().smbServers.single().passwordConfigured)
            assertTrue(repository.connectionSettings().cloudflareAccess.secretConfigured)
            val invalidAccess = request("/api/connections", redacted.copy(cloudflareAccess = ConnectionAccessSettings("", null)))
            assertEquals(400, invalidAccess.first)
            val current = repository.connectionSettings()
            val bad = current.copy(sources = current.sources.map { if (it.id == "EDCB") it.copy(port = "0") else it })
            val rejected = request("/api/connections", bad)
            assertEquals(400, rejected.first)
            assertTrue(rejected.second.contains("INVALID_CONNECTION"))
            assertEquals(current, repository.connectionSettings())
            assertEquals(401, request("/api/connections", token = "wrong").first)

            session = ConnectionSetupSession("test-token", "EDCB")
            val scoped = current.copy(sources = current.sources.map { if (it.id == "EDCB") it.copy(host = "192.168.1.21") else it })
            assertEquals(200, request("/api/connections", scoped).first)
            val otherSource = scoped.copy(sources = scoped.sources.map { if (it.id == "KONOMITV") it.copy(host = "https://changed.example.org") else it })
            assertEquals(200, request("/api/connections", otherSource).first)
            assertEquals(otherSource, repository.connectionSettings())
            assertEquals(200, request("/api/connections").first) // same link works for repeated visits
            session!!.revoke()
            assertEquals(401, request("/api/connections").first)
            assertEquals(401, request("/api/connections", one).first)
            assertEquals(otherSource, repository.connectionSettings())
            session = ConnectionSetupSession() // reopening generates a different secret
            assertEquals(43, session!!.token.length)
            assertEquals(401, request("/api/connections").first)
            assertEquals(200, request("/api/connections", token = session!!.token).first)
            assertEquals(401, request("/api/connections?token=${session!!.token}", token = "").first)
            session = null
            assertEquals(401, request("/api/connections").first)
            assertEquals(401, request("/api/connections", one).first)

            val html = request("/connections/")
            assertEquals(html.second, 200, html.first)
            assertTrue(html.second.contains("/connections/assets/"))
            val assets = Regex("(?:src|href)=\"(/connections/assets/[^\"]+)\"").findAll(html.second).map { it.groupValues[1] }.toList()
            assertTrue(assets.isNotEmpty())
            assets.forEach { assertEquals(it, 200, request(it).first) }
            if (browserPreview) {
                session = ConnectionSetupSession("browser-acceptance")
                assertTrue("Browser acceptance must finish within five minutes", browserFinished.await(300, java.util.concurrent.TimeUnit.SECONDS))
            }
        } finally { session?.revoke(); server.stop(0, 1000) }
    }

    @Test fun placeholderAndUnconfiguredSelectionCannotOverrideAConfiguredSource() {
        val configured = ConnectionSourcePolicy.configured(ConnectionSourcePolicy.KONOMI_PLACEHOLDER, "7000",
            "", "4510", "192.168.1.10", "40772")
        assertEquals(listOf(ConnectionSource.MIRAKURUN), configured)
        assertEquals("MIRAKURUN_ONLY", ConnectionSourcePolicy.resolve("KONOMITV", configured))
        assertEquals("KONOMITV", ConnectionSourcePolicy.resolve(null, emptyList()))
    }
}
