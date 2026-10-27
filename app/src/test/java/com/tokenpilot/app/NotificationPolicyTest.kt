package com.tokenpilot.app

import org.junit.Assert.*
import org.junit.Test

class NotificationPolicyTest {
    private val fiveHour = RemoteWindow("codex", 300, 42.0, 5_000)
    private val weekly = RemoteWindow("codex", 10080, 80.0, 10_000)
    private val snapshot = Snapshot(1_000, listOf(fiveHour, weekly))

    @Test fun schedulesEarliestFutureAndThenWeekly() {
        assertEquals(5_000L, nextReset(snapshot, emptySet(), 2_000))
        assertEquals(listOf(fiveHour), dueResets(snapshot, emptySet(), 5_000))
        assertEquals(10_000L, nextReset(snapshot, setOf(fiveHour.eventKey), 5_000))
    }

    @Test fun duplicateDeliveryAndDuplicateRowsAreSuppressed() {
        assertTrue(dueResets(snapshot, setOf(fiveHour.eventKey), 6_000).isEmpty())
        assertEquals(1, dueResets(Snapshot(1_000, listOf(fiveHour, fiveHour)), emptySet(), 6_000).size)
    }

    @Test fun newResetTimeIsANewEventAndOldTimeDisappears() {
        val changed = fiveHour.copy(reset = 8_000)
        val updated = Snapshot(2_000, listOf(changed))
        assertEquals(8_000L, nextReset(updated, setOf(fiveHour.eventKey), 6_000))
        assertTrue(dueResets(updated, emptySet(), 6_000).isEmpty())
    }

    @Test fun missingExpiredOrAlreadyPastAtFetchDoesNotSchedule() {
        assertNull(nextReset(Snapshot(1_000, listOf(fiveHour.copy(reset = null))), emptySet(), 2_000))
        assertTrue(dueResets(Snapshot(6_000, listOf(fiveHour)), emptySet(), 7_000).isEmpty())
        assertTrue(dueResets(snapshot, emptySet(), 100_000_000).isEmpty())
        assertNull(nextReset(Snapshot(1_000, emptyList()), emptySet(), 2_000))
    }

    @Test fun simultaneousWindowsDeliverTogetherAndMissingPercentStaysUnknown() {
        assertEquals(2, dueResets(Snapshot(1_000, listOf(fiveHour, weekly.copy(reset = 5_000))), emptySet(), 5_000).size)
        assertEquals("잔여량 미제공", remainingLabel(fiveHour.copy(percent = null), 2_000))
        assertEquals("예정 시각 지남 · 재확인 필요", remainingLabel(fiveHour, 5_000))
        assertEquals("마지막 조회 잔여 42%", remainingLabel(fiveHour, 2_000))
    }
}
