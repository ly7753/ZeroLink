package com.zero.link.domain.controller

import com.zero.link.common.result.AppResult
import com.zero.link.common.result.ConnectionError
import com.zero.link.domain.device.DiscoveredDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 远程控制网关抽象协议
 * 实现类描述具体的执行策略（如 WebRTC 直连）
 */
interface RemoteControlGateway {
    suspend fun connect(device: DiscoveredDevice): AppResult<Unit, ConnectionError>
    suspend fun reconnect(device: DiscoveredDevice): AppResult<Unit, ConnectionError>
    suspend fun sendTouch(device: DiscoveredDevice, action: Int, pointerId: Int, x: Float, y: Float): AppResult<Unit, ConnectionError>
    suspend fun sendKey(device: DiscoveredDevice, keyCode: Int): AppResult<Unit, ConnectionError>
    fun observeVideoConfig(): StateFlow<VideoStreamConfig?>
    suspend fun sendBlankScreen(blank: Boolean): AppResult<Unit, ConnectionError>
    suspend fun sendClipboard(text: String): AppResult<Unit, ConnectionError>
    fun observeClipboard(): kotlinx.coroutines.flow.SharedFlow<String>
    fun disconnect()
}
