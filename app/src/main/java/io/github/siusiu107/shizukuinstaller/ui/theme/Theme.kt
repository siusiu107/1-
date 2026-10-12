package io.github.siusiu107.shizukuinstaller.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val Brand = Color(0xFF6C4CF1)
val BrandAlt = Color(0xFF4A32C8)
val Mint = Color(0xFF12C7A0)
val Danger = Color(0xFFE5484D)
val Warn = Color(0xFFE8A13A)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E0FF),
    onPrimaryContainer = Color(0xFF1E1052),
    secondary = Color(0xFF615B7A),
    tertiary = Mint,
    onTertiary = Color.White,
    background = Color(0xFFF7F5FF),
    onBackground = Color(0xFF16131F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16131F),
    surfaceVariant = Color(0xFFECE8F6),
    onSurfaceVariant = Color(0xFF494458),
    outlineVariant = Color(0xFFDAD4EA),
    error = Danger,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBCABFF),
    onPrimary = Color(0xFF26144F),
    primaryContainer = Color(0xFF42309A),
    onPrimaryContainer = Color(0xFFE7E0FF),
    secondary = Color(0xFFC9C2E6),
    tertiary = Color(0xFF53E4C0),
    onTertiary = Color(0xFF00382C),
    background = Color(0xFF121019),
    onBackground = Color(0xFFE8E4F3),
    surface = Color(0xFF1B1824),
    onSurface = Color(0xFFE8E4F3),
    surfaceVariant = Color(0xFF2A2636),
    onSurfaceVariant = Color(0xFFC9C2DC),
    outlineVariant = Color(0xFF393346),
    error = Color(0xFFFF9FA2),
)

@Composable
fun SsukTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
