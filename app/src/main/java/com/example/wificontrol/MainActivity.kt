package com.example.wificontrol

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var wifi: WifiManager
    private lateinit var status: TextView
    private lateinit var networkList: LinearLayout
    private lateinit var password: EditText
    private lateinit var security: Spinner
    private lateinit var selectedLabel: TextView
    private var selected: ScanResult? = null
    private var receiverRegistered = false
    @Volatile private var shizukuShell: IWifiShellService? = null
    @Volatile private var shizukuBindRequested = false

    private val shizukuArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(this, WifiShellUserService::class.java))
            .daemon(false)
            .tag("wifi-control-shell")
            .version(2)
    }

    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shizukuShell = service?.let { IWifiShellService.Stub.asInterface(it) }
            shizukuBindRequested = false
            setStatus(
                if (shizukuShell != null) "Shizuku מחובר. אפשר להפעיל Wi-Fi או להתחבר בלי Root."
                else "Shizuku התחבר אך שירות הפקודות לא זמין."
            )
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shizukuShell = null
            shizukuBindRequested = false
            setStatus("חיבור Shizuku נותק. אפשר לנסות להתחבר מחדש.")
        }
    }

    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread {
            try {
                if (!Shizuku.isPreV11() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                ) {
                    bindShizukuService()
                } else {
                    setStatus("Shizuku זוהה. לחץ על ״חבר Shizuku / ADB״ ואשר הרשאה.")
                }
            } catch (e: Exception) {
                setStatus("Shizuku זוהה אך אינו מוכן: " + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_SHIZUKU) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                setStatus("הרשאת Shizuku אושרה. מחבר שירות…")
                bindShizukuService()
            } else {
                setStatus("הרשאת Shizuku נדחתה. לא בוצע שינוי ב-Wi-Fi.")
            }
        }
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) showResults()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        createUi()
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        if (!receiverRegistered) {
            val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(scanReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(scanReceiver, filter)
            }
            receiverRegistered = true
        }
        updateStatus()
        if (Shizuku.pingBinder() && shizukuShell == null && !shizukuBindRequested) {
            try {
                if (!Shizuku.isPreV11() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                ) bindShizukuService()
            } catch (_: Exception) {
                // The explicit Shizuku button displays the actionable error.
            }
        }
    }

    override fun onPause() {
        if (receiverRegistered) {
            unregisterReceiver(scanReceiver)
            receiverRegistered = false
        }
        super.onPause()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(shizukuBinderListener)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        if (shizukuShell != null || shizukuBindRequested) {
            try {
                if (Shizuku.pingBinder()) Shizuku.unbindUserService(shizukuArgs, shizukuConnection, true)
            } catch (_: Exception) {
                // The Shizuku process may have stopped already.
            }
        }
        shizukuShell = null
        shizukuBindRequested = false
        super.onDestroy()
    }

    private fun createUi() {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        val scroll = ScrollView(this)
        scroll.addView(body)
        setContentView(scroll)

        fun text(value: String, size: Float, bold: Boolean = false): TextView {
            return TextView(this).apply {
                text = value
                textSize = size
                gravity = Gravity.RIGHT
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(7), 0, dp(7))
                body.addView(this, matchWidth())
            }
        }
        fun button(label: String, action: () -> Unit) {
            body.addView(Button(this).apply {
                text = label
                setOnClickListener { action() }
            }, matchWidth().apply { bottomMargin = dp(7) })
        }

        text("WiFi Control 1.1", 25f, true)
        text("סריקת רשתות, חיבור וניהול Wi-Fi מתוך האפליקציה.", 15f)
        status = text("בודק מצב Wi-Fi…", 15f, true)

        button("סרוק רשתות Wi-Fi") {
            if (hasScanPermissions()) startScan()
        }
        button("הפעל / כבה Wi-Fi") { toggleWifiWithFallback() }
        button("חבר Shizuku / ADB (מומלץ ללא Root)") { prepareShizukuAccess() }
        button("הורד את Shizuku") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
        }
        button("פתח לוח חיבור Android (חלופה)") { openConnectivityPanel() }

        text("התחברות לרשת שנבחרה", 19f, true)
        selectedLabel = text("לא נבחרה רשת", 14f)
        password = EditText(this).apply {
            hint = "סיסמת הרשת (אם נדרשת)"
            textDirection = View.TEXT_DIRECTION_LTR
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        body.addView(password, matchWidth())
        security = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                listOf("WPA2", "WPA3", "פתוחה")
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        body.addView(security, matchWidth())
        button("התחבר לרשת שנבחרה") {
            if (hasScanPermissions()) connectSelected()
        }

        text("רשתות שנמצאו", 19f, true)
        text("לחץ על רשת כדי לבחור אותה. עוצמת האות מוצגת ב-dBm.", 13f)
        networkList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        body.addView(networkList, matchWidth())
        text(
            "ללא Root אפשר להשתמש ב-Shizuku עם הרשאת ADB מפורשת. " +
                "בלי Root או Shizuku, Android אינה מאפשרת לאפליקציה רגילה להדליק או לכבות Wi-Fi בשקט.",
            12f
        )
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun hasScanPermissions(): Boolean {
        val missing = listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return true
        requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        setStatus("נדרשת הרשאת מיקום כדי לסרוק רשתות Wi-Fi.")
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (hasAllPermissions()) startScan()
            else setStatus("הרשאת המיקום לא אושרה. לא ניתן לסרוק רשתות.")
        }
    }

    private fun hasAllPermissions(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun updateStatus() {
        val enabled = try { wifi.isWifiEnabled } catch (_: Exception) { false }
        val connection = try {
            if (Build.VERSION.SDK_INT >= 23) wifi.connectionInfo?.ssid else null
        } catch (_: Exception) { null }
        status.text = if (enabled) {
            if (!connection.isNullOrBlank() && connection != "<unknown ssid>") "Wi-Fi פעיל • $connection"
            else "Wi-Fi פעיל • לא מחובר לרשת"
        } else "Wi-Fi כבוי"
    }

    private fun setStatus(message: String) {
        runOnUiThread { status.text = message }
    }

    private fun startScan() {
        if (!hasScanPermissions()) return
        val location = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (Build.VERSION.SDK_INT >= 28 && !location.isLocationEnabled) {
            setStatus("יש להפעיל שירותי מיקום במכשיר כדי לקבל תוצאות סריקה.")
            return
        }
        try {
            if (wifi.startScan()) setStatus("הסריקה נשלחה למערכת. ממתין לתוצאות…")
            else {
                setStatus("Android הגביל סריקה חדשה. מציג תוצאות אחרונות.")
                showResults()
            }
        } catch (e: Exception) {
            setStatus("הסריקה נכשלה: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun showResults() {
        val results = try {
            wifi.scanResults.filter { it.SSID.isNotBlank() }
                .distinctBy { it.BSSID }
                .sortedByDescending { it.level }
        } catch (e: Exception) {
            setStatus("לא ניתן לקרוא תוצאות: " + (e.message ?: "שגיאה"))
            emptyList()
        }

        runOnUiThread {
            networkList.removeAllViews()
            if (results.isEmpty()) {
                networkList.addView(TextView(this).apply {
                    text = "לא נמצאו רשתות. בדוק Wi-Fi, הרשאות ושירותי מיקום."
                    textSize = 14f
                })
                return@runOnUiThread
            }
            results.forEach { result ->
                val type = detectSecurity(result)
                networkList.addView(Button(this).apply {
                    text = result.SSID + "\n" + type + " • " + result.level + " dBm • " + result.frequency + " MHz"
                    textSize = 13f
                    gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                    setOnClickListener {
                        selected = result
                        selectedLabel.text = "נבחרה: " + result.SSID + " (" + type + ")"
                        security.setSelection(when (type) {
                            "WPA3" -> 1
                            "פתוחה" -> 2
                            else -> 0
                        })
                    }
                }, matchWidth().apply { bottomMargin = dp(5) })
            }
            setStatus("נמצאו " + results.size + " רשתות Wi-Fi.")
        }
    }

    private fun detectSecurity(result: ScanResult): String {
        val caps = result.capabilities.uppercase(Locale.ROOT)
        return when {
            caps.contains("SAE") || caps.contains("WPA3") -> "WPA3"
            caps.contains("WPA") || caps.contains("WEP") -> "WPA2"
            else -> "פתוחה"
        }
    }

    private fun prepareShizukuAccess() {
        try {
            if (!Shizuku.pingBinder()) {
                setStatus("Shizuku אינו פעיל. התקן את Shizuku, הפעל אותו ואשר Wireless debugging או Root.")
                return
            }
            if (Shizuku.isPreV11()) {
                setStatus("גרסת Shizuku ישנה מדי. עדכן את Shizuku לגרסה עדכנית.")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bindShizukuService()
            } else {
                setStatus("ממתין לאישור הרשאת Shizuku…")
                Shizuku.requestPermission(REQUEST_SHIZUKU)
            }
        } catch (e: Exception) {
            setStatus("לא ניתן להתחבר ל-Shizuku: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun bindShizukuService() {
        if (shizukuShell != null || shizukuBindRequested) return
        if (!Shizuku.pingBinder()) {
            setStatus("Shizuku לא פעיל. פתח את Shizuku והפעל את השירות.")
            return
        }
        shizukuBindRequested = true
        try {
            Shizuku.bindUserService(shizukuArgs, shizukuConnection)
            setStatus("מבקש מ-Shizuku להפעיל שירות Wi-Fi…")
        } catch (e: Exception) {
            shizukuBindRequested = false
            setStatus("הפעלת שירות Shizuku נכשלה: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun toggleWifiWithFallback() {
        val targetEnabled = !isWifiEnabled()
        val targetWord = if (targetEnabled) "enabled" else "disabled"
        setStatus("מנסה " + if (targetEnabled) "להפעיל" else "לכבות" + " Wi-Fi…")
        Thread {
            var success = false
            var detail = ""
            val rootCommands = listOf(
                listOf("/system/bin/cmd", "wifi", "set-wifi-enabled", targetWord),
                listOf("/system/bin/svc", "wifi", if (targetEnabled) "enable" else "disable")
            )
            for (args in rootCommands) {
                val result = runRootCommand(args)
                detail = result.second
                if (result.first == 0 && waitForWifiState(targetEnabled)) {
                    success = true
                    break
                }
            }

            if (!success) {
                val remote = shizukuShell
                if (remote != null) {
                    try {
                        val result = parseRemote(remote.setWifiEnabled(targetEnabled))
                        detail = result.second
                        if (result.first == 0 && waitForWifiState(targetEnabled)) success = true
                    } catch (e: Exception) {
                        detail = e.message ?: e.javaClass.simpleName
                    }
                }
            }

            runOnUiThread {
                if (success) {
                    setStatus(if (targetEnabled) "Wi-Fi הופעל ואומת." else "Wi-Fi כובה ואומת.")
                } else {
                    val accessHint = if (shizukuShell == null) {
                        " אין Root מאושר או חיבור Shizuku. לחץ על ״חבר Shizuku / ADB״ והפעל את השירות."
                    } else {
                        " Shizuku מחובר אך המכשיר חסם את פקודת Wi-Fi."
                    }
                    setStatus("הפעולה לא הצליחה: " + detail + accessHint)
                }
            }
        }.start()
    }

    private fun connectSelected() {
        val item = selected
        if (item == null) {
            setStatus("בחר תחילה רשת מהרשימה.")
            return
        }
        val ssid = item.SSID
        val mode = security.selectedItem?.toString() ?: "WPA2"
        val pass = password.text?.toString().orEmpty()
        if (mode != "פתוחה" && pass.isBlank()) {
            setStatus("הזן סיסמה לרשת המאובטחת.")
            return
        }
        val kind = when (mode) {
            "פתוחה" -> "open"
            "WPA3" -> "wpa3"
            else -> "wpa2"
        }
        setStatus("מנסה להתחבר ל-" + ssid + "…")
        Thread {
            var success = false
            var detail = ""
            val rootArgs = mutableListOf("/system/bin/cmd", "wifi", "connect-network", ssid, kind)
            if (kind != "open") rootArgs.add(pass)
            val rootResult = runRootCommand(rootArgs)
            detail = rootResult.second
            if (rootResult.first == 0 && waitForNetwork(ssid)) success = true

            if (!success) {
                val remote = shizukuShell
                if (remote != null) {
                    try {
                        val remoteResult = parseRemote(remote.connectNetwork(ssid, kind, pass))
                        detail = remoteResult.second
                        if (remoteResult.first == 0 && waitForNetwork(ssid)) success = true
                    } catch (e: Exception) {
                        detail = e.message ?: e.javaClass.simpleName
                    }
                }
            }

            runOnUiThread {
                when {
                    success -> setStatus("התחברת לרשת " + ssid + " ואומת מצב החיבור.")
                    Build.VERSION.SDK_INT >= 29 -> {
                        setStatus("אין הרשאת Root/Shizuku או שהחיבור נחסם. מנסה הצעת רשת רגילה… " + detail)
                        connectSuggestion(ssid, kind, pass)
                    }
                    else -> connectLegacy(ssid, kind, pass)
                }
            }
        }.start()
    }

    private fun connectSuggestion(ssid: String, kind: String, pass: String) {
        try {
            val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)
            when (kind) {
                "wpa2" -> builder.setWpa2Passphrase(pass)
                "wpa3" -> {
                    if (Build.VERSION.SDK_INT < 29) {
                        setStatus("חיבור WPA3 בהצעת רשת דורש Android 10 ומעלה.")
                        return
                    }
                    builder.setWpa3Passphrase(pass)
                }
            }
            val suggestion = builder.build()
            wifi.removeNetworkSuggestions(listOf(suggestion))
            val result = wifi.addNetworkSuggestions(listOf(suggestion))
            if (result == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                setStatus("הצעת הרשת נשלחה ל-Android. המערכת תחליט אם ומתי להתחבר וייתכן שתבקש אישור.")
            } else setStatus("Android דחה את הצעת הרשת. קוד שגיאה: " + result)
        } catch (e: Exception) {
            setStatus("לא ניתן להוסיף הצעת רשת: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    @Suppress("DEPRECATION")
    private fun connectLegacy(ssid: String, kind: String, pass: String) {
        try {
            val config = WifiConfiguration().apply {
                SSID = "\"" + ssid + "\""
                if (kind == "open") allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                else {
                    preSharedKey = "\"" + pass + "\""
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                }
            }
            val id = wifi.addNetwork(config)
            if (id >= 0 && wifi.enableNetwork(id, true)) {
                wifi.reconnect()
                setStatus("נשלחה בקשת חיבור לרשת " + ssid + ".")
            } else setStatus("Android לא הצליח להוסיף או להפעיל את הרשת.")
        } catch (e: Exception) {
            setStatus("החיבור נכשל: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun openConnectivityPanel() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
            } else {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
        } catch (e: Exception) {
            setStatus("לא ניתן לפתוח את לוח הרשת: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun isWifiEnabled(): Boolean =
        try { wifi.isWifiEnabled } catch (_: Exception) { false }

    private fun waitForWifiState(expected: Boolean): Boolean {
        repeat(20) {
            if (isWifiEnabled() == expected) return true
            Thread.sleep(400)
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun waitForNetwork(ssid: String): Boolean {
        repeat(30) {
            try {
                val info = wifi.connectionInfo
                val actual = info?.ssid?.removeSurrounding("\"")
                if (info?.supplicantState == android.net.wifi.SupplicantState.COMPLETED && actual == ssid) {
                    return true
                }
            } catch (_: Exception) {
                // Keep polling; connection information may not be available yet.
            }
            Thread.sleep(500)
        }
        return false
    }

    private fun runRootCommand(args: List<String>): Pair<Int, String> {
        return try {
            val command = args.joinToString(" ") { shellQuote(it) }
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                124 to "Root command timed out or was not approved."
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                process.exitValue() to output
            }
        } catch (e: Exception) {
            1 to (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun parseRemote(raw: String): Pair<Int, String> {
        val firstLine = raw.substringBefore('\n')
        return (firstLine.toIntOrNull() ?: 1) to raw.substringAfter('\n', "").trim()
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 4021
        private const val REQUEST_SHIZUKU = 4022
    }
}
