package com.zero.link.controller
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.*
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.zero.link.controller.lan.*
import com.zero.link.controller.state.ControllerStore
import com.zero.link.controller.webrtc.WebRTCGateway
import com.zero.link.domain.controller.ControllerIntent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged

class MainActivity : ComponentActivity() {
    private lateinit var webrtcGateway: WebRTCGateway
    private lateinit var store: ControllerStore

    private lateinit var deviceListView: LinearLayout; private lateinit var remoteContainer: FrameLayout
    
    private lateinit var previewView: SurfaceView; 
    private lateinit var hintText: TextView; private lateinit var deviceListLayout: LinearLayout
    private var streamWidth = 0; private var streamHeight = 0; private var previewTouchReady = false

    private val floatingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                FloatingControlService.ACTION_EXIT_FULLSCREEN -> store.dispatch(ControllerIntent.Disconnect)
                FloatingControlService.ACTION_HOME -> store.dispatch(ControllerIntent.SendHome)
                FloatingControlService.ACTION_BACK -> store.dispatch(ControllerIntent.SendBack)
                FloatingControlService.ACTION_TOGGLE_BLANK -> store.dispatch(ControllerIntent.ToggleBlankScreen)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(18, 24, 38); window.navigationBarColor = Color.rgb(18, 24, 38)

        webrtcGateway = WebRTCGateway(lifecycleScope)
        
        // 【终极修复】：获取绝对物理分辨率，杜绝 2200 这种残缺高度导致乱压尺寸！
        val realMetrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(realMetrics)
        webrtcGateway.clientW = realMetrics.widthPixels
        webrtcGateway.clientH = realMetrics.heightPixels

        store = ControllerStore(LanDiscoveryRepository(applicationContext), webrtcGateway, lifecycleScope)

        deviceListLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(24)) }

        deviceListView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(18, 24, 38))
            addView(TextView(context).apply { text = "ZeroLink 虚拟机列表"; textSize = 28f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD); setPadding(dp(24), dp(20), 0, dp(4)) })
            addView(ScrollView(context).apply { addView(deviceListLayout) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        previewView = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    previewTouchReady = true; applyPreviewTransform(); webrtcGateway.setSurface(holder.surface)
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) { applyPreviewTransform() }
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    previewTouchReady = false; webrtcGateway.setSurface(null)
                }
            })
            setOnTouchListener { _, e -> handleTouch(e) }
        }
        
        hintText = TextView(this).apply { text = "等待连接建立..."; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; layoutParams = FrameLayout.LayoutParams(-1, -1) }
        remoteContainer = FrameLayout(this).apply { 
            visibility = View.GONE; setBackgroundColor(Color.BLACK); 
            addView(previewView); addView(hintText)
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyPreviewTransform() }
        }
        setContentView(FrameLayout(this).apply { addView(deviceListView); addView(remoteContainer) })

        val filter = IntentFilter().apply { 
            addAction(FloatingControlService.ACTION_EXIT_FULLSCREEN); addAction(FloatingControlService.ACTION_HOME)
            addAction(FloatingControlService.ACTION_BACK); addAction(FloatingControlService.ACTION_TOGGLE_BLANK)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(floatingReceiver, filter)
        
        lifecycleScope.launch {
            store.state.distinctUntilChanged { old, new -> old.isConnected == new.isConnected && old.isConnecting == new.isConnecting && old.discoveredDevices == new.discoveredDevices }.collect { s ->
                if (s.isConnected) enterVmos() else exitVmos()
                if (!s.isConnected && !s.isConnecting && s.lastMessage.contains("fail", true)) Toast.makeText(this@MainActivity, "连接失败: 目标设备不可达", Toast.LENGTH_SHORT).show()
                renderDevices(s.discoveredDevices)
            }
        }
        lifecycleScope.launch { store.videoConfig.collect { c ->
            if (c != null && c.width > 0) {
                streamWidth = c.width; streamHeight = c.height
                applyPreviewTransform(); hintText.visibility = View.GONE
            }
        } }

        store.dispatch(ControllerIntent.StartDiscovery)

        var lastLocalClipboard = ""
        lifecycleScope.launch {
            store.clipboardFlow.collect { text ->
                if (text != lastLocalClipboard) {
                    lastLocalClipboard = text
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("ZeroLink", text))
                    Toast.makeText(this@MainActivity, "远端已复制", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.addPrimaryClipChangedListener {
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString() ?: ""
                if (text.isNotEmpty() && text != lastLocalClipboard) {
                    lastLocalClipboard = text
                    store.dispatch(ControllerIntent.SyncClipboard(text))
                }
            }
        }
    }

    private fun renderDevices(devices: List<com.zero.link.domain.device.DiscoveredDevice>) {
        deviceListLayout.removeAllViews()
        if (devices.isEmpty()) { deviceListLayout.addView(TextView(this).apply { text = "局域网搜索中..."; setTextColor(Color.GRAY) }); return }
        devices.forEach { d -> deviceListLayout.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.rgb(32, 40, 56)) }
            isClickable = true; setOnClickListener { store.dispatch(ControllerIntent.ConnectToDevice(d)) }
            addView(TextView(context).apply { text = d.name; textSize = 18f; setTextColor(Color.WHITE); setTypeface(null, Typeface.BOLD) })
            addView(TextView(context).apply { text = "${d.model} • 点击进入系统"; textSize = 13f; setTextColor(Color.rgb(47, 124, 246)); setPadding(0, dp(4), 0, 0) })
        })}
    }

    private fun enterVmos() {
        if (remoteContainer.visibility == View.VISIBLE) return
        deviceListView.visibility = View.GONE; remoteContainer.visibility = View.VISIBLE
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        
        if (Build.VERSION.SDK_INT >= 30) { 
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { 
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE 
            } 
        } else { 
            @Suppress("DEPRECATION") 
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY) 
        }
        if (Settings.canDrawOverlays(this)) { val i = Intent(this, FloatingControlService::class.java).apply { action = FloatingControlService.ACTION_SHOW }; if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i) } else { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
    }

    private fun exitVmos() {
        deviceListView.visibility = View.VISIBLE; remoteContainer.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        if (Build.VERSION.SDK_INT >= 30) { window.setDecorFitsSystemWindows(true); window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()) }
        else { @Suppress("DEPRECATION") window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE }
    }

    private fun applyPreviewTransform() {
        val vw = remoteContainer.width.toFloat()
        val vh = remoteContainer.height.toFloat()
        if (vw <= 0f || vh <= 0f || streamWidth <= 0 || streamHeight <= 0) return

        var finalW = streamWidth.toFloat()
        var finalH = streamHeight.toFloat()

        val fillRatio = maxOf(finalW / vw, finalH / vh)

        if (fillRatio > 1.0f) {
            val scale = 1.0f / fillRatio
            finalW *= scale; finalH *= scale
        } else if (fillRatio < 0.65f) {
            val scale = 1.0f / fillRatio
            finalW *= scale; finalH *= scale
        }

        val lp = previewView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != finalW.toInt() || lp.height != finalH.toInt()) {
            lp.width = finalW.toInt()
            lp.height = finalH.toInt()
            lp.gravity = Gravity.CENTER
            previewView.layoutParams = lp
        }
    }

    private fun handleTouch(e: MotionEvent): Boolean {
        if (!previewTouchReady || streamWidth <= 0 || previewView.width <= 0) return false
        var p = previewView.parent; while (p != null) { p.requestDisallowInterceptTouchEvent(true); p = p.parent }

        val actionMasked = e.actionMasked
        val pointerIndex = e.actionIndex
        val pointerId = e.getPointerId(pointerIndex)

        if (actionMasked == MotionEvent.ACTION_MOVE) {
            for (i in 0 until e.pointerCount) {
                val pid = e.getPointerId(i)
                val nx = (e.getX(i) / previewView.width).coerceIn(0f, 1f)
                val ny = (e.getY(i) / previewView.height).coerceIn(0f, 1f)
                store.dispatch(ControllerIntent.SendTouch(actionMasked, pid, nx, ny))
            }
        } else {
            val nx = (e.getX(pointerIndex) / previewView.width).coerceIn(0f, 1f)
            val ny = (e.getY(pointerIndex) / previewView.height).coerceIn(0f, 1f)
            store.dispatch(ControllerIntent.SendTouch(actionMasked, pointerId, nx, ny))
        }
        return true
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus && remoteContainer.visibility == View.VISIBLE) { if (Build.VERSION.SDK_INT >= 30) { window.insetsController?.let { it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()); it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE } } else { @Suppress("DEPRECATION") window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY) } } }
    override fun onDestroy() { super.onDestroy(); LocalBroadcastManager.getInstance(this).unregisterReceiver(floatingReceiver); runCatching { stopService(Intent(this, FloatingControlService::class.java)) } }
}
