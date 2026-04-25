package com.zero.link.domain.controller

/**
 * 视频流配置领域值对象
 */
data class VideoStreamConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val rotation: Int,
    val rebind: Int = 0
)
