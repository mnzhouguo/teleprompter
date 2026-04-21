package com.example.teleprompter

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class AudioCapture(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CHUNK_SIZE_BYTES = SAMPLE_RATE * 2 / 10  // 100ms
        private const val TAG = "AudioCapture"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var scoReceiver: BroadcastReceiver? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * 开始采集。自动优先使用蓝牙 SCO 耳机，不可用时回退到手机内置麦克风。
     * @param onChunk   每 100ms 一包 PCM
     * @param onStatus  true=正常录音，false=麦克风不可用
     * @param onDevice  回调当前使用的音频设备描述
     */
    @SuppressLint("MissingPermission")
    fun start(
        onChunk: (ByteArray) -> Unit,
        onStatus: ((Boolean) -> Unit)? = null,
        onDevice: ((String) -> Unit)? = null
    ) {
        captureJob = scope.launch {
            // 1. 尝试连接蓝牙 SCO
            val btConnected = connectBluetoothSco()
            val audioSource: Int
            if (btConnected) {
                Log.i(TAG, "蓝牙 SCO 已连接，使用耳机麦克风")
                onDevice?.invoke("🎧 蓝牙耳机")
                audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION
            } else {
                Log.i(TAG, "蓝牙不可用，使用手机内置麦克风")
                onDevice?.invoke("📱 手机麦克风")
                audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
            }

            // 2. 采集循环（含失败重建）
            while (isActive) {
                val record = buildAudioRecord(audioSource)
                if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                    record?.release()
                    onStatus?.invoke(false)
                    delay(1000)
                    continue
                }

                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    record.release()
                    onStatus?.invoke(false)
                    delay(1000)
                    continue
                }

                onStatus?.invoke(true)
                audioRecord = record
                val buffer = ByteArray(CHUNK_SIZE_BYTES)
                var broken = false

                while (isActive && !broken) {
                    val read = record.read(buffer, 0, CHUNK_SIZE_BYTES)
                    when {
                        read > 0 -> onChunk(buffer.copyOf(read))
                        read < 0 -> {
                            Log.w(TAG, "AudioRecord.read 返回错误: $read")
                            broken = true
                        }
                    }
                }

                runCatching { record.stop(); record.release() }
                audioRecord = null
                if (broken && isActive) delay(500)
            }
        }
    }

    /**
     * 尝试建立蓝牙 SCO 通道，最多等待 4 秒。
     * SCO 连接成功后，AudioRecord(VOICE_COMMUNICATION) 会自动从耳机麦克风取音。
     */
    @Suppress("DEPRECATION")
    private suspend fun connectBluetoothSco(): Boolean {
        // 检查是否有蓝牙 SCO 设备
        val hasBtDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        } else {
            audioManager.isBluetoothScoAvailableOffCall
        }
        if (!hasBtDevice) return false

        val scoChannel = Channel<Boolean>(Channel.CONFLATED)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) ?: return
                Log.d(TAG, "SCO 状态变化: $state")
                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED     -> scoChannel.trySend(true)
                    AudioManager.SCO_AUDIO_STATE_ERROR         -> scoChannel.trySend(false)
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED  -> scoChannel.trySend(false)
                }
            }
        }

        mainHandler.post {
            context.registerReceiver(
                receiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            )
            scoReceiver = receiver
            audioManager.startBluetoothSco()
            Log.d(TAG, "已调用 startBluetoothSco，等待 SCO 连接...")
        }

        val result = withTimeoutOrNull(4000L) { scoChannel.receive() } ?: false
        Log.i(TAG, "SCO 连接结果: $result")
        return result
    }

    @SuppressLint("MissingPermission")
    private fun buildAudioRecord(source: Int): AudioRecord? = try {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufSize = maxOf(minBuf, CHUNK_SIZE_BYTES * 2)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .setEncoding(AUDIO_FORMAT)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .build()
        } else {
            AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufSize)
        }
    } catch (e: Exception) {
        Log.e(TAG, "构建 AudioRecord 失败: ${e.message}")
        null
    }

    @Suppress("DEPRECATION")
    fun stop() {
        captureJob?.cancel()
        captureJob = null
        runCatching { audioRecord?.stop(); audioRecord?.release() }
        audioRecord = null
        mainHandler.post {
            scoReceiver?.let { runCatching { context.unregisterReceiver(it) } }
            scoReceiver = null
            audioManager.stopBluetoothSco()
        }
    }
}
