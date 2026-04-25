package com.zero.link.controlled.shizuku

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuPermissionHelper {
    private const val REQ_CODE = 1001

    fun isShizukuActive(): Boolean {
        return runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    }

    fun ensurePermission(
        onGranted: () -> Unit,
        onDenied: () -> Unit
    ) {
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                onGranted()
                return
            }

            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != REQ_CODE) return
                    Shizuku.removeRequestPermissionResultListener(this)
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        onGranted()
                    } else {
                        onDenied()
                    }
                }
            }

            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(REQ_CODE)
        } catch (e: Exception) {
            android.util.Log.w("ShizukuPermissionHelper", "Permission request failed: ${e.message}")
            onDenied()
        }
    }
}
