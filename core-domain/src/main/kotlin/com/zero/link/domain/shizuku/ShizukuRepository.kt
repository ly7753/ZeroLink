package com.zero.link.domain.shizuku

import com.zero.link.common.result.AppResult
import com.zero.link.common.result.ConnectionError

/**
 * Shizuku 会话抽象协议
 * 经过极致架构瘦身，目前仅负责生命周期与 DeathWatch 维持。
 */
interface ConnectionSession {
    fun isAlive(): Boolean
    fun close()
}

interface ShizukuRepository {
    suspend fun connect(): AppResult<ConnectionSession, ConnectionError>
}
