package com.beeregg2001.komorebi.di

import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.BuildConfig
import com.beeregg2001.komorebi.data.api.KonomiApi
import com.beeregg2001.komorebi.data.model.StreamSource
import com.beeregg2001.komorebi.data.auth.HonomiSessionStore
import com.beeregg2001.komorebi.data.api.interceptor.CloudflareAccessConfiguration
import com.beeregg2001.komorebi.data.api.interceptor.CloudflareAccessInterceptor
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import javax.inject.Named

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(settingsRepository: SettingsRepository, sessionStore: HonomiSessionStore, cloudflareAccessInterceptor: CloudflareAccessInterceptor): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            redactHeader("Authorization")
            redactHeader(CloudflareAccessConfiguration.CLIENT_ID_HEADER)
            redactHeader(CloudflareAccessConfiguration.CLIENT_SECRET_HEADER)
            level =
                if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.HEADERS else HttpLoggingInterceptor.Level.NONE
        }

        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // ★ 修正: Interceptorを明示的に指定し、SettingsRepositoryから正しくURLを取得する
            .addInterceptor(com.beeregg2001.komorebi.data.api.interceptor.BackendOriginInterceptor {
                settingsRepository.cloudflareAccessConfiguration.value
            })
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val session = sessionStore.current()
                val requestOrigin = "${request.url.scheme}://${request.url.host}:${request.url.port}"
                val authenticated = if (session != null && session.origin == requestOrigin) {
                    request.newBuilder().header("Authorization", "Bearer ${session.token}").build()
                } else request
                chain.proceed(authenticated)
            })
            // WebSocket handshakes execute application interceptors but not network
            // interceptors, so scope the first handshake here as well.
            .addInterceptor(cloudflareAccessInterceptor)
            // Logging must stay innermost (added after the interceptors that rewrite the URL
            // and add Authorization / CF-Access-* headers).  Otherwise Debug logs show the
            // pre-rewrite request and make the Access headers look as if they were dropped.
            .addInterceptor(logging)
            .addNetworkInterceptor(cloudflareAccessInterceptor)
            .build()
    }

    @Provides
    @Singleton
    @Named("access")
    fun provideAccessOkHttpClient(cloudflareAccessInterceptor: CloudflareAccessInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(cloudflareAccessInterceptor)
            .addNetworkInterceptor(cloudflareAccessInterceptor)
            .build()

    @Provides
    @Singleton
    fun provideGson(): Gson {
        return Gson()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, gson: Gson): Retrofit {
        return Retrofit.Builder()
            // ここはダミーの初期値（Interceptorで動的に書き換わるため何でもOK）
            .baseUrl("https://192-168-11-100.local.konomi.tv:7000")
            .client(okHttpClient.newBuilder()
                .addInterceptor(com.beeregg2001.komorebi.data.api.interceptor.BackendApiResponseInterceptor())
                .build())
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    @Provides
    @Singleton
    fun provideKonomiApi(retrofit: Retrofit): KonomiApi {
        return retrofit.create(KonomiApi::class.java)
    }
}
