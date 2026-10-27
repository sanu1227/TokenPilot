package com.tokenpilot.app

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class UsageTest {
    @Test fun invalidDates() {
        assertNull(parseReset("2026-02-30 12:00"))
        assertNull(parseReset("bad"))
        assertNotNull(parseReset("2028-02-29 12:00"))
    }
    @Test fun oversizedYearsReturnValidationFailure() {
        listOf(
            "+999999999-12-31 23:59", "-999999999-01-01 00:00",
            "+1000000000-01-01 00:00", "-1000000000-01-01 00:00"
        ).forEach { assertNull(it, parseReset(it, ZoneOffset.UTC)) }
    }
    @Test fun validDatesKeepTheExistingRangeAndFormat() {
        assertEquals(0L, parseReset(" 1970-01-01 00:00 ", ZoneOffset.UTC))
        assertEquals(-60000L, parseReset("1969-12-31 23:59", ZoneOffset.UTC))
        listOf(
            "0000-02-29 12:00", "-0001-01-01 00:00", "1900-01-01 00:00",
            "2000-02-29 12:00", "2028-02-29 12:00", "9999-12-31 23:59",
            "+10000-01-01 00:00", "+20000-01-01 00:00", "-20000-01-01 00:00"
        ).forEach { text ->
            val parsed = parseReset(text, ZoneOffset.UTC)
            assertNotNull(text, parsed)
            assertEquals(text, displayTime(requireNotNull(parsed), ZoneOffset.UTC))
        }
    }
    @Test fun invalidCalendarAndFormatStayRejected() {
        listOf(
            "", "2026/10/12 12:00", "2026-10-12T12:00", "2026-10-12 12:00:00",
            "2026-02-29 12:00", "1900-02-29 12:00", "2100-02-29 12:00",
            "2026-04-31 12:00", "2026-13-01 12:00", "2026-01-00 12:00",
            "2026-01-01 24:00", "2026-01-01 12:60", "2026-1-1 12:00"
        ).forEach { assertNull(it, parseReset(it, ZoneOffset.UTC)) }
    }
    @Test fun millisecondBoundariesUseTheSelectedTimeZone() {
        listOf(ZoneOffset.UTC, ZoneOffset.MIN, ZoneOffset.MAX, ZoneId.of("Asia/Seoul")).forEach { zone ->
            val latest = Instant.ofEpochMilli(Long.MAX_VALUE).atZone(zone).toLocalDateTime().withSecond(0).withNano(0)
            val earliest = Instant.ofEpochMilli(Long.MIN_VALUE).atZone(zone).toLocalDateTime().withSecond(0).withNano(0).plusMinutes(1)
            assertEquals(zone.id, latest.atZone(zone).toInstant().toEpochMilli(), parseReset(timeFormat.format(latest), zone))
            assertEquals(zone.id, earliest.atZone(zone).toInstant().toEpochMilli(), parseReset(timeFormat.format(earliest), zone))
            assertNull(zone.id, parseReset(timeFormat.format(latest.plusMinutes(1)), zone))
            assertNull(zone.id, parseReset(timeFormat.format(earliest.minusMinutes(1)), zone))
        }
    }
    @Test fun percentBounds() {
        listOf("101", "-1", "1.5", "", "abc").forEach { assertNull(validPercent(it)) }
        assertEquals(0, validPercent("0"))
        assertEquals(100, validPercent("100"))
    }
    @Test fun expiryAndRounding() {
        assertTrue(countdown(10, 10).contains("재확인 필요"))
        assertEquals("0일 0시간 1분 후", countdown(1, 0))
        assertEquals("1일 0시간 0분 후", countdown(86400000, 0))
    }
    @Test fun dstAmbiguity() {
        val zone = ZoneId.of("America/New_York")
        assertNull(parseReset("2026-03-08 02:30", zone))
        assertNull(parseReset("2026-11-01 01:30", zone))
        assertNotNull(parseReset("2026-03-08 03:30", zone))
    }
}
