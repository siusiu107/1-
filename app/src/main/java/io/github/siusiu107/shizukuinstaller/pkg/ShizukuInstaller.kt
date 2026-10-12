package io.github.siusiu107.shizukuinstaller.pkg

import android.os.ParcelFileDescriptor
import io.github.siusiu107.shizukuinstaller.Shell
import java.io.IOException

/**
 * Shizuku(adb/root) 셸로 `pm install` 세션을 돌려 설치한다.
 * APK 바이트는 stdin 파이프로 흘려보내므로 셸(uid 2000)이 앱 전용 캐시를 못 읽는 문제를 피한다.
 */
object ShizukuInstaller {

    class InstallException(message: String) : Exception(message)

    private val SESSION = Regex("""\[(\d+)]""")

    /**
     * @param grantPermissions -g (런타임 권한 자동 부여)
     * @param oldSdkCompat --bypass-low-target-sdk-block (구버전 앱 호환 설치)
     * @param allowDowngrade -d
     */
    fun install(
        apks: List<ApkSources.Apk>,
        grantPermissions: Boolean,
        oldSdkCompat: Boolean,
        allowDowngrade: Boolean,
        log: ApkSources.Logger,
    ) {
        if (apks.isEmpty()) throw InstallException("설치할 APK 가 없어요.")

        val createArgs = ArrayList<String>().apply {
            add("pm"); add("install-create"); add("-r")
            if (grantPermissions) add("-g")
            if (allowDowngrade) add("-d")
            if (oldSdkCompat) add("--bypass-low-target-sdk-block")
        }
        val created = Shell.run(*createArgs.toTypedArray())
        if (!created.ok) throw InstallException(friendly(created.output, created.code))
        val sessionId = SESSION.find(created.output)?.groupValues?.get(1)
            ?: throw InstallException("설치 세션을 만들지 못했어요.\n${created.output}")

        try {
            apks.forEachIndexed { i, apk ->
                log.log("${apk.name} 쓰는 중…")
                val pipe = ParcelFileDescriptor.createPipe()
                val read = pipe[0]
                val write = pipe[1]
                val writer = Thread {
                    try {
                        ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                            apk.file.inputStream().use { it.copyTo(out, 1 shl 16) }
                        }
                    } catch (_: IOException) {
                    }
                }
                writer.start()
                val res = Shell.run(
                    "pm", "install-write", "-S", apk.size.toString(),
                    sessionId, "split$i", "-",
                    stdin = read,
                )
                writer.join()
                if (!res.ok) {
                    abandon(sessionId)
                    throw InstallException(friendly(res.output, res.code))
                }
            }

            log.log("마무리 중…")
            val commit = Shell.run("pm", "install-commit", sessionId)
            if (!commit.ok) {
                abandon(sessionId)
                throw InstallException(friendly(commit.output, commit.code))
            }
        } catch (e: InstallException) {
            throw e
        } catch (t: Throwable) {
            abandon(sessionId)
            throw InstallException(t.message ?: t.toString())
        }
    }

    private fun abandon(id: String) {
        runCatching { Shell.run("pm", "install-abandon", id) }
    }

    /** pm 의 영어 에러를 한국어로 간단 번역. */
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
            else -> "설치에 실패했어요.\n${output.take(400)}"
        }
    }
}
