package com.beeregg2001.komorebi.data.repository.tif

import android.app.Application
import android.content.Context
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContextImpl

private const val AIRPLAY_INPUT = "com.mediatek.AirplayAPK/.AirPlayTvInputService"

@Implements(TvInputInfo::class)
class AirPlayInputInfoShadow {
    @Implementation fun getId() = AIRPLAY_INPUT
    @Implementation fun isPassthroughInput() = true
    @Implementation fun isHidden(context: Context) = false
    @Implementation fun loadCustomLabel(context: Context): CharSequence? = null
    @Implementation fun loadLabel(context: Context): CharSequence = "AirPlay"
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class,
    shadows = [TestTvInputManager::class, AirPlayInputInfoShadow::class])
class AirPlayInputTest {
    @Test fun airPlayUsesTheOrdinaryPassthroughTuneTarget() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        Shadow.extract<ShadowContextImpl>(context.baseContext).setSystemService(
            Context.TV_INPUT_SERVICE, Shadow.newInstanceOf(TvInputManager::class.java),
        )
        val channel = TifRepository(context).passthroughChannels(TifInput.inputs(context)).single()
        assertEquals("AirPlay", channel.name)
        val target = TifInput.resolveTarget(context, channel.id)
        assertEquals(AIRPLAY_INPUT, target?.inputId)
        assertEquals(TvContract.buildChannelUriForPassthroughInput(AIRPLAY_INPUT), target?.channelUri)
    }
}
