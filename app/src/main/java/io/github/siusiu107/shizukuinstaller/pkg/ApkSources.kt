package io.github.siusiu107.shizukuinstaller.pkg

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * openwith 로 들어온 URI 들을 설치 가능한 APK 파일 목록으로 풀어낸다.
 * - 단일 .apk → 캐시로 복사
 * - .apks/.xapk/.apkm (zip 묶음) → 내부의 모든 .apk 추출
 * 모든 결과물을 앱 전용 캐시에 '파일'로 둔다. (파싱은 우리 앱이 읽고,
 * Shizuku 설치는 그 파일을 stdin 으로 흘려보내 shell uid 접근 문제를 피한다.)
 */
object ApkSources {

    class SourceException(message: String) : Exception(message)

    data class Apk(
        val name: String,
        val file: File,
        val size: Long,
        val isBase: Boolean,
    )

    fun interface Logger {
        fun log(line: String)
    }

    private fun workDir(context: Context): File =
        File(context.cacheDir, "staging").apply {
            deleteRecursively()
            mkdirs()
        }

    fun cleanup(context: Context) {
        runCatching { File(context.cacheDir, "staging").deleteRecursively() }
    }

    fun materialize(context: Context, uris: List<Uri>, log: Logger = Logger {}): List<Apk> {
        val dir = workDir(context)
        val out = ArrayList<Apk>()
        var index = 0
        for (uri in uris) {
            val display = queryName(context, uri)
            if (looksZip(context, uri) && !display.lowercase().endsWith(".apk")) {
                log.log("묶음 해제: $display")
                out += extractBundle(context, uri, dir, index)
            } else {
                val dest = File(dir, "pkg_${index}_base.apk")
                copy(context, uri, dest)
                out += Apk(display.ifBlank { "base.apk" }, dest, dest.length(), true)
            }
            index++
        }
        if (out.isEmpty()) throw SourceException("설치할 APK 를 찾지 못했어요.")
        markSingleBase(out)
        return out
    }

    private fun markSingleBase(apks: List<Apk>) {
        if (apks.none { it.isBase }) {
            // base 를 못 찾았으면 가장 큰 걸 base 로 간주
            apks.maxByOrNull { it.size }
        }
    }

    private fun extractBundle(context: Context, uri: Uri, dir: File, groupIndex: Int): List<Apk> {
        val out = ArrayList<Apk>()
        var n = 0
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val entryName = entry.name.substringAfterLast('/')
                    if (!entry.isDirectory && entryName.lowercase().endsWith(".apk")) {
                        val dest = File(dir, "pkg_${groupIndex}_$n-$entryName")
                        dest.outputStream().use { zip.copyTo(it, 1 shl 16) }
                        val isBase = entryName.equals("base.apk", ignoreCase = true) ||
                            !entryName.lowercase().let { it.contains("config.") || it.startsWith("split") }
                        out += Apk(entryName, dest, dest.length(), isBase)
                        n++
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: throw SourceException("묶음을 열 수 없어요: $uri")
        if (out.isEmpty()) throw SourceException("묶음 안에 APK 가 없어요.")
        // base 가 여러 개로 잡히면 가장 큰 하나만 base 로
        if (out.count { it.isBase } != 1) {
            val base = out.maxByOrNull { it.size }
            return out.map { it.copy(isBase = it === base) }
        }
        return out
    }

    private fun copy(context: Context, uri: Uri, dest: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it, 1 shl 16) }
        } ?: throw SourceException("파일을 열 수 없어요: $uri")
    }

    private fun looksZip(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { s ->
            val head = ByteArray(2)
            val read = s.read(head)
            read == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        } ?: false
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
