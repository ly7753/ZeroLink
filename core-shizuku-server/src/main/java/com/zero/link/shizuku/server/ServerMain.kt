package com.zero.link.shizuku.server

import android.media.*
import android.os.*
import com.zero.link.common.protocol.*
import com.zero.link.shizuku.server.capture.DisplayCaptureController
import com.zero.link.shizuku.server.gl.GlFrameRenderer
import com.zero.link.shizuku.server.inject.InputInjector
import com.zero.link.shizuku.server.system.BlankScreenController
import com.zero.link.shizuku.server.system.ShellEnvironment
import java.net.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.*
import java.util.concurrent.atomic.AtomicBoolean

object ServerMain {
    private val stopped = AtomicBoolean(false)

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
            ShellEnvironment.bypassHiddenApi()
            ShellEnvironment.applyWorkarounds()
            val dc = DisplayCaptureController(ShellEnvironment.getDisplayManagerGlobal())
            dc.refreshDisplayInfo()

            Thread({ runHeartbeatServer() }, "heartbeat-worker").start()
            Thread({ runNioStreamServer(dc) }, "nio-stream-server").start()
            Thread({ runAudioServer() }, "audio-stream-server").start()
            
            Looper.loop()
        } catch (t: Throwable) { 
            t.printStackTrace() 
            Thread.sleep(2000)
            Runtime.getRuntime().exit(1)
        }
    }

    private fun runHeartbeatServer() {
        val server = ServerSocketChannel.open()
        server.socket().reuseAddress = true
        server.bind(InetSocketAddress("0.0.0.0", ShizukuServerConfig.HEARTBEAT_PORT))
        while (!stopped.get()) {
            try {
                val client = server.accept()
                Channels.newInputStream(client).read()
                Runtime.getRuntime().exit(0)
            } catch (e: Exception) {
                Runtime.getRuntime().exit(0)
            }
        }
    }

    private fun runAudioServer() {
        try {
            val audioServer = ServerSocketChannel.open().apply { socket().reuseAddress = true; bind(InetSocketAddress(WebRTCProtocol.AUDIO_PORT)) }
            while (!stopped.get()) {
                try {
                    val client = audioServer.accept()
                    Thread({ handleAudioClient(client) }, "audio-client").start()
                } catch (e: Exception) {}
            }
        } catch (e: Exception) { }
    }

    private fun handleAudioClient(channel: SocketChannel) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        var record: AudioRecord? = null
        try {
            channel.socket().tcpNoDelay = true
            channel.socket().sendBufferSize = 1024 * 1024
            
            val sampleRate = 48000
            val channelConfig = AudioFormat.CHANNEL_IN_STEREO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            
            record = AudioRecord(8, sampleRate, channelConfig, audioFormat, minSize * 4)
            if (record!!.state != AudioRecord.STATE_INITIALIZED) return
            
            record!!.startRecording()
            val buffer = ByteBuffer.allocateDirect(minSize / 2)
            while (!stopped.get() && channel.isOpen) {
                buffer.clear()
                val read = record!!.read(buffer, buffer.capacity())
                if (read > 0) {
                    buffer.limit(read)
                    while (buffer.hasRemaining() && channel.isOpen) channel.write(buffer)
                }
            }
        } catch (e: Exception) {
        } finally {
            runCatching { record?.stop(); record?.release() }
            runCatching { channel.close() }
        }
    }

    private fun startClipboardSync(writer: PrintWriter, stopFlag: AtomicBoolean) {
        Thread({
            try {
                val cm = ShellEnvironment.getSystemContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                var lastText = ""
                while (!stopFlag.get()) {
                    val clip = cm.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        val text = clip.getItemAt(0).text?.toString() ?: ""
                        if (text.isNotEmpty() && text != lastText) {
                            lastText = text
                            val b64 = android.util.Base64.encodeToString(text.toByteArray(), android.util.Base64.NO_WRAP)
                            writer.println("CLIPBOARD:$b64")
                        }
                    }
                    Thread.sleep(1500)
                }
            } catch (e: Exception) {}
        }, "clipboard-sync").start()
    }

    private fun runNioStreamServer(dc: DisplayCaptureController) {
        try {
            val controlServer = ServerSocketChannel.open().apply { socket().reuseAddress = true; bind(InetSocketAddress(WebRTCProtocol.SIGNALING_PORT)) }
            val videoServer = ServerSocketChannel.open().apply { socket().reuseAddress = true; bind(InetSocketAddress(WebRTCProtocol.VIDEO_PORT)) }

            while (!stopped.get()) {
                try {
                    val controlChannel = controlServer.accept()
                    val videoChannel = videoServer.accept()
                    Thread({ handleClient(controlChannel, videoChannel, dc) }, "nio-client").start()
                } catch (e: Exception) { }
            }
        } catch (e: Exception) { }
    }

    private fun handleClient(controlChannel: SocketChannel, videoChannel: SocketChannel, dc: DisplayCaptureController) {
        var codec: MediaCodec? = null
        var renderer: GlFrameRenderer? = null
        
        try {
            controlChannel.socket().tcpNoDelay = true
            videoChannel.socket().tcpNoDelay = true
            videoChannel.socket().sendBufferSize = 2 * 1024 * 1024 

            val reader = BufferedReader(InputStreamReader(Channels.newInputStream(controlChannel)))
            val writer = PrintWriter(Channels.newOutputStream(controlChannel), true)

            var clientW = 1080
            var clientH = 2400
            val firstLine = reader.readLine()
            if (firstLine != null && firstLine.startsWith("CLIENT_SIZE:")) {
                val parts = firstLine.substring(12).split(":")
                if (parts.size >= 2) {
                    clientW = parts[0].toIntOrNull() ?: 1080
                    clientH = parts[1].toIntOrNull() ?: 2400
                }
            }

            dc.refreshDisplayInfo()
            val physW = dc.physicalWidth
            val physH = dc.physicalHeight
            
            var finalEncodeW = physW
            var finalEncodeH = physH
            if (finalEncodeW > clientW || finalEncodeH > clientH) {
                val scale = minOf(clientW.toFloat() / physW, clientH.toFloat() / physH)
                finalEncodeW = (physW * scale).toInt()
                finalEncodeH = (physH * scale).toInt()
            }

            // 【终极修复】：严格 16 像素 (Macroblock) 对齐！
            // shr 4 shl 4: 右移 4 位再左移 4 位，完美干掉不能被 16 整除的余数。
            // 2340 -> 2336，2400 -> 2400。
            finalEncodeW = (finalEncodeW shr 4) shl 4
            finalEncodeH = (finalEncodeH shr 4) shl 4

            codec = MediaCodec.createEncoderByType("video/avc")
            val caps = codec.codecInfo.getCapabilitiesForType("video/avc")
            val maxBitrate = caps.videoCapabilities.bitrateRange.upper ?: 10_000_000
            var configured = false
            
            val configs = listOf(
                Pair(minOf(25_000_000, maxBitrate), 60),
                Pair(minOf(15_000_000, maxBitrate), 60),
                Pair(minOf(8_000_000, maxBitrate), 30)
            )

            for ((bit, fps) in configs) {
                try {
                    val fmt = MediaFormat.createVideoFormat("video/avc", finalEncodeW, finalEncodeH).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_BIT_RATE, bit)
                        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                        setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD, 60)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        }
                        if (caps.encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
                            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && 
                            caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
                            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                        }
                    }
                    codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    configured = true
                    break
                } catch (e: Exception) {
                    codec.reset()
                }
            }
            if (!configured) throw RuntimeException("All encoder configs failed")

            renderer = GlFrameRenderer(finalEncodeW, finalEncodeH)
            renderer.initialize(codec.createInputSurface(), dc.screenWidth, dc.screenHeight)
            dc.bindCaptureSurface(renderer.getInputSurface())
            codec.start()
            
            writer.println("CONFIG:${finalEncodeW}:${finalEncodeH}:${dc.rotation}")
            
            val injector = InputInjector()
            injector.wakeUp()
            
            Thread({ handleSignaling(reader, writer, dc, injector) }, "nio-sig-handler").start()
            startClipboardSync(writer, stopped)

            val info = MediaCodec.BufferInfo()
            val headerBuf = ByteBuffer.allocateDirect(8)
            var frameCount = 0

            while (!stopped.get() && controlChannel.isOpen && videoChannel.isOpen) {
                if (frameCount++ % 60 == 0) dc.refreshDisplayInfo()
                if (dc.consumePendingRebind()) {
                    dc.refreshDisplayInfo() 
                    renderer.updateSourceSize(dc.screenWidth, dc.screenHeight)
                    dc.bindCaptureSurface(renderer.getInputSurface())
                }
                
                val drawRot = dc.rotation * -90f
                val hasNewFrame = renderer.awaitAndDraw(drawRot, 33)
                
                var idx = codec.dequeueOutputBuffer(info, if (hasNewFrame) 0 else 10000)
                while (idx >= 0) {
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(idx)
                        if (buf != null) {
                            buf.position(info.offset).limit(info.offset + info.size)
                            headerBuf.clear()
                            headerBuf.putInt(info.size)
                            headerBuf.putInt(info.flags)
                            headerBuf.flip()
                            val buffers = arrayOf(headerBuf, buf)
                            while (headerBuf.hasRemaining() || buf.hasRemaining()) {
                                videoChannel.write(buffers)
                            }
                        }
                    }
                    codec.releaseOutputBuffer(idx, false)
                    idx = codec.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (e: Exception) {
        } finally {
            dc.releaseCaptureSurface()
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            renderer?.destroy()
            BlankScreenController.restoreIfNeed()
            runCatching { videoChannel.close() }; runCatching { controlChannel.close() }
        }
    }

    private fun handleSignaling(
        reader: BufferedReader, writer: PrintWriter,
        dc: DisplayCaptureController, injector: InputInjector
    ) {
        try {
            while (!stopped.get()) {
                val line = reader.readLine() ?: break
                when {
                    line.startsWith("TOUCH:") -> {
                        val parts = line.substring(6).split(":")
                        if (parts.size >= 4) {
                            val action = parts[0].toInt(); val pointerId = parts[1].toInt()
                            val nx = parts[2].toFloat(); val ny = parts[3].toFloat()
                            
                            val physW = dc.physicalWidth
                            val physH = dc.physicalHeight
                            
                            val physX = nx * (physW - 1)
                            val physY = ny * (physH - 1)
                            
                            val logX: Float
                            val logY: Float
                            when (dc.rotation) {
                                1 -> { logX = physY; logY = (physW - 1) - physX }
                                2 -> { logX = (physW - 1) - physX; logY = (physH - 1) - physY }
                                3 -> { logX = (physH - 1) - physY; logY = physX }
                                else -> { logX = physX; logY = physY }
                            }
                            
                            injector.injectTouch(action, pointerId, logX, logY)
                        }
                    }
                    line.startsWith("BLANK:") -> {
                        val blank = line.substring(6) == "1"
                        BlankScreenController.setBlank(blank)
                    }
                    line.startsWith("KEY:") -> {
                        val keyCode = line.substring(4).toInt()
                        val downTime = SystemClock.uptimeMillis()
                        runCatching {
                            val keClass = Class.forName("android.view.KeyEvent")
                            val obtain = keClass.getDeclaredMethod("obtain", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                            injector.injectEvent(obtain.invoke(null, downTime, downTime, 0, keyCode, 0) as android.view.InputEvent, 0)
                            injector.injectEvent(obtain.invoke(null, downTime, SystemClock.uptimeMillis(), 1, keyCode, 0) as android.view.InputEvent, 0)
                        }
                    }
                    line.startsWith("CLIPBOARD:") -> {
                        runCatching {
                            val b64 = line.substring(10)
                            val text = String(android.util.Base64.decode(b64, android.util.Base64.NO_WRAP))
                            val cm = ShellEnvironment.getSystemContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("ZeroLink", text))
                        }
                    }
                    line == "PING" -> writer.println("PONG")
                    line == "BYE" -> break
                }
            }
        } catch (e: Exception) {}
    }
}
