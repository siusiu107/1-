package io.github.siusiu107.shizukuinstaller

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import io.github.siusiu107.shizukuinstaller.pkg.Prefs
import io.github.siusiu107.shizukuinstaller.pkg.WirelessAdb
import kotlinx.coroutines.launch
import io.github.siusiu107.shizukuinstaller.ui.BrandLogo
import io.github.siusiu107.shizukuinstaller.ui.theme.*
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private val reqCode = 1001
    private var stateHolder: MutableState<Shell.State>? = null

    private val permListener = Shizuku.OnRequestPermissionResultListener { code, _ -> if (code == reqCode) refresh() }
    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Shizuku.addRequestPermissionResultListener(permListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)

        setContent {
            val prefs = remember { Prefs(this) }
            val themeMode = remember { mutableStateOf(prefs.theme) }
            SsukTheme(forceDark = themeDark(themeMode.value)) {
                val state = remember { mutableStateOf(Shell.state()) }
                stateHolder = state
                val history = remember { mutableStateOf(prefs.history()) }
                val adbConnected = remember { mutableStateOf(WirelessAdb.connected(this)) }
                val owner = LocalLifecycleOwner.current
                DisposableEffect(owner) {
                    val obs = LifecycleEventObserver { _, e ->
                        if (e == Lifecycle.Event.ON_RESUME) {
                            state.value = Shell.state()
                            history.value = prefs.history()
                            themeMode.value = prefs.theme
                            adbConnected.value = WirelessAdb.connected(this@MainActivity)
                            Shell.prewarm()
                            // 이전에 페어링한 적 있으면 백그라운드로 무선 재연결 시도
                            if (!adbConnected.value && state.value != Shell.State.READY) {
                                lifecycleScope.launch {
                                    if (WirelessAdb.autoConnect(this@MainActivity)) adbConnected.value = true
                                }
                            }
                        }
                    }
                    owner.lifecycle.addObserver(obs)
                    onDispose { owner.lifecycle.removeObserver(obs) }
                }

                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                    if (uris.isNotEmpty()) installUris(uris)
                }

                HomeScreen(
                    state = state.value,
                    history = history.value,
                    adbConnected = adbConnected.value,
                    onOpenPairing = { startActivity(Intent(this, PairingActivity::class.java)) },
                    onPrimary = {
                        when (state.value) {
                            Shell.State.NO_PERMISSION ->
                                if (Shizuku.shouldShowRequestPermissionRationale()) openShizuku()
                                else Shizuku.requestPermission(reqCode)
                            else -> state.value = Shell.state()
                        }
                    },
                    onOpenShizuku = { openShizuku() },
                    onPickFiles = {
                        picker.launch(arrayOf(
                            "application/vnd.android.package-archive",
                            "application/octet-stream",
                            "application/zip",
                            "application/x-zip-compressed",
                        ))
                    },
                    onInstallUrl = { installUrl(it) },
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    onOpenGuide = { startActivity(Intent(this, GuideActivity::class.java)) },
                    onOpenApp = { pkg -> packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) } },
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permListener)
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
    }

    private fun refresh() { stateHolder?.value = Shell.state() }

    private fun installUris(uris: List<Uri>) {
        val intent = Intent(this, InstallActivity::class.java).apply {
            action = Intent.ACTION_SEND_MULTIPLE
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            type = "application/vnd.android.package-archive"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val cd = ClipData.newUri(contentResolver, "apk", uris.first())
            for (i in 1 until uris.size) cd.addItem(ClipData.Item(uris[i]))
            clipData = cd
        }
        startActivity(intent)
    }

    private fun installUrl(url: String) {
        startActivity(Intent(this, InstallActivity::class.java).putExtra(InstallActivity.EXTRA_URL, url))
    }

    private fun openShizuku() {
        val launch = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (launch != null) { startActivity(launch); return }
        val market = Intent(Intent.ACTION_VIEW, "market://details?id=moe.shizuku.privileged.api".toUri())
        val web = Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api".toUri())
        startActivity(if (market.resolveActivity(packageManager) != null) market else web)
    }
}

private fun themeDark(mode: Prefs.ThemeMode): Boolean? = when (mode) {
    Prefs.ThemeMode.LIGHT -> false
    Prefs.ThemeMode.DARK -> true
    Prefs.ThemeMode.SYSTEM -> null
}

@Composable
private fun HomeScreen(
    state: Shell.State,
    history: List<Prefs.HistoryItem>,
    adbConnected: Boolean,
    onOpenPairing: () -> Unit,
    onPrimary: () -> Unit,
    onOpenShizuku: () -> Unit,
    onPickFiles: () -> Unit,
    onInstallUrl: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenApp: (String) -> Unit,
) {
    var showUrlDialog by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            // top bar
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onOpenGuide) {
                    Icon(Icons.Rounded.HelpOutline, "가이드", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Rounded.Settings, "설정", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                BrandLogo(size = 72.dp)
                Spacer(Modifier.height(14.dp))
                Text("쓱설치", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(4.dp))
                Text("탭 한 번, 쓱 설치", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(24.dp))

            // primary actions
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onPickFiles, modifier = Modifier.weight(1f).height(52.dp)) {
                    Icon(Icons.Rounded.FolderOpen, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp)); Text("APK 파일 선택")
                }
                FilledTonalButton(onClick = { showUrlDialog = true }, modifier = Modifier.height(52.dp)) {
                    Icon(Icons.Rounded.Link, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp)); Text("링크")
                }
            }

            Spacer(Modifier.height(16.dp))
            StatusCard(state, onPrimary, onOpenShizuku)

            Spacer(Modifier.height(12.dp))
            WirelessCard(adbConnected, onOpenPairing)

            if (history.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                HistoryCard(history, onOpenApp)
            }

            Spacer(Modifier.height(16.dp))
            StepsCard()
            Spacer(Modifier.height(16.dp))
            NoticeCard()
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showUrlDialog) {
        UrlDialog(onDismiss = { showUrlDialog = false }, onConfirm = {
            showUrlDialog = false
            onInstallUrl(it)
        })
    }
}

