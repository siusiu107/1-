package io.github.siusiu107.shizukuinstaller

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

/** openwith(파일 탭 / 공유 / 다음으로 열기)로 진입하는 설치 화면 */
class InstallActivity : AppCompatActivity() {

    private val requestCode = 2002

    private lateinit var title: TextView
    private lateinit var logView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var positive: Button
    private lateinit var negative: Button

    private var uris: List<Uri> = emptyList()
    private val logBuffer = StringBuilder()

    private val permListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == requestCode) {
            if (result == android.content.pm.PackageManager.PERMISSION_GRANTED) start()
            else fail(getString(R.string.hint_no_perm))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_install)
        title = findViewById(R.id.title)
        logView = findViewById(R.id.log)
        progress = findViewById(R.id.progress)
        positive = findViewById(R.id.positive)
        negative = findViewById(R.id.negative)

        Shizuku.addRequestPermissionResultListener(permListener)

        uris = extractUris(intent)
        if (uris.isEmpty()) {
            title.text = getString(R.string.no_file)
            positive.visibility = View.GONE
            negative.text = getString(R.string.close)
            negative.setOnClickListener { finish() }
            return
        }

        title.text = resources.getQuantityString(R.plurals.ready_to_install, uris.size, uris.size)
        negative.setOnClickListener { finish() }
        positive.setOnClickListener { begin() }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permListener)
    }

    private fun begin() {
        when (Shell.state()) {
            Shell.State.READY -> start()
            Shell.State.NO_PERMISSION -> {
                if (Shizuku.shouldShowRequestPermissionRationale()) fail(getString(R.string.denied_before))
                else Shizuku.requestPermission(requestCode)
            }
            Shell.State.NOT_RUNNING -> fail(getString(R.string.hint_not_running))
            Shell.State.TOO_OLD -> fail(getString(R.string.hint_too_old))
        }
    }

    private fun start() {
        positive.isEnabled = false
        negative.isEnabled = false
        progress.visibility = View.VISIBLE
        logBuffer.setLength(0)
        appendLog(getString(R.string.installing, Shell.backendName()))

        thread(name = "install") {
            try {
                Installer.install(this, uris, emptyList()) { line -> appendLog(line) }
                done(true, null)
            } catch (e: Installer.InstallException) {
                done(false, e.message)
            } catch (t: Throwable) {
                done(false, t.toString())
            }
        }
    }

    private fun done(success: Boolean, error: String?) = runOnUiThread {
        progress.visibility = View.GONE
        negative.isEnabled = true
        if (success) {
            title.text = getString(R.string.install_success)
            val pkg = firstPackageName()
            val launch = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
            if (launch != null) {
                positive.isEnabled = true
                positive.text = getString(R.string.open_app)
                positive.setOnClickListener {
                    startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                }
            } else {
                positive.visibility = View.GONE
            }
            negative.text = getString(R.string.done)
        } else {
            title.text = getString(R.string.install_failed)
            appendLog("\n${error.orEmpty()}")
            positive.isEnabled = true
            positive.text = getString(R.string.retry)
            positive.setOnClickListener { begin() }
            negative.text = getString(R.string.close)
        }
    }

    private fun fail(msg: String) {
        title.text = getString(R.string.cannot_install)
        appendLog(msg)
        progress.visibility = View.GONE
    }

    private fun appendLog(line: String) = runOnUiThread {
        logBuffer.append(line).append('\n')
        logView.text = logBuffer.toString().trim()
    }

    /** 설치 후 "열기" 버튼을 위해 첫 APK 의 패키지명을 추출(실패해도 무방) */
    @Suppress("DEPRECATION")
    private fun firstPackageName(): String? {
        val uri = uris.firstOrNull() ?: return null
        return try {
            val file = java.io.File(cacheDir, "peek.apk")
            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            // zip 묶음이면 base apk 가 아닐 수 있으나, 실패 시 열기 버튼만 숨겨짐
            val pkg = packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.packageName
            file.delete()
            pkg
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun extractUris(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        val out = LinkedHashSet<Uri>()
        intent.data?.let { out += it }
        when (intent.action) {
            Intent.ACTION_SEND ->
                (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let { out += it }
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { out += it.filterNotNull() }
        }
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { out += it }
        }
        return out.toList()
    }
}
