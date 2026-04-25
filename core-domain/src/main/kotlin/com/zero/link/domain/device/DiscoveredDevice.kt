package com.zero.link.domain.device

/**
 * 局域网发现的受控设备领域模型
 */
data class DiscoveredDevice(
    val name: String,
    val model: String,
    val hostTag: String,
    val hostAddress: String
) {
    val displayName: String get() = "$name · $model"
}
