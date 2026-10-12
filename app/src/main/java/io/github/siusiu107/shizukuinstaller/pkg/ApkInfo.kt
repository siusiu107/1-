package io.github.siusiu107.shizukuinstaller.pkg

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import java.io.File

/** base APK 를 파싱해 설치 전 보여줄 정보 (아카이브만으로 얻을 수 있는 값 위주, 전부 best-effort). */
data class ApkInfo(
    val label: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val targetSdk: Int,
    val minSdk: Int,
    val icon: Drawable?,
    val totalSize: Long,
    val splitCount: Int,
    val needsOldSdkCompat: Boolean,
    val installedVersionCode: Long?, // 보이면 설치된 버전, 안 보이면 null
) {
    val isUpdate: Boolean get() = installedVersionCode != null && versionCode >= installedVersionCode
    val isDowngrade: Boolean get() = installedVersionCode != null && versionCode < installedVersionCode

    companion object {
        /** 이 안드로이드 버전이 거부하는 최소 targetSdk. (A14=23, A15+=24, 그 이하=0) */
        fun minTargetSdkRequired(): Int = when {
            Build.VERSION.SDK_INT >= 35 -> 24
            Build.VERSION.SDK_INT >= 34 -> 23
            else -> 0
        }

        fun parse(context: Context, base: File, totalSize: Long, splitCount: Int): ApkInfo {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val pi = pm.getPackageArchiveInfo(base.absolutePath, 0)
            val appInfo = pi?.applicationInfo
            if (appInfo != null) {
                appInfo.sourceDir = base.absolutePath
                appInfo.publicSourceDir = base.absolutePath
            }
            val label = appInfo?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
                ?.takeIf { it.isNotBlank() } ?: (pi?.packageName ?: base.name)
            val icon = appInfo?.let { runCatching { it.loadIcon(pm) }.getOrNull() }
            val target = appInfo?.targetSdkVersion ?: 0
            val min = if (Build.VERSION.SDK_INT >= 24) appInfo?.minSdkVersion ?: 0 else 0
            val vcode = if (pi != null) {
                if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
            } else 0L

            val pkg = pi?.packageName ?: "?"
            val installed: Long? = runCatching {
                val installedPi = pm.getPackageInfo(pkg, 0)
                if (Build.VERSION.SDK_INT >= 28) installedPi.longVersionCode
                else @Suppress("DEPRECATION") installedPi.versionCode.toLong()
            }.getOrNull()

            val threshold = minTargetSdkRequired()
            val needsCompat = threshold > 0 && target in 1 until threshold

            return ApkInfo(
                label = label,
                packageName = pkg,
                versionName = pi?.versionName ?: "?",
                versionCode = vcode,
                targetSdk = target,
                minSdk = min,
                icon = icon,
                totalSize = totalSize,
                splitCount = splitCount,
                needsOldSdkCompat = needsCompat,
                installedVersionCode = installed,
            )
        }
    }
}
