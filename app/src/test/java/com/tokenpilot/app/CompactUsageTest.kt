package com.tokenpilot.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CompactUsageTest {
    @Test fun fixedQuotaSlotsNeverHideMissingDataOrGuessBuckets() {
        val row = RemoteWindow("codex", 10080, 98.0, 9000)
        val data = Snapshot(1000, listOf(row))
        assertEquals("정보 없음", quotaValue(data, 300, 2000))
        assertEquals("98%", quotaValue(data, 10080, 2000))
        assertEquals("재확인", quotaValue(data, 10080, 9000))
        assertEquals("정보 없음", quotaValue(null, 300, 2000))
        assertEquals("0%", quotaValue(Snapshot(1000, listOf(row.copy(percent = 0.0))), 10080, 2000))
        assertEquals("앱 확인", quotaValue(Snapshot(1000, listOf(row, row.copy(bucket = "other"))), 10080, 2000))
    }
    @Test fun showsTwoWindowsAndKeepsUnknownDistinctFromZero() {
        val rows = listOf(RemoteWindow("codex", 10080, 0.0, 9000), RemoteWindow("codex", 300, null, 8000))
        assertEquals("5시간 —  ·  주간 0%", compactUsage(Snapshot(1000, rows), 2000))
        assertEquals("5시간 재확인  ·  주간 0%", compactUsage(Snapshot(1000, rows), 8000))
    }
    @Test fun handlesNoDataAndAdditionalWindows() {
        assertEquals("조회 정보 없음", compactUsage(null, 2000))
        assertEquals("조회 정보 없음", compactUsage(Snapshot(1000, emptyList()), 2000))
        val row = RemoteWindow("codex", 300, 50.0, 9000)
        assertEquals("5시간 50%  ·  5시간 50% 외 1개", compactUsage(Snapshot(1000, listOf(row, row, row)), 2000))
    }
}
