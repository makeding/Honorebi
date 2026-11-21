package com.beeregg2001.komorebi.ui.live

import android.media.tv.TvContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.beeregg2001.komorebi.data.repository.tif.TifInput
import com.beeregg2001.komorebi.data.repository.tif.TifRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual system inputs through the production TIF adapter. */
@RunWith(AndroidJUnit4::class)
class TifInputDeviceTest {
    @Test
    fun publishedInputsMapToTheirOwnTuneTargets() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val infos = TifInput.inputs(context)
        assertTrue("No system TV inputs are registered", infos.isNotEmpty())
        val response = TifRepository(context).getChannels()
        val channels = listOf(response.terrestrial, response.bs, response.cs, response.others, response.inputs)
            .flatMap { it.orEmpty() }
        assertTrue("No visible TIF channels or passthrough inputs", channels.isNotEmpty())
        for (channel in channels) {
            val target = TifInput.resolveTarget(context, channel.id)
            assertNotNull("Unresolvable channel: ${channel.id}", target)
            assertTrue(infos.any { it.id == target!!.inputId })
            if (channel.id.startsWith("tif:input:")) {
                assertEquals(TvContract.buildChannelUriForPassthroughInput(target!!.inputId), target.channelUri)
            }
        }
        assertNull(TifInput.resolveTarget(context, "tif:input:missing.test.input"))
    }
}
