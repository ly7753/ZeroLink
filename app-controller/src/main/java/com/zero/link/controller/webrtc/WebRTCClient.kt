package com.zero.link.controller.webrtc

import android.media.*
import android.view.Surface
import com.zero.link.common.protocol.*
import com.zero.link.domain.device.DiscoveredDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import android.os.Handler
import android.os.HandlerThread

internal class WebRTCClient(private val scope: CoroutineScope) {
    private val stopped = AtomicBoolean(false)
    private var controlSocket: Socket? = null
    private var videoSocket: Socket? = null
    private var audioSocket: Socket? = null
    private var controlOutput: OutputStream? = null
    private var controlReader: BufferedReader? = null
    private var codec: MediaCodec? = null
    private var outputSurface: Surface? = null
    
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0
    @Volatile private var needsDecoderReinit = false
    
    @Volatile private var csdBuffer: ByteArray? = null

    private val writerThread = HandlerThread("tcp-writer").also { it.start() }
    private val writerHandler = Handler(writerThread.looper)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    val clipboardEventFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val width: Int, val height: Int) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    var previewWidth = 1080; var previewHeight = 2400
    
    fun connect(device: DiscoveredDevice, clientW: Int, clientH: Int): Boolean {
        if (stopped.get()) return false
        return try {
            _connectionState.value = ConnectionState.Connecting
            previewWidth = clientW; previewHeight = clientH
            
            val targetIp = device.hostAddress
            
            controlSocket = Socket().apply { 
                connect(InetSocketAddress(targetIp, WebRTCProtocol.SIGNALING_PORT), 3000)
                tcpNoDelay = true 
            }
            videoSocket = Socket().apply { 
                connect(InetSocketAddress(targetIp, WebRTCProtocol.VIDEO_PORT), 3000)
                tcpNoDelay = true
                receiveBufferSize = 2 * 1024 * 1024 
            }
            audioSocket = Socket().apply { 
                connect(InetSocketAddress(targetIp, WebRTCProtocol.AUDIO_PORT), 3000)
                tcpNoDelay = true 
            }

            controlOutput = controlSocket!!.getOutputStream()
            controlReader = BufferedReader(InputStreamReader(controlSocket!!.getInputStream()))

            sendLine("CLIENT_SIZE:$previewWidth:$previewHeight")

            val configLine = controlReader!!.readLine() ?: throw IOException("未接收到引擎画面参数")
            if (configLine.startsWith("CONFIG:")) {
                val parts = configLine.substring(7).split(":")
                videoWidth = parts[0].toIntOrNull() ?: 0
                videoHeight = parts[1].toIntOrNull() ?: 0
            }

            scope.launch(Dispatchers.IO) { receiveAndDecodeVideoFlow() }
            scope.launch(Dispatchers.IO) { receiveAndPlayAudioFlow() }
            scope.launch(Dispatchers.IO) { handleSignaling() }
            scope.launch(Dispatchers.IO) { sendHeartbeat() }

            _connectionState.value = ConnectionState.Connected(videoWidth, videoHeight)
            true
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error(e.message ?: "网络直连被拒绝或超时")
            cleanup()
            false
        }
    }

    fun setSurface(surface: Surface?) {
        outputSurface = surface
        val currentCodec = codec
        if (surface == null || !surface.isValid) {
            try { currentCodec?.stop(); currentCodec?.release() } catch (_: Exception) {}
            codec = null
            return
        }
        if (currentCodec != null) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    currentCodec.setOutputSurface(surface)
                    return
                }
            } catch (e: Exception) {}
        }
        needsDecoderReinit = true
    }

    private fun initDecoder(width: Int, height: Int) {
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
        codec = null
        if (outputSurface == null || !outputSurface!!.isValid) return
        
        val csd = csdBuffer ?: return

        try {
            val newCodec = MediaCodec.createDecoderByType("video/avc")
            val fmt = MediaFormat.createVideoFormat("video/avc", width, height)
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            newCodec.configure(fmt, outputSurface, null, 0)
            newCodec.start()
            codec = newCodec
        } catch (e: Exception) { 
            android.util.Log.e("WebRTCClient", "Decoder init failed: ${e.message}")
            codec = null 
        }
    }

    private fun receiveAndDecodeVideoFlow() {
        var input: java.io.DataInputStream? = null
        var frameBuffer = ByteArray(512 * 1024)
        var frameIndex = 0L
        try {
            input = java.io.DataInputStream(videoSocket!!.getInputStream())
            val timeoutUs = 33000L
            val info = android.media.MediaCodec.BufferInfo()

            while (!stopped.get()) {
                val frameSize = input.readInt()
                val frameFlags = input.readInt()
                if (frameSize <= 0 || frameSize > 8 * 1024 * 1024) throw java.io.IOException("Invalid frame size")
                
                if (frameBuffer.size < frameSize) frameBuffer = ByteArray(frameSize + 1024) 
                input.readFully(frameBuffer, 0, frameSize)

                if ((frameFlags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    csdBuffer = frameBuffer.copyOf(frameSize)
                    if (videoWidth > 0 && videoHeight > 0) {
                        initDecoder(videoWidth, videoHeight)
                    }
                    // [修复] 如果配置帧里打包了关键帧（这在某些编码器上常见），绝对不能直接丢弃！
                    if ((frameFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0 && frameSize < 1024) {
                        continue 
                    }
                }

                if (codec == null && (outputSurface == null || !outputSurface!!.isValid)) {
                    continue
                }
                
                if (needsDecoderReinit) {
                    needsDecoderReinit = false
                    if (videoWidth > 0 && videoHeight > 0) initDecoder(videoWidth, videoHeight)
                }
                
                val currentCodec = codec ?: continue

                try {
                    val idx = runCatching { currentCodec.dequeueInputBuffer(timeoutUs) }.getOrDefault(-1)
                    if (idx >= 0) {
                        val buf = currentCodec.getInputBuffer(idx)!!
                        buf.clear(); buf.put(frameBuffer, 0, frameSize)
                        currentCodec.queueInputBuffer(idx, 0, frameSize, frameIndex++ * 16666L, frameFlags)
                    }

                    var outIdx = currentCodec.dequeueOutputBuffer(info, 0)
                    while (outIdx >= 0) {
                        val render = outputSurface?.isValid == true
                        currentCodec.releaseOutputBuffer(outIdx, render)
                        outIdx = currentCodec.dequeueOutputBuffer(info, 0)
                    }
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            android.util.Log.e("WebRTCClient", "Video loop error: ${e.message}")
        } finally {
            if (!stopped.get()) disconnect()
        }
    }

    private fun receiveAndPlayAudioFlow() {
        var track: AudioTrack? = null
        try {
            val input = java.io.DataInputStream(audioSocket!!.getInputStream())
            val sampleRate = 48000
            val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            
            track = AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelConfig, audioFormat, minSize * 4, AudioTrack.MODE_STREAM)
            val buffer = ByteArray(minSize / 2)
            track.play()
            
            while (!stopped.get()) {
                val read = input.read(buffer)
                if (read > 0) track.write(buffer, 0, read)
                else break
            }
        } catch (e: Exception) {
        } finally {
            runCatching { track?.stop(); track?.release() }
        }
    }

    private fun handleSignaling() {
        try {
            while (!stopped.get()) {
                val line = controlReader?.readLine() ?: break
                if (line.startsWith("CONFIG:")) {
                    val parts = line.substring(7).split(":")
                    if (parts.size >= 2) {
                        videoWidth = parts[0].toIntOrNull() ?: videoWidth
                        videoHeight = parts[1].toIntOrNull() ?: videoHeight
                        needsDecoderReinit = true
                        _connectionState.value = ConnectionState.Connected(videoWidth, videoHeight)
                    }
                }
                if (line.startsWith("CLIPBOARD:")) {
                    runCatching {
                        val text = String(android.util.Base64.decode(line.substring(10), android.util.Base64.NO_WRAP))
                        clipboardEventFlow.tryEmit(text)
                    }
                }
            }
        } catch (e: Exception) { 
        } finally {
            if (!stopped.get()) disconnect()
        }
    }

    private suspend fun sendHeartbeat() {
        while (!stopped.get()) {
            try { kotlinx.coroutines.delay(3000L); sendLine("PING") } catch (_: Exception) { break }
        }
    }

    private fun sendLine(line: String) {
        val bytes = (line + "\n").toByteArray()
        writerHandler.post {
            try { controlOutput?.write(bytes); controlOutput?.flush() } catch (_: Exception) {}
        }
    }

    fun sendTouch(action: Int, pointerId: Int, x: Float, y: Float) = sendLine("TOUCH:$action:$pointerId:$x:$y")
    fun sendKey(keyCode: Int) = sendLine("KEY:$keyCode")
    fun sendBlankScreen(blank: Boolean) = sendLine("BLANK:${if(blank) 1 else 0}")
    fun sendClipboard(text: String) {
        val b64 = android.util.Base64.encodeToString(text.toByteArray(), android.util.Base64.NO_WRAP)
        sendLine("CLIPBOARD:$b64")
    }

    fun disconnect() {
        if (stopped.compareAndSet(false, true)) {
            sendLine("BYE")
            writerHandler.removeCallbacksAndMessages(null)
            cleanup()
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private fun cleanup() {
        runCatching { codec?.stop(); codec?.release() }
        runCatching { controlSocket?.close() }
        runCatching { videoSocket?.close() }
        runCatching { audioSocket?.close() }
        codec = null; controlSocket = null; videoSocket = null; audioSocket = null
        writerThread.quitSafely()
    }
}
