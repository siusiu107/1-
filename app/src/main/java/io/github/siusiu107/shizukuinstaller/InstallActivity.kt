package io.github.siusiu107.shizukuinstaller

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import io.github.siusiu107.shizukuinstaller.pkg.*
import io.github.siusiu107.shizukuinstaller.ui.theme.*
import kotlinx.coroutines.Dispatchers
import rikka.shizuku.Shizuku
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Engine { SHIZUKU, NORMAL }
private enum class Phase { PARSING, READY, INSTALLING, SUCCESS, FAILURE }

class InstallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 설치를 누르기 전에 Shizuku 연결을 미리 맺어 첫 설치 지연을 숨긴다
        Shell.prewarm()
        val url = intent?.getStringExtra(EXTRA_URL)
        val uris = extractUris(intent)
        setContent {
            val prefs = remember { Prefs(this) }
            SsukTheme(forceDark = themeDark(prefs.theme)) {
                InstallSheet(uris = uris, downloadUrl = url, prefs = prefs) { finish() }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { ApkSources.cleanup(this) }
    }

    @Suppress("DEPRECATION")
    private fun extractUris(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        val out = LinkedHashSet<Uri>()
        intent.data?.let { out += it }
        when (intent.action) {
            Intent.ACTION_SEND -> (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let { out += it }
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { out += it.filterNotNull() }
        }
        intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { out += it } }
        return out.toList()
    }

    companion object {
        const val EXTRA_URL = "io.github.siusiu107.shizukuinstaller.extra.URL"
    }
}

private fun themeDark(mode: Prefs.ThemeMode): Boolean? = when (mode) {
    Prefs.ThemeMode.LIGHT -> false
    Prefs.ThemeMode.DARK -> true
    Prefs.ThemeMode.SYSTEM -> null
}

