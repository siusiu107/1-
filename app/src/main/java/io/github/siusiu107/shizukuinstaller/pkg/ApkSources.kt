package io.github.siusiu107.shizukuinstaller.pkg

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 설치 소스(열린 URI, 선택한 파일, 내려받은 파일)를 설치 가능한 APK 파일 목록으로 풀어낸다.
 * - 단일 .apk → 캐시로 복사
 * - .apks/.xapk/.apkm (zip 묶음) → 내부의 모든 .apk 추출
 * 결과물은 전부 앱 전용 캐시에 '파일'로 둔다. (파싱은 우리 앱이 읽고,
 * Shizuku 설치는 그 파일을 stdin 으로 흘려보내 shell uid 접근 문제를 피한다.)
 */
object ApkSources {

    class SourceException(message: String) : Exception(message)

    data class Apk(val name: String, val file: File, val size: Long, val isBase: Boolean)

    fun interface Logger {
        fun log(line: String)
    }

    /** 이름 + 스트림 열기 로 추상화한 입력 소스. */
    private class Source(val name: String, val open: () -> InputStream)

    private fun workDir(context: Context): File =
        File(context.cacheDir, "staging").apply { deleteRecursively(); mkdirs() }

    fun cleanup(context: Context) {
        runCatching { File(context.cacheDir, "staging").deleteRecursively() }
        runCatching { File(context.cacheDir, "download").deleteRecursively() }
    }

    fun materialize(context: Context, uris: List<Uri>, log: Logger = Logger {}): List<Apk> {
        val sources = uris.map { uri ->
            Source(queryName(context, uri)) {
                context.contentResolver.openInputStream(uri) ?: throw SourceException("파일을 열 수 없어요.")
            }
        }
        return run(context, sources, log)
    }

    fun materializeFiles(context: Context, files: List<File>, log: Logger = Logger {}): List<Apk> {
        val sources = files.map { f -> Source(f.name) { f.inputStream() } }
        return run(context, sources, log)
    }

    private fun run(context: Context, sources: List<Source>, log: Logger): List<Apk> {
        val dir = workDir(context)
        val out = ArrayList<Apk>()
        sources.forEachIndexed { index, src ->
            if (looksZip(src) && !src.name.lowercase().endsWith(".apk")) {
                log.log("묶음 해제: ${src.name}")
                out += extractBundle(src, dir, index)
            } else {
                val dest = File(dir, "pkg_${index}_base.apk")
                copyWithProgress(src, dest, log)
                out += Apk(src.name.ifBlank { "base.apk" }, dest, dest.length(), true)
            }
        }
        if (out.isEmpty()) throw SourceException("설치할 APK 를 찾지 못했어요.")
        return out
    }

    private fun copyWithProgress(src: Source, dest: File, log: Logger) {
        src.open().use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var total = 0L
                var lastTick = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    if (total - lastTick > 4L * 1024 * 1024) {
                        lastTick = total
                        log.log("준비 중… ${total / (1024 * 1024)} MB")
                    }
                }
            }
        }
    }

    private fun extractBundle(src: Source, dir: File, groupIndex: Int): List<Apk> {
        val out = ArrayList<Apk>()
        var n = 0
        ZipInputStream(BufferedInputStream(src.open())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val entryName = entry.name.substringAfterLast('/')
                if (!entry.isDirectory && entryName.lowercase().endsWith(".apk")) {
                    val dest = File(dir, "pkg_${groupIndex}_$n-$entryName")
                    dest.outputStream().use { zip.copyTo(it, 1 shl 20) }
                    val lower = entryName.lowercase()
                    val isBase = lower == "base.apk" || !(lower.contains("config.") || lower.startsWith("split"))
                    out += Apk(entryName, dest, dest.length(), isBase)
                    n++
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (out.isEmpty()) throw SourceException("묶음 안에 APK 가 없어요.")
        if (out.count { it.isBase } != 1) {
            val base = out.maxByOrNull { it.size }
            return out.map { it.copy(isBase = it === base) }
        }
        return out
    }

    private fun looksZip(src: Source): Boolean = try {
        src.open().use { s ->
            val head = ByteArray(2)
            s.read(head) == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        }
    } catch (_: IOException) {
        false
    }

    fun queryName(context: Context, uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "base.apk"
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "base.apk"
    }
}
