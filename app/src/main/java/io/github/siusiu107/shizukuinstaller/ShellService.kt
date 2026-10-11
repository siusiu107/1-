package io.github.siusiu107.shizukuinstaller

import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Shizuku 가 app_process 로 띄우는 UserService.
 * 이 클래스는 앱 프로세스가 아니라 shell(adb) 또는 root 권한의 별도 프로세스에서 실행된다.
 */
class ShellService : IShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun run(cmd: Array<String>, stdin: ParcelFileDescriptor?): Bundle {
        val result = Bundle()
        try {
            val process = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val output = ByteArrayOutputStream()
            val reader = thread(name = "shell-output") {
                try {
                    process.inputStream.use { it.copyTo(output) }
                } catch (_: IOException) {
                }
            }
            try {
                process.outputStream.use { out ->
                    if (stdin != null) {
                        ParcelFileDescriptor.AutoCloseInputStream(stdin).use { it.copyTo(out, 1 shl 16) }
                    }
                }
            } catch (_: IOException) {
                // 명령이 먼저 끝나서 파이프가 닫힌 경우: 출력에 원인이 남아 있으므로 계속 진행
            }
            val code = process.waitFor()
            reader.join()
            result.putInt(KEY_CODE, code)
            result.putString(KEY_OUTPUT, output.toString().trim())
        } catch (t: Throwable) {
            result.putInt(KEY_CODE, -1)
            result.putString(KEY_OUTPUT, t.toString())
        } finally {
            try {
                stdin?.close()
            } catch (_: IOException) {
            }
        }
        return result
    }

    companion object {
        const val KEY_CODE = "code"
        const val KEY_OUTPUT = "output"
    }
}
