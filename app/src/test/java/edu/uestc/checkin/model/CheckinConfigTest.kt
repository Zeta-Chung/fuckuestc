package edu.uestc.checkin.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class CheckinConfigTest {
    private fun config() = CheckinConfig(
        account = "test-account",
        password = "test-password",
        fingerprint = "TEST-FINGERPRINT",
        latitude = BigDecimal("30.123456"),
        longitude = BigDecimal("103.123456"),
        address = "test address",
        checkinTime = LocalTime.of(22, 0),
        zoneId = ZoneId.of("Asia/Shanghai"),
        userAgent = "test-agent",
        accuracy = 30,
        locationDurationMs = 612,
        networkTypeCode = "wifi",
        locationEnabled = false,
        locationAuthorized = "",
        locationAccuracy = "",
        errorMessage = ""
    )

    @Test fun validPayload() {
        val config = config()
        assertTrue(config.validate().isEmpty())
        val payload = config.payload(LocalDateTime.of(2026, 10, 15, 23, 0, 11))
        assertEquals("2026-10-15 23:00:11", payload.getString("clientTime"))
        assertEquals("gcj02", payload.getString("coordinateSystemCode"))
        assertTrue(payload.toString().contains("30.123456"))
        assertEquals("test address", payload.getString("reverseGeocode"))
        assertEquals(false, payload.getBoolean("locationEnabled"))
        assertEquals(612L, payload.getLong("locationDurationMs"))
    }

    @Test fun invalidInputs() {
        assertTrue(config().copy(account = " ").validate().contains("账号不能为空"))
        assertTrue(config().copy(latitude = BigDecimal("91")).validate().contains("纬度必须在 -90 到 90 之间"))
        assertTrue(config().copy(address = " ").validate().contains("地址不能为空"))
        assertTrue(config().copy(userAgent = "test\r\nHeader: injected").validate().contains("User-Agent 格式无效"))
    }

    @Test fun checkinTimeMustStayInsideAllowedWindow() {
        assertEquals(
            listOf("打卡时间必须在 21:00 到 23:30 之间"),
            config().copy(checkinTime = LocalTime.of(20, 59)).validate()
        )
        assertEquals(
            listOf("打卡时间必须在 21:00 到 23:30 之间"),
            config().copy(checkinTime = LocalTime.of(23, 31)).validate()
        )
        assertTrue(config().copy(checkinTime = LocalTime.of(21, 0)).validate().isEmpty())
        assertTrue(config().copy(checkinTime = LocalTime.of(23, 30)).validate().isEmpty())
    }

    @Test fun serializedCoordinatesAlwaysHaveSixFractionDigits() {
        val config = config().copy(latitude = BigDecimal("30"), longitude = BigDecimal("103.5"))
        val body = config.payloadJson(LocalDateTime.of(2026, 10, 15, 23, 0, 11))
        assertTrue(body.contains("\"latitude\":30.000000"))
        assertTrue(body.contains("\"longitude\":103.500000"))
        val parsed = org.json.JSONObject(body)
        assertEquals(30.0, parsed.getDouble("latitude"), 0.0)
        val canonical = config.payload(LocalDateTime.of(2026, 10, 15, 23, 0, 11))
        val keys = canonical.keys().asSequence().toSet()
        assertEquals(keys, parsed.keys().asSequence().toSet())
        assertEquals(canonical.getInt("accuracy"), parsed.getInt("accuracy"))
        assertEquals(canonical.getLong("locationDurationMs"), parsed.getLong("locationDurationMs"))
        for (key in keys - setOf("latitude", "longitude", "accuracy", "locationDurationMs")) {
            assertEquals(canonical.get(key), parsed.get(key))
        }
    }
}
