package com.tokenpilot.app

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class TimeZoneTest {
    @Test fun seoulRendersResetWithDateRollover() {
        val instant = 1791047426000L
        assertEquals("2026-10-03 17:10", displayTime(instant, ZoneId.of("UTC")))
        assertEquals("2026-10-04 02:10", displayTime(instant, ZoneId.of("Asia/Seoul")))
    }
    @Test fun manualInputUsesSelectedZone() {
        val seoul = parseReset("2026-09-29 18:59", ZoneId.of("Asia/Seoul"))
        val utc = parseReset("2026-09-29 09:59", ZoneId.of("UTC"))
        assertEquals(utc, seoul)
    }
    @Test fun invalidSavedZoneFallsBackToDevice() {
        assertEquals(ZoneId.systemDefault(), selectedZone("invalid/zone"))
        assertEquals(ZoneId.systemDefault(), selectedZone("자동 (기기 시간대)"))
    }
}
