package io.github.siusiu107.shizukuinstaller

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Shizuku 상태 확인 + UserService(ShellService) 연결 관리 */
object Shell {

    enum class State { NOT_RUNNING, TOO_OLD, NO_PERMISSION, READY }

    data class Result(val code: Int, val output: String) {
        val ok: Boolean get() = code == 0
    }

    fun state(): State {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) return State.NOT_RUNNING
        return try {
            if (Shizuku.isPreV11()) return State.TOO_OLD
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return State.NO_PERMISSION
            // 권한이 있다고 캐시돼 있어도 실제로 바인더 트랜잭션이 되는지 확인한다.
            // (서비스가 죽었거나 권한이 회수됐는데 READY 로 굳는 거짓 양성 방지)
            Shizuku.getUid()
            State.READY
        } catch (_: Throwable) {
            // 여기까지 왔는데 예외면: 권한이 막 회수됐거나 서비스가 사라진 상태
            if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) State.NO_PERMISSION
            else State.NOT_RUNNING
        }
    }

    /** 상태 화면의 '자세히' 에 보여줄 원시 신호 (문제 진단용). */
    fun diagnostics(): String {
        fun s(block: () -> Any?): String = runCatching { block()?.toString() ?: "null" }
            .getOrElse { "오류(${it.javaClass.simpleName})" }
        val perm = runCatching {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) "허용됨" else "거부됨"
        }.getOrElse { "오류(${it.javaClass.simpleName})" }
        return "pingBinder=${s { Shizuku.pingBinder() }}\n" +
            "preV11=${s { Shizuku.isPreV11() }}\n" +
            "permission=$perm\n" +
            "uid=${s { Shizuku.getUid() }}\n" +
            "version=${s { Shizuku.getVersion() }}"
    }

    /** "root" / "adb" 등 Shizuku 가 어떤 권한으로 돌고 있는지 */
    fun backendName(): String = try {
        when (Shizuku.getUid()) {
            0 -> "root"
            2000 -> "adb"
            else -> "uid ${Shizuku.getUid()}"
        }
    } catch (_: Throwable) {
        "?"
    }

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val lock = Any()

    @Volatile
    private var service: IShellService? = null

    @Volatile
    private var latch: CountDownLatch? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                service = IShellService.Stub.asInterface(binder)
            }
            latch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    /** 백그라운드 스레드에서만 호출할 것 (연결될 때까지 블록됨) */
    private fun service(): IShellService {
        synchronized(lock) {
            service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
            service = null
            check(state() == State.READY) { "Shizuku 가 실행 중이 아니거나 권한이 없습니다." }
            val l = CountDownLatch(1)
            latch = l
            Shizuku.bindUserService(args, connection)
            if (!l.await(20, TimeUnit.SECONDS)) {
                error("Shizuku 서비스 연결 시간 초과")
            }
            return service ?: error("Shizuku 서비스에 연결하지 못했습니다.")
        }
    }

    fun run(vararg cmd: String, stdin: ParcelFileDescriptor? = null): Result {
        val bundle = service().run(arrayOf(*cmd), stdin)
        return Result(
            bundle.getInt(ShellService.KEY_CODE, -1),
            bundle.getString(ShellService.KEY_OUTPUT).orEmpty(),
        )
    }

    /**
     * 설치를 누르기 전에 미리 UserService 연결을 맺어둔다.
     * (첫 설치 때 생기는 1~3초 바인딩 지연을 숨김)
     */
    fun prewarm() {
        if (service != null) return
        Thread {
            runCatching { if (state() == State.READY) service() }
        }.apply { isDaemon = true; start() }
    }
}
