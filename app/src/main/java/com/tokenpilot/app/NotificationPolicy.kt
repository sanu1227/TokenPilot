package com.tokenpilot.app

data class RemoteWindow(val bucket: String, val minutes: Long?, val percent: Double?, val reset: Long?) {
    val label: String get() = "$bucket · " + when (minutes) {
        300L -> "5시간"; 10080L -> "주간"; null -> "기간 미제공"; else -> "${minutes}분"
    }
    // Length-prefixed bucket prevents delimiter collisions between event identities.
    val eventKey: String get() = "${bucket.length}:$bucket:$minutes:$reset"
}

data class Snapshot(val checked: Long, val windows: List<RemoteWindow>)

// Display-only placeholders must never be saved as real usage or scheduled for reminders.
fun displayWindows(snapshot: Snapshot?): List<RemoteWindow> {
    val actual = snapshot?.windows.orEmpty()
    return (actual + listOf(300L, 10080L).filter { minutes -> actual.none { it.minutes == minutes } }
        .map { RemoteWindow("codex", it, null, null) }).sortedBy { it.minutes ?: Long.MAX_VALUE }
}

fun quotaValue(snapshot: Snapshot?, minutes: Long, now: Long): String {
    val matches = snapshot?.windows.orEmpty().filter { it.minutes == minutes }
    // Multiple buckets must not be represented as one arbitrary quota.
    val row = matches.singleOrNull() ?: return if (matches.isEmpty()) "정보 없음" else "앱 확인"
    return when {
        row.reset != null && row.reset <= now -> "재확인"
        row.percent == null -> "정보 없음"
        else -> "${row.percent.toInt()}%"
    }
}

fun compactUsage(snapshot: Snapshot?, now: Long): String {
    if (snapshot == null || snapshot.windows.isEmpty()) return "조회 정보 없음"
    val rows = snapshot.windows.sortedBy { it.minutes ?: Long.MAX_VALUE }
    return rows.take(2).joinToString("  ·  ") { row ->
        val name = when (row.minutes) { 300L -> "5시간"; 10080L -> "주간"; else -> row.label }
        val value = when {
            row.reset != null && row.reset <= now -> "재확인"
            row.percent == null -> "—"
            else -> "${row.percent.toInt()}%"
        }
        "$name $value"
    } + if (rows.size > 2) " 외 ${rows.size - 2}개" else ""
}

fun pendingResets(snapshot: Snapshot, delivered: Set<String>): List<RemoteWindow> =
    snapshot.windows.filter { it.reset != null && it.reset > snapshot.checked && it.eventKey !in delivered }
        .distinctBy { it.eventKey }

fun dueResets(snapshot: Snapshot, delivered: Set<String>, now: Long): List<RemoteWindow> =
    pendingResets(snapshot, delivered).filter { it.reset!! <= now && it.reset > now - 86_400_000L }

fun nextReset(snapshot: Snapshot, delivered: Set<String>, now: Long): Long? =
    pendingResets(snapshot, delivered).mapNotNull { it.reset }.filter { it > now }.minOrNull()

fun remainingLabel(row: RemoteWindow, now: Long): String = when {
    row.reset != null && row.reset <= now -> "예정 시각 지남 · 재확인 필요"
    row.percent == null -> "잔여량 미제공"
    else -> "마지막 조회 잔여 ${row.percent.toInt()}%"
}
