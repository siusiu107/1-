package io.github.siusiu107.shizukuinstaller

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.siusiu107.shizukuinstaller.pkg.Prefs
import io.github.siusiu107.shizukuinstaller.pkg.WirelessAdb
import io.github.siusiu107.shizukuinstaller.ui.theme.*
import kotlinx.coroutines.launch

class PairingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefs = remember { Prefs(this) }
            SsukTheme(
                forceDark = when (prefs.theme) {
                    Prefs.ThemeMode.LIGHT -> false
                    Prefs.ThemeMode.DARK -> true
                    Prefs.ThemeMode.SYSTEM -> null
                },
            ) { PairingScreen(onBack = { finish() }) }
        }
    }
}

private enum class Step { IDLE, PAIRING, CONNECTING, DONE, ERROR }

@Composable
private fun PairingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var code by remember { mutableStateOf("") }
    var pairPort by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(if (WirelessAdb.connected(context)) Step.DONE else Step.IDLE) }
    var message by remember { mutableStateOf("") }
    var notifStarted by remember { mutableStateOf(false) }

    // 설정/알림에서 페어링이 끝나고 돌아오면 연결 상태 반영
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                // 락 없는 확인이라 메인 스레드에서 안전. 끊겼으면 '연결됨' 표시도 내린다
                if (WirelessAdb.connected(context)) step = Step.DONE
                else if (step == Step.DONE) step = Step.IDLE
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    fun launchNotifPairing() {
        PairingService.start(context)
        notifStarted = true
        if (step == Step.ERROR) { step = Step.IDLE; message = "" }
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchNotifPairing()
        else {
            step = Step.ERROR
            message = "알림 권한이 있어야 알림에서 코드를 입력할 수 있어요. 설정에서 쓱설치 알림을 허용해 주세요."
        }
    }

    fun beginNotifPairing() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            step = Step.ERROR
            message = "쓱설치 알림이 꺼져 있어요. 설정에서 알림을 켜 주세요."
        } else {
            launchNotifPairing()
        }
    }

    // mDNS 로 페어링 포트 자동 채우기 (best-effort)
    LaunchedEffect(Unit) {
        if (step != Step.DONE) {
            val p = WirelessAdb.findPairingPort(context)
            if (p > 0 && pairPort.isBlank()) pairPort = p.toString()
        }
    }

    fun start() {
        val c = code.trim()
        val pp = pairPort.trim().toIntOrNull()
        if (c.length < 6 || pp == null) {
            message = "페어링 코드(6자리)와 페어링 포트를 입력해 주세요."
            step = Step.ERROR
            return
        }
        scope.launch {
            try {
                step = Step.PAIRING; message = "페어링 중…"
                val paired = WirelessAdb.pair(context, pp, c)
                if (!paired) throw WirelessAdb.AdbException("페어링이 거부됐어요. 코드/포트를 확인해 주세요.")
                step = Step.CONNECTING; message = "연결 중…"
                val cp = connectPort.trim().toIntOrNull()
                val ok = if (cp != null) WirelessAdb.connect(context, cp) else WirelessAdb.autoConnect(context)
                if (!ok) throw WirelessAdb.AdbException("페어링은 됐지만 연결을 못 찾았어요. '연결 포트' 를 입력해 다시 시도해 주세요.")
                step = Step.DONE; message = ""
            } catch (t: Throwable) {
                step = Step.ERROR; message = t.message ?: "실패했어요."
            }
        }
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "뒤로") }
                Text("무선 디버깅 연결", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {

                if (step == Step.DONE) {
                    ConnectedCard(onDisconnect = {
                        scope.launch { WirelessAdb.disconnect(context); step = Step.IDLE }
                    })
                    Spacer(Modifier.height(16.dp))
                }

                NotifPairCard(
                    started = notifStarted,
                    supported = Build.VERSION.SDK_INT >= 30,
                    onStart = { beginNotifPairing() },
                )
                Spacer(Modifier.height(16.dp))

                InfoCard()
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.DeveloperMode, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text("개발자 옵션 열기")
                }

                Spacer(Modifier.height(20.dp))
                Text("또는 직접 입력 (분할 화면 등으로 코드를 보면서)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = code, onValueChange = { code = it.filter { ch -> ch.isDigit() }.take(6) },
                    label = { Text("페어링 코드 (6자리)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = pairPort, onValueChange = { pairPort = it.filter { ch -> ch.isDigit() }.take(5) },
                    label = { Text("페어링 포트") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text("페어링 창에 보이는 포트 (자동 감지되면 채워져요)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = connectPort, onValueChange = { connectPort = it.filter { ch -> ch.isDigit() }.take(5) },
                    label = { Text("연결 포트 (선택)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text("무선 디버깅 메인 화면의 포트. 비우면 자동 탐색해요.") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(16.dp))
                val busy = step == Step.PAIRING || step == Step.CONNECTING
                Button(onClick = { start() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(10.dp)); Text(message)
                    } else {
                        Icon(Icons.Rounded.Wifi, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp)); Text("페어링하고 연결")
                    }
                }

                if (step == Step.ERROR && message.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(message, fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer, lineHeight = 18.sp)
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ConnectedCard(onDisconnect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Mint.copy(alpha = 0.14f)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CheckCircle, null, tint = Mint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("무선으로 연결됨", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text("이제 Shizuku 없이도 막힌 앱을 설치할 수 있어요.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onDisconnect) { Text("끊기") }
    }
}

@Composable
private fun InfoCard() {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp),
    ) {
        Text("준비 (Android 11 이상)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(10.dp))
        listOf(
            "설정 > 개발자 옵션 > '무선 디버깅' 을 켜요 (Wi-Fi 연결 필요).",
            "'페어링 코드로 기기 페어링' 을 눌러요.",
            "거기 뜨는 6자리 코드와 포트를 아래에 입력해요.",
        ).forEachIndexed { i, t ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("${i + 1}. ", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(t, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
private fun NotifPairCard(started: Boolean, supported: Boolean, onStart: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Brand.copy(alpha = 0.10f)).padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.NotificationsActive, null, tint = Brand, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text("알림으로 페어링", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Brand).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text("추천", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "설정의 페어링 창을 띄운 채로 알림창을 내려 코드를 입력하면 끝이에요. " +
                "페어링 창은 다른 앱으로 넘어가면 닫혀서, 이 방식이 가장 편해요.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 19.sp,
        )
        Spacer(Modifier.height(14.dp))
        Button(onClick = onStart, enabled = supported, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(Icons.Rounded.NotificationsActive, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    !supported -> "Android 11 이상에서만 지원돼요"
                    started -> "다시 시작"
                    else -> "알림으로 페어링 시작"
                },
            )
        }
        if (started) {
            Spacer(Modifier.height(12.dp))
            listOf(
                "개발자 옵션 › 무선 디버깅 › '페어링 코드로 기기 페어링' 을 누르세요.",
                "창이 열린 채로 알림창을 내리고 '코드 입력' 을 누르세요.",
                "6자리 코드를 입력하면 자동으로 페어링·연결돼요.",
            ).forEachIndexed { i, t ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text("${i + 1}. ", fontSize = 13.sp, color = Brand, fontWeight = FontWeight.Bold)
                    Text(t, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 19.sp)
                }
            }
        }
    }
}
