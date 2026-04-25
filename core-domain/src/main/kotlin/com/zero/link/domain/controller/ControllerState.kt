package com.zero.link.domain.controller

import com.zero.link.domain.device.DiscoveredDevice

/**
 * 控制器表现层单一状态源
 */
data class ControllerState(
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val lastMessage: String = "idle",
    val discoveredDevices: List<DiscoveredDevice> = emptyList(),
    val selectedDevice: DiscoveredDevice? = null
)
