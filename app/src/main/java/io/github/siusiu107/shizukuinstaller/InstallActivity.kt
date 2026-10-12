package io.github.siusiu107.shizukuinstaller

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import io.github.siusiu107.shizukuinstaller.pkg.ApkInfo
import io.github.siusiu107.shizukuinstaller.pkg.ApkSources
import io.github.siusiu107.shizukuinstaller.pkg.NormalInstaller
import io.github.siusiu107.shizukuinstaller.pkg.ShizukuInstaller
import io.github.siusiu107.shizukuinstaller.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Engine { SHIZUKU, NORMAL }
private enum class Phase { PARSING, READY, INSTALLING, SUCCESS, FAILURE }

class InstallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uris = extractUris(intent)
        setContent { SsukTheme { InstallSheet(uris) { finish() } } }
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
}

@Composable
private fun InstallSheet(uris: List<Uri>, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf(Phase.PARSING) }
    var info by remember { mutableStateOf<ApkInfo?>(null) }
    var apks by remember { mutableStateOf<List<ApkSources.Apk>>(emptyList()) }
    val shizukuReady = remember { Shell.state() == Shell.State.READY }
    var engine by remember { mutableStateOf(if (shizukuReady) Engine.SHIZUKU else Engine.NORMAL) }
    var logLine by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var recoverable by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (uris.isEmpty()) {
            errorMsg = "설치할 파일이 없어요."; phase = Phase.FAILURE; return@LaunchedEffect
        }
        try {
            val parsed = withContext(Dispatchers.IO) {
                val list = ApkSources.materialize(context, uris) { logLine = it }
                val base = list.firstOrNull { it.isBase } ?: list.maxByOrNull { it.size }!!
                list to ApkInfo.parse(context, base.file, list.sumOf { it.size }, list.size)
            }
            apks = parsed.first
            info = parsed.second
            if (parsed.second.needsOldSdkCompat && shizukuReady) engine = Engine.SHIZUKU
            phase = Phase.READY
        } catch (t: Throwable) {
            errorMsg = t.message ?: "파일을 읽지 못했어요."; phase = Phase.FAILURE
        }
    }

    fun runInstall(withEngine: Engine) {
        engine = withEngine
        phase = Phase.INSTALLING
        errorMsg = null
        scope.launch {
            val i = info
            try {
                if (withEngine == Engine.SHIZUKU) {
                    withContext(Dispatchers.IO) {
                        ShizukuInstaller.install(
                            apks = apks,
                            grantPermissions = true,
                            oldSdkCompat = i?.needsOldSdkCompat ?: false,
                            allowDowngrade = i?.isDowngrade ?: false,
                            log = { logLine = it },
                        )
                    }
                    phase = Phase.SUCCESS
                } else {
                    when (val r = NormalInstaller.install(context, apks) { logLine = it }) {
                        is NormalInstaller.Result.Success -> phase = Phase.SUCCESS
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
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
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
                Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 22.dp)
                    .padding(top = 12.dp, bottom = 20.dp),
            ) {
                Box(
                    Modifier.align(Alignment.CenterHorizontally).padding(bottom = 14.dp)
                        .size(width = 38.dp, height = 4.dp).clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                val currentInfo = info
                if (phase == Phase.PARSING) {
                    ParsingBody(logLine)
                } else if (currentInfo == null) {
                    FatalBody(errorMsg ?: "오류가 났어요.", onClose)
                } else {
                    Body(
                        info = currentInfo,
                        engine = engine,
                        shizukuReady = shizukuReady,
                        phase = phase,
                        logLine = logLine,
                        errorMsg = errorMsg,
                        recoverable = recoverable,
                        onInstall = { runInstall(it) },
                        onSetEngine = { engine = it },
                        onClose = onClose,
                        context = context,
                    )
                }
            }
        }
    }
}

@Composable
private fun ParsingBody(logLine: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("파일을 확인하는 중…", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
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
    info: ApkInfo,
    engine: Engine,
    shizukuReady: Boolean,
    phase: Phase,
    logLine: String,
    errorMsg: String?,
    recoverable: Boolean,
    onInstall: (Engine) -> Unit,
    onSetEngine: (Engine) -> Unit,
    onClose: () -> Unit,
    context: Context,
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
        if (info.isDowngrade) add(Badge("낮은 버전", Warn))
        else if (info.isUpdate) add(Badge("업데이트", Brand))
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
                        Icon(Icons.Rounded.Bolt, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text("Shizuku 로 설치")
                    }
                    FilledTonalButton(onClick = onClose) { Text("닫기") }
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
                    FilterChip(
                        selected = engine == Engine.NORMAL,
                        onClick = { onSetEngine(Engine.NORMAL) },
                        label = { Text("일반 설치") },
                    )
                    FilterChip(
                        selected = engine == Engine.SHIZUKU,
                        onClick = { onSetEngine(Engine.SHIZUKU) },
                        label = { Text("Shizuku") },
                        leadingIcon = { Icon(Icons.Rounded.Bolt, null, modifier = Modifier.size(16.dp)) },
                    )
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

private data class Badge(val text: String, val color: Color)

@Composable
private fun BadgeChip(b: Badge) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(b.color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
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
        useShizuku -> "Shizuku 로 설치돼요 (막힌 앱도 OK)"
        needsCompat && !shizukuReady -> "구버전 앱이에요. 막히면 Shizuku 를 켜고 다시 설치하세요."
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
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onClick = onClick,
)

private fun human(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    return "%.1f MB".format(kb / 1024.0)
}
