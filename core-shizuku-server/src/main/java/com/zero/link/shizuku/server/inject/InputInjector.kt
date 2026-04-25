package com.zero.link.shizuku.server.inject

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.os.Binder
import android.os.Looper
import android.os.SystemClock
import android.util.SparseArray
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import com.zero.link.shizuku.server.system.ShellEnvironment
import java.lang.reflect.Method

class FakeContext(base: Context) : ContextWrapper(base) {
    override fun getPackageName(): String = "com.android.shell"
    override fun getOpPackageName(): String = "com.android.shell"
    @SuppressLint("NewApi")
    override fun getAttributionSource(): AttributionSource = AttributionSource.Builder(2000).setPackageName("com.android.shell").build()
}

internal class InputInjector {
    private val inputManager: Any
    private val injectInputEventMethod: Method
    
    private val pointers = SparseArray<PointerCoords>()
    private val properties = SparseArray<MotionEvent.PointerProperties>()
    private var downTime: Long = 0

    class PointerCoords(var x: Float, var y: Float, var pressure: Float)

    init {
        if (Looper.myLooper() == null) Looper.prepare()
        val sysContext = ShellEnvironment.getSystemContext()
        Binder.clearCallingIdentity()
        FakeContext(sysContext)

        try {
            val vm = Class.forName("dalvik.system.VMRuntime")
            val rt = vm.getDeclaredMethod("getRuntime").invoke(null)
            vm.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java).invoke(rt, arrayOf("L"))
            
            val binder = Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String::class.java).invoke(null, "input") as android.os.IBinder
            inputManager = Class.forName("android.hardware.input.IInputManager\$Stub").getDeclaredMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)!!
            injectInputEventMethod = inputManager.javaClass.methods.first { it.name == "injectInputEvent" && it.parameterTypes.size >= 2 }
        } catch (e: Exception) {
            throw RuntimeException("Init Failed: ${e.message}", e)
        }
    }

    fun injectEvent(event: InputEvent, mode: Int = 0): Boolean {
        return try {
            if (injectInputEventMethod.parameterTypes.size == 2) injectInputEventMethod.invoke(inputManager, event, mode) as Boolean
            else injectInputEventMethod.invoke(inputManager, event, mode, 0) as Boolean
        } catch (e: Exception) { false }
    }

    // [修复] 新增亮屏接口，给系统一个激灵，强制刷新一次屏幕
    fun wakeUp() {
        try {
            val keClass = Class.forName("android.view.KeyEvent")
            val obtain = keClass.getDeclaredMethod("obtain", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val time = SystemClock.uptimeMillis()
            val eventDown = obtain.invoke(null, time, time, 0, 224, 0) as InputEvent // 224 = KEYCODE_WAKEUP
            val eventUp = obtain.invoke(null, time, time, 1, 224, 0) as InputEvent
            injectEvent(eventDown, 0)
            injectEvent(eventUp, 0)
        } catch (e: Exception) {}
    }

    fun injectTouch(actionMasked: Int, pointerId: Int, x: Float, y: Float) {
        val eventTime = SystemClock.uptimeMillis()

        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointers.clear(); properties.clear()
                downTime = eventTime
                addPointer(pointerId, x, y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> addPointer(pointerId, x, y)
            MotionEvent.ACTION_MOVE -> {
                pointers.get(pointerId)?.let { it.x = x; it.y = y }
            }
        }

        val pointerCount = pointers.size()
        if (pointerCount == 0) return

        val propsArray = Array(pointerCount) { MotionEvent.PointerProperties() }
        val coordsArray = Array(pointerCount) { MotionEvent.PointerCoords() }
        var actionIndex = -1

        for (i in 0 until pointerCount) {
            val id = pointers.keyAt(i)
            if (id == pointerId) actionIndex = i
            
            propsArray[i].apply { this.id = id; this.toolType = MotionEvent.TOOL_TYPE_FINGER }
            coordsArray[i].apply { 
                val pc = pointers.valueAt(i)
                this.x = pc.x; this.y = pc.y; this.pressure = pc.pressure; this.size = pc.pressure * 0.12f
            }
        }

        var finalAction = actionMasked
        if (actionMasked == MotionEvent.ACTION_POINTER_DOWN || actionMasked == MotionEvent.ACTION_POINTER_UP) {
            if (actionIndex != -1) {
                finalAction = (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) or actionMasked
            }
        }

        val event = MotionEvent.obtain(
            downTime, eventTime, finalAction, pointerCount, 
            propsArray, coordsArray, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        injectEvent(event, 0)
        event.recycle()

        if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
            pointers.clear(); properties.clear()
        } else if (actionMasked == MotionEvent.ACTION_POINTER_UP) {
            val indexToRemove = pointers.indexOfKey(pointerId)
            if (indexToRemove >= 0) {
                pointers.removeAt(indexToRemove)
                properties.removeAt(indexToRemove)
            }
        }
    }

    private fun addPointer(id: Int, x: Float, y: Float) {
        pointers.put(id, PointerCoords(x, y, 1.0f))
        properties.put(id, MotionEvent.PointerProperties().apply { this.id = id })
    }
}
