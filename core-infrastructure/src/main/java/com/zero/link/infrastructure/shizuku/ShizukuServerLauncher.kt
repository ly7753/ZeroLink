package com.zero.link.infrastructure.shizuku

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

internal class ShizukuServerLauncher {

    companion object {
        private const val ENTRY_CLASS = "com.zero.link.shizuku.server.ServerMain"
    }

    fun launchServer(maxFps: Int = 60): Process? {
        if (!Shizuku.pingBinder()) return null
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return null

        killExistingServer()

        return try {
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true

            // Use host APK path directly to bypass file copy overhead
            val apkPath = com.zero.link.infrastructure.ShizukuInfraProvider.appContext!!.applicationInfo.sourceDir
            val cmd = "export CLASSPATH=$apkPath; exec app_process / $ENTRY_CLASS --max-fps $maxFps > /data/local/tmp/server.log 2>&1"

            newProcessMethod.invoke(
                null,
                arrayOf("sh", "-c", cmd),
                null,
                null
            ) as? Process
        } catch (e: Exception) {
            android.util.Log.e("ShizukuServerLauncher", "Failed: ${e.message}")
            null
        }
    }

    private fun killExistingServer() {
        try {
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true
            val cmd = "pkill -f 'ServerMain' ; fuser -k 50990/tcp ; fuser -k 50991/tcp ; fuser -k 50992/tcp ; fuser -k 50995/tcp"
            val process = newProcessMethod.invoke(
                null,
                arrayOf("sh", "-c", cmd),
                null,
                null
            ) as? Process
            process?.waitFor()
        } catch (e: Exception) {
            android.util.Log.w("ShizukuServerLauncher", "killExistingServer failed: ${e.message}")
        }
    }
}
