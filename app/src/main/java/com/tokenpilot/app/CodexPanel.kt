package com.tokenpilot.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

private suspend fun fetchCodex(): Snapshot = withContext(Dispatchers.IO) {
    val connection = URI("http://127.0.0.1:18745/usage").toURL().openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 5000
        connection.readTimeout = 35000
        connection.instanceFollowRedirects = false
        check(connection.responseCode == 200)
        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        check(json.getString("source") == "codex-app-server")
        val checked = Instant.parse(json.getString("checkedAt")).toEpochMilli()
        val rows = json.getJSONArray("windows")
        Snapshot(checked, List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val percent = if (row.isNull("remainingPercent")) null else row.getDouble("remainingPercent").also { require(it.isFinite() && it in 0.0..100.0) }
            val reset = if (row.isNull("resetsAt")) null else Math.multiplyExact(row.getLong("resetsAt"), 1000L)
            RemoteWindow(row.getString("bucketId"), if (row.isNull("windowMinutes")) null else row.getLong("windowMinutes"), percent, reset)
        })
    } finally { connection.disconnect() }
}

@Composable
fun CodexPanel(zone: ZoneId, sortByReset: Boolean = false) {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    var snapshot by remember { mutableStateOf<Snapshot?>(null) }
    var error by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(owner, refresh) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                loading = true
                try {
                    val data = fetchCodex()
                    snapshot = data
                    error = false
                    UsageNotifications.saveSnapshot(context, data)
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = true; UsageNotifications.connectionFailed(context) }
                finally { loading = false; now = System.currentTimeMillis() }
                delay(60000)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF17263C), contentColor = Color.White)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Codex", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(if (error) "연결 확인 필요" else if (loading) "조회 중" else "PC 동기화", style = MaterialTheme.typography.labelMedium, color = Color(0xFFB3EBD8))
                }
                Text(if (error) "PC 서버와 USB/에뮬레이터 연결을 확인하세요." else "화면을 보고 있을 때 60초마다 자동 갱신", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFD2DEEC))
                FilledTonalButton(onClick = { refresh++ }, enabled = !loading, colors = ButtonDefaults.filledTonalButtonColors(disabledContainerColor = Color(0xFF33455D), disabledContentColor = Color(0xFFD2DEEC))) { Text(if (loading) "불러오는 중…" else "새로고침") }
            }
        }
        run {
            val data = snapshot
            data?.let {
                Text("마지막 확인 · ${displayTime(it.checked, zone)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error || now - it.checked > 120000) Text("이전 조회값입니다. 현재 잔여량과 다를 수 있습니다.", color = MaterialTheme.colorScheme.error)
            }
            val available = displayWindows(data)
            val rows = if (sortByReset) available.sortedBy { it.reset ?: Long.MAX_VALUE } else available
            rows.forEach { row ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val name = when (row.minutes) { 300L -> "5시간"; 10080L -> "주간"; null -> "기간 미제공"; else -> "${row.minutes}분" }
                        Text("$name 한도 · ${row.bucket}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        val accent = if (row.percent != null && row.percent <= 20) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(row.percent?.let { "${it.toInt()}%" } ?: "—", fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold, color = accent)
                            Text(if (row.percent == null) "정보 없음" else "남음", modifier = Modifier.padding(bottom = 7.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (row.percent == null) Text(
                            if (data == null) { if (loading) "사용량을 조회하고 있습니다." else "PC 연결 후 새로고침하면 표시됩니다." }
                            else "현재 조회 응답에 잔여량이 없습니다. 값이 제공되면 자동으로 표시됩니다.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        row.percent?.let { LinearProgressIndicator(progress = { (it / 100).toFloat() }, modifier = Modifier.fillMaxWidth().height(8.dp), color = accent, trackColor = MaterialTheme.colorScheme.surfaceVariant) }
                        row.reset?.let {
                            Text(countdown(it, now), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text("초기화 · ${displayTime(it, zone)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } ?: Text("초기화 시각 미제공")
                    }
                }
            }
        }
    }
}
