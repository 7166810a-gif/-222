package com.example.wificontrol

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
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

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                showResults()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        createUi()
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
    }

    override fun onPause() {
        if (receiverRegistered) {
            unregisterReceiver(scanReceiver)
            receiverRegistered = false
        }
        super.onPause()
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

        text("סורק ומנהל Wi-Fi", 25f, true)
        text("סריקת רשתות, בחירת רשת וניסיון התחברות מתוך האפליקציה.", 15f)
        status = text("בודק מצב Wi-Fi…", 15f, true)

        button("סרוק רשתות Wi-Fi") {
            if (hasScanPermissions()) startScan()
        }
        button("הפעל / כבה Wi-Fi (ניסיון Root)") { toggleWifiWithRoot() }

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
            "הערה: הסריקה כפופה להרשאות, לשירותי המיקום ולהגבלות Android. " +
                "ללא Root, המערכת מחליטה אם ומתי להתחבר להצעת רשת.",
            12f
        )
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun hasScanPermissions(): Boolean {
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return true
        requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        setStatus("נדרשת הרשאת מיקום ומכשירים בקרבת מקום כדי לסרוק רשתות.")
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
            else setStatus("ההרשאות לא אושרו. לא ניתן לסרוק רשתות.")
        }
    }

    private fun hasAllPermissions(): Boolean {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false
        return true
    }

    @Suppress("DEPRECATION")
    private fun updateStatus() {
        val enabled = try { wifi.isWifiEnabled } catch (_: Exception) { false }
        status.text = if (enabled) "Wi-Fi פעיל" else "Wi-Fi כבוי"
    }

    private fun setStatus(message: String) {
        runOnUiThread { status.text = message }
    }

    private fun startScan() {
        if (!hasScanPermissions()) return
        val location = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (Build.VERSION.SDK_INT >= 28 && !location.isLocationEnabled) {
            setStatus("כדי לקבל תוצאות סריקה, יש להפעיל שירותי מיקום במכשיר.")
            return
        }
        try {
            if (wifi.startScan()) setStatus("הסריקה נשלחה למערכת. ממתין לתוצאות…")
            else {
                setStatus("המערכת הגבילה סריקה חדשה. מציג תוצאות אחרונות.")
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
                    text = "לא נמצאו רשתות. בדוק ש-Wi-Fi פעיל ונסה שוב."
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

    private fun toggleWifiWithRoot() {
        val enable = try { !wifi.isWifiEnabled } catch (_: Exception) { true }
        setStatus("מנסה לשנות מצב Wi-Fi באמצעות su…")
        Thread {
            val result = runRoot("cmd wifi set-wifi-enabled " + if (enable) "enabled" else "disabled")
                .let { if (it.first) it else runRoot("svc wifi " + if (enable) "enable" else "disable") }
            runOnUiThread {
                if (result.first) {
                    setStatus("פקודת שינוי מצב Wi-Fi התקבלה. בודק מצב…")
                    status.postDelayed({ updateStatus() }, 1800)
                } else setStatus("Root לא זמין או שהפקודה אינה נתמכת. " + result.second)
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
        setStatus("מנסה להתחבר ל-" + ssid + " באמצעות Root…")
        Thread {
            val kind = when (mode) {
                "פתוחה" -> "open"
                "WPA3" -> "wpa3"
                else -> "wpa2"
            }
            var result = runRoot(
                "cmd wifi connect-network " + quote(ssid) + " " + kind +
                    if (kind == "open") "" else " " + quote(pass)
            )
            runOnUiThread {
                if (result.first) {
                    setStatus("פקודת חיבור נשלחה עבור " + ssid + ". בדוק את מצב החיבור במכשיר.")
                    Toast.makeText(this, "נשלחה בקשת חיבור", Toast.LENGTH_SHORT).show()
                } else if (Build.VERSION.SDK_INT >= 29) {
                    setStatus("Root לא זמין. מנסה לשלוח הצעת רשת ל-Android…")
                    connectSuggestion(ssid, kind, pass)
                } else {
                    connectLegacy(ssid, kind, pass)
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
                        setStatus("חיבור WPA3 ללא Root דורש Android 10 ומעלה.")
                        return
                    }
                    builder.setWpa3Passphrase(pass)
                }
            }
            val suggestion = builder.build()
            wifi.removeNetworkSuggestions(listOf(suggestion))
            val result = wifi.addNetworkSuggestions(listOf(suggestion))
            if (result == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                setStatus(
                    "הצעת הרשת " + ssid + " נשלחה ל-Android. המערכת תחליט אם ומתי להתחבר; ייתכן שתידרש הסכמה."
                )
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

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private fun runRoot(command: String): Pair<Boolean, String> {
        return try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val finished = process.waitFor(12, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                false to "הפקודה חרגה ממגבלת הזמן."
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                (process.exitValue() == 0) to output
            }
        } catch (e: Exception) {
            false to (e.message ?: "su אינו זמין")
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 4021
    }
}
