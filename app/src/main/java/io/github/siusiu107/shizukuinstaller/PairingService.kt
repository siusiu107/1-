package io.github.siusiu107.shizukuinstaller

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.muntashirakon.adb.android.AdbMdns
import io.github.siusiu107.shizukuinstaller.pkg.WirelessAdb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 알림으로 무선 디버깅 페어링.
 *
 * 설정의 '페어링 코드로 기기 페어링' 창은 다른 앱으로 넘어가면 닫히고 코드가 만료된다.
 * 그래서 이 서비스가 mDNS 로 페어링 포트를 찾아두고, 알림의 입력창(RemoteInput)으로 코드를 받아
 * 설정 창을 띄운 채로 페어링 → 연결까지 끝낸다.
 */
class PairingService : Service() {

    companion object {
        private const val ACTION_START = "io.github.siusiu107.shizukuinstaller.pairing.START"
        private const val ACTION_REPLY = "io.github.siusiu107.shizukuinstaller.pairing.REPLY"
        private const val ACTION_STOP = "io.github.siusiu107.shizukuinstaller.pairing.STOP"
        private const val KEY_CODE = "pairing_code"
        private const val CHANNEL_ID = "wireless_pairing"
        private const val NOTIF_ID = 4242
        private const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, PairingService::class.java).setAction(ACTION_START),
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mdns: AdbMdns? = null
    private var idleJob: Job? = null

    @Volatile private var pairingPort = -1
    @Volatile private var working = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        // getForegroundService 로 시작될 수 있으므로 어떤 액션이든 먼저 포그라운드로 올린다
        val first = when (intent?.action) {
            ACTION_REPLY -> statusNotification("페어링 중…")
            ACTION_STOP -> statusNotification("중지하는 중…")
            else -> searchingNotification()
        }
        if (!goForeground(first)) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> finish(null)
            ACTION_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_CODE)?.toString().orEmpty().filter { it.isDigit() }
                onCode(code)
            }
            else -> startDiscovery()
        }
        resetIdleTimer()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { mdns?.stop() }
        mdns = null
        scope.cancel()
    }

    // ---- 흐름 ----

    private fun startDiscovery() {
        if (mdns != null) return
        mdns = AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { _, port ->
            val before = pairingPort
            pairingPort = port // 창이 닫히면 -1, 다시 열리면 새 포트
            if (!working) {
                if (port > 0 && before <= 0) {
                    update(inputNotification("페어링 창을 찾았어요! 여기에 6자리 코드를 입력하세요.", alert = true))
                } else if (port <= 0 && before > 0) {
                    update(searchingNotification())
                }
            }
        }.also { runCatching { it.start() } }
    }

    private fun onCode(code: String) {
        if (code.length != 6) {
            update(inputNotification("코드는 6자리 숫자예요. 다시 입력해 주세요."))
            return
        }
        if (working) return
        working = true
        scope.launch {
            try {
                startDiscovery()
                // 코드를 먼저 넣었는데 아직 포트를 못 찾았으면 잠깐 기다린다
                var port = pairingPort
                val deadline = System.currentTimeMillis() + 8000
                while (port <= 0 && System.currentTimeMillis() < deadline) {
                    delay(200); port = pairingPort
                }
                if (port <= 0) {
                    update(inputNotification("페어링 창을 못 찾았어요. 설정의 '페어링 코드로 기기 페어링' 창을 연 채로 다시 입력해 주세요."))
                    return@launch
                }
                update(statusNotification("페어링 중…"))
                if (!WirelessAdb.pair(this@PairingService, port, code)) {
                    throw WirelessAdb.AdbException("페어링이 거부됐어요. 코드를 다시 확인해 주세요.")
                }
                update(statusNotification("연결 중…"))
                val connected = WirelessAdb.autoConnect(this@PairingService, 8000)
                finish(
                    if (connected) "페어링·연결 완료! 이제 막힌 앱도 쓱 설치돼요."
                    else "페어링 완료. 쓱설치를 열면 자동으로 연결돼요.",
                )
            } catch (t: Throwable) {
                update(inputNotification(t.message ?: "페어링에 실패했어요. 다시 입력해 주세요."))
            } finally {
                working = false
                resetIdleTimer()
            }
        }
    }

    private fun finish(doneMessage: String?) {
        runCatching { mdns?.stop() }
        mdns = null
        idleJob?.cancel()
        if (doneMessage != null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            update(doneNotification(doneMessage))
        } else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    /** 아무 입력 없이 오래 떠 있지 않도록 */
    private fun resetIdleTimer() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (!working) finish(null)
        }
    }

    // ---- 알림 ----

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "무선 디버깅 페어링", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "알림에서 페어링 코드를 입력해 무선 디버깅에 연결해요."
                    setShowBadge(false)
                },
            )
        }
    }

    private fun base(text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ssuk)
            .setContentTitle("무선 디버깅 페어링")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent())
            .setOngoing(true)

    private fun searchingNotification(): Notification =
        base("설정 › 개발자 옵션 › 무선 디버깅 › '페어링 코드로 기기 페어링' 을 열어 주세요. 창이 열린 채로 이 알림에서 코드를 입력하면 돼요.")
            .setSilent(true)
            .addAction(codeInputAction())
            .addAction(devSettingsAction())
            .addAction(stopAction())
            .build()

    private fun inputNotification(text: String, alert: Boolean = false): Notification =
        base(text)
            .setSilent(!alert)
            .setOnlyAlertOnce(!alert)
            .addAction(codeInputAction())
            .addAction(devSettingsAction())
            .addAction(stopAction())
            .build()

    private fun statusNotification(text: String): Notification =
        base(text)
            .setSilent(true)
            .setProgress(0, 0, true)
            .build()

    private fun doneNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ssuk)
            .setContentTitle("쓱설치")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

    private fun codeInputAction(): NotificationCompat.Action {
        val input = RemoteInput.Builder(KEY_CODE).setLabel("6자리 페어링 코드").build()
        val intent = Intent(this, PairingService::class.java).setAction(ACTION_REPLY)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0) // RemoteInput 은 mutable 필수
        val pi = if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(this, 1, intent, flags)
        else PendingIntent.getService(this, 1, intent, flags)
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_ssuk, "코드 입력", pi)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .build()
    }

    private fun devSettingsAction(): NotificationCompat.Action {
        val pi = PendingIntent.getActivity(
            this, 3,
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(0, "개발자 옵션", pi).build()
    }

    private fun stopAction(): NotificationCompat.Action {
        val intent = Intent(this, PairingService::class.java).setAction(ACTION_STOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(this, 2, intent, flags)
        else PendingIntent.getService(this, 2, intent, flags)
        return NotificationCompat.Action.Builder(0, "그만두기", pi).build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 4,
        Intent(this, PairingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun goForeground(n: Notification): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
        true
    } catch (_: Throwable) {
        false
    }

    @SuppressLint("MissingPermission")
    private fun update(n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        runCatching { NotificationManagerCompat.from(this).notify(NOTIF_ID, n) }
    }
}
