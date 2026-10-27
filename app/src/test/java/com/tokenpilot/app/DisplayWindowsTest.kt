package com.tokenpilot.app

import org.junit.Assert.*
import org.junit.Test

class DisplayWindowsTest {
    @Test fun alwaysShowsBothPeriodsWithoutInventingNumbers() {
        val weekly = RemoteWindow("codex", 10080, 97.0, 9000)
        val data = Snapshot(1000, listOf(weekly))
        val rows = displayWindows(data)
        assertEquals(listOf(300L, 10080L), rows.map { it.minutes })
        assertNull(rows[0].percent)
        assertNull(rows[0].reset)
        assertEquals(weekly, rows[1])
        assertEquals(listOf(weekly), data.windows)
        assertEquals(2, displayWindows(null).size)
    }
    @Test fun realFiveHourAndOtherBucketsArePreserved() {
        val rows = listOf(RemoteWindow("codex", 300, 0.0, 8000), RemoteWindow("other", 300, 60.0, 9000), RemoteWindow("codex", 10080, 90.0, 10000))
        assertEquals(rows, displayWindows(Snapshot(1000, rows)))
    }
}
