package com.beeregg2001.komorebi.data.repository.tif

import org.junit.Assert.assertEquals
import org.junit.Test

class TifInputHierarchyTest {
    @Test fun singleHdmiChildReplacesItsPortEntry() {
        val port = "com.mediatek.tis/.HdmiInputService/HW2"
        val device = "com.mediatek.tis/.HdmiInputService/HDMI100003"

        val visible = TifInput.visiblePassthroughIds(listOf(
            TifInput.PassthroughRelation(port, null, isHdmi = true),
            TifInput.PassthroughRelation(device, port, isHdmi = true),
            TifInput.PassthroughRelation("airplay/.Input", null, isHdmi = false),
        ))

        assertEquals(setOf(device, "airplay/.Input"), visible)
    }

    @Test fun multipleHdmiChildrenKeepTheirPortAndNestedSources() {
        val port = "com.mediatek.tis/.HdmiInputService/HW2"
        val first = "com.mediatek.tis/.HdmiInputService/HDMI100003"
        val second = "com.mediatek.tis/.HdmiInputService/HDMI100004"

        val visible = TifInput.visiblePassthroughIds(listOf(
            TifInput.PassthroughRelation(port, null, isHdmi = true),
            TifInput.PassthroughRelation(first, port, isHdmi = true),
            TifInput.PassthroughRelation(second, port, isHdmi = true),
        ))

        assertEquals(setOf(port, first, second), visible)
    }

    @Test fun nonHdmiInputsAreNotCollapsedByParentRelation() {
        val parent = "component/.Composite/HW5"
        val child = "component/.Composite/DEVICE"

        val visible = TifInput.visiblePassthroughIds(listOf(
            TifInput.PassthroughRelation(parent, null, isHdmi = false),
            TifInput.PassthroughRelation(child, parent, isHdmi = false),
        ))

        assertEquals(setOf(parent, child), visible)
    }
}
