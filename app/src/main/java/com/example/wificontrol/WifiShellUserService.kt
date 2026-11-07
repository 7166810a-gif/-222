package com.example.wificontrol

import android.system.Os
import java.util.concurrent.TimeUnit

class WifiShellUserService : IWifiShellService.Stub() {

    override fun setWifiEnabled(enabled: Boolean): String {
        val state = if (enabled) "enabled" else "disabled"
        return execute(arrayOf("/system/bin/cmd", "wifi", "set-wifi-enabled", state))
    }

    override fun connectNetwork(ssid: String?, securityType: String?, password: String?): String {
        if (ssid.isNullOrEmpty()) return "2\nSSID is empty"
        val type = securityType?.lowercase() ?: return "2\nSecurity type is missing"
        if (type !in setOf("open", "owe", "wpa2", "wpa3", "wep")) {
            return "2\nUnsupported security type"
        }
        if (type != "open" && type != "owe" && password.isNullOrEmpty()) {
            return "2\nPassword is required"
        }
        val args = mutableListOf("/system/bin/cmd", "wifi", "connect-network", ssid, type)
        if (type != "open" && type != "owe") args.add(password!!)
        return execute(args.toTypedArray())
    }

    override fun getUid(): Int = Os.getuid()

    private fun execute(args: Array<String>): String {
        return try {
            val process = ProcessBuilder(*args).redirectErrorStream(true).start()
            val finished = process.waitFor(12, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                "124\nCommand timed out"
            } else {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                process.exitValue().toString() + "\n" + output
            }
        } catch (e: Exception) {
            "1\n" + (e.message ?: e.javaClass.simpleName)
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}
