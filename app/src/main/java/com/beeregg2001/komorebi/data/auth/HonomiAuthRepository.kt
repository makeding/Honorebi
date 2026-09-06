package com.beeregg2001.komorebi.data.auth

import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HonomiAuthRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val sessionStore: HonomiSessionStore,
    okHttpClient: OkHttpClient,
    private val gson: Gson,
) {
    private val loginClient = okHttpClient
    private val _session = MutableStateFlow(sessionStore.current())
    val session: StateFlow<HonomiSession?> = _session.asStateFlow()

    suspend fun createPairing(): Result<DeviceAuthRequest> = withContext(Dispatchers.IO) {
        runCatching {
            val origin = currentOrigin()
            val request = Request.Builder()
                .url(origin.newBuilder().addPathSegments("api/users/device-auth").build())
                .post(gson.toJson(DeviceAuthCreateRequest("Komorebi")).toRequestBody(JSON))
                .build()
            loginClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("ペアリングを開始できませんでした (HTTP ${response.code})")
                gson.fromJson(response.body.charStream(), DeviceAuthRequest::class.java).let { pairing ->
                    pairing.copy(verificationUrl = origin.resolve(pairing.verificationUrl)?.toString()
                        ?: error("確認 URL が不正です"))
                }
            }
        }
    }

    suspend fun pollPairing(pairing: DeviceAuthRequest): Result<HonomiSession?> = withContext(Dispatchers.IO) {
        runCatching {
            val origin = currentOrigin()
            val tokenRequest = Request.Builder()
                .url(origin.newBuilder().addPathSegments("api/users/device-auth/token").build())
                .post(gson.toJson(DeviceAuthTokenRequest(pairing.deviceCode)).toRequestBody(JSON))
                .build()
            val token = loginClient.newCall(tokenRequest).execute().use { response ->
                if (response.code == 202) return@runCatching null
                if (!response.isSuccessful) error("ペアリングに失敗しました (HTTP ${response.code})")
                gson.fromJson(response.body.charStream(), UserAccessToken::class.java).accessToken
            }
            val userRequest = Request.Builder()
                .url(origin.newBuilder().addPathSegments("api/users/me").build())
                .header("Authorization", "Bearer $token")
                .build()
            val user = loginClient.newCall(userRequest).execute().use { response ->
                if (!response.isSuccessful) error("アカウント情報を確認できませんでした (HTTP ${response.code})")
                gson.fromJson(response.body.charStream(), KonomiUser::class.java)
            }
            HonomiSession(canonicalOrigin(origin), token, user.id, user.name).also {
                sessionStore.save(it)
                _session.value = it
            }
        }
    }

    fun logout() {
        sessionStore.clear()
        _session.value = null
    }

    private suspend fun currentOrigin(): HttpUrl {
        val ip = settingsRepository.konomiIp.first().trimEnd('/')
        val port = settingsRepository.konomiPort.first().toIntOrNull() ?: error("ポート番号が不正です")
        val base = com.beeregg2001.komorebi.common.UrlBuilder.formatBaseUrl(ip, port.toString(), "http").toHttpUrl()
        return base.newBuilder().encodedPath("/").query(null).fragment(null).build()
    }

    private fun canonicalOrigin(url: HttpUrl): String = "${url.scheme}://${url.host}:${url.port}"

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
