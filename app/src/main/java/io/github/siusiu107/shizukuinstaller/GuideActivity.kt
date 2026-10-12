package io.github.siusiu107.shizukuinstaller

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.siusiu107.shizukuinstaller.pkg.Prefs
import io.github.siusiu107.shizukuinstaller.pkg.WirelessAdb
import io.github.siusiu107.shizukuinstaller.ui.theme.*
import rikka.shizuku.Shizuku

class GuideActivity : ComponentActivity() {

    private val reqCode = 3003
    private var stateHolder: MutableState<Shell.State>? = null
    private val permListener = Shizuku.OnRequestPermissionResultListener { code, _ ->
        if (code == reqCode) stateHolder?.value = Shell.state()
    }
    private val binderReceived = Shizuku.OnBinderReceivedListener { stateHolder?.value = Shell.state() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Shizuku.addRequestPermissionResultListener(permListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        setContent {
            val prefs = remember { Prefs(this) }
            SsukTheme(
                forceDark = when (prefs.theme) {
                    Prefs.ThemeMode.LIGHT -> false
                    Prefs.ThemeMode.DARK -> true
                    Prefs.ThemeMode.SYSTEM -> null
                },
            ) {
                val state = remember { mutableStateOf(Shell.state()) }
                stateHolder = state
                val adb = remember { mutableStateOf(WirelessAdb.connected(this)) }
                val owner = LocalLifecycleOwner.current
                DisposableEffect(owner) {
                    val obs = LifecycleEventObserver { _, e ->
                        if (e == Lifecycle.Event.ON_RESUME) {
                            state.value = Shell.state(); adb.value = WirelessAdb.connected(this@GuideActivity)
                        }
                    }
                    owner.lifecycle.addObserver(obs)
                    onDispose { owner.lifecycle.removeObserver(obs) }
                }
                GuideScreen(
                    state = state.value,
                    adbConnected = adb.value,
                    unknownAllowed = canInstallUnknown(),
                    onBack = { finish() },
                    onOpenUnknownSources = { openUnknownSources() },
                    onInstallShizuku = { openShizuku() },
                    onRequestShizuku = {
                        if (Shizuku.shouldShowRequestPermissionRationale()) openShizuku()
                        else runCatching { Shizuku.requestPermission(reqCode) }
                    },
                    onOpenPairing = { startActivity(Intent(this, PairingActivity::class.java)) },
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permListener)
        Shizuku.removeBinderReceivedListener(binderReceived)
    }

    private fun canInstallUnknown(): Boolean =
        if (Build.VERSION.SDK_INT >= 26) packageManager.canRequestPackageInstalls() else true

    private fun openUnknownSources() {
        val intent = if (Build.VERSION.SDK_INT >= 26)
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri())
        else Intent(Settings.ACTION_SECURITY_SETTINGS)
        runCatching { startActivity(intent) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } }
    }

    private fun openShizuku() {
        val launch = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (launch != null) { startActivity(launch); return }
        val market = Intent(Intent.ACTION_VIEW, "market://details?id=moe.shizuku.privileged.api".toUri())
        val web = Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api".toUri())
        startActivity(if (market.resolveActivity(packageManager) != null) market else web)
    }
}

@Composable
private fun GuideScreen(
    state: Shell.State,
    adbConnected: Boolean,
    unknownAllowed: Boolean,
    onBack: () -> Unit,
    onOpenUnknownSources: () -> Unit,
    onInstallShizuku: () -> Unit,
    onRequestShizuku: () -> Unit,
    onOpenPairing: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "뒤로") }
                Text("권한 · 설정 가이드", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {

                Text(
                    "APK 를 설치하는 방법은 3가지예요. 하나만 설정해두면 돼요.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 19.sp,
                )
                Spacer(Modifier.height(16.dp))

                // 1) 일반 설치
                MethodCard(
                    icon = Icons.Rounded.Android, accent = MaterialTheme.colorScheme.primary,
                    title = "① 일반 설치", subtitle = "가장 간단 · 추가 앱 불필요",
                    done = unknownAllowed, doneText = "허용됨",
                ) {
                    GuideText("이 앱이 APK 를 설치할 수 있도록 '알 수 없는 앱 설치' 를 한 번 허용하면 돼요. 설치할 때 시스템 확인 창이 떠요.")
                    Spacer(Modifier.height(12.dp))
                    if (!unknownAllowed) {
                        Button(onClick = onOpenUnknownSources, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Lock, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("알 수 없는 앱 설치 허용 열기")
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 2) Shizuku
                val shizukuDone = state == Shell.State.READY
                MethodCard(
                    icon = Icons.Rounded.Bolt, accent = Brand,
                    title = "② Shizuku", subtitle = "빠르고 강력 · 막힌 앱도 설치",
                    done = shizukuDone, doneText = "준비됨",
                ) {
                    StepLine(1, "Shizuku 앱 설치", state != Shell.State.NOT_RUNNING || shizukuDone)
                    StepLine(2, "Shizuku 실행 (무선 디버깅 또는 루트)", state == Shell.State.NO_PERMISSION || shizukuDone)
                    StepLine(3, "쓱설치에 권한 허용", shizukuDone)
                    Spacer(Modifier.height(12.dp))
                    when (state) {
                        Shell.State.NOT_RUNNING -> Button(onClick = onInstallShizuku, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Download, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Shizuku 설치 / 실행")
                        }
                        Shell.State.NO_PERMISSION -> Button(onClick = onRequestShizuku, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Check, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("쓱설치에 권한 허용")
                        }
                        Shell.State.TOO_OLD -> Button(onClick = onInstallShizuku, modifier = Modifier.fillMaxWidth()) { Text("Shizuku 업데이트") }
                        Shell.State.READY -> {}
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 3) 무선 ADB
                MethodCard(
                    icon = Icons.Rounded.Wifi, accent = Mint,
                    title = "③ 무선 디버깅 연결", subtitle = "Shizuku·PC 없이 · Android 11+",
                    done = adbConnected, doneText = "연결됨",
                ) {
                    GuideText("앱이 직접 무선 디버깅으로 페어링해서 설치해요. 개발자 옵션에서 무선 디버깅을 켜고 페어링 코드를 입력하면 돼요.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onOpenPairing, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Wifi, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text(if (adbConnected) "연결 관리" else "무선 페어링 시작")
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp),
                ) {
                    Icon(Icons.Rounded.Lightbulb, null, tint = Warn, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "팁: 앱을 처음 받았을 때 설치가 막히면, ② 또는 ③ 을 설정해두면 다음부터 막힘 없이 바로 설치돼요.",
                        fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun MethodCard(
    icon: ImageVector, accent: Color, title: String, subtitle: String,
    done: Boolean, doneText: String, content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (done) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = Mint, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp)); Text(doneText, fontSize = 12.sp, color = Mint, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun GuideText(text: String) {
    Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 19.sp)
}

@Composable
private fun StepLine(num: Int, text: String, done: Boolean) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(22.dp).clip(CircleShape)
                .background(if (done) Mint else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
            else Text("$num", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}
