package com.example.teleprompter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.SpannableString
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView

/**
 * 前台服务 —— 整个提词器的"大脑"。
 *
 * 职责:
 *   1. 显示一个系统级悬浮窗,飘在相机等任何 App 之上
 *   2. 启动麦克风采集 → 送入豆包 ASR → 识别结果给到对齐引擎 → 驱动滚动
 *   3. 处理悬浮窗拖动、关闭
 *   4. 前台通知保活,防止系统在后台杀掉
 *
 * 为什么必须是 Service 而不是 Activity 管悬浮窗?
 *   Activity 被切到后台时 UI 会被系统回收
 *   只有 Service(尤其是 foreground service)能稳定承载跨 App 悬浮的 UI
 *
 * 为什么 foregroundServiceType 必须声明 microphone?
 *   Android 12+ 限制后台 App 访问麦克风
 *   必须在 manifest 声明类型 + startForeground 时传入类型,系统才放行
 */
class FloatingService : Service() {

    companion object {
        private const val TAG = "FloatingService"
        private const val NOTIF_CHANNEL_ID = "teleprompter_channel"
        private const val NOTIF_ID = 1001

        const val EXTRA_SCRIPT = "extra_script"
        const val EXTRA_APP_ID = "extra_app_id"
        const val EXTRA_ACCESS_TOKEN = "extra_access_token"
    }

    // ----- 系统相关 -----
    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    // ----- UI 组件 -----
    private lateinit var scrollView: ScrollView
    private lateinit var scriptText: TextView
    private lateinit var debugText: TextView

    // ----- 业务模块 -----
    private lateinit var audioCapture: AudioCapture
    private lateinit var asrClient: DoubaoAsrClient
    private lateinit var syncEngine: VoiceSyncEngine
    private lateinit var scrollController: ScrollController

    // UI 必须在主线程更新
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val script = intent?.getStringExtra(EXTRA_SCRIPT) ?: "(未传入稿件)"
        val appId = intent?.getStringExtra(EXTRA_APP_ID) ?: ""
        val accessToken = intent?.getStringExtra(EXTRA_ACCESS_TOKEN) ?: ""

        // 第一时间进入前台状态,避免被系统杀
        startAsForeground()

        // 初始化各模块
        setupFloatingWindow(script)
        setupPipeline(script, appId, accessToken)

        return START_NOT_STICKY
    }

    // =============== 前台通知 ===============

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID, "提词器",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val notif: Notification = Notification.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("智能提词器运行中")
            .setContentText("点击返回 App")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        // Android 10+ 带 foregroundServiceType 参数的 startForeground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // =============== 悬浮窗 ===============

    private fun setupFloatingWindow(script: String) {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this)
            .inflate(R.layout.floating_teleprompter, null)

        scrollView = floatingView!!.findViewById(R.id.scroll_view)
        scriptText = floatingView!!.findViewById(R.id.script_text)
        debugText = floatingView!!.findViewById(R.id.debug_text)
        scriptText.text = script

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // TYPE_APPLICATION_OVERLAY: Android 8+ 唯一合法的用户级悬浮窗类型
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // flags 说明:
            //   NOT_FOCUSABLE: 不抢占焦点,下层 App(如相机)依然能接收按键
            //   LAYOUT_NO_LIMITS: 允许悬浮窗布局超出屏幕边界(便于拖到边缘)
            //   HARDWARE_ACCELERATED: 打开硬件加速,滚动不卡
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            // TRANSLUCENT: 支持透明背景(我们的半透明黑底需要这个)
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 200
            alpha = 0.9f
        }

        windowManager.addView(floatingView, layoutParams)

        setupDrag()
        setupCloseButton()
    }

    /** 顶部抓手支持拖动,按下时记录原始位置,滑动时更新 y */
    private fun setupDrag() {
        val dragHandle = floatingView!!.findViewById<View>(R.id.drag_handle)
        var initialY = 0
        var initialTouchY = 0f

        dragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = layoutParams.y
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, layoutParams)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupCloseButton() {
        floatingView!!.findViewById<View>(R.id.btn_close).setOnClickListener {
            stopSelf()
        }
    }

    // =============== 流水线组装 ===============

    private fun setupPipeline(script: String, appId: String, accessToken: String) {
        syncEngine = VoiceSyncEngine(script)
        scrollController = ScrollController(scrollView, scriptText)
        audioCapture = AudioCapture(this)   // 传入 Context 以便管理蓝牙 SCO

        // 初始化 Spannable，之后只更新 span 不重置文本，避免触发重新布局
        val spannable = SpannableString(script)
        scriptText.setText(spannable, TextView.BufferType.SPANNABLE)

        asrClient = DoubaoAsrClient(
            appId = appId,
            accessToken = accessToken,
            onText = { text, _ ->
                mainHandler.post {
                    val pos = syncEngine.onAsrIncrement(text)
                    updateHighlight(pos)
                    scrollController.scrollToChar(pos)
                    debugText.text = "「$text」"
                }
            },
            onError = { msg ->
                Log.e(TAG, "ASR 错误: $msg")
                mainHandler.post { debugText.text = "错误: $msg" }
            }
        )
        asrClient.connect()

        audioCapture.start(
            onChunk = { pcm -> asrClient.sendAudio(pcm) },
            onDevice = { device ->
                mainHandler.post { debugText.text = "$device 已就绪" }
            },
            onStatus = { available ->
                mainHandler.post {
                    if (available) {
                        // 恢复后不覆盖设备提示，保持静默
                    } else {
                        debugText.text = "⚠️ 麦克风不可用，等待恢复..."
                    }
                }
            }
        )
    }

    // 已读部分：金色；当前词：亮白背景；未读：正常白色
    private fun updateHighlight(pos: Int) {
        val sp = scriptText.text as? Spannable ?: return
        val len = sp.length
        val safePos = pos.coerceIn(0, len)

        // 清除旧 span
        sp.getSpans(0, len, ForegroundColorSpan::class.java).forEach { sp.removeSpan(it) }
        sp.getSpans(0, len, BackgroundColorSpan::class.java).forEach { sp.removeSpan(it) }

        if (safePos > 0) {
            // 已读：金色文字
            sp.setSpan(
                ForegroundColorSpan(Color.parseColor("#FFD700")),
                0, safePos,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        // 当前字：高亮背景（白底黑字），让当前读到的位置一眼可见
        val curEnd = (safePos + 1).coerceAtMost(len)
        if (safePos < len) {
            sp.setSpan(
                BackgroundColorSpan(Color.WHITE),
                safePos, curEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sp.setSpan(
                ForegroundColorSpan(Color.BLACK),
                safePos, curEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    // =============== 生命周期清理 ===============

    override fun onDestroy() {
        super.onDestroy()
        runCatching { audioCapture.stop() }
        runCatching { asrClient.close() }
        runCatching { scrollController.stop() }
        runCatching {
            if (floatingView != null) windowManager.removeView(floatingView)
        }
        floatingView = null
    }
}
