package com.zero.link.domain.controller

import com.zero.link.domain.device.DiscoveredDevice

/**
 * 控制器意图密封接口
 * 所有状态变更必须通过预定义意图触发
 */
sealed interface ControllerIntent {
    data object StartDiscovery : ControllerIntent
    data class ConnectToDevice(val device: DiscoveredDevice) : ControllerIntent
    data object Reconnect : ControllerIntent
    data object Disconnect : ControllerIntent
    data class SendTouch(val action: Int, val pointerId: Int, val x: Float, val y: Float) : ControllerIntent
    data object SendHome : ControllerIntent
    data object SendBack : ControllerIntent
    data object ToggleBlankScreen : ControllerIntent
    data class SyncClipboard(val text: String) : ControllerIntent
}
