package com.zero.link.controlled.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

internal class DeviceAdvertiser(
    private val context: Context,
    private val deviceNameProvider: () -> String,
    private val deviceIdProvider: () -> String
) {
    private val TAG = "DeviceAdvertiser"
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun start() {
        if (registrationListener != null) return

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "ZeroLink_${deviceIdProvider().take(6)}"
            serviceType = LanDiscoveryProtocol.SERVICE_TYPE
            port = LanDiscoveryProtocol.CONTROL_PORT
            
            setAttribute("name", deviceNameProvider())
            setAttribute("model", android.os.Build.MODEL)
            setAttribute("tag", deviceIdProvider())
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                Log.i(TAG, "mDNS 广播已启动: ${NsdServiceInfo.serviceName}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "mDNS 注册失败, 错误码: $errorCode")
            }
            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Log.i(TAG, "mDNS 广播已停止")
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "mDNS 取消注册失败: $errorCode")
            }
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    fun stop() {
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {
                Log.w(TAG, "取消注册异常: ${e.message}")
            }
            registrationListener = null
        }
    }
}
