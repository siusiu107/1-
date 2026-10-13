package io.github.siusiu107.shizukuinstaller.pkg

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 앱이 직접 ADB 클라이언트가 되어 기기의 무선 디버깅 데몬과 페어링/연결하고,
 * 그 셸로 `pm install` 을 돌린다. (Shizuku·PC 불필요)
 */
object WirelessAdb {

    class AdbException(message: String) : Exception(message)

    /** 같은 기기의 adbd 는 모든 인터페이스에 바인딩되므로 항상 루프백으로 접속한다. */
    private const val LOCALHOST = "127.0.0.1"

    /**
     * 현재 살아 있는 연결. libadb 의 isConnected() 는 매니저 락(mLock)을 잡는데, autoConnect/pair 가
     * 그 락을 몇 초씩 쥐고 있어서 메인 스레드에서 부르면 ANR 이 난다. 그래서 락 없이 읽을 수 있게 따로 들고 있는다.
     */
    @Volatile
    private var live: AdbConnection? = null

    private val reconnecting = AtomicBoolean(false)

    /** 락을 잡지 않으므로 메인 스레드에서 불러도 안전하다. */
    @Suppress("UNUSED_PARAMETER")
    fun connected(context: Context): Boolean =
        live?.let { it.isConnected && it.isConnectionEstablished } == true

    private fun track(m: AdbManager, ok: Boolean): Boolean {
        if (ok || m.isConnected) live = m.adbConnection
        return connectedNow()
    }

    private fun connectedNow(): Boolean = live?.let { it.isConnected && it.isConnectionEstablished } == true

    suspend fun pair(context: Context, port: Int, code: String): Boolean = withContext(Dispatchers.IO) {
        val ok = try {
            AdbManager.get(context).pair(LOCALHOST, port, code)
        } catch (t: Throwable) {
            throw AdbException(pairError(t))
        }
        if (ok) Prefs(context).paired = true
        ok
    }

    suspend fun connect(context: Context, port: Int): Boolean = withContext(Dispatchers.IO) {
        val m = AdbManager.get(context)
        m.setTimeout(30, TimeUnit.SECONDS)
        try {
            track(m, if (m.isConnected) true else m.connect(LOCALHOST, port))
        } catch (t: Throwable) {
            throw AdbException(connectError(t))
        }
    }

    suspend fun autoConnect(context: Context, timeoutMs: Long = 5000): Boolean = withContext(Dispatchers.IO) {
        if (connectedNow()) return@withContext true
        val m = AdbManager.get(context)
        m.setTimeout(30, TimeUnit.SECONDS)
        try {
            track(m, if (m.isConnected) true else m.autoConnect(context, timeoutMs))
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 화면 진입 시 조용히 재연결. 페어링한 적이 있고(Android 11+) 아직 연결 안 됐을 때만,
     * 그리고 동시에 하나만 돈다. (매 onResume 마다 5초짜리 mDNS 탐색이 쌓이지 않게)
     */
    suspend fun reconnectInBackground(context: Context): Boolean {
        if (connectedNow()) return true
        if (Build.VERSION.SDK_INT < 30 || !Prefs(context).paired) return false
        if (!reconnecting.compareAndSet(false, true)) return false
        return try {
            autoConnect(context)
        } finally {
            reconnecting.set(false)
        }
    }

    suspend fun disconnect(context: Context) = withContext(Dispatchers.IO) {
        live = null
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
        if (!connectedNow()) throw AdbException("무선 연결이 끊겼어요. 다시 연결해 주세요.")
        val m = AdbManager.get(context)
        val flags = buildList {
            add("-r")
            if (grantPermissions) add("-g")
            if (allowDowngrade) add("-d")
            if (oldSdkCompat) add("--bypass-low-target-sdk-block")
        }.joinToString(" ")

        if (apks.size == 1) {
            log.log("설치 중…")
            val apk = apks[0]
            // 경로 인자를 빼야 표준입력에서 읽는다. 끝에 '-' 를 붙이면 일부 기기의 pm 이 옵션으로 오인해
            // "Unknown option -" 으로 거부한다.
            val out = exec(m, "exec:pm install $flags -S ${apk.size}", apk.file)
            if (out.contains("Success")) return
            // 그래도 옵션 해석이 다른 기기면 세션 방식으로 한 번 더
            if (!isOptionError(out)) throw AdbException(friendly(out))
            log.log("다른 방식으로 다시 설치하는 중…")
        }
        installSession(m, apks, flags, log)
    }

    private fun installSession(m: AdbManager, apks: List<ApkSources.Apk>, flags: String, log: ApkSources.Logger) {
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

    /**
     * exec 스트림 실행: stdin 파일이 있으면 그 바이트를 흘려보내고, 표준출력을 모아 돌려준다.
     *
     * - 한 번의 write 가 정확히 WRTE 패킷 하나가 되도록 maxData 이하로 잘라 보낸다.
     *   (libadb 는 큰 write 를 OKAY 를 기다리지 않고 여러 패킷으로 연달아 보내는데,
     *   adbd 는 그걸 흐름 제어 위반으로 보고 연결을 끊는다 → "Stream closed")
     * - 원격(pm)이 먼저 끝나거나 에러로 스트림을 닫아도 예외로 끝내지 않고,
     *   pm 이 실제로 출력한 결과를 끝까지 읽어서 돌려준다.
     */
    private fun exec(m: AdbManager, destination: String, stdin: File?): String {
        val stream = m.openStream(destination)
        val output = java.io.ByteArrayOutputStream()
        val reader = Thread {
            try {
                val input = stream.openInputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    synchronized(output) { output.write(buf, 0, n) }
                }
            } catch (_: Throwable) {
                // 스트림 종료 — 지금까지 받은 출력으로 판단
            }
        }.apply { start() }

        var writeError: Throwable? = null
        try {
            if (stdin != null) {
                val chunk = chunkSize(m)
                val buf = ByteArray(chunk)
                stdin.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        stream.write(buf, 0, n) // 패킷 1개 → 다음 write 는 OKAY 를 받은 뒤에 나간다
                    }
                }
                runCatching { stream.flush() }
            }
        } catch (t: Throwable) {
            writeError = t
        }

        reader.join(180_000)
        runCatching { stream.close() }
        val text = synchronized(output) { output.toString(Charsets.UTF_8.name()) }.trim()

        if (writeError != null && text.isBlank()) {
            val dropped = !connectedNow()
            throw AdbException(
                if (dropped) "무선 연결이 끊겼어요. 무선 디버깅이 켜져 있는지 확인하고 다시 시도해 주세요."
                else "APK 를 보내는 중에 기기가 전송을 끊었어요. 다시 시도해 주세요. (${writeError.message})",
            )
        }
        return text
    }

    /** 협상된 최대 패킷 크기 이하, 너무 크지 않게 (응답 대기 지연과 메모리 균형) */
    private fun chunkSize(m: AdbManager): Int {
        val max = runCatching { (live ?: m.adbConnection)?.maxData }.getOrNull() ?: (64 * 1024)
        return max.coerceIn(4 * 1024, 256 * 1024)
    }

    private fun isOptionError(out: String): Boolean =
        out.contains("Unknown option") || out.contains("IllegalArgumentException")

    private fun friendly(raw: String): String {
        val out = InstallErrors.summarize(raw)
        val o = raw.uppercase()
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
