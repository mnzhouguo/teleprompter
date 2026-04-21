package com.example.teleprompter

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 豆包大模型流式语音识别 WebSocket 客户端。
 *
 * 协议简介(火山引擎自定义的二进制帧格式):
 *   [4字节 header] + [4字节 payload size (big-endian)] + [payload(gzip 压缩)]
 *
 * Header 4 字节含义:
 *   byte 0: 高 4 位 = 协议版本(固定 0b0001), 低 4 位 = header 大小(固定 0b0001 表示 4 字节)
 *   byte 1: 高 4 位 = 消息类型, 低 4 位 = 类型特定 flags
 *   byte 2: 高 4 位 = 序列化方法(0b0001 = JSON), 低 4 位 = 压缩方式(0b0001 = gzip)
 *   byte 3: 保留字节,填 0
 *
 * 消息类型:
 *   0b0001 = FULL_CLIENT_REQUEST  (首包,传参数 JSON)
 *   0b0010 = AUDIO_ONLY_REQUEST   (音频数据,低 4 位 flag=0b0010 表示最后一包)
 *   0b1001 = FULL_SERVER_RESPONSE (服务端识别结果)
 *   0b1111 = SERVER_ERROR_RESPONSE
 *
 * 交互流程:
 *   1. WebSocket 握手时带鉴权 header(Authorization/X-Api-*)
 *   2. 发首包 FULL_CLIENT_REQUEST(JSON,含音频格式/识别参数)
 *   3. 循环发 AUDIO_ONLY_REQUEST(每 100ms 一包 PCM)
 *   4. 服务端每收到音频就推 FULL_SERVER_RESPONSE(含增量/全量识别文本)
 *
 * 注意:真实生产环境要有重连/错误处理/签名过期刷新等,Demo 只保留主干。
 */
