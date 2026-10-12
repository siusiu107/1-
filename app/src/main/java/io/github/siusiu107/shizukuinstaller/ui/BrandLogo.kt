package io.github.siusiu107.shizukuinstaller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.siusiu107.shizukuinstaller.ui.theme.Brand
import io.github.siusiu107.shizukuinstaller.ui.theme.BrandAlt

@Composable
fun BrandLogo(size: Dp = 64.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(percent = 30))
            .background(Brush.linearGradient(listOf(Brand, BrandAlt))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.InstallMobile,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.52f),
        )
    }
}
