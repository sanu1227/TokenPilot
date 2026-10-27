package com.tokenpilot.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        UsageNotifications.reconcile(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        val prefs = getSharedPreferences("usage", MODE_PRIVATE)
        var failed = false
        val initial = try {
            val array = JSONArray(prefs.getString("records", "[]"))
            List(array.length()) { i ->
                val r = array.getJSONObject(i)
                Usage(r.getString("service"), r.getString("window"), r.getInt("percent"), r.getLong("reset"), r.getLong("updated"))
                    .also { require(it.service in services && it.window in windows && it.percent in 0..100) }
            }
        } catch (_: Exception) { failed = true; emptyList() }
        setContent {
            var records by remember { mutableStateOf(initial) }
            PilotTheme {
                PilotApp(records, failed) { next ->
                    val array = JSONArray()
                    next.forEach { r -> array.put(JSONObject().put("service", r.service).put("window", r.window).put("percent", r.percent).put("reset", r.reset).put("updated", r.updated)) }
                    prefs.edit().putString("records", array.toString()).apply()
                    records = next
                }
            }
        }
    }
}

@Composable
private fun PilotApp(records: List<Usage>, failed: Boolean, save: (List<Usage>) -> Unit) {
    val context = LocalContext.current
    val settings = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    var zoneChoice by remember { mutableStateOf(settings.getString("timezone", "자동 (기기 시간대)") ?: "자동 (기기 시간대)") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editor by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Usage?>(null) }
    var deleting by remember { mutableStateOf<Usage?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Re-evaluate the device zone on each clock tick, including after returning from system settings.
    val zone = remember(zoneChoice, now) { selectedZone(zoneChoice) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15000) } }
    Scaffold(bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            listOf("홈", "초기화", "설정").forEachIndexed { i, name ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { TabMark(i) }, label = { Text(name) })
            }
        }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("TOKENPILOT  /  CODEX", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(listOf("나의 AI 사용량", "다음 초기화", "앱 설정")[tab], style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(listOf("남은 여유를 확인하고, 다음 작업을 계획하세요.", "다시 시작할 시간을 한눈에 확인하세요.", "내 환경에 맞게 TokenPilot을 관리하세요.")[tab], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("표시 시간대 · ${zone.id}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (failed) Text("저장 기록을 읽지 못했습니다. 원본을 유지하고 있으니 앱을 다시 시작하세요.", color = MaterialTheme.colorScheme.error)
            if (tab == 2) {
                NotificationSettings()
                Text("시간 및 표시", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Choice("시간대", zoneChoice, listOf("자동 (기기 시간대)", "Asia/Seoul") + ZoneId.getAvailableZoneIds().sorted().filter { it != "Asia/Seoul" }) {
                    zoneChoice = it
                    settings.edit().putString("timezone", it).apply()
                    UsageNotifications.reconcile(context)
                }
                Text("한국 시간은 Asia/Seoul을 선택하세요. 자동은 휴대폰 설정을 따릅니다.")
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("연결 및 데이터", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("PC 연결 · 개발용", color = MaterialTheme.colorScheme.primary)
                        Text("USB/에뮬레이터의 ADB 연결과 PC companion 실행이 필요합니다. 화면을 보고 있을 때 60초마다 조회합니다.")
                        HorizontalDivider()
                        Text("기록은 이 기기에 저장됩니다. 앱을 삭제하면 기록도 삭제됩니다.")
                        Text("무선 연결과 백그라운드 사용량 조회는 개발 예정입니다. 예약 알림은 마지막 조회 시각을 기준으로 동작합니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                CodexPanel(zone = zone, sortByReset = tab == 1)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("수동 기록", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (tab == 0) TextButton(onClick = { selected = null; editor = true }, enabled = !failed) { Text("+ 기록 추가") }
                }
                if (records.isEmpty()) Text("추가한 기록이 없습니다. 자동 연결 외 사용량은 홈에서 직접 기록할 수 있어요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val sorted = if (tab == 1) records.sortedBy { it.reset } else records.sortedWith(compareBy({ services.indexOf(it.service) }, { it.window }))
                sorted.forEach { r ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${r.service} · ${r.window}", style = MaterialTheme.typography.titleLarge)
                            Text("마지막 입력 잔여량 ${r.percent}%")
                            LinearProgressIndicator(progress = { r.percent / 100f }, modifier = Modifier.fillMaxWidth())
                            Text(countdown(r.reset, now))
                            Text("초기화 예정 ${displayTime(r.reset, zone)}")
                            Text("수동 입력 · ${displayTime(r.updated, zone)} 확인")
                            Row {
                                TextButton(onClick = { selected = r; editor = true }, enabled = !failed) { Text("수정") }
                                TextButton(onClick = { deleting = r }, enabled = !failed) { Text("삭제") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (editor) key(zone.id) { UsageEditor(selected, records, zone, { editor = false }) { r ->
        save(records.filterNot { it == selected } + r)
        editor = false
    }
    }
    deleting?.let { r ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("기록 삭제") }, text = { Text("${r.service} ${r.window} 기록을 삭제할까요?") }, confirmButton = { TextButton(onClick = { save(records - r); deleting = null }) { Text("삭제") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } })
    }
}

@Composable
private fun UsageEditor(existing: Usage?, records: List<Usage>, zone: ZoneId, close: () -> Unit, save: (Usage) -> Unit) {
    var service by remember { mutableStateOf(existing?.service ?: services.first()) }
    var window by remember { mutableStateOf(existing?.window ?: windows.first()) }
    var percent by remember { mutableStateOf(existing?.percent?.toString() ?: "") }
    var reset by remember { mutableStateOf(existing?.let { displayTime(it.reset, zone) } ?: "") }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text("사용량 등록 / 수정") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("서비스 화면에서 직접 확인한 값을 입력하세요.")
            Choice("서비스", service, services) { service = it }
            Choice("제한 구간", window, windows) { window = it }
            OutlinedTextField(percent, { percent = it }, label = { Text("남은 비율 (0~100)") }, singleLine = true)
            OutlinedTextField(reset, { reset = it }, label = { Text("초기화 시각") }, placeholder = { Text("2026-09-21 15:30") }, singleLine = true)
            Text("yyyy-MM-dd HH:mm · ${zone.id}")
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        val value = validPercent(percent)
        val time = parseReset(reset, zone)
        val now = System.currentTimeMillis()
        error = when {
            value == null -> "비율은 0~100 사이 정수로 입력하세요."
            time == null -> "시각 형식이나 날짜를 확인하세요."
            time <= now -> "현재 이후의 초기화 시각을 입력하세요."
            records.any { it != existing && it.service == service && it.window == window } -> "이미 등록된 구간입니다. 기존 기록을 수정하세요."
            else -> ""
        }
        if (error.isEmpty()) save(Usage(service, window, requireNotNull(value), requireNotNull(time), now))
    }) { Text("저장") } }, dismissButton = { TextButton(onClick = close) { Text("취소") } })
}

@Composable
private fun Choice(label: String, value: String, options: List<String>, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("$label: $value ▾") }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { select(item); expanded = false }) }
        }
    }
}
