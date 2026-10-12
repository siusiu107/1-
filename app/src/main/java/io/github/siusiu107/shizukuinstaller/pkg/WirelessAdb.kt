package io.github.siusiu107.shizukuinstaller.pkg

import android.content.Context
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 앱이 직접 ADB 클라이언트가 되어 기기의 무선 디버깅 데몬과 페어링/연결하고,
 * 그 셸로 `pm install` 을 돌린다. (Shizuku·PC 불필요)
 */
object WirelessAdb {

    class AdbException(message: String) : Exception(message)

    fun connected(context: Context): Boolean =
        runCatching { AdbManager.get(context).isConnected }.getOrDefault(false)

    suspend fun pair(context: Context, port: Int, code: String): Boolean = withContext(Dispatchers.IO) {
        try {
            AdbManager.get(context).pair(port, code)
        } catch (t: Throwable) {
            throw AdbException(pairError(t))
        }
    }

    suspend fun connect(context: Context, port: Int): Boolean = withContext(Dispatchers.IO) {
        val m = AdbManager.get(context)
        m.setTimeout(30, TimeUnit.SECONDS)
        try {
            if (m.isConnected) true else m.connect(port)
        } catch (t: Throwable) {
            throw AdbException(connectError(t))
        }
    }

    suspend fun autoConnect(context: Context, timeoutMs: Long = 5000): Boolean = withContext(Dispatchers.IO) {
        val m = AdbManager.get(context)
        m.setTimeout(30, TimeUnit.SECONDS)
        try {
            if (m.isConnected) true else m.autoConnect(context, timeoutMs)
        } catch (t: Throwable) {
            false
        }
    }

    suspend fun disconnect(context: Context) = withContext(Dispatchers.IO) {
        runCatching { AdbManager.get(context).disconnect() }
        Unit
    }

    /** mDNS 로 무선 페어링 포트를 탐지(선입력용, best-effort). 못 찾으면 -1. */
    suspend fun findPairingPort(context: Context, timeoutMs: Long = 4000): Int = withContext(Dispatchers.IO) {
        findMdnsPort(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING, timeoutMs)
    }

    private fun findMdnsPort(context: Context, type: String, timeoutMs: Long): Int {
        var found = -1
        val latch = java.util.concurrent.CountDownLatch(1)
        val mdns = AdbMdns(context, type) { _, port ->
            if (port > 0) { found = port; latch.countDown() }
        }
        mdns.start()
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            runCatching { mdns.stop() }
        }
        return found
    }

    fun install(
        context: Context,
        apks: List<ApkSources.Apk>,
        grantPermissions: Boolean,
        oldSdkCompat: Boolean,
        allowDowngrade: Boolean,
        log: ApkSources.Logger,
    ) {
        val m = AdbManager.get(context)
        if (!m.isConnected) throw AdbException("무선 연결이 끊겼어요. 다시 연결해 주세요.")
        val flags = buildList {
            add("-r")
            if (grantPermissions) add("-g")
            if (allowDowngrade) add("-d")
            if (oldSdkCompat) add("--bypass-low-target-sdk-block")
        }.joinToString(" ")

        if (apks.size == 1) {
            log.log("설치 중…")
            val apk = apks[0]
            val out = exec(m, "exec:pm install $flags -S ${apk.size} -", apk.file)
            if (!out.contains("Success")) throw AdbException(friendly(out))
            return
        }

        val create = exec(m, "exec:pm install-create $flags", null)
        val sid = Regex("""\[(\d+)]""").find(create)?.groupValues?.get(1)
            ?: throw AdbException("설치 세션을 만들지 못했어요.\n$create")
        try {
            apks.forEachIndexed { i, apk ->
                log.log("${apk.name} 쓰는 중…")
                val w = exec(m, "exec:pm install-write -S ${apk.size} $sid split$i -", apk.file)
                if (!w.contains("Success")) throw AdbException(friendly(w))
            }
            log.log("마무리 중…")
            val commit = exec(m, "exec:pm install-commit $sid", null)
            if (!commit.contains("Success")) throw AdbException(friendly(commit))
        } catch (e: Throwable) {
            runCatching { exec(m, "exec:pm install-abandon $sid", null) }
            if (e is AdbException) throw e else throw AdbException(e.message ?: "$e")
        }
    }

    /** exec 스트림 실행: stdin 파일이 있으면 그 바이트를 흘려보내고, 표준출력을 모아 돌려준다. */
    private fun exec(m: AdbManager, destination: String, stdin: File?): String {
        val stream = m.openStream(destination)
        val sb = StringBuilder()
        val reader = Thread {
            try {
                val input = stream.openInputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sb.append(String(buf, 0, n))
                }
            } catch (_: Throwable) {
            }
        }.apply { start() }
        try {
            if (stdin != null) {
                val out = stream.openOutputStream()
                stdin.inputStream().use { it.copyTo(out, 1 shl 20) }
                out.flush()
            }
            reader.join(180_000)
        } finally {
            runCatching { stream.close() }
        }
        return sb.toString().trim()
    }

    private fun friendly(out: String): String {
        val o = out.uppercase()
        return when {
            "SIGNATURE" in o || "UPDATE_INCOMPATIBLE" in o ->
                "서명이 기존 앱과 달라요. 먼저 기존 앱을 삭제해야 설치돼요 (데이터가 지워져요)."
            "OLDER_SDK" in o -> "구버전 앱이라 호환 설치가 필요해요. 다시 시도해 주세요."
            "VERSION_DOWNGRADE" in o -> "설치된 버전보다 낮아요. '낮은 버전 허용'으로 다시 시도해 보세요."
            "INSUFFICIENT_STORAGE" in o -> "저장 공간이 부족해요."
            out.isBlank() -> "설치에 실패했어요."
            else -> "설치에 실패했어요.\n${out.take(400)}"
        }
    }

    private fun pairError(t: Throwable): String =
        "페어링에 실패했어요. 코드와 포트를 다시 확인해 주세요. (${t.javaClass.simpleName})"

    private fun connectError(t: Throwable): String =
        "연결에 실패했어요. 무선 디버깅이 켜져 있는지, 연결 포트가 맞는지 확인해 주세요. (${t.javaClass.simpleName})"
}
