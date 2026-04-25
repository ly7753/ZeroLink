package com.zero.link.shizuku.server

/**
 * Shizuku Server 运行时配置常量。
 * 中继模式移除后，仅保留 DeathWatch 相关配置。
 */
internal object ShizukuServerConfig {
    const val HEARTBEAT_PORT = 50995

    // 心跳配置
    const val HEARTBEAT_INTERVAL_MS = 3000L
    const val HEARTBEAT_TIMEOUT_MS = 10000L
}
