package com.example.wificontrol

import android.system.Os
import java.util.concurrent.TimeUnit

class WifiShellUserService : IWifiShellService.Stub() {
    override fun runCommand(command: String?): String {
        if (command.isNullOrBlank()) return "2\nEmpty command"
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(10, TimeUnit.SECONDS)
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

    override fun getUid(): Int = Os.getuid()

    override fun destroy() {
        System.exit(0)
    }
}
