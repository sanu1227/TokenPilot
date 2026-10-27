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
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.text.selection.SelectionContainer
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun CodexPanel(zone: ZoneId, sortByReset: Boolean = false) {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    var snapshot by remember { mutableStateOf<Snapshot?>(null) }
    var error by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var initialized by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }
    var reconnect by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var device by remember { mutableStateOf<DeviceLogin?>(null) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var loginJob by remember { mutableStateOf<Job?>(null) }
    fun explain(error: Exception): String = (error as? CodexFailure)?.safeMessage
        ?: "연결 또는 응답을 확인하지 못했습니다. 네트워크를 확인하고 다시 시도해 주세요."
    LaunchedEffect(Unit) {
        try {
            connected = CodexSession.isConnected(context)
            if (connected) snapshot = UsageNotifications.lastSnapshot(context)
        } catch (_: Exception) {
            UsageNotifications.clearSnapshot(context)
            reconnect = true
            message = "저장된 로그인을 읽지 못했습니다. 연결 해제 후 다시 로그인해 주세요."
        }
        initialized = true
    }
    DisposableEffect(Unit) { onDispose { CodexSession.cancelLogin() } }
    LaunchedEffect(owner, refresh, connected, reconnect, busy) {
        if (!connected || reconnect || busy) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                loading = true
                try {
                    val data = CodexSession.usage(context)
                    snapshot = data
                    error = false
                    message = ""
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    error = true
                    message = explain(failure)
                    if ((failure as? CodexFailure)?.loginRequired == true) reconnect = true
                    UsageNotifications.connectionFailed(context)
                }
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
                    Text(if (!connected) "로그인 필요" else if (error || reconnect) "연결 확인 필요" else if (loading) "조회 중" else "휴대폰 직접 조회", style = MaterialTheme.typography.labelMedium, color = Color(0xFFB3EBD8))
                }
                Text(if (connected) "PC 없이 조회 · 화면을 보고 있을 때 60초마다 갱신" else "휴대폰 브라우저에서 Codex 계정을 연결하세요.", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFD2DEEC))
                if (!connected && !reconnect && device == null) {
                    FilledTonalButton(enabled = initialized && !busy, onClick = {
                        loginJob = scope.launch {
                            busy = true
                            message = ""
                            val attempt = CodexSession.newAttempt()
                            try {
                                val pending = CodexSession.begin()
                                device = pending
                                val deadline = SystemClock.elapsedRealtime() + 15 * 60 * 1000
                                while (SystemClock.elapsedRealtime() < deadline) {
                                    delay(pending.intervalMs)
                                    if (CodexSession.poll(context, pending, attempt)) {
                                        connected = true
                                        error = false
                                        snapshot = null
                                        return@launch
                                    }
                                }
                                message = "로그인 코드가 만료되었습니다. 다시 시작해 주세요."
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { message = explain(failure) }
                            finally { device = null; busy = false }
                        }
                    }) { Text(if (busy) "로그인 준비 중…" else "Codex 로그인") }
                }
                device?.let { pending ->
                    Text("아래 코드를 공식 로그인 페이지에 입력하세요. 15분 이내에 완료한 뒤 앱으로 돌아오세요.")
                    SelectionContainer { Text(pending.code, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
                    FilledTonalButton(onClick = {
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CODEX_LOGIN_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        catch (_: Exception) { message = "브라우저를 열지 못했습니다. auth.openai.com/codex/device 에서 코드를 입력하세요." }
                    }) { Text("공식 로그인 페이지 열기") }
                    Text("로그인 승인을 기다리는 중…", color = Color(0xFFB3EBD8))
                }
                if (busy && !connected) TextButton(onClick = {
                    CodexSession.cancelLogin()
                    loginJob?.cancel()
                    message = "로그인을 취소했습니다."
                }) { Text("로그인 취소", color = Color.White) }
                if (connected || reconnect) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { refresh++ }, enabled = !loading && !busy && !reconnect) { Text(if (loading) "불러오는 중…" else "새로고침") }
                        TextButton(enabled = !busy, onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    val revoked = CodexSession.logout(context)
                                    connected = false
                                    reconnect = false
                                    error = false
                                    snapshot = null
                                    message = if (revoked) "연결을 해제했습니다." else "기기에서 연결을 해제했습니다. 서버의 인증 폐기는 확인하지 못했습니다."
                                } catch (_: Exception) { message = "연결 해제에 실패했습니다. 다시 시도해 주세요." }
                                finally { busy = false }
                            }
                        }) { Text("연결 해제", color = Color.White) }
                    }
                }
                if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFD9A1))
                Text("개인 테스트용 연동 · 제공자 변경으로 연결이 중단될 수 있습니다.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFD2DEEC))
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
                            if (data == null) { if (loading) "사용량을 조회하고 있습니다." else "Codex 로그인 후 조회하면 표시됩니다." }
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
