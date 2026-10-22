package com.beeregg2001.komorebi.media

import android.os.Looper
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class CastRouteDiscoveryTest {

    @Test
    fun callbackExecutor_dispatchesOnMainLooper() {
        var callbackLooper: Looper? = null

        mainLooperExecutor().execute {
            callbackLooper = Looper.myLooper()
        }

        assertNull(callbackLooper)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(Looper.getMainLooper(), callbackLooper)
    }
}