@Composable
private fun InstallSheet(uris: List<Uri>, downloadUrl: String?, prefs: Prefs, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf(Phase.PARSING) }
    var info by remember { mutableStateOf<ApkInfo?>(null) }
    var apks by remember { mutableStateOf<List<ApkSources.Apk>>(emptyList()) }
    // Shizuku 바인더는 콜드스타트 시 늦게 도착할 수 있고, 무선 연결도 백그라운드로 복구되므로 실시간 상태로 둔다
    var shizukuReady by remember { mutableStateOf(PrivilegedInstaller.available(context)) }
    var engineTouched by remember { mutableStateOf(false) }
    var engine by remember {
        mutableStateOf(
            when (prefs.defaultEngine) {
                Prefs.DefaultEngine.NORMAL -> Engine.NORMAL
                else -> if (shizukuReady) Engine.SHIZUKU else Engine.NORMAL
            },
        )
    }
    var logLine by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var recoverable by remember { mutableStateOf(false) }

    // 고급 옵션
    var optGrant by remember { mutableStateOf(prefs.grantPermissions) }
    var optDowngrade by remember { mutableStateOf(prefs.allowDowngrade) }
    var optCompat by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val parsed = withContext(Dispatchers.IO) {
                val list = when {
                    downloadUrl != null -> {
                        val f = Downloader.download(context, downloadUrl) { logLine = it }
                        ApkSources.materializeFiles(context, listOf(f)) { logLine = it }
                    }
                    uris.isNotEmpty() -> ApkSources.materialize(context, uris) { logLine = it }
                    else -> throw ApkSources.SourceException("설치할 파일이 없어요.")
                }
                val base = list.firstOrNull { it.isBase } ?: list.maxByOrNull { it.size }!!
                list to ApkInfo.parse(context, base.file, list.sumOf { it.size }, list.size)
            }
            apks = parsed.first
            info = parsed.second
            optDowngrade = optDowngrade || parsed.second.isDowngrade
            optCompat = parsed.second.needsOldSdkCompat
            phase = Phase.READY
        } catch (t: Throwable) {
            errorMsg = t.message ?: "파일을 읽지 못했어요."; phase = Phase.FAILURE
        }
    }

    // Shizuku 바인더 도착/소멸을 실시간 반영
    DisposableEffect(Unit) {
        val received = Shizuku.OnBinderReceivedListener {
            shizukuReady = PrivilegedInstaller.available(context)
            if (shizukuReady) Shell.prewarm()
        }
        val dead = Shizuku.OnBinderDeadListener { shizukuReady = PrivilegedInstaller.available(context) }
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        onDispose {
            Shizuku.removeBinderReceivedListener(received)
            Shizuku.removeBinderDeadListener(dead)
        }
    }

    // 앱이 꺼져 있다가 파일로 바로 열린 경우: 페어링해 둔 무선 연결을 조용히 복구
    LaunchedEffect(Unit) {
        if (!shizukuReady && WirelessAdb.reconnectInBackground(context)) {
            shizukuReady = PrivilegedInstaller.available(context)
        }
    }

    // 사용자가 직접 고르지 않았다면, 가용성/앱 정보가 바뀔 때 기본 엔진을 다시 적용
    LaunchedEffect(shizukuReady, info) {
        if (!engineTouched && (phase == Phase.PARSING || phase == Phase.READY)) {
            val wantFast = prefs.defaultEngine != Prefs.DefaultEngine.NORMAL || info?.needsOldSdkCompat == true
            engine = if (shizukuReady && wantFast) Engine.SHIZUKU else Engine.NORMAL
        }
    }

    fun runInstall(withEngine: Engine) {
        engineTouched = true
        engine = withEngine
        phase = Phase.INSTALLING
        errorMsg = null
        scope.launch {
            try {
                if (withEngine == Engine.SHIZUKU) {
                    withContext(Dispatchers.IO) {
                        PrivilegedInstaller.install(
                            context = context,
                            apks = apks,
                            grantPermissions = optGrant,
                            oldSdkCompat = optCompat,
                            allowDowngrade = optDowngrade,
                            log = { logLine = it },
                        )
                    }
                    info?.let { prefs.addHistory(it.packageName, it.label, System.currentTimeMillis()) }
                    phase = Phase.SUCCESS
                } else {
                    when (val r = NormalInstaller.install(context, apks) { logLine = it }) {
                        is NormalInstaller.Result.Success -> {
                            info?.let { prefs.addHistory(it.packageName, it.label, System.currentTimeMillis()) }
                            phase = Phase.SUCCESS
                        }
                        is NormalInstaller.Result.Failure -> {
                            errorMsg = r.message; recoverable = r.recoverable; phase = Phase.FAILURE
                        }
                    }
                }
            } catch (t: Throwable) {
                errorMsg = t.message ?: "설치에 실패했어요."; recoverable = true; phase = Phase.FAILURE
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
            .noRippleClick { if (phase != Phase.INSTALLING) onClose() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().noRippleClick { },
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 3.dp,
        ) {
            Column(
                Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 22.dp).padding(top = 12.dp, bottom = 20.dp),
            ) {
                Box(
                    Modifier.align(Alignment.CenterHorizontally).padding(bottom = 14.dp)
                        .size(width = 38.dp, height = 4.dp).clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                val currentInfo = info
                if (phase == Phase.PARSING) {
                    ParsingBody(logLine, downloadUrl != null)
                } else if (currentInfo == null) {
                    FatalBody(errorMsg ?: "오류가 났어요.", onClose)
                } else {
                    Body(
                        info = currentInfo, engine = engine, shizukuReady = shizukuReady, phase = phase,
                        logLine = logLine, errorMsg = errorMsg, recoverable = recoverable,
                        optGrant = optGrant, optDowngrade = optDowngrade, optCompat = optCompat,
                        onOptGrant = { optGrant = it; prefs.grantPermissions = it },
                        onOptDowngrade = { optDowngrade = it },
                        onOptCompat = { optCompat = it },
                        onInstall = { runInstall(it) }, onSetEngine = { engine = it; engineTouched = true },
                        onClose = onClose, context = context,
                    )
                }
            }
        }
    }
}

@Composable
private fun ParsingBody(logLine: String, downloading: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(if (downloading) "파일을 내려받는 중…" else "파일을 확인하는 중…", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
        if (logLine.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(logLine, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun FatalBody(msg: String, onClose: () -> Unit) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text("설치할 수 없어요", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(msg, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("닫기") }
    }
}

@Composable
private fun Body(
    info: ApkInfo, engine: Engine, shizukuReady: Boolean, phase: Phase,
    logLine: String, errorMsg: String?, recoverable: Boolean,
    optGrant: Boolean, optDowngrade: Boolean, optCompat: Boolean,
    onOptGrant: (Boolean) -> Unit, onOptDowngrade: (Boolean) -> Unit, onOptCompat: (Boolean) -> Unit,
    onInstall: (Engine) -> Unit, onSetEngine: (Engine) -> Unit, onClose: () -> Unit, context: Context,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val bmp = remember(info.packageName) { info.icon?.toBitmap(96, 96)?.asImageBitmap() }
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) Image(bmp, null, modifier = Modifier.size(48.dp))
            else Icon(Icons.Rounded.Android, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(info.label, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append("v${info.versionName}")
                    if (info.splitCount > 1) append(" · 분할 ${info.splitCount}개")
                    append(" · ${human(info.totalSize)}")
                },
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(info.packageName, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

    val badges = buildList {
        if (info.isDowngrade) add(Badge("낮은 버전", Warn)) else if (info.isUpdate) add(Badge("업데이트", Brand))
        if (info.needsOldSdkCompat) add(Badge("구버전 앱", Warn))
    }
    if (badges.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { badges.forEach { BadgeChip(it) } }
    }

    Spacer(Modifier.height(18.dp))

    when (phase) {
        Phase.SUCCESS -> {
            ResultRow(Icons.Rounded.CheckCircle, Mint, "설치 완료")
            Spacer(Modifier.height(18.dp))
            val launch = remember(info.packageName) {
                runCatching { context.packageManager.getLaunchIntentForPackage(info.packageName) }.getOrNull()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (launch != null) {
                    Button(onClick = { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); onClose() }, modifier = Modifier.weight(1f)) { Text("열기") }
                    FilledTonalButton(onClick = onClose) { Text("완료") }
                } else {
                    Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("완료") }
                }
            }
        }

        Phase.FAILURE -> {
            ResultRow(Icons.Rounded.ErrorOutline, MaterialTheme.colorScheme.error, "설치 실패")
            Spacer(Modifier.height(8.dp))
            Text(errorMsg ?: "설치에 실패했어요.", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 19.sp)
            Spacer(Modifier.height(18.dp))
            val offerShizuku = recoverable && engine == Engine.NORMAL && shizukuReady
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (offerShizuku) {
                    Button(onClick = { onInstall(Engine.SHIZUKU) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Bolt, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("빠르게 설치")
                    }
                    FilledTonalButton(onClick = onClose) { Text("닫기") }
                } else if (engine == Engine.SHIZUKU) {
                    // 빠른 설치가 실패하면 같은 엔진만 반복하지 않도록 일반 설치로 빠져나갈 길을 준다
                    FilledTonalButton(onClick = { onInstall(Engine.SHIZUKU) }, modifier = Modifier.weight(1f)) { Text("다시 시도") }
                    OutlinedButton(onClick = { onInstall(Engine.NORMAL) }) { Text("일반 설치로") }
                } else {
                    FilledTonalButton(onClick = { onInstall(engine) }, modifier = Modifier.weight(1f)) { Text("다시 시도") }
                    OutlinedButton(onClick = onClose) { Text("닫기") }
                }
            }
        }

        else -> {
            EngineBanner(engine, shizukuReady, info.needsOldSdkCompat)
            if (phase != Phase.INSTALLING && shizukuReady) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(engine == Engine.NORMAL, { onSetEngine(Engine.NORMAL) }, { Text("일반 설치") })
                    FilterChip(engine == Engine.SHIZUKU, { onSetEngine(Engine.SHIZUKU) }, { Text("빠른 설치") },
                        leadingIcon = { Icon(Icons.Rounded.Bolt, null, modifier = Modifier.size(16.dp)) })
                }
                if (engine == Engine.SHIZUKU) {
                    AdvancedOptions(optGrant, optDowngrade, optCompat, onOptGrant, onOptDowngrade, onOptCompat)
                }
            }
            Spacer(Modifier.height(16.dp))
            if (phase == Phase.INSTALLING) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(logLine.ifBlank { "설치 중…" }, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onInstall(engine) }, modifier = Modifier.weight(1f)) {
                        Text("설치", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(onClick = onClose) { Text("취소") }
                }
            }
        }
    }
}

