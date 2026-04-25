package com.zero.link.infrastructure.shizuku

import rikka.shizuku.Shizuku
import com.zero.link.domain.shizuku.*
import com.zero.link.common.result.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*
import java.net.*
import java.nio.channels.*
import com.zero.link.infrastructure.ShizukuInfraProvider

internal class ShizukuInfrastructureRepository(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : ShizukuRepository {
    private val serverLauncher = ShizukuServerLauncher()
    private var heartbeatJob: Job? = null
    private var heartbeatSocket: SocketChannel? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    companion object {
        const val HEARTBEAT_PORT = 50995
    }

    override suspend fun connect(): AppResult<ConnectionSession, ConnectionError> {
        return withContext(Dispatchers.IO) {
            try {
                _connectionState.value = ConnectionState.Connecting
                if (!Shizuku.pingBinder()) {
                    _connectionState.value = ConnectionState.Error("Shizuku dead")
                    return@withContext AppResult.Failure(ConnectionError.TransportLost("Shizuku dead", null))
                }

                // Store reference to the active socket instance
                var activeSocket = heartbeatSocket

                if (heartbeatJob == null || activeSocket?.isOpen != true) {
                    runCatching { activeSocket?.close() }
                    serverLauncher.launchServer()

                    var hbConnected = false
                    for (i in 1..40) {
                        try {
                            val socket = SocketChannel.open(InetSocketAddress("127.0.0.1", HEARTBEAT_PORT))
                            socket.socket().tcpNoDelay = true;
                            activeSocket = socket // 赋值给外部变量
                            startHeartbeat(socket)
                            hbConnected = true
                            break
                        } catch (e: Exception) {
                            if (i == 40) {
                                android.util.Log.w("ShizukuRepo", "DeathWatch connect attempt $i failed: ${e.message}")
                            }
                            Thread.sleep(50)
                        }
                    }
                    if (!hbConnected) {
                        return@withContext AppResult.Failure(ConnectionError.TransportLost("DeathWatch connect failed", null))
                    }
                }

                _connectionState.value = ConnectionState.Connected
                                AppResult.Success(SimpleConnectionSession(this@ShizukuInfrastructureRepository, activeSocket))
            } catch (e: Exception) {
                android.util.Log.e("ShizukuRepo", "Connect failed: ${e.message}", e)
                _connectionState.value = ConnectionState.Error(e.message ?: "Unknown")
                AppResult.Failure(ConnectionError.TransportLost(e.message ?: "Unknown", e))
            }
        }
    }

    private fun startHeartbeat(socket: SocketChannel) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            try {
                heartbeatSocket = socket
                Channels.newInputStream(socket).read()
            } catch (e: Exception) {
                android.util.Log.w("ShizukuRepo", "DeathWatch broken: ${e.message}")
                _connectionState.value = ConnectionState.Error("NIO DeathWatch broken")
            }
        }
    }

    fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatSocket?.close()
        heartbeatSocket = null
    }

    fun disconnect() {
        stopHeartbeat()
        _connectionState.value = ConnectionState.Disconnected
    }
}

internal sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

internal class SimpleConnectionSession(
    private val repository: ShizukuInfrastructureRepository,
    private val heartbeatSocket: SocketChannel?
) : ConnectionSession {
    override fun isAlive(): Boolean = heartbeatSocket?.isOpen == true
    override fun close() {
        repository.stopHeartbeat()
    }
}
