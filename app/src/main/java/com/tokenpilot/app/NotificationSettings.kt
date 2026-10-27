package com.tokenpilot.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
fun NotificationSettings() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var allowed by remember { mutableStateOf(UsageNotifications.allowed(context)) }
    var status by remember { mutableStateOf(UsageNotifications.enabled(context, UsageNotifications.STATUS)) }
    var reset by remember { mutableStateOf(UsageNotifications.enabled(context, UsageNotifications.RESET)) }
    var message by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = UsageNotifications.allowed(context)
        message = if (allowed) "알림 권한이 허용됐습니다. 원하는 알림을 켜주세요." else "권한이 없어 알림을 보낼 수 없습니다. 시스템 알림 설정에서 허용할 수 있습니다."
        UsageNotifications.reconcile(context)
    }
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allowed = UsageNotifications.allowed(context)
                UsageNotifications.reconcile(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("알림", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(if (allowed) "앱 알림 권한 허용됨" else "알림 권한이 필요합니다", color = MaterialTheme.colorScheme.primary)
            if (!allowed && Build.VERSION.SDK_INT >= 33) Button(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("알림 권한 요청") }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("알림창 사용량 표시", Modifier.weight(1f))
                Switch(status, {
                    status = it
                    UsageNotifications.setEnabled(context, UsageNotifications.STATUS, it)
                }, enabled = allowed || status, modifier = Modifier.semantics { contentDescription = "알림창 사용량 표시" })
            }
            Text("마지막 조회값과 확인 시각을 표시합니다. 앱을 열 때 갱신하며, 실시간 사용량은 아닙니다.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("초기화 예정 알림", Modifier.weight(1f))
                Switch(reset, {
                    reset = it
                    UsageNotifications.setEnabled(context, UsageNotifications.RESET, it)
                }, enabled = allowed || reset, modifier = Modifier.semantics { contentDescription = "초기화 예정 알림" })
            }
            Text("Codex가 제공한 5시간·주간 등 초기화 시각을 예약합니다. 앱을 닫아도 알리지만 절전 상태에서는 늦어질 수 있습니다. 실제 초기화 완료를 보장하지 않습니다.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                message = if (UsageNotifications.test(context)) "약 10초 후 테스트 알림을 예약했습니다. 절전 상태에서는 늦어질 수 있습니다."
                    else "알림이 차단되어 예약하지 못했습니다. 시스템 알림 설정에서 초기화 예정 알림을 허용하세요."
            }, enabled = allowed) { Text("예약 알림 테스트") }
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text("시스템 알림 설정") }
            Text("알림 종류별 소리·차단은 시스템 설정에서 변경합니다. Android 버전에 따라 사용량 알림도 밀어서 지울 수 있습니다. 앱 강제 종료 시 다시 열어야 예약이 복구됩니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}
