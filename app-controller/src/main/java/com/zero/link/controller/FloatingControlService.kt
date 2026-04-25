package com.zero.link.controller

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlin.math.abs

class FloatingControlService : Service() {
    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    private lateinit var params: WindowManager.LayoutParams
    private var isExpanded = false

    companion object {
        const val ACTION_SHOW = "com.zero.link.controller.FLOATING_SHOW"
        const val ACTION_HOME = "com.zero.link.controller.FLOATING_HOME"
        const val ACTION_BACK = "com.zero.link.controller.FLOATING_BACK"
        const val ACTION_EXIT_FULLSCREEN = "com.zero.link.controller.FLOATING_EXIT_FS"
        const val ACTION_TOGGLE_BLANK = "com.zero.link.controller.FLOATING_TOGGLE_BLANK"
    }

    override fun onBind(i: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val chan = NotificationChannel("zl", "ZL", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
        startForeground(1001, NotificationCompat.Builder(this, "zl").setContentTitle("ZeroLink").setSmallIcon(android.R.drawable.ic_menu_compass).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHOW) show()
        return START_STICKY
    }

    private fun show() {
        if (root != null) return
        root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        
        val ball = TextView(this).apply {
            text = "ZL"; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(180, 40, 40, 40)); setStroke(2, Color.WHITE) }
            layoutParams = LinearLayout.LayoutParams(dp(50), dp(50))
        }

        val menu = LinearLayout(this).apply {
            visibility = View.GONE; setPadding(dp(10), 0, dp(10), 0)
            background = GradientDrawable().apply { cornerRadius = dp(25).toFloat(); setColor(Color.argb(200, 30, 30, 30)) }
            addView(makeBtn("返回") { send(ACTION_BACK) })
            addView(makeBtn("主页") { send(ACTION_HOME) })
            addView(makeBtn("退出") { send(ACTION_EXIT_FULLSCREEN) })
            addView(makeBtn("黑屏") { send(ACTION_TOGGLE_BLANK) })
        }

        root!!.addView(ball); root!!.addView(menu)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 100; y = 500 }

        var sx = 0f; var sy = 0f; var px = 0; var py = 0; var moved = false
        ball.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; px = params.x; py = params.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        moved = true; params.x = px + dx.toInt(); params.y = py + dy.toInt()
                        wm.updateViewLayout(root, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) { isExpanded = !isExpanded; menu.visibility = if (isExpanded) View.VISIBLE else View.GONE }
                    true
                }
                else -> false
            }
        }
        wm.addView(root, params)
    }

    private fun makeBtn(txt: String, cb: () -> Unit) = Button(this).apply {
        text = txt; setTextColor(Color.WHITE); background = null; setOnClickListener { cb() }
    }

    private fun send(a: String) = LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(a))
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    
    // [修复]: 更加健壮的安全移除逻辑
    override fun onDestroy() { 
        runCatching { 
            if (root?.isAttachedToWindow == true) {
                wm.removeView(root) 
            }
        }
        super.onDestroy() 
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) { super.onTaskRemoved(rootIntent); stopSelf() } // 划掉后台卡片时，停止前台服务
}