@Composable
private fun AdvancedOptions(
    grant: Boolean, downgrade: Boolean, compat: Boolean,
    onGrant: (Boolean) -> Unit, onDowngrade: (Boolean) -> Unit, onCompat: (Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = !open }.padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Tune, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("고급 옵션", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
    AnimatedVisibility(open) {
        Column {
            OptionRow("권한 자동 허용", "앱의 런타임 권한을 설치와 함께 허용해요.", grant, onGrant)
            OptionRow("낮은 버전 허용", "설치된 버전보다 낮아도 설치해요.", downgrade, onDowngrade)
            OptionRow("구버전 앱 호환 설치", "오래된 앱도 설치되게 해요.", compat, onCompat)
        }
    }
}

@Composable
private fun OptionRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(desc, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private data class Badge(val text: String, val color: Color)

@Composable
private fun BadgeChip(b: Badge) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(b.color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp)) {
        Text(b.text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = b.color)
    }
}

@Composable
private fun EngineBanner(engine: Engine, shizukuReady: Boolean, needsCompat: Boolean) {
    val useShizuku = engine == Engine.SHIZUKU
    val tint = when {
        useShizuku -> Brand
        needsCompat && !shizukuReady -> Warn
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = if (useShizuku) Icons.Rounded.Bolt else if (needsCompat && !shizukuReady) Icons.Rounded.Info else Icons.Rounded.Android
    val text = when {
        useShizuku -> "빠른 설치로 진행해요 (막힌 앱도 OK)"
        needsCompat && !shizukuReady -> "구버전 앱이에요. Shizuku 또는 무선 연결을 켜면 설치돼요."
        else -> "일반 설치로 진행해요"
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.10f)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ResultRow(icon: ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Modifier.noRippleClick(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick,
)

private fun human(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    return "%.1f MB".format(kb / 1024.0)
}
