package com.tokenpilot.app

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject

/** Local reminders only. No account credentials or background network requests. */
object UsageNotifications {
    const val STATUS = "usage_status"
    const val RESET = "usage_reset"
    private const val STATUS_ID = 100
    private const val RESET_ID = 101
    private const val TEST_ID = 102
    private const val RESET_ACTION = "com.tokenpilot.app.RESET_REMINDER"
    private const val TEST_ACTION = "com.tokenpilot.app.TEST_REMINDER"
    private fun prefs(context: Context) = context.getSharedPreferences("notifications", Context.MODE_PRIVATE)
    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)
    fun enabled(context: Context, key: String) = prefs(context).getBoolean(key, false)
    fun allowed(context: Context): Boolean = manager(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun channels(context: Context) {
        manager(context).createNotificationChannels(listOf(
            NotificationChannel(STATUS, "사용량 표시", NotificationManager.IMPORTANCE_LOW).apply {
                description = "마지막 조회 사용량과 확인 시각을 조용히 표시합니다."
            },
            NotificationChannel(RESET, "초기화 예정 알림", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "저장된 초기화 예정 시각이 되면 재확인을 안내합니다."
            }
        ))
    }

    private fun channelAllowed(context: Context, id: String) = allowed(context) &&
        manager(context).getNotificationChannel(id)?.importance != NotificationManager.IMPORTANCE_NONE

    fun setEnabled(context: Context, key: String, value: Boolean) {
        require(key == STATUS || key == RESET)
        prefs(context).edit().putBoolean(key, value).apply()
        if (!value && key == RESET) {
            manager(context).cancel(RESET_ID)
            manager(context).cancel(TEST_ID)
            cancel(context, TEST_ACTION)
        }
        reconcile(context)
    }

    fun saveSnapshot(context: Context, snapshot: Snapshot) {
        val rows = JSONArray()
        snapshot.windows.forEach { row ->
            rows.put(JSONObject().put("bucket", row.bucket).put("minutes", row.minutes ?: JSONObject.NULL)
                .put("percent", row.percent ?: JSONObject.NULL).put("reset", row.reset ?: JSONObject.NULL))
        }
        val stored = prefs(context)
        val keys = snapshot.windows.map { it.eventKey }.toSet()
        val delivered = stored.getStringSet("delivered", emptySet()).orEmpty().intersect(keys)
        stored.edit().putString("snapshot", JSONObject().put("source", "direct-codex").put("checked", snapshot.checked).put("windows", rows).toString())
            .putStringSet("delivered", delivered).putBoolean("failed", false).apply()
        reconcile(context)
    }

    fun connectionFailed(context: Context) {
        prefs(context).edit().putBoolean("failed", true).apply()
        reconcile(context)
    }

    fun clearSnapshot(context: Context) {
        prefs(context).edit().remove("snapshot").remove("delivered").remove("failed").commit()
        cancel(context, RESET_ACTION)
        cancel(context, TEST_ACTION)
        manager(context).cancel(STATUS_ID)
        manager(context).cancel(RESET_ID)
        manager(context).cancel(TEST_ID)
    }

    fun lastSnapshot(context: Context): Snapshot? = load(context)

    private fun load(context: Context): Snapshot? = try {
        prefs(context).getString("snapshot", null)?.let { text ->
            val json = JSONObject(text)
            require(json.optString("source") == "direct-codex") // Do not display old PC/account data after migration.
            val rows = json.getJSONArray("windows")
            Snapshot(json.getLong("checked"), List(rows.length()) { i ->
                val row = rows.getJSONObject(i)
                RemoteWindow(row.getString("bucket"), if (row.isNull("minutes")) null else row.getLong("minutes"),
                    if (row.isNull("percent")) null else row.getDouble("percent").also { require(it.isFinite() && it in 0.0..100.0) },
                    if (row.isNull("reset")) null else row.getLong("reset"))
            })
        }
    } catch (_: Exception) { null }

    private fun zone(context: Context) = selectedZone(context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getString("timezone", "자동 (기기 시간대)") ?: "자동 (기기 시간대)")

    private fun builder(context: Context, channel: String, title: String, text: String): Notification.Builder {
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setVisibility(Notification.VISIBILITY_PRIVATE)
    }

    private fun post(context: Context, id: Int, notification: Notification): Boolean {
        // The user can revoke permission between the check and posting.
        if (!allowed(context)) return false
        return try { manager(context).notify(id, notification); true } catch (_: SecurityException) { false }
    }

    private fun operation(context: Context, action: String) = PendingIntent.getBroadcast(context, 0,
        Intent(context, UsageNotificationReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun cancel(context: Context, action: String) {
        context.getSystemService(AlarmManager::class.java).cancel(operation(context, action))
    }

    private fun schedule(context: Context, action: String, time: Long) {
        // Inexact by design: no exact-alarm special access and no long-running service.
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, operation(context, action))
    }

    fun reconcile(context: Context) {
        channels(context)
        val stored = prefs(context)
        val data = load(context)
        val now = System.currentTimeMillis()
        val displayZone = zone(context)
        if (enabled(context, STATUS) && channelAllowed(context, STATUS)) {
            val body = if (data == null) "조회 기록이 없습니다. 앱에서 로그인 후 새로고침하세요." else buildString {
                append("마지막 확인 ${displayTime(data.checked, displayZone)} · ${displayZone.id}\n")
                if (stored.getBoolean("failed", false)) append("연결 실패 · 저장된 이전 값입니다.\n")
                append(data.windows.joinToString("\n") { row ->
                    "${row.label}: ${remainingLabel(row, now)}" +
                        (row.reset?.let { "\n초기화 예정 ${displayTime(it, displayZone)}" } ?: "\n초기화 시각 미제공")
                }.ifEmpty { "제공된 한도 정보가 없습니다." })
                append("\n실시간 조회 아님 · 앱을 열어 갱신하세요.")
            }
            val summary = compactUsage(data, now)
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            fun card(expanded: Boolean) = RemoteViews(context.packageName, R.layout.notification_usage).apply {
                listOf(R.id.usage_five to 300L, R.id.usage_week to 10080L).forEach { (id, minutes) ->
                    val value = quotaValue(data, minutes, now)
                    setTextViewText(id, value)
                    setTextViewTextSize(id, android.util.TypedValue.COMPLEX_UNIT_SP, if (value.endsWith("%")) 19f else 13f)
                    setContentDescription(id, "${if (minutes == 300L) "5시간" else "주간"} 마지막 조회 잔여량 $value")
                }
                setTextViewText(R.id.usage_details, body)
                setViewVisibility(R.id.usage_details, if (expanded) View.VISIBLE else View.GONE)
                setOnClickPendingIntent(R.id.usage_open, open)
            }
            post(context, STATUS_ID, builder(context, STATUS, "Codex · 마지막 조회 사용량", body)
                .setContentText(summary)
                .setStyle(Notification.DecoratedCustomViewStyle())
                .setCustomContentView(card(false)).setCustomBigContentView(card(true))
                .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).build())
        } else manager(context).cancel(STATUS_ID)

        cancel(context, RESET_ACTION)
        if (!enabled(context, RESET) || !channelAllowed(context, RESET) || data == null) return
        val delivered = stored.getStringSet("delivered", emptySet()).orEmpty().toMutableSet()
        val due = dueResets(data, delivered, now)
        if (due.isNotEmpty()) {
            val text = due.joinToString("\n") { "${it.label} · ${displayTime(it.reset!!, displayZone)}" } +
                "\n저장된 초기화 예정 시각이 지났습니다. 실제 잔여량은 앱에서 다시 확인하세요."
            if (post(context, RESET_ID, builder(context, RESET, "Codex 초기화 예정 시각 도달", text).setAutoCancel(true).build())) {
                delivered.addAll(due.map { it.eventKey })
                // Persist before receiver exits; repeated deliveries/reboots must not notify twice.
                stored.edit().putStringSet("delivered", delivered).commit()
            }
        }
        nextReset(data, delivered, now)?.let { schedule(context, RESET_ACTION, it) }
    }

    fun test(context: Context): Boolean {
        channels(context)
        if (!channelAllowed(context, RESET)) return false
        schedule(context, TEST_ACTION, System.currentTimeMillis() + 10_000L)
        return true
    }

    fun receive(context: Context, action: String?) {
        if (action == TEST_ACTION) {
            channels(context)
            if (channelAllowed(context, RESET)) post(context, TEST_ID,
                builder(context, RESET, "TokenPilot 예약 알림 테스트", "예약 알림이 도착했습니다. 실제 사용량이나 초기화 시각은 바뀌지 않습니다.")
                    .setAutoCancel(true).build())
        } else reconcile(context)
    }
}

class UsageNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        UsageNotifications.receive(context, intent.action)
    }
}
