package edu.uestc.checkin.schedule

import edu.uestc.checkin.model.CheckinConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class CheckinCompensationTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun config(at: LocalTime = LocalTime.of(22, 0)) = CheckinConfig(
        "test", "password", "trusted", BigDecimal("30"), BigDecimal("104"), "address",
        at, zone, "agent", 30, 612, "wifi", false, "", "", ""
    )

    private fun at(hour: Int, minute: Int) = ZonedDateTime.of(2026, 10, 22, hour, minute, 0, 0, zone)

    @Test fun runsOnlyAfterConfiguredTime() {
        assertFalse(CheckinCompensation.shouldRun(at(21, 30), config(), false))
        assertTrue(CheckinCompensation.shouldRun(at(22, 0), config(), false))
        assertTrue(CheckinCompensation.shouldRun(at(22, 30), config(), false))
    }

    @Test fun stopsOutsideAllowedWindow() {
        assertFalse(CheckinCompensation.shouldRun(at(20, 59), config(), false))
        assertFalse(CheckinCompensation.shouldRun(at(23, 31), config(), false))
    }

    @Test fun skipsWhenAlreadyAttempted() {
        assertFalse(CheckinCompensation.shouldRun(at(22, 30), config(), true))
    }
}