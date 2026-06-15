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
import android.view.KeyEvent
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCamera2Interop::class)
class VideoRecordActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SCRIPT = "extra_script"
        /** 关联远程文稿 id；>0 时录制结束会上传语音转写 */
        const val EXTRA_SCRIPT_ID = "extra_script_id"
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
    private lateinit var speedText: TextView
    private lateinit var overlayHeader: LinearLayout
    private lateinit var resizeHandle: LinearLayout
    private lateinit var zoomPanel: LinearLayout
    private lateinit var zoomButtons: List<TextView>
    private lateinit var tabBar: LinearLayout
    private lateinit var tabFullText: FrameLayout
    private lateinit var tabKeywords: FrameLayout
    private lateinit var tabFullTextLabel: TextView
    private lateinit var tabKeywordsLabel: TextView
    private lateinit var tabFullTextIndicator: View
    private lateinit var tabKeywordsIndicator: View
    private lateinit var contentFullText: FrameLayout
    private lateinit var contentKeywords: FrameLayout
    private var configWindow = 5
    private var configForward = 60
    private var configBack = 3
    private var currentTab = 0 // 0: 全文提示, 1: 关键字提示

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
    private var scriptId: Long = 0L
    private var appId = ""
    private var accessToken = ""

    private var recordingSeconds = 0
    private var recordingStartTime = 0L
    private var recordingStartPosition = 0
    private var lastScrollLineTime = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            recordingSeconds++
            timerValue.text = String.format("%02d:%02d", recordingSeconds / 60, recordingSeconds % 60)
            updateReadingSpeed()
            mainHandler.postDelayed(this, 1000)
        }
    }
    private val deferredTranscriptUpload = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        val t = syncEngine?.accumulatedAsrTranscript()?.trim().orEmpty()
        uploadTranscriptAfterRecording(t)
    }
    private val scrollStopRunnable = Runnable {
        onManualScrollStopped()
    }
    private var isScrolling = false

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

        script = ScriptContentFilter.forDisplay(intent.getStringExtra(EXTRA_SCRIPT) ?: "")
        scriptId = intent.getLongExtra(EXTRA_SCRIPT_ID, 0L)
        appId = intent.getStringExtra(EXTRA_APP_ID) ?: ""
        accessToken = intent.getStringExtra(EXTRA_ACCESS_TOKEN) ?: ""

        previewView = findViewById(R.id.preview_view)
        scrollView = findViewById(R.id.scroll_view)
        scriptText = findViewById(R.id.script_text)
        debugText = findViewById(R.id.debug_text)
        speedText = findViewById(R.id.speed_text)
        btnRecord = findViewById(R.id.btn_record)
        btnSwitchCamera = findViewById(R.id.btn_switch_camera)
        btnBack = findViewById(R.id.btn_back)
        timerText = findViewById(R.id.recording_timer)
        timerValue = findViewById(R.id.timer_value)
        overlayHeader = findViewById(R.id.overlay_header)
        resizeHandle = findViewById(R.id.resize_handle)
        zoomPanel = findViewById(R.id.zoom_panel)
        tabBar = findViewById(R.id.tab_bar)
        tabFullText = findViewById(R.id.tab_full_text)
        tabKeywords = findViewById(R.id.tab_keywords)
        tabFullTextLabel = findViewById(R.id.tab_full_text_label)
        tabKeywordsLabel = findViewById(R.id.tab_keywords_label)
        tabFullTextIndicator = findViewById(R.id.tab_full_text_indicator)
        tabKeywordsIndicator = findViewById(R.id.tab_keywords_indicator)
        contentFullText = findViewById(R.id.content_full_text)
        contentKeywords = findViewById(R.id.content_keywords)

        zoomButtons = listOf(
            findViewById<TextView>(R.id.btn_zoom_0_8),
            findViewById<TextView>(R.id.btn_zoom_1_0),
            findViewById<TextView>(R.id.btn_zoom_1_2),
            findViewById<TextView>(R.id.btn_zoom_1_4),
            findViewById<TextView>(R.id.btn_zoom_1_6),
            findViewById<TextView>(R.id.btn_zoom_1_8),
            findViewById<TextView>(R.id.btn_zoom_2_0)
        )

        // Tab 切换
        tabFullText.setOnClickListener { switchTab(0) }
        tabKeywords.setOnClickListener { switchTab(1) }

        val controller = ScrollController(scrollView, scriptText, script)
        scrollController = controller
        scriptText.setText(buildTextWithLineNumbers(script, controller.originalLineStarts), TextView.BufferType.SPANNABLE)

        // 监听 ScrollView 滚动
        scrollView.viewTreeObserver.addOnScrollChangedListener {
            if (!isRecording && currentTab == 0) {
                isScrolling = true
                mainHandler.removeCallbacks(scrollStopRunnable)
                mainHandler.postDelayed(scrollStopRunnable, 150)
            }
        }

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

        // ── 匹配参数配置对话框 ──
        val btnSettings = findViewById<FrameLayout>(R.id.btn_settings)
        btnSettings.setOnClickListener { showConfigDialog() }

        // 点击 header 折叠 / 展开提词器
        overlayHeader.setOnClickListener {
            overlayExpanded = !overlayExpanded
            val vis = if (overlayExpanded) View.VISIBLE else View.GONE
            tabBar.visibility = vis
            contentFullText.visibility = if (overlayExpanded && currentTab == 0) View.VISIBLE else View.GONE
            contentKeywords.visibility = if (overlayExpanded && currentTab == 1) View.VISIBLE else View.GONE
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
                    val lp = contentFullText.layoutParams
                    lp.height = (lp.height + delta.toInt()).coerceIn(minH, maxH)
                    contentFullText.layoutParams = lp
                    contentKeywords.layoutParams = lp
                    true
                }
                else -> false
            }
        }

        checkAndStartCamera()
    }

    private fun switchTab(tab: Int) {
        if (currentTab == tab) return
        currentTab = tab

        // 更新 Tab UI
        if (tab == 0) {
            tabFullTextLabel.setTextColor(Color.parseColor("#FFD700"))
            tabFullTextLabel.typeface = android.graphics.Typeface.DEFAULT_BOLD
            tabFullTextIndicator.visibility = View.VISIBLE
            tabKeywordsLabel.setTextColor(Color.parseColor("#80FFFFFF"))
            tabKeywordsLabel.typeface = android.graphics.Typeface.DEFAULT
            tabKeywordsIndicator.visibility = View.INVISIBLE
            contentFullText.visibility = View.VISIBLE
            contentKeywords.visibility = View.GONE
        } else {
            tabFullTextLabel.setTextColor(Color.parseColor("#80FFFFFF"))
            tabFullTextLabel.typeface = android.graphics.Typeface.DEFAULT
            tabFullTextIndicator.visibility = View.INVISIBLE
            tabKeywordsLabel.setTextColor(Color.parseColor("#FFD700"))
            tabKeywordsLabel.typeface = android.graphics.Typeface.DEFAULT_BOLD
            tabKeywordsIndicator.visibility = View.VISIBLE
            contentFullText.visibility = View.GONE
            contentKeywords.visibility = View.VISIBLE
        }
    }

    /**
     * 构建带行号的 SpannableString
     */
    private fun buildTextWithLineNumbers(text: String, lineStarts: List<Int>): SpannableString {
        val spannable = SpannableString(text)
        for (i in lineStarts.indices) {
            val start = lineStarts[i]
            val end = if (i < lineStarts.size - 1) lineStarts[i + 1] else text.length
            // 找到该行实际结束位置（下一个换行符之前）
            val lineEnd = text.indexOf('\n', start).let { if (it == -1) end else it }
            spannable.setSpan(
                LineNumberSpan(i + 1, start),
                start,
                lineEnd,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }

    private fun onManualScrollStopped() {
        isScrolling = false
        if (currentTab != 0) return

        val charIndex = scrollController?.getCurrentPositionCharIndex() ?: 0
        syncEngine?.setPosition(charIndex)
        updateHighlight(charIndex)
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
                        else {
                            toast("视频已保存至相册「提词器」文件夹")
                            mainHandler.postDelayed(deferredTranscriptUpload, 450)
                        }
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
        // 先清理之前的 syncEngine（如果有的话），确保完全清空旧的转写内容
        syncEngine?.reset()
        syncEngine = null
        
        // 创建全新的 syncEngine 实例
        syncEngine = VoiceSyncEngine(script,
            windowSize = configWindow,
            searchForward = configForward,
            searchBack = configBack)
        scriptText.setText(buildTextWithLineNumbers(script, scrollController!!.originalLineStarts), TextView.BufferType.SPANNABLE)

        // 使用当前 ScrollView 可见位置作为起始点
        val startCharIndex = scrollController?.getCurrentPositionCharIndex() ?: 0
        syncEngine?.setPosition(startCharIndex)
        updateHighlight(startCharIndex)

        asrClient = DoubaoAsrClient(
            appId = appId,
            accessToken = accessToken,
            onText = { text, isFinal ->
                val engine = syncEngine
                val pos = if (engine != null && text.isNotEmpty()) {
                    engine.onAsrIncrement(text, isFinal)
                } else {
                    -1
                }
                mainHandler.post {
                    val eng = syncEngine
                    val controller = scrollController
                    if (eng == null || controller == null) return@post
                    if (pos >= 0) {
                        updateHighlight(pos)
                        controller.scrollToChar(pos)
                    }
                    val mark = if (eng.lastMatched) "✓" else "✗"
                    debugText.text = "$mark [${eng.lastBuffer}] ${"%.2f".format(eng.lastScore)}"
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

    private fun updateReadingSpeed() {
        if (recordingSeconds == 0) return
        val pos = syncEngine?.currentPosition ?: 0
        val charsPerMin = (pos * 60 / recordingSeconds)
        speedText.text = "${charsPerMin}字/分"
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 调试：记录所有按键事件
        if (event.action == KeyEvent.ACTION_DOWN) {
            val dev = event.device
            android.util.Log.d("Record", "KEY: code=${event.keyCode} name=${KeyEvent.keyCodeToString(event.keyCode)} ext=${dev?.isExternal} devName=${dev?.name} src=${dev?.sources}")
        }

        if (event.action == KeyEvent.ACTION_DOWN && isBluetoothKey(event)) {
            val now = System.currentTimeMillis()
            if (now - lastScrollLineTime < 300) return true
            lastScrollLineTime = now

            val charIndex = scrollController?.scrollOneLine() ?: 0
            if (charIndex > 0) {
                syncEngine?.setPosition(charIndex)
                updateHighlight(charIndex)
            }
            android.util.Log.d("Record", "BT scroll: key=${event.keyCode} charIndex=$charIndex")
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isBluetoothKey(event: KeyEvent): Boolean {
        val device = event.device ?: return false
        if (!device.isExternal) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_CAMERA,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> true
            else -> false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(deferredTranscriptUpload)
        stopRecording()
        stopTeleprompter()
        stopTimer()
        cameraProvider?.unbindAll()
    }

    private fun showConfigDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_config, null)
        val seekWin = view.findViewById<android.widget.SeekBar>(R.id.seek_window)
        val seekFwd = view.findViewById<android.widget.SeekBar>(R.id.seek_forward)
        val seekBck = view.findViewById<android.widget.SeekBar>(R.id.seek_back)
        val valWin = view.findViewById<TextView>(R.id.val_window)
        val valFwd = view.findViewById<TextView>(R.id.val_forward)
        val valBck = view.findViewById<TextView>(R.id.val_back)

        // 初始值
        seekWin.progress = configWindow - 3
        seekFwd.progress = configForward - 10
        seekBck.progress = configBack - 1
        valWin.text = "$configWindow"
        valFwd.text = "$configForward"
        valBck.text = "$configBack"

        val listener = object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: android.widget.SeekBar, v: Int, user: Boolean) {
                when (seek.id) {
                    R.id.seek_window -> { configWindow = v + 3; valWin.text = "$configWindow" }
                    R.id.seek_forward -> { configForward = v + 10; valFwd.text = "$configForward" }
                    R.id.seek_back -> { configBack = v + 1; valBck.text = "$configBack" }
                }
                syncEngine?.let {
                    it.windowSize = configWindow
                    it.searchForward = configForward
                    it.searchBack = configBack
                }
            }
            override fun onStartTrackingTouch(p0: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(p0: android.widget.SeekBar?) {}
        }
        seekWin.setOnSeekBarChangeListener(listener)
        seekFwd.setOnSeekBarChangeListener(listener)
        seekBck.setOnSeekBarChangeListener(listener)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("匹配参数设置")
            .setView(view)
            .setPositiveButton("确定", null)
            .show()
    }

    /**
     * 将录制过程中累计的 ASR 文本写入远程 `playback_content` 字段。
     */
    private fun uploadTranscriptAfterRecording(transcript: String) {
        if (scriptId <= 0L || transcript.isEmpty()) return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                VideoScriptApiService.updateScript(scriptId, transcript = transcript)
            }
            result.fold(
                onSuccess = { toast("语音转写已保存到服务器") },
                onFailure = { e -> toast("语音转写保存失败: ${e.message}") }
            )
        }
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
