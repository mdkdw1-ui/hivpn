package com.linkshare.app

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import hev.htproxy.TProxyService
import java.io.File
import kotlin.concurrent.thread

class VpnClientService : VpnService() {
    private var pfd: ParcelFileDescriptor? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var statsThread: Thread? = null
    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.i("vpn", "onStartCommand action=${intent?.action}")
        if (intent?.action == "stop") {
            stopTunnel(); stopSelf(); return START_NOT_STICKY
        }
        val host = intent?.getStringExtra("host") ?: return START_NOT_STICKY
        val allowedApps = intent.getStringArrayListExtra("allowed_apps") ?: arrayListOf()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = intent.getParcelableExtra<Network>("network")
            if (network != null) {
                try {
                    (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).bindProcessToNetwork(network)
                    AppLog.i("vpn", "Wi-Fi 네트워크 바인딩: $network")
                } catch (t: Throwable) { AppLog.e("vpn", "네트워크 바인딩 실패", t) }
            }
        }
        startTunnel(host, allowedApps)
        return START_NOT_STICKY
    }

    private fun startTunnel(host: String, allowedApps: List<String>) {
        stopTunnel()
        try {
            Class.forName("hev.htproxy.TProxyService")
            val builder = Builder()
                .setSession("LinkShare")
                .setMtu(1500)
                .addAddress("198.18.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("198.18.0.2")

            if (allowedApps.isEmpty()) {
                builder.addDisallowedApplication(packageName)
            } else {
                for (pkg in allowedApps) {
                    try { builder.addAllowedApplication(pkg) }
                    catch (t: Throwable) { AppLog.e("vpn", "addAllowedApplication 실패 $pkg", t) }
                }
            }
            val fd = builder.establish()
            if (fd == null) {
                AppLog.e("vpn", "establish() null")
                sendStat("VPN 권한 없음 / 다른 VPN 우선")
                return
            }
            pfd = fd
            AppLog.i("vpn", "TUN fd=${fd.fd}, allowed=${allowedApps.size}")

            try {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                           else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm.createWifiLock(mode, "linkshare").apply { setReferenceCounted(false); acquire() }
            } catch (_: Throwable) {}

            val logFile = File(filesDir, "hev.log")
            logFile.delete()
            val cfg = File(filesDir, "hev.yml")
            cfg.writeText(
                """
                tunnel:
                  mtu: 1500
                  ipv4: 198.18.0.1
                socks5:
                  address: $host
                  port: ${ServerService.PORT}
                  udp: 'udp'
                  pipeline: true
                mapdns:
                  address: 198.18.0.2
                  port: 53
                  network: 100.64.0.0
                  netmask: 255.192.0.0
                  cache-size: 10000
                misc:
                  log-file: ${logFile.absolutePath}
                  log-level: warn
                """.trimIndent() + "\n"
            )
            val r = TProxyService.TProxyStartService(cfg.absolutePath, fd.fd)
            started = true
            AppLog.i("vpn", "TProxyStartService=$r")
            sendStat("연결됨 → $host (화이트리스트 ${allowedApps.size}개)")
            startStats()
        } catch (t: Throwable) {
            AppLog.e("vpn", "터널 시작 실패", t)
            sendStat("실패: ${t.message}")
            stopTunnel()
        }
    }

    private fun startStats() {
        statsThread = thread(isDaemon = true) {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(2000)
                    val s = TProxyService.TProxyGetStats()
                    if (s.size >= 4) sendStat("tx ${s[1]}B / rx ${s[3]}B")
                }
            } catch (_: InterruptedException) {
            } catch (t: Throwable) { AppLog.e("hev", "통계 오류", t) }
        }
    }

    private fun sendStat(msg: String) {
        sendBroadcast(Intent(ACTION_STAT).setPackage(packageName).putExtra("msg", msg))
    }

    private fun stopTunnel() {
        statsThread?.interrupt(); statsThread = null
        if (started) { started = false; try { TProxyService.TProxyStopService() } catch (_: Throwable) {} }
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        try { wifiLock?.release() } catch (_: Exception) {}
        wifiLock = null
    }

    override fun onRevoke() { stopTunnel(); stopSelf() }
    override fun onDestroy() { stopTunnel(); super.onDestroy() }

    companion object {
        const val ACTION_STAT = "com.linkshare.app.VPN_STAT"
    }
}