@Composable
private fun UrlDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Link, null) },
        title = { Text("링크로 설치") },
        text = {
            Column {
                Text("APK 또는 묶음(.apks/.xapk) 직접 다운로드 주소를 넣어주세요.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    placeholder = { Text("https://…/app.apk") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text("받아서 설치") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun StatusCard(state: Shell.State, onPrimary: () -> Unit, onOpenShizuku: () -> Unit) {
    val (dot, title, desc) = when (state) {
        Shell.State.READY -> Triple(Mint, "Shizuku 준비됨", "막힌 앱도 쓱 설치할 수 있어요 (${Shell.backendName()} 권한).")
        Shell.State.NO_PERMISSION -> Triple(Warn, "권한이 필요해요", "쓱설치에 Shizuku 권한을 한 번 허용해 주세요.")
        Shell.State.NOT_RUNNING -> Triple(MaterialTheme.colorScheme.onSurfaceVariant, "Shizuku 꺼짐", "일반 설치는 바로 돼요. 막힌 앱까지 설치하려면 Shizuku 를 켜세요.")
        Shell.State.TOO_OLD -> Triple(Warn, "Shizuku 업데이트 필요", "Shizuku 를 최신(v11+) 으로 올려 주세요.")
    }
    val dotColor by animateColorAsState(dot, label = "dot")
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor))
                Spacer(Modifier.width(10.dp))
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.height(8.dp))
            Text(desc, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (state) {
                    Shell.State.NO_PERMISSION -> Button(onClick = onPrimary, modifier = Modifier.weight(1f)) { Text("권한 허용") }
                    Shell.State.READY -> FilledTonalButton(onClick = onPrimary, modifier = Modifier.weight(1f)) { Text("상태 새로고침") }
                    else -> {
                        Button(onClick = onOpenShizuku, modifier = Modifier.weight(1f)) { Text("Shizuku 열기") }
                        FilledTonalButton(onClick = onPrimary) { Text("새로고침") }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            var showDiag by remember { mutableStateOf(false) }
            val diag = remember(state, showDiag) { if (showDiag) Shell.diagnostics() else "" }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(
                    if (showDiag) "자세히 닫기" else "자세히",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { showDiag = !showDiag }.padding(4.dp),
                )
            }
            AnimatedVisibility(showDiag) {
                Box(
                    Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
                ) {
                    Text(diag, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 17.sp)
                }
            }
        }
    }
}

@Composable
private fun WirelessCard(connected: Boolean, onOpenPairing: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenPairing), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                    .background((if (connected) Mint else MaterialTheme.colorScheme.primary).copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (connected) Icons.Rounded.Wifi else Icons.Rounded.WifiFind,
                    null, tint = if (connected) Mint else MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (connected) "무선으로 연결됨" else "무선 디버깅으로 연결",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (connected) "Shizuku 없이 막힌 앱 설치 가능" else "Shizuku·PC 없이 앱에서 바로 페어링",
                    fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HistoryCard(history: List<Prefs.HistoryItem>, onOpenApp: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(vertical = 16.dp)) {
            Text("최근 설치", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(8.dp))
            history.take(6).forEach { item ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenApp(item.pkg) }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = Mint, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                        Text(item.pkg, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Icon(Icons.Rounded.Launch, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun StepsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("이렇게 써요", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(16.dp))
            Step(Icons.Rounded.FolderOpen, "파일 선택 또는 공유", "위 'APK 파일 선택' 을 누르거나, 파일앱·브라우저에서 APK 를 '쓱설치로 설치' 로 여세요.")
            Spacer(Modifier.height(14.dp))
            Step(Icons.Rounded.TouchApp, "설치 버튼 한 번", "앱 정보를 확인하고 설치를 누르면 끝이에요.")
            Spacer(Modifier.height(14.dp))
            Step(Icons.Rounded.Bolt, "막히면 Shizuku 로", "일반 설치가 막히면 Shizuku 로 바로 다시 설치해요.")
        }
    }
}

@Composable
private fun Step(icon: ImageVector, title: String, desc: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(desc, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun NoticeCard() {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.Shield, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            "믿을 수 있는 APK 만 설치하세요. 설치 차단은 기기를 보호하려는 기능이라, 출처가 분명한 앱에만 쓰는 걸 권해요.",
            fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp, textAlign = TextAlign.Start,
        )
    }
}
