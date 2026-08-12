package com.beeregg2001.komorebi.data.auth

import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.KonomiUser
import com.beeregg2001.komorebi.data.model.UserAccessToken
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
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

    suspend fun login(username: String, password: String): Result<HonomiSession> = withContext(Dispatchers.IO) {
        runCatching {
            val origin = currentOrigin()
            val tokenRequest = Request.Builder()
                .url(origin.newBuilder().addPathSegments("api/users/token").build())
                .post(FormBody.Builder().add("username", username).add("password", password).build())
                .build()
            val token = loginClient.newCall(tokenRequest).execute().use { response ->
                if (!response.isSuccessful) error("ログインに失敗しました (HTTP ${response.code})")
                gson.fromJson(response.body?.charStream(), UserAccessToken::class.java).accessToken
            }
            val userRequest = Request.Builder()
                .url(origin.newBuilder().addPathSegments("api/users/me").build())
                .header("Authorization", "Bearer $token")
                .build()
            val user = loginClient.newCall(userRequest).execute().use { response ->
                if (!response.isSuccessful) error("アカウント情報を確認できませんでした (HTTP ${response.code})")
                gson.fromJson(response.body?.charStream(), KonomiUser::class.java)
            }
            HonomiSession(origin.toString().trimEnd('/'), token, user.id, user.name).also {
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
        val base = (if (ip.startsWith("http://") || ip.startsWith("https://")) ip else "http://$ip").toHttpUrl()
        return base.newBuilder().port(port).encodedPath("/").query(null).fragment(null).build()
    }
}
