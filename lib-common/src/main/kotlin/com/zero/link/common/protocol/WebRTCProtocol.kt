package com.zero.link.common.protocol

object WebRTCProtocol {
    const val SIGNALING_PORT = 50990  // TCP 控制与信令端口
    const val VIDEO_PORT = 50991      // TCP 裸流媒体端口
    const val AUDIO_PORT = 50992      // TCP 音频内录透传端口
    const val HEARTBEAT_PORT = 50995  // TCP DeathWatch
}
