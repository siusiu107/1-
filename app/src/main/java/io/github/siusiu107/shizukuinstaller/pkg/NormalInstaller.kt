package io.github.siusiu107.shizukuinstaller.pkg

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 시스템 PackageInstaller 로 설치하는 일반 경로 (Shizuku 가 꺼져 있을 때 폴백).
 * 안드로이드가 자체 설치 확인 화면을 띄우고, 사용자가 수락해야 설치된다.
 */
object NormalInstaller {

    sealed interface Result {
        data object Success : Result
        data class Failure(val message: String, val recoverable: Boolean) : Result
    }

    private const val ACTION = "io.github.siusiu107.shizukuinstaller.NORMAL_INSTALL_RESULT"

    suspend fun install(
        context: Context,
        apks: List<ApkSources.Apk>,
        log: ApkSources.Logger,
    ): Result = suspendCancellableCoroutine { cont ->
        val app = context.applicationContext
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)

        val sessionId = try {
            installer.createSession(params)
        } catch (t: Throwable) {
            cont.resume(Result.Failure("설치 세션을 만들지 못했어요: ${t.message}", recoverable = false))
            return@suspendCancellableCoroutine
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirm = @Suppress("DEPRECATION")
                        (intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
                        if (confirm != null) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { app.startActivity(confirm) }
                        }
                        // 최종 결과 브로드캐스트를 계속 기다림 (등록 유지)
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        runCatching { app.unregisterReceiver(this) }
                        if (cont.isActive) cont.resume(Result.Success)
                    }
                    else -> {
                        runCatching { app.unregisterReceiver(this) }
                        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                        if (cont.isActive) cont.resume(Result.Failure(translate(status, msg), recoverable = true))
                    }
                }
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)

        cont.invokeOnCancellation {
            runCatching { app.unregisterReceiver(receiver) }
            runCatching { installer.abandonSession(sessionId) }
        }

        try {
            installer.openSession(sessionId).use { session ->
                apks.forEachIndexed { i, apk ->
                    log.log("${apk.name} 쓰는 중…")
                    session.openWrite("split$i", 0, apk.size).use { out ->
                        apk.file.inputStream().use { it.copyTo(out, 1 shl 16) }
                        session.fsync(out)
                    }
                }
                val flags = if (android.os.Build.VERSION.SDK_INT >= 31)
                    PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(
                    app, sessionId,
                    Intent(ACTION).setPackage(app.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or flags,
                )
                log.log("설치 확인을 기다리는 중…")
                session.commit(pi.intentSender)
            }
        } catch (t: Throwable) {
            runCatching { app.unregisterReceiver(receiver) }
            runCatching { installer.abandonSession(sessionId) }
            if (cont.isActive) cont.resume(Result.Failure("설치에 실패했어요: ${t.message}", recoverable = true))
        }
    }

    private fun translate(status: Int, msg: String): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "설치가 취소됐어요."
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "기기가 설치를 막았어요. (보안 설정 또는 Play Protect)"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "이미 설치된 앱과 충돌해요. (서명 불일치일 수 있어요)"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "이 기기와 호환되지 않아요. (구버전 앱이거나 ABI 불일치)"
        PackageInstaller.STATUS_FAILURE_INVALID -> "APK 파일이 올바르지 않아요."
        PackageInstaller.STATUS_FAILURE_STORAGE -> "저장 공간이 부족해요."
        else -> if (msg.isNotBlank()) "설치에 실패했어요: $msg" else "설치에 실패했어요."
    }
}
