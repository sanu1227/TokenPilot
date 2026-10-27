package com.tokenpilot.app

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

data class Usage(val service: String, val window: String, val percent: Int, val reset: Long, val updated: Long)
val services = listOf("Codex", "Claude", "Gemini", "Grok")
val windows = listOf("5시간", "세션", "일간", "주간")
val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)
fun displayTime(time: Long, zone: ZoneId): String = timeFormat.format(java.time.Instant.ofEpochMilli(time).atZone(zone))
fun selectedZone(id: String): ZoneId = try {
    if (id == "자동 (기기 시간대)") ZoneId.systemDefault() else ZoneId.of(id)
} catch (_: java.time.DateTimeException) { ZoneId.systemDefault() }
fun parseReset(text: String, zone: ZoneId = ZoneId.systemDefault()): Long? = try {
    val local = LocalDateTime.parse(text.trim(), timeFormat)
    val offsets = zone.rules.getValidOffsets(local)
    if (offsets.size != 1) null else local.toInstant(offsets.first()).toEpochMilli()
} catch (_: java.time.DateTimeException) { null }
catch (_: ArithmeticException) { null }
fun validPercent(text: String) = text.toIntOrNull()?.takeIf { it in 0..100 }
fun countdown(reset: Long, now: Long): String {
    if (reset <= now) return "예정 시각 지남 · 재확인 필요"
    val minutes = (reset - now + 59999) / 60000
    return "${minutes / 1440}일 ${(minutes % 1440) / 60}시간 ${minutes % 60}분 후"
}
