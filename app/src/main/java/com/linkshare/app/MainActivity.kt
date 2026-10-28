package com.linkshare.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult

class MainActivity : Activity() {
    private lateinit var hostEt: EditText
    private lateinit var userEt: EditText
    private lateinit var passEt: EditText
    private lateinit var statusTitle: TextView
    private lateinit var statusDesc: TextView
    private lateinit var statView: TextView
    private lateinit var btnServer: Button
    private lateinit var btnClient: Button
    private lateinit var qrView: ImageView
    private var lastQr = ""
    private var isClientMode = false
    private var pendingHost = ""
    private var pendingNetwork: android.net.Network? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private lateinit var prefs: SharedPreferences
    private var receiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("linkshare", MODE_PRIVATE)
        ServerService.authUser = prefs.getString("srv_user", "") ?: ""
        ServerService.authPass = prefs.getString("srv_pass", "") ?: ""
        requestNecessaryPermissions()
        buildUI()
        registerStat()
    }

    override fun onDestroy() {
        try { receiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun registerStat() {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val msg = i?.getStringExtra("msg") ?: return
                statView.text = msg
            }
        }
        val f = IntentFilter(VpnClientService.ACTION_STAT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(receiver, f)
    }

    private fun requestNecessaryPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.POST_NOTIFICATIONS
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
        } else perms += Manifest.permission.ACCESS_FINE_LOCATION
        perms += Manifest.permission.CAMERA
        requestPermissions(perms.toTypedArray(), 1)
    }

    private fun buildUI() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#F5F7FA"))
        }
        root.addView(TextView(this).apply {
            text = "LinkShare"; textSize = 26f; setTextColor(Color.parseColor("#0D47A1"))
            setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
        })

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE)
            setPadding(pad, pad, pad, pad); setElevation(8f)
        }
        statusTitle = TextView(this).apply {
            text = "대기 중"; textSize = 18f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#333333")); gravity = Gravity.CENTER
        }
        statusDesc = TextView(this).apply {
            text = "버튼을 눌러 시작하세요"; textSize = 12f
            setTextColor(Color.parseColor("#666666")); gravity = Gravity.CENTER; setPadding(0, 4, 0, 0)
        }
        statView = TextView(this).apply {
            text = "통계: -"; textSize = 11f
            setTextColor(Color.parseColor("#1565C0")); gravity = Gravity.CENTER; setPadding(0, 4, 0, 0)
        }
        statusCard.addView(statusTitle); statusCard.addView(statusDesc); statusCard.addView(statView)
        root.addView(statusCard, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, pad, 0, pad) })

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnServer = Button(this).apply {
            text = "서버 시작"; setBackgroundColor(Color.parseColor("#1565C0")); setTextColor(Color.WHITE)
            setOnClickListener { toggleServer() }
        }
        btnClient = Button(this).apply {
            text = "VPN 연결"; setBackgroundColor(Color.parseColor("#2E7D32")); setTextColor(Color.WHITE)
            setOnClickListener { toggleClient() }
        }
        btnRow.addView(btnServer, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(4, 0, 4, 0) })
        btnRow.addView(btnClient, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(4, 0, 4, 0) })
        root.addView(btnRow)

        val btnScan = Button(this).apply {
            text = "QR 스캔"; setBackgroundColor(Color.parseColor("#F57C00")); setTextColor(Color.WHITE)
            setOnClickListener { startQrScan() }
        }
        val btnWifi = Button(this).apply {
            text = "WiFi 직접 입력"; setBackgroundColor(Color.parseColor("#6A1B9A")); setTextColor(Color.WHITE)
            setOnClickListener { showWifiDialog() }
        }
        val btnApps = Button(this).apply {
            text = "VPN 경유 앱 선택"; setBackgroundColor(Color.parseColor("#00838F")); setTextColor(Color.WHITE)
            setOnClickListener { showAppPicker() }
        }
        root.addView(btnScan, LinearLayout.LayoutParams(-1, -2).apply { setMargins(4, pad/2, 4, 0) })
        root.addView(btnWifi, LinearLayout.LayoutParams(-1, -2).apply { setMargins(4, pad/2, 4, 0) })
        root.addView(btnApps, LinearLayout.LayoutParams(-1, -2).apply { setMargins(4, pad/2, 4, 0) })

        hostEt = EditText(this).apply {
            hint = "서버 IP (예: 192.168.43.1)"; setText(NetUtil.serverHost(this@MainActivity))
        }
        userEt = EditText(this).apply {
            hint = "SOCKS5 사용자 (비우면 인증 없음)"
            setText(prefs.getString("srv_user", ""))
        }
        passEt = EditText(this).apply {
            hint = "SOCKS5 비밀번호"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("srv_pass", ""))
        }
        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inputRow.addView(hostEt); inputRow.addView(userEt); inputRow.addView(passEt)
        root.addView(inputRow, LinearLayout.LayoutParams(-1, -2).apply { setMargins(4, pad, 4, 0) })

        qrView = ImageView(this).apply { visibility = View.GONE; scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(qrView, LinearLayout.LayoutParams((180 * resources.displayMetrics.density).toInt(), (180 * resources.displayMetrics.density).toInt()).apply { gravity = Gravity.CENTER })

        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) })

        val utilRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun utilBtn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; textSize = 11f; setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#1565C0")); setOnClickListener { f() }
        }
        utilRow.addView(utilBtn("로그") { startActivity(Intent(this, LogActivity::class.java)) })
        utilRow.addView(utilBtn("설정") { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) })
        utilRow.addView(utilBtn("테스트") { runTest(hostEt.text.toString().trim()) })
        root.addView(utilRow)

        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    private fun showWifiDialog() {
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(30, 20, 30, 10) }
        val ssidEt = EditText(this).apply { hint = "SSID" }
        val pwEt = EditText(this).apply { hint = "비밀번호 (열린 WiFi면 비움)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        ll.addView(ssidEt); ll.addView(pwEt)
        AlertDialog.Builder(this).setTitle("WiFi 직접 입력").setView(ll)
            .setPositiveButton("연결") { _, _ ->
                val s = ssidEt.text.toString().trim()
                val p = pwEt.text.toString()
                if (s.isNotEmpty()) connectToWifi(s, p)
            }
            .setNegativeButton("취소", null).show()
    }

    private fun showAppPicker() {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName != packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { pm.getApplicationLabel(it).toString() }
        val labels = apps.map { "${pm.getApplicationLabel(it)} (${it.packageName})" }.toTypedArray()
        val saved = prefs.getStringSet("allowed_apps", emptySet()) ?: emptySet()
        val checked = BooleanArray(apps.size) { apps[it].packageName in saved }
        AlertDialog.Builder(this)
            .setTitle("VPN 경유할 앱 (미선택 시 전체)")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("저장") { _, _ ->
                val set = apps.filterIndexed { i, _ -> checked[i] }.map { it.packageName }.toSet()
                prefs.edit().putStringSet("allowed_apps", set).apply()
                Toast.makeText(this, "${set.size}개 선택됨", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .setNeutralButton("전체 해제") { _, _ ->
                prefs.edit().putStringSet("allowed_apps", emptySet()).apply()
                Toast.makeText(this, "화이트리스트 초기화", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun startQrScan() {
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("서버 폰의 Wi-Fi QR 코드를 스캔하세요")
        integrator.setBeepEnabled(false); integrator.initiateScan()
    }

    private fun handleWifiQr(qrText: String) {
        try {
            if (!qrText.startsWith("WIFI:")) {
                val host = qrText.trim()
                if (host.matches(Regex("^[0-9.]+(:[0-9]+)?$"))) {
                    hostEt.setText(host.substringBefore(":"))
                    requestVpnPermissionAndStart(host.substringBefore(":")); return
                }
                updateStatus("QR 오류", "Wi-Fi QR이 아닙니다.", Color.RED); return
            }
            val ssid = Regex("S:([^;]+)").find(qrText)?.groupValues?.get(1)?.replace("\\\\", "\\") ?: return
            val pass = Regex("P:([^;]+)").find(qrText)?.groupValues?.get(1)?.replace("\\\\", "\\") ?: ""
            updateStatus("Wi-Fi 연결 중", "SSID: $ssid", Color.parseColor("#F57C00"))
            connectToWifi(ssid, pass)
        } catch (t: Throwable) { updateStatus("QR 오류", t.message ?: "해석 실패", Color.RED) }
    }

    private fun connectToWifi(ssid: String, pass: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val spec = WifiNetworkSpecifier.Builder().setSsid(ssid)
                    .apply { if (pass.isNotEmpty()) setWpa2Passphrase(pass) }.build()
                val req = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).setNetworkSpecifier(spec).build()
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                wifiCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        runOnUiThread {
                            pendingNetwork = network
                            updateStatus("Wi-Fi 연결됨", "IP 확인 중...", Color.parseColor("#2E7D32"))
                            statusDesc.postDelayed({
                                val host = NetUtil.serverHostFromNetwork(this@MainActivity, network)
                                hostEt.setText(host)
                                try { cm.bindProcessToNetwork(network) } catch (_: Throwable) {}
                                updateStatus("VPN 연결 중", "서버 $host", Color.parseColor("#F57C00"))
                                requestVpnPermissionAndStart(host)
                            }, 1200)
                        }
                    }
                    override fun onUnavailable() {
                        runOnUiThread { updateStatus("Wi-Fi 실패", "수동 연결 필요", Color.RED) }
                    }
                }
                cm.requestNetwork(req, wifiCallback!!)
            } else {
                updateStatus("수동 연결", "설정에서 연결하세요", Color.parseColor("#F57C00"))
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
        } catch (t: Throwable) { updateStatus("Wi-Fi 오류", t.message ?: "오류", Color.RED) }
    }

    private fun toggleServer() {
        if (ServerService.running) {
            LocalHotspot.stop(); stopService(Intent(this, ServerService::class.java))
            updateStatus("대기 중", "서버 중지됨", Color.parseColor("#333333"))
        } else {
            ServerService.authUser = userEt.text.toString().trim()
            ServerService.authPass = passEt.text.toString()
            prefs.edit().putString("srv_user", ServerService.authUser)
                .putString("srv_pass", ServerService.authPass).apply()

            updateStatus("서버 시작 중", "핫스팟 활성화", Color.parseColor("#F57C00"))
            LocalHotspot.start(this) { ok ->
                if (ok) {
                    try {
                        startForegroundService(Intent(this, ServerService::class.java))
                        updateStatus("서버 실행 중",
                            "SSID: ${LocalHotspot.ssid}\nPW: ${LocalHotspot.pass}",
                            Color.parseColor("#1565C0"))
                        showQr()
                    } catch (t: Throwable) { updateStatus("오류", t.message ?: "실패", Color.RED) }
                } else updateStatus("실패", "핫스팟 불가", Color.RED)
            }
        }
        refresh()
    }

    private fun toggleClient() {
        if (isClientMode) {
            startService(Intent(this, VpnClientService::class.java).setAction("stop"))
            isClientMode = false
            updateStatus("대기 중", "연결 해제됨", Color.parseColor("#333333"))
        } else {
            val host = hostEt.text.toString().trim()
            if (host.isEmpty()) { Toast.makeText(this, "IP 입력 필요", Toast.LENGTH_SHORT).show(); return }
            pendingNetwork = null
            requestVpnPermissionAndStart(host)
        }
        refresh()
    }

    private fun requestVpnPermissionAndStart(host: String) {
        val prep = VpnService.prepare(this)
        if (prep != null) { pendingHost = host; @Suppress("DEPRECATION") startActivityForResult(prep, 100) }
        else startVpn(host)
    }

    private fun startVpn(host: String) {
        try {
            val allowed = ArrayList(prefs.getStringSet("allowed_apps", emptySet()) ?: emptySet<String>())
            val intent = Intent(this, VpnClientService::class.java)
                .putExtra("host", host)
                .putStringArrayListExtra("allowed_apps", allowed)
            pendingNetwork?.let { intent.putExtra("network", it) }
            startService(intent)
            isClientMode = true
            updateStatus("VPN 연결됨", "서버 $host / 지정 ${allowed.size}개 앱", Color.parseColor("#2E7D32"))
        } catch (t: Throwable) { updateStatus("오류", t.message ?: "VPN 실패", Color.RED) }
    }

    private fun updateStatus(title: String, desc: String, color: Int) {
        statusTitle.text = title; statusTitle.setTextColor(color); statusDesc.text = desc
    }

    private fun showQr() {
        if (LocalHotspot.ssid.isEmpty()) { qrView.visibility = View.GONE; return }
        val key = "${LocalHotspot.ssid}|${LocalHotspot.pass}|${LocalHotspot.sec}"
        if (key != lastQr) {
            try { qrView.setImageBitmap(QrHelper.wifi(LocalHotspot.ssid, LocalHotspot.pass, LocalHotspot.sec)); lastQr = key } catch (_: Throwable) {}
        }
        qrView.visibility = View.VISIBLE
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result: IntentResult? = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents == null) updateStatus("스캔 취소", "QR 스캔 취소", Color.GRAY)
            else handleWifiQr(result.contents)
        } else if (requestCode == 100) {
            if (resultCode == Activity.RESULT_OK) startVpn(pendingHost)
            else updateStatus("권한 거부", "VPN 필요", Color.RED)
        } else super.onActivityResult(requestCode, resultCode, data)
    }

    private fun runTest(host: String) {
        if (host.isEmpty()) return
        Toast.makeText(this, "테스트 중...", Toast.LENGTH_SHORT).show()
        Thread {
            val msg = "대상: $host\nTCP: ${Socks5Test.tcp(host, ServerService.PORT)}\nUDP: ${Socks5Test.udp(host, ServerService.PORT)}"
            runOnUiThread { AlertDialog.Builder(this).setTitle("결과").setMessage(msg).setPositiveButton("확인", null).show() }
        }.start()
    }

    private fun refresh() {
        if (ServerService.running) { btnServer.text = "서버 중지"; btnServer.setBackgroundColor(Color.parseColor("#C62828")) }
        else { btnServer.text = "서버 시작"; btnServer.setBackgroundColor(Color.parseColor("#1565C0")) }
        if (isClientMode) { btnClient.text = "VPN 해제"; btnClient.setBackgroundColor(Color.parseColor("#C62828")) }
        else { btnClient.text = "VPN 연결"; btnClient.setBackgroundColor(Color.parseColor("#2E7D32")) }
    }
}
