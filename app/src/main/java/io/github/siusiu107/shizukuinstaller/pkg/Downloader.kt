package io.github.siusiu107.shizukuinstaller.pkg

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 직접 링크(URL)에서 APK/묶음 파일을 내려받는다. (사용자가 입력한 주소) */
object Downloader {

    class DownloadException(message: String) : Exception(message)

    suspend fun download(context: Context, rawUrl: String, onProgress: (String) -> Unit): File =
        withContext(Dispatchers.IO) {
            val url = normalize(rawUrl)
            val dir = File(context.cacheDir, "download").apply { mkdirs() }
            val dest = File(dir, "download_${System.currentTimeMillis()}.bin")

            var conn: HttpURLConnection? = null
            try {
                var current = URL(url)
                var redirects = 0
                while (true) {
                    conn = (current.openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 20000
                        readTimeout = 30000
                        setRequestProperty("User-Agent", "SsukInstall")
                        setRequestProperty("Accept", "*/*")
                    }
                    val code = conn!!.responseCode
                    if (code in 300..399) {
                        val loc = conn!!.getHeaderField("Location") ?: throw DownloadException("잘못된 리디렉션이에요.")
                        conn!!.disconnect()
                        if (++redirects > 5) throw DownloadException("리디렉션이 너무 많아요.")
                        current = URL(current, loc)
                        continue
                    }
                    if (code !in 200..299) throw DownloadException("다운로드 실패 (HTTP $code)")
                    break
                }

                val total = conn!!.contentLengthLong
                onProgress("내려받는 중… ${if (total > 0) human(total) else ""}")
                conn!!.inputStream.use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var got = 0L
                        var lastTick = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            if (got - lastTick > 512 * 1024) {
                                lastTick = got
                                onProgress(
                                    if (total > 0) "내려받는 중… ${human(got)} / ${human(total)}"
                                    else "내려받는 중… ${human(got)}",
                                )
                            }
                        }
                    }
                }
                if (dest.length() == 0L) throw DownloadException("빈 파일이에요.")
                dest
            } catch (e: DownloadException) {
                dest.delete(); throw e
            } catch (t: Throwable) {
                dest.delete(); throw DownloadException("다운로드 실패: ${t.message}")
            } finally {
                conn?.disconnect()
            }
        }

    private fun normalize(raw: String): String {
        val t = raw.trim()
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    }

    private fun human(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.0f KB".format(kb)
        return "%.1f MB".format(kb / 1024.0)
    }
}
