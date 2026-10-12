package io.github.siusiu107.shizukuinstaller

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.siusiu107.shizukuinstaller.ui.BrandLogo
import io.github.siusiu107.shizukuinstaller.ui.theme.*
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private val reqCode = 1001
    private var stateHolder: MutableState<Shell.State>? = null

    private val permListener = Shizuku.OnRequestPermissionResultListener { code, _ ->
        if (code == reqCode) refresh()
    }
    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Shizuku.addRequestPermissionResultListener(permListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)

        setContent {
            SsukTheme {
                val state = remember { mutableStateOf(Shell.state()) }
                stateHolder = state
                val owner = LocalLifecycleOwner.current
                DisposableEffect(owner) {
                    val obs = LifecycleEventObserver { _, e ->
                        if (e == Lifecycle.Event.ON_RESUME) state.value = Shell.state()
                    }
                    owner.lifecycle.addObserver(obs)
                    onDispose { owner.lifecycle.removeObserver(obs) }
                }
                HomeScreen(
                    state = state.value,
                    onPrimary = {
                        when (state.value) {
                            Shell.State.NO_PERMISSION -> {
                                if (Shizuku.shouldShowRequestPermissionRationale()) openShizuku()
                                else Shizuku.requestPermission(reqCode)
                            }
                            else -> state.value = Shell.state()
                        }
                    },
                    onOpenShizuku = { openShizuku() },
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

    private fun refresh() {
        stateHolder?.value = Shell.state()
    }

    private fun openShizuku() {
        val pm = packageManager
        val launch = pm.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (launch != null) {
            startActivity(launch)
        } else {
            val market = Intent(Intent.ACTION_VIEW, "market://details?id=moe.shizuku.privileged.api".toUri())
            val web = Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api".toUri())
            startActivity(if (market.resolveActivity(pm) != null) market else web)
        }
    }
}

@Composable
private fun HomeScreen(
    state: Shell.State,
    onPrimary: () -> Unit,
    onOpenShizuku: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            BrandLogo(size = 76.dp)
            Spacer(Modifier.height(16.dp))
            Text("쓱설치", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(4.dp))
            Text(
                "탭 한 번, 쓱 설치",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))

            StatusCard(state, onPrimary, onOpenShizuku)
            Spacer(Modifier.height(16.dp))
            StepsCard()
            Spacer(Modifier.height(16.dp))
            NoticeCard()
            Spacer(Modifier.height(16.dp))
        }
    }
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
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
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
                    Shell.State.NO_PERMISSION ->
                        Button(onClick = onPrimary, modifier = Modifier.weight(1f)) { Text("권한 허용") }
                    Shell.State.READY ->
                        FilledTonalButton(onClick = onPrimary, modifier = Modifier.weight(1f)) { Text("상태 새로고침") }
                    else -> {
                        Button(onClick = onOpenShizuku, modifier = Modifier.weight(1f)) { Text("Shizuku 열기") }
                        FilledTonalButton(onClick = onPrimary) { Text("새로고침") }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StepsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("이렇게 써요", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(16.dp))
            Step(Icons.Rounded.FolderOpen, "1", "APK 를 공유하거나 열기", "파일앱·브라우저에서 APK 를 열고 '쓱설치로 설치' 를 고르세요.")
            Spacer(Modifier.height(14.dp))
            Step(Icons.Rounded.TouchApp, "2", "설치 버튼 한 번", "앱 정보를 확인하고 설치를 누르면 끝이에요.")
            Spacer(Modifier.height(14.dp))
            Step(Icons.Rounded.Bolt, "3", "막히면 Shizuku 로", "일반 설치가 막히면 Shizuku 로 바로 다시 설치해요.")
        }
    }
}

@Composable
private fun Step(icon: ImageVector, num: String, title: String, desc: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
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
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.Shield, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            "믿을 수 있는 APK 만 설치하세요. 설치 차단은 기기를 보호하려는 기능이라, 출처가 분명한 앱에만 쓰는 걸 권해요.",
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp,
            textAlign = TextAlign.Start,
        )
    }
}
