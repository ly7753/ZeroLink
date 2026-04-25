package com.zero.link.shizuku.server.system

import android.os.Build
import android.os.IBinder
import java.lang.reflect.Method

object BlankScreenController {
    private var isBlanked = false
    private const val POWER_MODE_OFF = 0
    private const val POWER_MODE_NORMAL = 2

    fun setBlank(blank: Boolean) {
        if (isBlanked == blank) return
        try {
            val token = getPhysicalDisplayToken() ?: return
            val surfaceControlClass = Class.forName("android.view.SurfaceControl")
            val setDisplayPowerMode: Method = surfaceControlClass.getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType)
            
            val mode = if (blank) POWER_MODE_OFF else POWER_MODE_NORMAL
            setDisplayPowerMode.invoke(null, token, mode)
            isBlanked = blank
            System.err.println("[*] Screen blank state set to: $blank")
        } catch (e: Exception) {
            System.err.println("[!] Failed to set blank screen: ${e.message}")
        }
    }

    private fun getPhysicalDisplayToken(): IBinder? {
        try {
            val surfaceControlClass = Class.forName("android.view.SurfaceControl")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val getPhysicalDisplayIds = surfaceControlClass.getMethod("getPhysicalDisplayIds")
                val ids = getPhysicalDisplayIds.invoke(null) as LongArray
                if (ids.isNotEmpty()) {
                    val getPhysicalDisplayToken = surfaceControlClass.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
                    return getPhysicalDisplayToken.invoke(null, ids[0]) as IBinder
                }
            } else {
                val getInternalDisplayToken = surfaceControlClass.getMethod("getInternalDisplayToken")
                return getInternalDisplayToken.invoke(null) as IBinder
            }
        } catch (e: Exception) {
            System.err.println("[!] Failed to get display token: ${e.message}")
        }
        return null
    }
    
    // 异常断开时强制恢复屏幕，防止手机永远黑屏变砖
    fun restoreIfNeed() {
        if (isBlanked) setBlank(false)
    }
}
