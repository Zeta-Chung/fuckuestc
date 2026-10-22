package edu.uestc.checkin.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPresetsTest {
    @Test fun bothPresetsAreConfigured() {
        assertTrue(LocationPresets.XINYUAN.configured)
        assertTrue(LocationPresets.XIAONEI.configured)
        assertTrue(LocationPresets.XINYUAN.address.isNotBlank())
        assertTrue(LocationPresets.XIAONEI.address.isNotBlank())
    }

    @Test fun configuredPresetsMatchTheirOwnCoordinates() {
        assertEquals("欣苑", LocationPresets.find("30.674677", "104.096273")?.label)
        assertEquals("校内", LocationPresets.find("30.6784710", "104.098075")?.label)
        assertNull(LocationPresets.find("30.674677", "104.098075"))
    }

    @Test fun coordinatesMatchNumerically() {
        val preset = LocationPreset("test", "测试", "30.123456", "103.654321", "地址")
        assertTrue(preset.matches("30.123456", "103.654321"))
        assertTrue(preset.matches("30.1234560", "103.654321"))
        assertFalse(preset.matches("30.123457", "103.654321"))
        assertFalse(preset.matches("", "103.654321"))
    }
}