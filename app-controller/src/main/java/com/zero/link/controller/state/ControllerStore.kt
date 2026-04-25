package com.zero.link.controller.state

import com.zero.link.common.result.AppResult
import com.zero.link.common.result.ConnectionError
import com.zero.link.controller.lan.LanDiscoveryRepository
import com.zero.link.domain.controller.ControllerIntent
import com.zero.link.domain.controller.ControllerState
import com.zero.link.domain.controller.RemoteControlGateway
import com.zero.link.domain.controller.VideoStreamConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal class ControllerStore(
    private val discoveryRepository: LanDiscoveryRepository,
    private var gateway: RemoteControlGateway,
    private val scope: CoroutineScope
) {
    private val mutableState = MutableStateFlow(ControllerState())
    val state: StateFlow<ControllerState> = mutableState.asStateFlow()

    private val _videoConfig = MutableStateFlow<VideoStreamConfig?>(null)
    val videoConfig: StateFlow<VideoStreamConfig?> = _videoConfig.asStateFlow()
    val clipboardFlow = MutableSharedFlow<String>()
    private var gatewaySubJob: Job? = null
    private var discoveryJob: Job? = null
    private var isBlanked = false

    fun switchGateway(newGateway: RemoteControlGateway) {
        if (mutableState.value.isConnected) {
            gateway.disconnect()
            mutableState.update { it.copy(isConnected = false, selectedDevice = null) }
        }
        gateway = newGateway
        _videoConfig.value = null
        gatewaySubJob?.cancel()
        gatewaySubJob = scope.launch {
            val g = gateway // 快照，避免并发修改竞态
            launch { g.observeVideoConfig().collect { _videoConfig.value = it } }
            launch { g.observeClipboard().collect { clipboardFlow.emit(it) } }
        }
    }

    init {
        gatewaySubJob = scope.launch {
            val g = gateway // 快照，避免并发修改竞态
            launch { g.observeVideoConfig().collect { _videoConfig.value = it } }
            launch { g.observeClipboard().collect { clipboardFlow.emit(it) } }
        }
    }

    fun dispatch(intent: ControllerIntent) {
        when (intent) {
            is ControllerIntent.StartDiscovery -> {
                android.util.Log.i("ControllerStore", "Starting device discovery...")
                if (discoveryJob == null) {
                    discoveryJob = scope.launch {
                        android.util.Log.d("ControllerStore", "Discovery coroutine started")
                        discoveryRepository.discoverDevices().collect { d ->
                            android.util.Log.d("ControllerStore", "Updating device list with: ${d.displayName}")
                            mutableState.update { it.copy(discoveredDevices = (it.discoveredDevices + d).distinctBy { it.hostTag }) }
                            android.util.Log.i("ControllerStore", "Device list updated: ${mutableState.value.discoveredDevices.size} devices")
                        }
                    }
                } else {
                    android.util.Log.w("ControllerStore", "Discovery already running")
                }
            }
            is ControllerIntent.ConnectToDevice -> {
                android.util.Log.i("ControllerStore", "Connecting to ${intent.device.displayName}...")
                scope.launch {
                    mutableState.update { it.copy(isConnecting = true) }
                    val result = try {
                        withTimeout(15000) {
                            gateway.connect(intent.device)
                        }
                    } catch (e: TimeoutCancellationException) {
                        android.util.Log.e("ControllerStore", "Connection timeout")
                        AppResult.Failure(ConnectionError.Timeout("Connection timeout", e))
                    } catch (e: Exception) {
                        android.util.Log.e("ControllerStore", "Connection exception: ${e.message}", e)
                        AppResult.Failure(ConnectionError.TransportLost(e.message ?: "Unknown", e))
                    }
                    val ok = result is AppResult.Success
                    android.util.Log.i("ControllerStore", "Connection result: $ok")
                    mutableState.update {
                        it.copy(
                            isConnecting = false,
                            isConnected = ok,
                            lastMessage = if (ok) "connected" else connectionErrorToMessage((result as? AppResult.Failure)?.error),
                            selectedDevice = if (ok) intent.device else null
                        )
                    }
                }
            }
            is ControllerIntent.Reconnect -> {
                android.util.Log.i("ControllerStore", "Reconnecting...")
                val sel = mutableState.value.selectedDevice ?: return
                scope.launch {
                    mutableState.update { it.copy(isConnecting = true) }
                    val result = gateway.reconnect(sel)
                    val ok = result is AppResult.Success
                    mutableState.update {
                        it.copy(
                            isConnecting = false,
                            isConnected = ok,
                            lastMessage = if (ok) "reconnected" else connectionErrorToMessage((result as? AppResult.Failure)?.error)
                        )
                    }
                }
            }
            is ControllerIntent.Disconnect -> {
                android.util.Log.i("ControllerStore", "Disconnecting...")
                gateway.disconnect()
                mutableState.update { it.copy(isConnected = false) }
            }
            is ControllerIntent.SendTouch -> {
                scope.launch {
                    val sel = mutableState.value.selectedDevice ?: return@launch
                    gateway.sendTouch(sel, intent.action, intent.pointerId, intent.x, intent.y)
                }
            }
            is ControllerIntent.SendHome -> scope.launch {
                gateway.sendKey(mutableState.value.selectedDevice ?: return@launch, 3)
            }
            is ControllerIntent.SendBack -> scope.launch {
                gateway.sendKey(mutableState.value.selectedDevice ?: return@launch, 4)
            }
            is ControllerIntent.ToggleBlankScreen -> scope.launch {
                isBlanked = !isBlanked
                gateway.sendBlankScreen(isBlanked)
            }
            is ControllerIntent.SyncClipboard -> scope.launch {
                gateway.sendClipboard(intent.text)
            }
        }
    }

    private fun connectionErrorToMessage(error: ConnectionError?): String {
        return when (error) {
            is ConnectionError.Timeout -> "connect timeout"
            is ConnectionError.Unauthorized -> "permission denied"
            is ConnectionError.TransportLost -> "transport lost"
            null -> "unknown error"
        }
    }
}
