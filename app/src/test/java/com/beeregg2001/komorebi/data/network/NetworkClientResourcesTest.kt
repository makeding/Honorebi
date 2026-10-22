package com.beeregg2001.komorebi.data.network

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.api.interceptor.CloudflareAccessInterceptor
import com.beeregg2001.komorebi.di.NetworkModule
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class NetworkClientResourcesTest {
    @Test
    fun purposeScopedClientsShareConnectionsButKeepIndependentDispatchersAndPolicies() {
        val pool = ConnectionPool()
        val apiPolicy = Interceptor { chain -> chain.proceed(chain.request()) }
        val imagePolicy = Interceptor { chain -> chain.proceed(chain.request()) }

        val api = purposeScopedHttpClient(pool) {
            addInterceptor(apiPolicy)
            readTimeout(30, TimeUnit.SECONDS)
        }
        val image = purposeScopedHttpClient(pool) {
            addNetworkInterceptor(imagePolicy)
        }

        assertSame(pool, api.connectionPool)
        assertSame(pool, image.connectionPool)
        assertNotSame(api.dispatcher, image.dispatcher)
        assertEquals(listOf(apiPolicy), api.interceptors)
        assertEquals(emptyList<Interceptor>(), image.interceptors)
        assertEquals(listOf(imagePolicy), image.networkInterceptors)
        assertEquals(30_000, api.readTimeoutMillis)
    }

    @Test
    fun accessAndImageClientsKeepTheirExistingCloudflareInterceptorPlacement() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val cloudflareAccess = CloudflareAccessInterceptor(SettingsRepository(context))
        val pool = ConnectionPool()

        val access = NetworkModule.provideAccessOkHttpClient(cloudflareAccess, pool)
        val image = NetworkModule.provideImageOkHttpClient(cloudflareAccess, pool)

        assertEquals(listOf(cloudflareAccess), access.interceptors)
        assertEquals(listOf(cloudflareAccess), access.networkInterceptors)
        assertEquals(emptyList<Interceptor>(), image.interceptors)
        assertEquals(listOf(cloudflareAccess), image.networkInterceptors)
        assertSame(pool, access.connectionPool)
        assertSame(pool, image.connectionPool)
        assertNotSame(access.dispatcher, image.dispatcher)
    }
}
