package edu.uestc.checkin.schedule

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class DailySchedulerTest {
    @Test fun pastTimeSchedulesTomorrow() {
        val now = ZonedDateTime.of(2026, 10, 22, 23, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
        assertEquals("2026-10-23T22:00+08:00[Asia/Shanghai]", DailyScheduler.nextOccurrence(now, LocalTime.of(22, 0)).toString())
    }

    @Test fun futureTimeSchedulesToday() {
        val now = ZonedDateTime.of(2026, 10, 22, 20, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
        assertEquals("2026-10-22T22:00+08:00[Asia/Shanghai]", DailyScheduler.nextOccurrence(now, LocalTime.of(22, 0)).toString())
    }

    @Test fun dstGapUsesFirstValidLocalTime() {
        val zone = ZoneId.of("Europe/Berlin")
        val now = ZonedDateTime.of(2026, 3, 29, 1, 0, 0, 0, zone)
        assertEquals(3, DailyScheduler.nextOccurrence(now, LocalTime.of(2, 30)).hour)
    }
}
