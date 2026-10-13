package io.github.siusiu107.shizukuinstaller.pkg

import android.content.Context
import io.github.siusiu107.shizukuinstaller.Shell

/** 권한 설치 경로 통합: Shizuku 가 준비됐으면 Shizuku, 아니면 무선 ADB 연결을 사용. */
object PrivilegedInstaller {

    fun backend(context: Context): String? = when {
        Shell.state() == Shell.State.READY -> "shizuku"
        WirelessAdb.connected(context) -> "adb"
        else -> null
    }

    fun available(context: Context): Boolean = backend(context) != null

    fun label(context: Context): String = when (backend(context)) {
        "shizuku" -> "Shizuku"
        "adb" -> "무선 ADB"
        else -> "빠른 설치"
    }

    suspend fun install(
        context: Context,
        apks: List<ApkSources.Apk>,
        grantPermissions: Boolean,
        oldSdkCompat: Boolean,
        allowDowngrade: Boolean,
        log: ApkSources.Logger,
    ) {
        var b = backend(context)
        // 무선 연결이 끊겨 있으면(화면 꺼짐·Wi-Fi 재접속 등) 설치 전에 한 번 다시 붙어 본다
        if (b == null && Prefs(context).paired) {
            log.log("무선 디버깅에 다시 연결하는 중…")
            if (WirelessAdb.autoConnect(context)) b = backend(context)
        }
        when (b) {
            "shizuku" -> ShizukuInstaller.install(apks, grantPermissions, oldSdkCompat, allowDowngrade, log)
            "adb" -> WirelessAdb.install(context, apks, grantPermissions, oldSdkCompat, allowDowngrade, log)
            else -> throw IllegalStateException(
                "Shizuku 도 무선 연결도 준비되지 않았어요. 무선 디버깅이 켜져 있는지 확인하거나 일반 설치를 써 주세요.",
            )
        }
    }
}
