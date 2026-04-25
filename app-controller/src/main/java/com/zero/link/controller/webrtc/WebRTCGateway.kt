package com.zero.link.controller.webrtc

import android.view.Surface
import com.zero.link.common.result.AppResult
import com.zero.link.common.result.ConnectionError
import com.zero.link.domain.controller.RemoteControlGateway
import com.zero.link.domain.controller.VideoStreamConfig
import com.zero.link.domain.device.DiscoveredDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean

internal class WebRTCGateway(
    private val scope: CoroutineScope
) : RemoteControlGateway {

    private val videoConfigFlow = MutableStateFlow<VideoStreamConfig?>(null)
    private var client: WebRTCClient? = null
    private var currentDevice: DiscoveredDevice? = null
    private val isConnecting = AtomicBoolean(false)
    private var pendingSurface: Surface? = null

    // Default client dimensions
    var clientW: Int = 1080
    var clientH: Int = 2400

    override suspend fun connect(device: DiscoveredDevice): AppResult<Unit, ConnectionError> {
        if (!isConnecting.compareAndSet(false, true)) {
            android.util.Log.w("WebRTCGateway", "Already connecting, skip")
            return AppResult.Failure(ConnectionError.TransportLost("Already connecting", null))
        }

        try {
            client?.disconnect()
            client = null
            videoConfigFlow.value = null
            currentDevice = device

            android.util.Log.d("WebRTCGateway", "=== Connecting to ${device.displayName} at ${device.hostAddress} ===")

            val newClient = WebRTCClient(scope)
            val success = withContext(Dispatchers.IO) {
                // Pass client dimensions to the connect method
                newClient.connect(device, clientW, clientH)
            }

            if (success) {
                client = newClient
                val state = newClient.connectionState.value
                val (w, h) = if (state is WebRTCClient.ConnectionState.Connected) state.width to state.height else 0 to 0
                if (w > 0 && h > 0) {
                    videoConfigFlow.value = VideoStreamConfig(width = w, height = h, fps = 60, rotation = 0)
                }
                scope.launch {
                    newClient.connectionState.collect { s ->
                        if (s is WebRTCClient.ConnectionState.Connected && s.width > 0 && s.height > 0) {
                            videoConfigFlow.value = VideoStreamConfig(width = s.width, height = s.height, fps = 60, rotation = 0)
                        }
                    }
                }
                pendingSurface?.let { surface ->
                    android.util.Log.d("WebRTCGateway", "Passing pending surface to client")
                    newClient.setSurface(surface)
                }
                android.util.Log.i("WebRTCGateway", "WebRTC connection established")
                return AppResult.Success(Unit)
            } else {
                android.util.Log.e("WebRTCGateway", "Connect returned false")
                return AppResult.Failure(ConnectionError.TransportLost("WebRTC handshake failed", null))
            }
        } catch (e: Exception) {
            android.util.Log.e("WebRTCGateway", "Connection exception: ${e.message}")
            return AppResult.Failure(ConnectionError.TransportLost(e.message ?: "Unknown", e))
        } finally {
            isConnecting.set(false)
        }
    }

    fun setSurface(surface: Surface?) {
        pendingSurface = surface
        client?.setSurface(surface)
    }

    override suspend fun reconnect(device: DiscoveredDevice): AppResult<Unit, ConnectionError> {
        disconnect()
        delay(300)
        return connect(device)
    }

    override suspend fun sendTouch(device: DiscoveredDevice, action: Int, pointerId: Int, x: Float, y: Float): AppResult<Unit, ConnectionError> {
        return try {
            client?.sendTouch(action, pointerId, x, y)
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Failure(ConnectionError.TransportLost(e.message ?: "Send touch failed", e))
        }
    }

    override suspend fun sendKey(device: DiscoveredDevice, keyCode: Int): AppResult<Unit, ConnectionError> {
        return try {
            client?.sendKey(keyCode)
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Failure(ConnectionError.TransportLost(e.message ?: "Send key failed", e))
        }
    }

    override fun observeVideoConfig(): StateFlow<VideoStreamConfig?> = videoConfigFlow.asStateFlow()

    override suspend fun sendBlankScreen(blank: Boolean): AppResult<Unit, ConnectionError> {
        return try {
            client?.sendBlankScreen(blank)
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Failure(ConnectionError.TransportLost(e.message ?: "Send blank failed", e))
        }
    }

    override suspend fun sendClipboard(text: String): AppResult<Unit, ConnectionError> {
        return try { client?.sendClipboard(text); AppResult.Success(Unit) } 
        catch (e: Exception) { AppResult.Failure(ConnectionError.TransportLost("Clipboard failed", e)) }
    }
    
    override fun observeClipboard(): kotlinx.coroutines.flow.SharedFlow<String> {
        return client?.clipboardEventFlow ?: kotlinx.coroutines.flow.MutableSharedFlow()
    }

    override fun disconnect() {
        client?.disconnect()
        client = null
        videoConfigFlow.value = null
        isConnecting.set(false)
    }
}