class DoubaoAsrClient(
    private val appId: String,       // 火山引擎控制台创建应用时分配
    private val accessToken: String, // 同上
    private val onText: (text: String, isFinal: Boolean) -> Unit,
    private val onError: (msg: String) -> Unit
) {
    companion object {
        private const val URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel"

        // 协议常量
        private const val PROTOCOL_VERSION = 0b0001
        private const val HEADER_SIZE = 0b0001
        private const val FULL_CLIENT_REQUEST = 0b0001
        private const val AUDIO_ONLY_REQUEST = 0b0010
        private const val FULL_SERVER_RESPONSE = 0b1001
        private const val SERVER_ERROR_RESPONSE = 0b1111

        private const val NO_SEQUENCE = 0b0000
        private const val NEG_SEQUENCE = 0b0010  // 最后一包音频的 flag

        private const val JSON_SERIALIZATION = 0b0001
        private const val GZIP_COMPRESSION = 0b0001
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)  // 防止中间网络设备断连
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    @Volatile private var sessionReady = false

    fun connect() {
        // 为本次识别生成唯一 ID(调试和排障用)
        val requestId = UUID.randomUUID().toString()

        val request = Request.Builder()
            .url(URL)
            .addHeader("X-Api-App-Key", appId)
            .addHeader("X-Api-Access-Key", accessToken)
            .addHeader("X-Api-Resource-Id", "volc.bigasr.sauc.duration")
            .addHeader("X-Api-Request-Id", requestId)
            // Connect-Id 可选,服务端日志里用来定位问题
            .addHeader("X-Api-Connect-Id", UUID.randomUUID().toString())
            .build()

        webSocket = client.newWebSocket(request, listener)
    }

    /**
     * 发送一包 PCM 音频(被 AudioCapture 周期性调用)
     */
    fun sendAudio(pcm: ByteArray, isLast: Boolean = false) {
        if (!sessionReady) return  // 首包响应前不发音频

        val header = buildHeader(
            messageType = AUDIO_ONLY_REQUEST,
            flags = if (isLast) NEG_SEQUENCE else NO_SEQUENCE
        )
        val payload = gzip(pcm)
        val frame = assembleFrame(header, payload)
        webSocket?.send(frame.toByteString())
    }

    fun close() {
        webSocket?.close(1000, "client_close")
        webSocket = null
        sessionReady = false
    }

    // ===================== 私有 =====================

    private val listener = object : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            android.util.Log.i("DoubaoASR", "WebSocket 已连接，发送首包")
            sendFullClientRequest()
        }

        override fun onMessage(ws: WebSocket, bytes: ByteString) {
            parseServerResponse(bytes.toByteArray())
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            android.util.Log.e("DoubaoASR", "WebSocket 失败: ${t.message}  HTTP=${response?.code}")
            onError("WebSocket 失败: ${t.message}")
            sessionReady = false
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            android.util.Log.i("DoubaoASR", "WebSocket 关闭: $code $reason")
            sessionReady = false
        }
    }

    /** 首包:JSON 配置 */
    private fun sendFullClientRequest() {
        val json = JSONObject().apply {
            put("user", JSONObject().apply {
                put("uid", "teleprompter_user_${System.currentTimeMillis()}")
            })
            put("audio", JSONObject().apply {
                put("format", "pcm")
                put("rate", AudioCapture.SAMPLE_RATE)
                put("bits", 16)
                put("channel", 1)
                put("codec", "raw")
            })
            put("request", JSONObject().apply {
                // 增量返回:服务端只推送新识别到的部分,省带宽也利于对齐算法
                put("result_type", "single")
                // 启用中间结果(边说边推,比等整句结束再推延迟低很多)
                put("show_utterances", false)
                put("enable_itn", true)        // 逆文本归一化,"一九七零"→"1970"
                put("enable_punc", false)      // 提词场景不需要标点
            })
        }.toString()

        val header = buildHeader(messageType = FULL_CLIENT_REQUEST)
        val payload = gzip(json.toByteArray(Charsets.UTF_8))
        val frame = assembleFrame(header, payload)
        webSocket?.send(frame.toByteString())

        // 豆包不需要等服务端 ack,直接标记 ready 即可开始送音频
        sessionReady = true
    }

    /** 解析服务端下行包 */
    private fun parseServerResponse(bytes: ByteArray) {
        if (bytes.size < 4) return

        val messageType = (bytes[1].toInt() and 0xFF) ushr 4
        val flags       = bytes[1].toInt() and 0x0F
        val compression = bytes[2].toInt() and 0x0F

        // 响应帧 flags bit0=1 时带 sequence number（4B），布局为：
        // [4B header][4B seq][4B payloadSize][payload]  → payload 从 byte 12 起
        // 否则标准布局：[4B header][4B payloadSize][payload] → payload 从 byte 8 起
        val payloadOffset = if ((flags and 0x01) != 0) 12 else 8
        val payloadSize   = ByteBuffer.wrap(bytes, payloadOffset - 4, 4).int

        if (bytes.size < payloadOffset + payloadSize) return
        val rawPayload = bytes.copyOfRange(payloadOffset, payloadOffset + payloadSize)

        val payload = if (compression == GZIP_COMPRESSION) ungzip(rawPayload) else rawPayload

        when (messageType) {
            FULL_SERVER_RESPONSE -> {
                val text = String(payload, Charsets.UTF_8)
                android.util.Log.d("DoubaoASR", "服务端响应: $text")
                try {
                    val result = JSONObject(text)
                    val resultObj = result.optJSONObject("result") ?: return
                    val asrText = resultObj.optString("text", "")
                    val isFinal = resultObj.optBoolean("definite", false)
                    if (asrText.isNotEmpty()) {
                        android.util.Log.i("DoubaoASR", "识别结果: \"$asrText\"  final=$isFinal")
                        onText(asrText, isFinal)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("DoubaoASR", "响应解析失败: ${e.message}  raw=$text")
                }
            }
            SERVER_ERROR_RESPONSE -> {
                val errText = String(payload, Charsets.UTF_8)
                android.util.Log.e("DoubaoASR", "服务端错误: $errText")
                onError("服务端错误: $errText")
            }
        }
    }

    /** 构造 4 字节 header */
    private fun buildHeader(messageType: Int, flags: Int = NO_SEQUENCE): ByteArray {
        return byteArrayOf(
            ((PROTOCOL_VERSION shl 4) or HEADER_SIZE).toByte(),
            ((messageType shl 4) or flags).toByte(),
            ((JSON_SERIALIZATION shl 4) or GZIP_COMPRESSION).toByte(),
            0x00                                            // reserved
        )
    }

    /** 拼接完整帧: header(4) + payload_size(4, big-endian) + payload */
    private fun assembleFrame(header: ByteArray, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(header.size + 4 + payload.size)
        out.write(header)
        out.write(ByteBuffer.allocate(4).putInt(payload.size).array())
        out.write(payload)
        return out.toByteArray()
    }

    private fun gzip(raw: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(raw) }
        return bos.toByteArray()
    }

    private fun ungzip(raw: ByteArray): ByteArray {
        return GZIPInputStream(raw.inputStream()).use { it.readBytes() }
    }
}
