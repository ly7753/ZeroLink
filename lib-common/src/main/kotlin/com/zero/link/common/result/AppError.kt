package com.zero.link.common.result

/**
 * 所有业务强类型错误的通用防腐边界
 */
sealed interface AppError {
    
    val message: String?
    val throwable: Throwable?
}

/**
 * 伴随异常根节点扩展定义的常见模块错误，例如 ShizukuError 等
 */
sealed interface ConnectionError : AppError {
    data class Timeout(override val message: String = "Connection Timeout", override val throwable: Throwable? = null) : ConnectionError
    data class Unauthorized(override val message: String = "Shizuku Not Authorized", override val throwable: Throwable? = null) : ConnectionError
    data class TransportLost(override val message: String = "Socket or WebRTC Transport Disconnected", override val throwable: Throwable? = null) : ConnectionError
}

sealed interface SystemError : AppError {
    data class EngineNotRunning(override val message: String = "Core Bypass Engine Not Initialized", override val throwable: Throwable? = null) : SystemError
    data class Unknown(override val message: String = "Unknown Side Effect Fallback", override val throwable: Throwable? = null): SystemError
}
