package io.github.siusiu107.shizukuinstaller

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Shizuku 셸을 통해 `pm install-create / install-write / install-commit` 세션으로 APK 를 설치한다.
 * APK 바이트는 stdin 파이프로 흘려보내므로 앱 전용 파일을 셸(uid 2000)이 못 읽는 문제를 피한다.
 */
object Installer {

    data class Apk(val name: String, val size: Long, val open: () -> InputStream)

    fun interface Logger {
        fun log(line: String)
    }

    private val NAME = Regex("""Success: created install session \[(\d+)]""")

    class InstallException(message: String) : Exception(message)

    /**
     * [uris] 각각을 열어 하나의 설치 세션에 밀어넣는다.
     * - APK 파일 하나 → 단일 설치
     * - .apks/.xapk/.apkm(zip 묶음) → 내부의 모든 .apk 를 분할 설치
     * - 여러 URI → 전부 하나의 세션으로 분할 설치
     */
    fun install(context: Context, uris: List<Uri>, extraArgs: List<String>, log: Logger) {
        val apks = ArrayList<Apk>()
        for (uri in uris) apks += expand(context, uri, log)
        if (apks.isEmpty()) throw InstallException("설치할 APK 를 찾지 못했습니다.")

        val createArgs = ArrayList<String>().apply {
            add("pm"); add("install-create")
            add("-r"); add("-g")   // 재설치 허용 + 런타임 권한 자동 부여
            addAll(extraArgs)
        }
        log.log("$ ${createArgs.joinToString(" ")}")
        val created = Shell.run(*createArgs.toTypedArray())
        if (!created.ok) throw InstallException("세션 생성 실패 (code ${created.code}):\n${created.output}")
        val sessionId = NAME.find(created.output)?.groupValues?.get(1)
            ?: throw InstallException("세션 ID 를 읽지 못했습니다:\n${created.output}")
        log.log("세션 $sessionId 생성됨, APK ${apks.size}개")

        try {
            apks.forEachIndexed { index, apk ->
                val split = "split_$index"
                log.log("쓰는 중: ${apk.name} (${human(apk.size)})")
                val pipe = ParcelFileDescriptor.createPipe()
                val read = pipe[0]
                val write = pipe[1]
                val writer = Thread {
                    try {
                        ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                            apk.open().use { it.copyTo(out, 1 shl 16) }
                        }
                    } catch (_: IOException) {
                    }
                }
                writer.start()
                val size = if (apk.size > 0) apk.size.toString() else "-1"
                val res = Shell.run(
                    "pm", "install-write", "-S", size, sessionId, split, "-",
                    stdin = read,
                )
                writer.join()
                if (!res.ok) {
                    abandon(sessionId)
                    throw InstallException("APK 쓰기 실패 (${apk.name}, code ${res.code}):\n${res.output}")
                }
            }

            log.log("커밋 중...")
            val commit = Shell.run("pm", "install-commit", sessionId)
            if (!commit.ok) {
                abandon(sessionId)
                throw InstallException("설치 실패 (code ${commit.code}):\n${commit.output}")
            }
            log.log("완료: ${commit.output.ifBlank { "Success" }}")
        } catch (e: InstallException) {
            throw e
        } catch (t: Throwable) {
            abandon(sessionId)
            throw InstallException(t.toString())
        }
    }

    private fun abandon(sessionId: String) {
        try {
            Shell.run("pm", "install-abandon", sessionId)
        } catch (_: Throwable) {
        }
    }

    /** URI 를 APK 목록으로 전개한다. zip 묶음이면 내부 apk 들을 임시 파일로 풀어서 반환. */
    private fun expand(context: Context, uri: Uri, log: Logger): List<Apk> {
        val display = queryName(context, uri)
        val lower = display.lowercase()
        val resolver = context.contentResolver

        if (lower.endsWith(".apk")) {
            val size = querySize(context, uri)
            return listOf(Apk(display, size) {
                resolver.openInputStream(uri) ?: throw IOException("열 수 없음: $uri")
            })
        }

        // 묶음일 수 있으니 zip 으로 열어 본다
        val head = resolver.openInputStream(uri)?.use { it.readNBytes(4) } ?: ByteArray(0)
        val isZip = head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        if (!isZip) {
            // zip 아님 → 확장자 없는 단일 apk 로 간주
            val size = querySize(context, uri)
            return listOf(Apk(display.ifBlank { "base.apk" }, size) {
                resolver.openInputStream(uri) ?: throw IOException("열 수 없음: $uri")
            })
        }

        log.log("묶음 해제: $display")
        val out = ArrayList<Apk>()
        val cacheDir = java.io.File(context.cacheDir, "apks").apply { mkdirs(); deleteRecursively(); mkdirs() }
        resolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.lowercase().endsWith(".apk")) {
                        val file = java.io.File(cacheDir, entry.name.substringAfterLast('/'))
                        file.outputStream().use { zip.copyTo(it, 1 shl 16) }
                        out += Apk(file.name, file.length()) { file.inputStream() }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        if (out.isEmpty()) throw InstallException("묶음 안에서 APK 를 찾지 못했습니다: $display")
        return out
    }

    private fun queryName(context: Context, uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "base.apk"
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            } ?: uri.lastPathSegment ?: "base.apk"
        } catch (_: Throwable) {
            uri.lastPathSegment ?: "base.apk"
        }
    }

    private fun querySize(context: Context, uri: Uri): Long {
        if (uri.scheme == "file") return java.io.File(uri.path ?: "").length()
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getLong(idx) else -1L
            } ?: -1L
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun human(bytes: Long): String {
        if (bytes < 0) return "?"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        return "%.1f MB".format(mb)
    }
}
