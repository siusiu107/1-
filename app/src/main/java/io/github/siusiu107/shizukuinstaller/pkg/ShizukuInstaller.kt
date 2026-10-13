package io.github.siusiu107.shizukuinstaller.pkg

import android.os.ParcelFileDescriptor
import io.github.siusiu107.shizukuinstaller.Shell
import java.io.IOException

/**
 * Shizuku(adb/root) 셸로 `pm install` 세션을 돌려 설치한다.
 * APK 바이트는 stdin 파이프로 흘려보내므로 셸(uid 2000)이 앱 전용 캐시를 못 읽는 문제를 피한다.
 *
 * - 단일 APK: `pm install -S` 한 번으로 스트리밍 (세션 3단계 생략, 더 빠름)
 * - 여러 APK(분할): install-create / install-write / install-commit 세션
 */
object ShizukuInstaller {

    class InstallException(message: String) : Exception(message)

    private val SESSION = Regex("""\[(\d+)]""")

    fun install(
        apks: List<ApkSources.Apk>,
        grantPermissions: Boolean,
        oldSdkCompat: Boolean,
        allowDowngrade: Boolean,
        log: ApkSources.Logger,
    ) {
        if (apks.isEmpty()) throw InstallException("설치할 APK 가 없어요.")

        val flags = buildList {
            add("-r")
            if (grantPermissions) add("-g")
            if (allowDowngrade) add("-d")
            if (oldSdkCompat) add("--bypass-low-target-sdk-block")
        }

        if (apks.size == 1) installSingle(apks[0], flags, log)
        else installSession(apks, flags, log)
    }

    private fun installSingle(apk: ApkSources.Apk, flags: List<String>, log: ApkSources.Logger) {
        log.log("설치 중…")
        val pipe = ParcelFileDescriptor.createPipe()
        val writer = streamFile(apk, pipe[1])
        // 경로 인자를 빼야 표준입력에서 읽는다. 끝에 '-' 를 붙이면 일부 기기의 pm 이
        // 옵션으로 오인해 "Unknown option -" 으로 거부한다.
        val args = buildList {
            add("pm"); add("install"); addAll(flags); add("-S"); add(apk.size.toString())
        }
        val res = Shell.run(*args.toTypedArray(), stdin = pipe[0])
        writer.join()
        if (res.ok) return
        // 옵션 해석이 다른 기기면 세션 방식으로 한 번 더 시도
        if (res.output.contains("Unknown option") || res.output.contains("IllegalArgumentException")) {
            log.log("다른 방식으로 다시 설치하는 중…")
            installSession(listOf(apk), flags, log)
            return
        }
        throw InstallException(friendly(res.output, res.code))
    }

    private fun installSession(apks: List<ApkSources.Apk>, flags: List<String>, log: ApkSources.Logger) {
        val createArgs = buildList { add("pm"); add("install-create"); addAll(flags) }
        val created = Shell.run(*createArgs.toTypedArray())
        if (!created.ok) throw InstallException(friendly(created.output, created.code))
        val sessionId = SESSION.find(created.output)?.groupValues?.get(1)
            ?: throw InstallException("설치 세션을 만들지 못했어요.\n${created.output}")

        try {
            apks.forEachIndexed { i, apk ->
                log.log("${apk.name} 쓰는 중…")
                val pipe = ParcelFileDescriptor.createPipe()
                val writer = streamFile(apk, pipe[1])
                val res = Shell.run(
                    "pm", "install-write", "-S", apk.size.toString(), sessionId, "split$i", "-",
                    stdin = pipe[0],
                )
                writer.join()
                if (!res.ok) { abandon(sessionId); throw InstallException(friendly(res.output, res.code)) }
            }
            log.log("마무리 중…")
            val commit = Shell.run("pm", "install-commit", sessionId)
            if (!commit.ok) { abandon(sessionId); throw InstallException(friendly(commit.output, commit.code)) }
        } catch (e: InstallException) {
            throw e
        } catch (t: Throwable) {
            abandon(sessionId); throw InstallException(t.message ?: t.toString())
        }
    }

    private fun streamFile(apk: ApkSources.Apk, write: ParcelFileDescriptor): Thread =
        Thread {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                    apk.file.inputStream().use { it.copyTo(out, 1 shl 20) }
                }
            } catch (_: IOException) {
            }
        }.apply { start() }

    private fun abandon(id: String) {
        runCatching { Shell.run("pm", "install-abandon", id) }
    }

    private fun friendly(output: String, code: Int): String {
        val o = output.uppercase()
        return when {
            "SIGNATURE" in o || "UPDATE_INCOMPATIBLE" in o ->
                "서명이 기존에 설치된 앱과 달라요. 먼저 기존 앱을 삭제해야 설치돼요 (데이터가 지워져요)."
            "OLDER_SDK" in o || "TARGET" in o ->
                "구버전 앱이라 호환 설치가 필요해요. 다시 시도하면 자동으로 호환 설치를 적용해요."
            "VERSION_DOWNGRADE" in o ->
                "설치된 버전보다 낮아요. '낮은 버전 허용'으로 다시 시도해 보세요."
            "INSUFFICIENT_STORAGE" in o -> "저장 공간이 부족해요."
            "INVALID_APK" in o || "NO_CERTIFICATES" in o -> "APK 파일이 손상됐거나 올바르지 않아요."
            output.isBlank() -> "설치에 실패했어요 (코드 $code)."
            else -> "설치에 실패했어요.\n${InstallErrors.summarize(output)}"
        }
    }
}
