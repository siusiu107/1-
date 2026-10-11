package io.github.siusiu107.shizukuinstaller

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private val requestCode = 1001

    private val permListener = Shizuku.OnRequestPermissionResultListener { code, _ ->
        if (code == requestCode) refresh()
    }
    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh() }

    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var grant: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        detail = findViewById(R.id.detail)
        grant = findViewById(R.id.grant)

        grant.setOnClickListener {
            when (Shell.state()) {
                Shell.State.NO_PERMISSION -> {
                    if (Shizuku.shouldShowRequestPermissionRationale()) {
                        toast(getString(R.string.denied_before))
                    } else {
                        Shizuku.requestPermission(requestCode)
                    }
                }
                Shell.State.READY -> toast(getString(R.string.already_ready))
                else -> refresh()
            }
        }

        Shizuku.addRequestPermissionResultListener(permListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permListener)
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
    }

    private fun refresh() {
        runOnUiThread {
            when (Shell.state()) {
                Shell.State.NOT_RUNNING -> {
                    status.text = getString(R.string.state_not_running)
                    detail.text = getString(R.string.hint_not_running)
                    grant.text = getString(R.string.refresh)
                    grant.isEnabled = true
                }
                Shell.State.TOO_OLD -> {
                    status.text = getString(R.string.state_too_old)
                    detail.text = getString(R.string.hint_too_old)
                    grant.text = getString(R.string.refresh)
                    grant.isEnabled = true
                }
                Shell.State.NO_PERMISSION -> {
                    status.text = getString(R.string.state_no_perm)
                    detail.text = getString(R.string.hint_no_perm)
                    grant.text = getString(R.string.grant)
                    grant.isEnabled = true
                }
                Shell.State.READY -> {
                    status.text = getString(R.string.state_ready, Shell.backendName())
                    detail.text = getString(R.string.hint_ready)
                    grant.text = getString(R.string.refresh)
                    grant.isEnabled = true
                }
            }
        }
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
}
