package com.beeregg2001.komorebi.setup

import com.beeregg2001.komorebi.data.ConnectionAccountSettings
import com.beeregg2001.komorebi.data.model.DeviceAuthRequest
import com.google.gson.GsonBuilder
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** Provider device codes stay on the TV; the browser receives only the verification link/code. */
fun Route.connectionAccountRoutes(
    session: () -> ConnectionSetupSession?,
    account: () -> ConnectionAccountSettings,
    origin: suspend () -> String,
    create: suspend () -> DeviceAuthRequest,
    poll: suspend (DeviceAuthRequest, ConnectionSetupSession) -> ConnectionAccountSettings?,
    logout: () -> Unit,
) {
    val gson = GsonBuilder().serializeNulls().create()
    var pairingSession: ConnectionSetupSession? = null
    var pairing: DeviceAuthRequest? = null
    var pairingOrigin = ""
    var deadline = 0L
    var nextPoll = 0L

    suspend fun guarded(call: io.ktor.server.application.ApplicationCall, action: suspend (ConnectionSetupSession) -> Any) {
        call.response.headers.append("Cache-Control", "no-store")
        val current = session()
        if (current == null) { call.setupExpired(); return }
        try {
            val result = current.authorized(call.request.headers["X-Setup-Token"]) { action(current) }
            call.respondText(gson.toJson(result), ContentType.Application.Json)
        } catch (error: Exception) { call.setupFailure(error, "ACCOUNT_FAILED") }
    }

    get("/api/connections/account") { guarded(call) { account() } }
    post("/api/connections/account/pair") {
        guarded(call) { current ->
            val currentOrigin = origin()
            val active = pairing.takeIf { pairingSession === current && pairingOrigin == currentOrigin && System.currentTimeMillis() < deadline }
                ?: create().also {
                    pairingSession = current; pairing = it; pairingOrigin = currentOrigin
                    deadline = System.currentTimeMillis() + it.expiresIn * 1000L
                    nextPoll = System.currentTimeMillis() + it.interval.coerceAtLeast(1) * 1000L
                }
            mapOf("verificationUrl" to active.verificationUrl, "userCode" to active.userCode)
        }
    }
    get("/api/connections/account/pair") {
        guarded(call) { current ->
            val active = pairing
            if (active == null || pairingSession !== current || pairingOrigin != origin() || System.currentTimeMillis() >= deadline) {
                pairing = null
                mapOf("status" to "expired", "userName" to account().userName)
            } else {
                val user = account().takeIf { it.userName != null } ?: if (System.currentTimeMillis() >= nextPoll) {
                    nextPoll = System.currentTimeMillis() + active.interval.coerceAtLeast(1) * 1000L
                    try { poll(active, current) } catch (error: Exception) { pairing = null; throw error }
                } else null
                if (user != null) pairing = null
                mapOf("status" to if (user == null) "waiting" else "success", "userName" to user?.userName)
            }
        }
    }
    post("/api/connections/account/logout") {
        guarded(call) {
            logout(); pairing = null
            account()
        }
    }
}
