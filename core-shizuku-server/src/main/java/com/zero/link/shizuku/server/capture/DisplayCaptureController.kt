package com.zero.link.shizuku.server.capture
import android.graphics.Rect
import android.os.IBinder
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

class DisplayCaptureController(private val displayManagerGlobal: Any) {
    private val needsRebind = AtomicBoolean(false)
    var screenWidth = 0; var screenHeight = 0; var rotation = 0; var layerStack = 0
    private var displayToken: IBinder? = null

    // 提取出绝对的物理尺寸（总是竖屏尺寸）
    val physicalWidth: Int get() = if (rotation == 1 || rotation == 3) screenHeight else screenWidth
    val physicalHeight: Int get() = if (rotation == 1 || rotation == 3) screenWidth else screenHeight

    fun refreshDisplayInfo() {
        try {
            val displayInfo = displayManagerGlobal.javaClass.getMethod("getDisplayInfo", Int::class.java).invoke(displayManagerGlobal, 0)
            val newW = readIntField(displayInfo, "logicalWidth")
            val newH = readIntField(displayInfo, "logicalHeight")
            val newRot = readIntField(displayInfo, "rotation")
            val newLayer = readIntField(displayInfo, "layerStack")
            if (screenWidth != newW || screenHeight != newH || rotation != newRot || layerStack != newLayer) {
                screenWidth = newW
                screenHeight = newH
                rotation = newRot
                layerStack = newLayer
                needsRebind.set(true)
            }
        } catch (e: Exception) {}
    }

    fun bindCaptureSurface(surface: Surface) {
        needsRebind.set(false)
        if (displayToken == null) {
            displayToken = try {
                Class.forName("android.window.DisplayControl").getMethod("createDisplay", String::class.java, Boolean::class.java).invoke(null, "ZeroLink", false) as IBinder
            } catch (e: Exception) {
                Class.forName("android.view.SurfaceControl").getMethod("createDisplay", String::class.java, Boolean::class.java).invoke(null, "ZeroLink", false) as IBinder
            }
        }
        try {
            val transactionClass = Class.forName("android.view.SurfaceControl\$Transaction")
            val transaction = transactionClass.getConstructor().newInstance()
            transactionClass.getMethod("setDisplaySurface", IBinder::class.java, Surface::class.java).invoke(transaction, displayToken, surface)
            
            // 修正：老老实实捕获当前的逻辑尺寸，不强行裁剪！
            val rect = Rect(0, 0, screenWidth, screenHeight)
            transactionClass.getMethod("setDisplayProjection", IBinder::class.java, Int::class.java, Rect::class.java, Rect::class.java)
                .invoke(transaction, displayToken, 0, rect, rect)
            transactionClass.getMethod("setDisplayLayerStack", IBinder::class.java, Int::class.java).invoke(transaction, displayToken, layerStack)
            transactionClass.getMethod("apply").invoke(transaction)
        } catch (e: Exception) {}
    }

    fun releaseCaptureSurface() {
        displayToken?.let { token ->
            runCatching { Class.forName("android.view.SurfaceControl").getMethod("destroyDisplay", IBinder::class.java).invoke(null, token) }
            displayToken = null
        }
    }
    fun consumePendingRebind(): Boolean = needsRebind.getAndSet(false)
    private fun readIntField(target: Any, name: String): Int { val f = target.javaClass.getDeclaredField(name); f.isAccessible = true; return f.getInt(target) }
}
