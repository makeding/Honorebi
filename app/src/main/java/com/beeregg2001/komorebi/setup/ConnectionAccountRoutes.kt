package com.beeregg2001.komorebi.setup

import com.beeregg2001.komorebi.data.ConnectionAccountSettings
import com.beeregg2001.komorebi.data.model.DeviceAuthRequest
import com.google.gson.GsonBuilder
import kotlinx.coroutines.*
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
    var result: ConnectionAccountSettings? = null
    var failure: Exception? = null
    var pairingStatus = "idle"
    var pairingJob: Job? = null

    suspend fun status(current: ConnectionSetupSession): Any {
        if (pairingSession !== current) return mapOf("status" to "idle", "userName" to account().userName)
        failure?.let { throw it }
        return mapOf("status" to pairingStatus, "userName" to (result ?: account()).userName)
    }

    suspend fun guarded(call: io.ktor.server.application.ApplicationCall, action: suspend (ConnectionSetupSession) -> Any) {
        call.response.headers.append("Cache-Control", "no-store")
        val current = session()
        if (current == null) { call.setupExpired(); return }
        try {
            val result = current.authorized(call.request.headers["X-Setup-Token"]) { action(current) }
            call.respondText(gson.toJson(result), ContentType.Application.Json)
        } catch (error: Exception) { call.setupFailure(error, "ACCOUNT_FAILED") }
    }

    get("/api/connections/account") { guarded(call) { current -> status(current) } }
    post("/api/connections/account/pair") {
        guarded(call) { current ->
            val currentOrigin = origin()
            val active = pairing.takeIf { pairingSession === current && pairingOrigin == currentOrigin && pairingStatus == "waiting" && System.currentTimeMillis() < deadline }
                ?: create().also {
                    pairingSession = current; pairing = it; pairingOrigin = currentOrigin
                    deadline = System.currentTimeMillis() + it.expiresIn * 1000L
                    result = null; failure = null; pairingStatus = "waiting"
                    pairingJob?.cancel()
                    val activePairing = it
                    pairingJob = current.scope.launch {
                        try {
                            while (System.currentTimeMillis() < deadline && current.accepts(current.token)) {
                                delay(activePairing.interval.coerceAtLeast(1) * 1000L)
                                val done = current.authorized(current.token) {
                                    if (pairingSession !== current || pairing !== activePairing) return@authorized true
                                    if (pairingOrigin != origin() || System.currentTimeMillis() >= deadline) {
                                        pairingStatus = "expired"; return@authorized true
                                    }
                                    val user = poll(activePairing, current)
                                    if (user != null) { result = user; pairingStatus = "success" }
                                    user != null
                                }
                                if (done) return@launch
                            }
                            current.authorized(current.token) { pairingStatus = "expired" }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            if (current.accepts(current.token)) current.authorized(current.token) {
                                failure = error; pairingStatus = "failure"
                            }
                        }
                    }
                }
            mapOf("verificationUrl" to active.verificationUrl, "userCode" to active.userCode)
        }
    }
    get("/api/connections/account/pair") { guarded(call) { current -> status(current) } }
    post("/api/connections/account/pair/cancel") {
        guarded(call) {
            pairingJob?.cancel(); pairing = null; pairingSession = null; result = null; failure = null; pairingStatus = "idle"
            account()
        }
    }
    post("/api/connections/account/logout") {
        guarded(call) {
            pairingJob?.cancel(); pairing = null; pairingSession = null; result = null; failure = null; pairingStatus = "idle"
            logout()
            account()
        }
    }
}
