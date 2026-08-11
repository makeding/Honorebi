package com.beeregg2001.komorebi.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() {
        val targetAppId = requireNotNull(
            InstrumentationRegistry.getArguments().getString("targetAppId")
        ) { "targetAppId not passed as instrumentation runner arg" }

        baselineProfileRule.collect(
            packageName = targetAppId
        ) {
            pressHome()
            startActivityAndWait()

            // Cover the current launcher path and compose the tab surfaces used at startup.
            device.waitForIdle()
            repeat(5) {
                device.pressDPadRight()
                device.waitForIdle()
            }
            repeat(5) {
                device.pressDPadLeft()
                device.waitForIdle()
            }
        }
    }
}
