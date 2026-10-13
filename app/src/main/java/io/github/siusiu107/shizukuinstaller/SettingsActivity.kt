package io.github.siusiu107.shizukuinstaller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.siusiu107.shizukuinstaller.pkg.Prefs
import io.github.siusiu107.shizukuinstaller.ui.theme.SsukTheme

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefs = remember { Prefs(this) }
            var theme by remember { mutableStateOf(prefs.theme) }
            SsukTheme(
                forceDark = when (theme) {
                    Prefs.ThemeMode.LIGHT -> false
                    Prefs.ThemeMode.DARK -> true
                    Prefs.ThemeMode.SYSTEM -> null
                },
            ) {
                SettingsScreen(prefs, theme, onThemeChange = { theme = it; prefs.theme = it }, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    prefs: Prefs,
    theme: Prefs.ThemeMode,
    onThemeChange: (Prefs.ThemeMode) -> Unit,
    onBack: () -> Unit,
) {
    var engine by remember { mutableStateOf(prefs.defaultEngine) }
    var grant by remember { mutableStateOf(prefs.grantPermissions) }
    var downgrade by remember { mutableStateOf(prefs.allowDowngrade) }

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "뒤로") }
                Text("설정", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                SectionTitle("기본 설치 방식")
                SettingsCard {
                    RadioRow("추천 (연결돼 있으면 빠른 설치)", engine == Prefs.DefaultEngine.AUTO) { engine = Prefs.DefaultEngine.AUTO; prefs.defaultEngine = engine }
                    RadioRow("항상 일반 설치 먼저", engine == Prefs.DefaultEngine.NORMAL) { engine = Prefs.DefaultEngine.NORMAL; prefs.defaultEngine = engine }
                    RadioRow("빠른 설치 우선 (Shizuku · 무선 디버깅)", engine == Prefs.DefaultEngine.SHIZUKU) { engine = Prefs.DefaultEngine.SHIZUKU; prefs.defaultEngine = engine }
                }

                Spacer(Modifier.height(16.dp))
                SectionTitle("빠른 설치 기본 옵션")
                SettingsCard {
                    SwitchRow("권한 자동 허용", "앱 런타임 권한을 설치와 함께 허용", grant) { grant = it; prefs.grantPermissions = it }
                    SwitchRow("낮은 버전 허용", "설치된 버전보다 낮아도 설치", downgrade) { downgrade = it; prefs.allowDowngrade = it }
                }

                Spacer(Modifier.height(16.dp))
                SectionTitle("테마")
                SettingsCard {
                    RadioRow("시스템 설정 따름", theme == Prefs.ThemeMode.SYSTEM) { onThemeChange(Prefs.ThemeMode.SYSTEM) }
                    RadioRow("라이트", theme == Prefs.ThemeMode.LIGHT) { onThemeChange(Prefs.ThemeMode.LIGHT) }
                    RadioRow("다크", theme == Prefs.ThemeMode.DARK) { onThemeChange(Prefs.ThemeMode.DARK) }
                }

                Spacer(Modifier.height(16.dp))
                SettingsCard {
                    Row(Modifier.fillMaxWidth().clickable { prefs.clearHistory() }.padding(16.dp)) {
                        Text("최근 설치 기록 지우기", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                    }
                }

                Spacer(Modifier.height(20.dp))
                Text("쓱설치 · v1.0", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) { Column(content = content) }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(desc, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
