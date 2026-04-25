package com.zero.link.shizuku.server.gl
import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.*
import android.view.Surface
import java.nio.*
import java.util.concurrent.atomic.AtomicBoolean

class GlFrameRenderer(private val encW: Int, private val encH: Int) : SurfaceTexture.OnFrameAvailableListener {
    private var eglD = EGL14.EGL_NO_DISPLAY; private var eglC = EGL14.EGL_NO_CONTEXT; private var eglS = EGL14.EGL_NO_SURFACE
    private var prog = 0; private var st: SurfaceTexture? = null; private var surf: Surface? = null
    private val avail = AtomicBoolean(false); private var thread: HandlerThread? = null
    private val posB = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().put(floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f)).also { it.position(0) }
    private val texB = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().put(floatArrayOf(0f,0f, 1f,0f, 0f,1f, 1f,1f)).also { it.position(0) }

    private val frameLock = java.lang.Object()
    
    // [修复核心]：管线冲刷计数器
    private var duplicateCount = 0
    private val MAX_DUP = 8 

    fun initialize(encSurf: Surface, screenW: Int, screenH: Int) {
        thread = HandlerThread("GL", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        while (thread?.looper == null) Thread.sleep(5)
        eglD = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2); EGL14.eglInitialize(eglD, v, 0, v, 1)
        val cfg = arrayOfNulls<EGLConfig>(1)
        EGL14.eglChooseConfig(eglD, intArrayOf(EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGLExt.EGL_RECORDABLE_ANDROID,1,EGL14.EGL_RENDERABLE_TYPE,4,EGL14.EGL_NONE), 0, cfg, 0, 1, IntArray(1), 0)
        eglC = EGL14.eglCreateContext(eglD, cfg[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        eglS = EGL14.eglCreateWindowSurface(eglD, cfg[0], encSurf, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(eglD, eglS, eglS, eglC)
        val vs = "uniform mat4 uM; uniform mat4 uS; attribute vec4 aP; attribute vec4 aT; varying vec2 vT; void main() { gl_Position = uM * aP; vT = (uS * aT).xy; }"
        val fs = "#extension GL_OES_EGL_image_external : require\nprecision mediump float;\nvarying vec2 vT;\nuniform samplerExternalOES s;\nvoid main() { gl_FragColor = texture2D(s, vT); }" 
        prog = GLES20.glCreateProgram().also { 
            GLES20.glAttachShader(it, compile(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(it, compile(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(it) 
        }
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0])
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        
        st = SurfaceTexture(t[0]).apply { 
            setDefaultBufferSize(screenW, screenH)
            setOnFrameAvailableListener(this@GlFrameRenderer, Handler(thread!!.looper)) 
        }
        surf = Surface(st); GLES20.glViewport(0, 0, encW, encH)
    }

    fun updateSourceSize(w: Int, h: Int) { st?.setDefaultBufferSize(w, h) }
    fun getInputSurface(): Surface = surf!!
    
    override fun onFrameAvailable(s: SurfaceTexture) { 
        synchronized(frameLock) {
            avail.set(true)
            (frameLock as java.lang.Object).notifyAll()
        }
    }

    fun awaitAndDraw(rot: Float, timeoutMs: Long): Boolean {
        synchronized(frameLock) {
            if (!avail.get()) {
                try { (frameLock as java.lang.Object).wait(timeoutMs) } catch (e: Exception) {}
            }
        }
        
        val s = st ?: return false
        val timestamp = System.nanoTime() // 必须使用单调递增时间，否则编码器会丢弃复制帧
        
        if (!avail.get()) {
            // [修复核心]：如果等不到新画面，且还没冲刷够 8 帧，我们就把老画面再画一遍塞给编码器
            if (duplicateCount < 30) {
                duplicateCount++
                drawTexture(s, rot, timestamp)
                return true
            }
            return false
        }
        
        avail.set(false)
        duplicateCount = 0 // 拿到真实新画面，计数器清零
        
        try { s.updateTexImage() } catch (e: Exception) { return false }
        
        drawTexture(s, rot, timestamp)
        return true
    }

    private fun drawTexture(s: SurfaceTexture, rot: Float, timestamp: Long) {
        val tm = FloatArray(16)
        s.getTransformMatrix(tm)
        GLES20.glUseProgram(prog)
        
        val mvp = FloatArray(16)
        Matrix.setIdentityM(mvp, 0)
        if (rot != 0f) Matrix.rotateM(mvp, 0, rot, 0f, 0f, 1f)
        
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uM"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uS"), 1, false, tm, 0)
        val ph = GLES20.glGetAttribLocation(prog, "aP"); GLES20.glEnableVertexAttribArray(ph); GLES20.glVertexAttribPointer(ph, 2, GLES20.GL_FLOAT, false, 8, posB)
        val th = GLES20.glGetAttribLocation(prog, "aT"); GLES20.glEnableVertexAttribArray(th); GLES20.glVertexAttribPointer(th, 2, GLES20.GL_FLOAT, false, 8, texB)
        
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        
        EGLExt.eglPresentationTimeANDROID(eglD, eglS, timestamp) 
        EGL14.eglSwapBuffers(eglD, eglS)
    }

    private fun compile(t: Int, s: String): Int { val id = GLES20.glCreateShader(t); GLES20.glShaderSource(id, s); GLES20.glCompileShader(id); return id }
    fun destroy() {
        runCatching { 
            EGL14.eglMakeCurrent(eglD, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglD, eglS); EGL14.eglDestroyContext(eglD, eglC); EGL14.eglTerminate(eglD)
            st?.release(); surf?.release(); thread?.quitSafely()
        }
    }
}
