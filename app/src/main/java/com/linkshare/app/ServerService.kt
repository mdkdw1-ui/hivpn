package com.linkshare.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

class ServerService : Service() {
    private var server: Socks5Server? = null
    private var wl: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.i("srv", "ServerService 시작 auth=${authUser.isNotEmpty()}")
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("srv", "Server", NotificationManager.IMPORTANCE_LOW))
            val n = Notification.Builder(this, "srv")
                .setContentTitle("LinkShare 서버 실행 중")
                .setContentText("SOCKS5 :$PORT ${if (authUser.isNotEmpty()) "(인증)" else ""}")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(1, n)

            if (wl == null) {
                wl = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "linkshare:server")
                    .apply { setReferenceCounted(false); acquire() }
            }
            if (server == null) {
                server = Socks5Server(PORT, authUser, authPass).also { it.start() }
                running = true
            }
        } catch (t: Throwable) {
            AppLog.e("srv", "서버 시작 실패", t)
            running = false; server = null; stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop(); server = null; running = false
        try { wl?.release() } catch (_: Exception) {}
        wl = null
        super.onDestroy()
    }

    companion object {
        const val PORT = 1080
        @Volatile var running = false
        @Volatile var authUser: String = ""
        @Volatile var authPass: String = ""
    }
}
