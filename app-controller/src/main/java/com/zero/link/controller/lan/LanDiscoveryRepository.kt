package com.zero.link.controller.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.zero.link.domain.device.DiscoveredDevice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.launch

internal class LanDiscoveryRepository(
    private val context: Context
) {
    private val TAG = "LanDiscovery"
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val resolveMutex = Mutex()

    fun discoverDevices(): Flow<DiscoveredDevice> = callbackFlow {
        Log.d(TAG, "=== 开始通过 mDNS 扫描 ZeroLink 设备 ===")

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType != LanDiscoveryProtocol.SERVICE_TYPE) return
                launch {
                    resolveMutex.withLock {
                        resolveServiceSafety(service)?.let { resolvedInfo ->
                            parseDevice(resolvedInfo)?.let { device ->
                                trySend(device)
                            }
                        }
                    }
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        nsdManager.discoverServices(LanDiscoveryProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

        awaitClose { nsdManager.stopServiceDiscovery(discoveryListener) }
    }

    private suspend fun resolveServiceSafety(service: NsdServiceInfo): NsdServiceInfo? {
        return kotlinx.coroutines.withTimeoutOrNull(3000L) { // 强制 3 秒超时，防止 NsdManager 底层装死导致死锁
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                try {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { 
                            if (cont.isActive) cont.resume(null) 
                        }
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) { 
                            if (cont.isActive) cont.resume(serviceInfo) 
                        }
                    })
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    private fun parseDevice(serviceInfo: NsdServiceInfo): DiscoveredDevice? {
        val hostAddress = serviceInfo.host?.hostAddress ?: return null
        val attrs = serviceInfo.attributes
        val name = attrs["name"]?.decodeToString() ?: "未知设备"
        val model = attrs["model"]?.decodeToString() ?: "Unknown Model"
        val tag = attrs["tag"]?.decodeToString() ?: serviceInfo.serviceName

        return DiscoveredDevice(
            name = name,
            model = model,
            hostTag = tag,
            hostAddress = hostAddress
        )
    }
}
