package com.zero.link.controlled.state

import com.zero.link.common.result.AppResult
import com.zero.link.domain.shizuku.ConnectionSession
import com.zero.link.domain.shizuku.ShizukuRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ControlledIntent {
    data object Bootstrap : ControlledIntent
}

data class ControlledState(
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val isControllerConnected: Boolean = false,
    val lastLog: String = "idle"
)

internal class ControlledStore(
    private val repository: ShizukuRepository,
    private val screenSizeProvider: () -> Pair<Int, Int>,
    private val scope: CoroutineScope
) {
    private val TAG = "ControlledStore"
    private val mutableState = MutableStateFlow(ControlledState())
    val state: StateFlow<ControlledState> = mutableState.asStateFlow()

    @Volatile
    var session: ConnectionSession? = null
        private set

    private var keepAliveJob: Job? = null
    private val connectMutex = Mutex()

    fun dispatch(intent: ControlledIntent) {
        when (intent) {
            is ControlledIntent.Bootstrap -> scope.launch { connectInternal() }
        }
    }

    suspend fun rebuildSessionSync(): Boolean = connectMutex.withLock {
        cleanupSession()
        mutableState.update { it.copy(isConnecting = true, isConnected = false, lastLog = "rebuilding session") }
        return when (val result = repository.connect()) {
            is AppResult.Success -> {
                session = result.data
                mutableState.update { it.copy(isConnecting = false, isConnected = true, lastLog = "reconnected") }
                startKeepAlive()
                true
            }
            is AppResult.Failure -> {
                mutableState.update { it.copy(isConnecting = false, isConnected = false, lastLog = "connect failed") }
                false
            }
        }
    }

    private suspend fun connectInternal() {
        connectMutex.withLock {
            cleanupSession()
            mutableState.update { it.copy(isConnecting = true, isConnected = false, lastLog = "bootstrapping") }
            when (val result = repository.connect()) {
                is AppResult.Success -> {
                    session = result.data
                    mutableState.update { it.copy(isConnecting = false, isConnected = true, lastLog = "engine ready") }
                    startKeepAlive()
                }
                is AppResult.Failure -> {
                    mutableState.update { it.copy(isConnecting = false, isConnected = false, lastLog = "connect failed") }
                }
            }
        }
    }

    private fun cleanupSession() {
        session?.close()
        session = null
    }

    fun shutdown() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        cleanupSession()
    }

    fun setControllerConnected(connected: Boolean) {
        mutableState.update { it.copy(isControllerConnected = connected) }
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (true) {
                delay(5000L)
                val alive = session?.isAlive() == true
                if (!alive) {
                    mutableState.update { it.copy(isConnected = false, lastLog = "engine lost, auto reconnecting...") }
                    if (!rebuildSessionSync()) {
                        mutableState.update { it.copy(isConnected = false, lastLog = "reconnect failed") }
                        cleanupSession()
                        break
                    }
                }
            }
        }
    }
}
