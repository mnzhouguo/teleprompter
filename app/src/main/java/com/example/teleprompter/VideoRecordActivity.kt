package com.example.teleprompter

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import android.view.Surface
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import android.view.WindowManager

@OptIn(ExperimentalCamera2Interop::class)
class VideoRecordActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SCRIPT = "extra_script"
        const val EXTRA_APP_ID = "extra_app_id"
        const val EXTRA_ACCESS_TOKEN = "extra_access_token"
    }

    private lateinit var previewView: PreviewView
    private lateinit var scrollView: ScrollView
    private lateinit var scriptText: TextView
    private lateinit var debugText: TextView
    private lateinit var btnRecord: FrameLayout
    private lateinit var btnSwitchCamera: FrameLayout
    private lateinit var btnBack: FrameLayout
    private lateinit var timerText: LinearLayout
    private lateinit var timerValue: TextView
    private lateinit var overlayHeader: LinearLayout
    private lateinit var resizeHandle: LinearLayout
    private lateinit var zoomPanel: LinearLayout
    private lateinit var zoomButtons: List<TextView>

    private var overlayExpanded = true
    private var lastResizeY = 0f
    private var currentZoom = 1.0f
    private var camera: androidx.camera.core.Camera? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

    private var audioCapture: AudioCapture? = null
    private var asrClient: DoubaoAsrClient? = null
    private var syncEngine: VoiceSyncEngine? = null
    private var scrollController: ScrollController? = null

    private var isRecording = false
    private var script = ""
    private var appId = ""
    private var accessToken = ""

    private var recordingSeconds = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            recordingSeconds++
            timerValue.text = String.format("%02d:%02d", recordingSeconds / 60, recordingSeconds % 60)
            mainHandler.postDelayed(this, 1000)
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.CAMERA] == true &&
            grants[Manifest.permission.RECORD_AUDIO] == true) {
            startCamera()
        } else {
            toast("需要相机和麦克风权限才能使用此功能")
            finish()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_video_record)

        script = intent.getStringExtra(EXTRA_SCRIPT) ?: ""
        appId = intent.getStringExtra(EXTRA_APP_ID) ?: ""
        accessToken = intent.getStringExtra(EXTRA_ACCESS_TOKEN) ?: ""

        previewView = findViewById(R.id.preview_view)
        scrollView = findViewById(R.id.scroll_view)
        scriptText = findViewById(R.id.script_text)
        debugText = findViewById(R.id.debug_text)
        btnRecord = findViewById(R.id.btn_record)
        btnSwitchCamera = findViewById(R.id.btn_switch_camera)
        btnBack = findViewById(R.id.btn_back)
        timerText = findViewById(R.id.recording_timer)
        timerValue = findViewById(R.id.timer_value)
        overlayHeader = findViewById(R.id.overlay_header)
        resizeHandle = findViewById(R.id.resize_handle)
        zoomPanel = findViewById(R.id.zoom_panel)

        zoomButtons = listOf(
            findViewById<TextView>(R.id.btn_zoom_0_8),
            findViewById<TextView>(R.id.btn_zoom_1_0),
            findViewById<TextView>(R.id.btn_zoom_1_2),
            findViewById<TextView>(R.id.btn_zoom_1_4),
            findViewById<TextView>(R.id.btn_zoom_1_6),
            findViewById<TextView>(R.id.btn_zoom_1_8),
            findViewById<TextView>(R.id.btn_zoom_2_0)
        )

        scriptText.setText(SpannableString(script), TextView.BufferType.SPANNABLE)

        btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        btnRecord.setOnClickListener { toggleRecording() }
        btnSwitchCamera.setOnClickListener { switchCamera() }

        // 焦距按钮点击
        val zoomValues = listOf(0.8f, 1.0f, 1.2f, 1.4f, 1.6f, 1.8f, 2.0f)
        zoomButtons.forEachIndexed { index, btn ->
            btn.setOnClickListener {
                currentZoom = zoomValues[index]
                applyZoom()
                updateZoomButtonStates()
            }
        }
        updateZoomButtonStates()

        // 点击 header 折叠 / 展开提词器
        overlayHeader.setOnClickListener {
            overlayExpanded = !overlayExpanded
            val vis = if (overlayExpanded) View.VISIBLE else View.GONE
            scrollView.visibility = vis
            resizeHandle.visibility = vis
            val arrow = findViewById<TextView>(R.id.btn_toggle_overlay)
            arrow.text = if (overlayExpanded) "▾" else "▴"
        }

        // 拖动手柄调整提词器高度
        resizeHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastResizeY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = event.rawY - lastResizeY
                    lastResizeY = event.rawY
                    val density = resources.displayMetrics.density
                    val minH = (80 * density).toInt()
                    val maxH = (resources.displayMetrics.heightPixels * 0.65f).toInt()
                    val lp = scrollView.layoutParams
                    lp.height = (lp.height + delta.toInt()).coerceIn(minH, maxH)
                    scrollView.layoutParams = lp
                    true
                }
                else -> false
            }
        }

        checkAndStartCamera()
    }

    private fun checkAndStartCamera() {
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startCamera() else permLauncher.launch(missing.toTypedArray())
    }

    private fun startCamera() {
        ProcessCameraProvider.getInstance(this).also { future ->
            future.addListener({
                cameraProvider = future.get()
                bindCameraUseCases()
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val previewBuilder = Preview.Builder()
        Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
            CaptureRequest.CONTROL_AF_MODE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        )
        val preview = previewBuilder.build().apply {
            setSurfaceProvider(previewView.surfaceProvider)
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
            .build()
        videoCapture = VideoCapture.Builder(recorder)
            .setTargetRotation(Surface.ROTATION_0)
            .build()

        provider.unbindAll()
        try {
            camera = provider.bindToLifecycle(this, cameraSelector, preview, videoCapture!!)
            camera?.cameraControl?.setZoomRatio(currentZoom)
        } catch (e: Exception) {
            toast("相机启动失败: ${e.message}")
        }
    }

    private fun switchCamera() {
        if (isRecording) return
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA)
            CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        bindCameraUseCases()
    }

    private fun toggleRecording() {
        if (isRecording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        val vc = videoCapture ?: return

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "teleprompter_${System.currentTimeMillis()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/提词器")
            }
        }
        val outputOptions = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()

        activeRecording = vc.output
            .prepareRecording(this, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isRecording = true
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        setRecordingUI(true)
                        startTeleprompter()
                        startTimer()
                    }
                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        setRecordingUI(false)
                        stopTeleprompter()
                        stopTimer()
                        if (event.hasError()) toast("录制出错: ${event.error}")
                        else toast("视频已保存至相册「提词器」文件夹")
                    }
                    else -> {}
                }
            }
    }

    private fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    private fun setRecordingUI(recording: Boolean) {
        val recordCenter = btnRecord.findViewById<View>(R.id.record_center)
        val lp = recordCenter.layoutParams
        if (recording) {
            lp.width = (20 * resources.displayMetrics.density).toInt()
            lp.height = (20 * resources.displayMetrics.density).toInt()
            recordCenter.layoutParams = lp
            recordCenter.setBackgroundResource(R.drawable.record_button_square_filled)
            btnSwitchCamera.isEnabled = false
            timerText.visibility = View.VISIBLE
            timerValue.text = "00:00"
        } else {
            lp.width = (70 * resources.displayMetrics.density).toInt()
            lp.height = (70 * resources.displayMetrics.density).toInt()
            recordCenter.layoutParams = lp
            recordCenter.setBackgroundResource(R.drawable.record_button_circle)
            btnSwitchCamera.isEnabled = true
            timerText.visibility = View.INVISIBLE
        }
    }

    private fun startTeleprompter() {
        syncEngine = VoiceSyncEngine(script)
        scrollController = ScrollController(scrollView, scriptText)
        scriptText.setText(SpannableString(script), TextView.BufferType.SPANNABLE)

        asrClient = DoubaoAsrClient(
            appId = appId,
            accessToken = accessToken,
            onText = { text, _ ->
                mainHandler.post {
                    // 安全检查：录制停止后可能这些对象已被清空
                    val engine = syncEngine
                    val controller = scrollController
                    if (engine == null || controller == null) return@post

                    val pos = engine.onAsrIncrement(text)
                    updateHighlight(pos)
                    controller.scrollToChar(pos)
                    debugText.text = "「$text」"
                }
            },
            onError = { msg ->
                mainHandler.post { debugText.text = "ASR 错误: $msg" }
            }
        )
        asrClient!!.connect()

        audioCapture = AudioCapture(this).also {
            it.start(
                onChunk = { pcm -> asrClient?.sendAudio(pcm) },
                onDevice = { device -> mainHandler.post { debugText.text = "$device 已就绪" } },
                onStatus = { available ->
                    if (!available) mainHandler.post { debugText.text = "⚠️ 麦克风不可用" }
                }
            )
        }
    }

    private fun stopTeleprompter() {
        runCatching { audioCapture?.stop() }
        runCatching { asrClient?.close() }
        runCatching { scrollController?.stop() }
        audioCapture = null
        asrClient = null
        scrollController = null
        mainHandler.post { debugText.text = "按录制键开始" }
    }

    private fun startTimer() {
        recordingSeconds = 0
        mainHandler.post(timerRunnable)
    }

    private fun stopTimer() {
        mainHandler.removeCallbacks(timerRunnable)
        recordingSeconds = 0
    }

    private fun updateHighlight(pos: Int) {
        val sp = scriptText.text as? Spannable ?: return
        val len = sp.length
        val safePos = pos.coerceIn(0, len)

        sp.getSpans(0, len, ForegroundColorSpan::class.java).forEach { sp.removeSpan(it) }
        sp.getSpans(0, len, BackgroundColorSpan::class.java).forEach { sp.removeSpan(it) }

        if (safePos > 0) {
            sp.setSpan(ForegroundColorSpan(Color.parseColor("#FFD700")),
                0, safePos, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (safePos < len) {
            val curEnd = (safePos + 1).coerceAtMost(len)
            sp.setSpan(BackgroundColorSpan(Color.WHITE),
                safePos, curEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(ForegroundColorSpan(Color.BLACK),
                safePos, curEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        stopTeleprompter()
        stopTimer()
        cameraProvider?.unbindAll()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ════════════════════════════════════════════
    //  焦距设置
    // ════════════════════════════════════════════

    private fun applyZoom() {
        camera?.cameraControl?.setZoomRatio(currentZoom)
    }

    private fun updateZoomButtonStates() {
        val zoomValues = listOf(0.8f, 1.0f, 1.2f, 1.4f, 1.6f, 1.8f, 2.0f)
        zoomButtons.forEachIndexed { index, btn ->
            if (zoomValues[index] == currentZoom) {
                btn.setBackgroundResource(R.drawable.bg_zoom_btn_selected)
                btn.setTextColor(Color.parseColor("#FFD700"))
            } else {
                btn.setBackgroundResource(R.drawable.bg_zoom_btn)
                btn.setTextColor(Color.parseColor("#80FFFFFF"))
            }
        }
    }
}
