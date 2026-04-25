package com.zero.link.controlled

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zero.link.controlled.lan.DeviceAdvertiser
import com.zero.link.controlled.shizuku.ShizukuPermissionHelper
import com.zero.link.controlled.state.ControlledIntent
import com.zero.link.controlled.state.ControlledStore
import com.zero.link.infrastructure.ShizukuInfraProvider
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var store: ControlledStore
    private lateinit var advertiser: DeviceAdvertiser
    
    private lateinit var statusTitle: TextView
    private lateinit var statusDesc: TextView
    private lateinit var shizukuLabel: TextView
    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.zero.link.infrastructure.ShizukuInfraProvider.initialize(applicationContext)

        advertiser = DeviceAdvertiser(applicationContext, { getString(R.string.app_name) }, { Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: android.os.Build.DEVICE })
        val repo = ShizukuInfraProvider.createRepository()
        val screenSizeProvider: () -> Pair<Int, Int> = { resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels }
        store = ControlledStore(repo, screenSizeProvider, lifecycleScope)

        val bgColor = Color.parseColor("#000000")
        val cardColor = Color.parseColor("#1C1C1E")
        val accentColor = Color.parseColor("#0A84FF")

        statusTitle = TextView(this).apply { textSize = 22f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        statusDesc = TextView(this).apply { textSize = 15f; setTextColor(Color.parseColor("#EBEBF5")); alpha = 0.6f; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(16)) }
        shizukuLabel = TextView(this).apply { textSize = 13f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setTextColor(Color.WHITE); setPadding(dp(12), dp(6), dp(12), dp(6)); background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(Color.parseColor("#333336")) } }
        logText = TextView(this).apply { textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(Color.parseColor("#34C759")); alpha = 0.8f; gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(32), dp(48), dp(32), dp(48))
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(cardColor) }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dp(24), 0, dp(24), 0) }
            addView(shizukuLabel, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = dp(24) })
            addView(statusTitle); addView(statusDesc); addView(logText)
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(bgColor); addView(card) }
        setContentView(root)

        bootstrapShizuku()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                store.state.collect { uiState ->
                    when {
                        uiState.isControllerConnected -> { statusTitle.text = "控制中"; statusTitle.setTextColor(Color.parseColor("#34C759")); statusDesc.text = "远程控制会话已建立" }
                        uiState.isConnected -> { statusTitle.text = "引擎就绪"; statusTitle.setTextColor(Color.parseColor("#34C759")); statusDesc.text = "等待控制端接入" }
                        uiState.isConnecting -> { statusTitle.text = "引擎启动中..."; statusTitle.setTextColor(accentColor); statusDesc.text = "正在连接 Shizuku 服务" }
                        else -> { statusTitle.text = "局域网广播中"; statusTitle.setTextColor(Color.WHITE); statusDesc.text = "静待连接，像夜海收起浪声" }
                    }
                    if (uiState.lastLog.isNotBlank() && uiState.lastLog != "idle") logText.text = "> ${uiState.lastLog}"
                }
            }
        }

        lifecycleScope.launch { advertiser.start(); try { kotlinx.coroutines.awaitCancellation() } finally { advertiser.stop() } }
    }

    private fun bootstrapShizuku() {
        if (!ShizukuPermissionHelper.isShizukuActive()) { shizukuLabel.text = "⚠ Shizuku 未激活"; shizukuLabel.setTextColor(Color.parseColor("#FF453A")); return }
        ShizukuPermissionHelper.ensurePermission(
            onGranted = { shizukuLabel.text = "✓ 引擎已授权"; shizukuLabel.setTextColor(Color.WHITE); (shizukuLabel.background as GradientDrawable).setColor(Color.parseColor("#0A84FF")); store.dispatch(ControlledIntent.Bootstrap) },
            onDenied = { shizukuLabel.text = "✗ 权限被拒绝"; shizukuLabel.setTextColor(Color.parseColor("#FF453A")) }
        )
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
    
    override fun onDestroy() {
        super.onDestroy()
        // Shutdown store and disconnect server on destroy
        if (::store.isInitialized) store.shutdown()
    }
}
