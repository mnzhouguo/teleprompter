package com.example.teleprompter

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.teleprompter.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private val doubaoAppId = "9578212060"
    private val doubaoAccessToken = "BVI-b4p-L8ZP_q0iek85BCWDFbJ5-3hc"

    private lateinit var binding: ActivityMainBinding

    // 从悬浮窗设置页返回后，在 onResume 里重新检查一次（时序保护）
    private var pendingOverlayCheck = false

    // 麦克风权限：若被"不再询问"，跳到 App 设置页
    private val micPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            checkOverlayAndStart()
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            toast("请在「设置 → 应用 → 权限」中手动开启麦克风")
            openAppSettings()
        } else {
            toast("需要麦克风权限才能语音同步")
        }
    }

    // 悬浮窗设置页返回：不在此回调直接判定，交给 onResume 再查一次
    private val overlayPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        pendingOverlayCheck = true   // onResume 会紧接着触发
    }

    // 通知权限（Android 13+，前台服务必需）
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // 无论是否授权都继续，通知权限不阻断主流程
        checkMicAndStart()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.scriptInput.setText(DEMO_SCRIPT)

        binding.scriptInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                binding.tvCharCount.text = "${s?.length ?: 0} 字"
            }
        })
        binding.tvCharCount.text = "${DEMO_SCRIPT.length} 字"

        binding.btnFloat.setOnClickListener { onStartClicked() }
        binding.btnVideo.setOnClickListener { launchVideoRecord() }
        binding.btnPaste.setOnClickListener { pasteFromClipboard() }
    }

    override fun onResume() {
        super.onResume()
        // 从悬浮窗设置页返回后，延迟一帧再查，避免系统状态未刷新
        if (pendingOverlayCheck) {
            pendingOverlayCheck = false
            binding.scriptInput.post {
                if (Settings.canDrawOverlays(this)) {
                    launchFloating()
                } else {
                    toast("悬浮窗权限未开启，请重试")
                }
            }
        }
    }

    private fun onStartClicked() {
        // Android 13+ 先申请通知权限，再走麦克风→悬浮窗流程
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!notifGranted) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        checkMicAndStart()
    }

    private fun checkMicAndStart() {
        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (micGranted) {
            checkOverlayAndStart()
        } else {
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun checkOverlayAndStart() {
        if (Settings.canDrawOverlays(this)) {
            launchFloating()
        } else {
            pendingOverlayCheck = false
            overlayPermLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            // MIUI 设备上标准设置页可能不生效，给出更明确的提示
            val isMiui = !getSystemProperty("ro.miui.ui.version.name").isNullOrEmpty()
            if (isMiui) {
                toast("MIUI：请在「更多权限 → 显示悬浮窗」中开启，然后返回")
            } else {
                toast("请开启「显示在其他应用上层」后返回")
            }
        }
    }

    private fun getSystemProperty(key: String): String? = try {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java).invoke(null, key) as? String
    } catch (_: Exception) { null }

    private fun launchFloating() {
        val script = binding.scriptInput.text.toString()
        if (script.isBlank()) { toast("请先输入稿件"); return }

        val intent = Intent(this, FloatingService::class.java).apply {
            putExtra(FloatingService.EXTRA_SCRIPT, script)
            putExtra(FloatingService.EXTRA_APP_ID, doubaoAppId)
            putExtra(FloatingService.EXTRA_ACCESS_TOKEN, doubaoAccessToken)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)

        toast("已启动，可切到相机 App")
        moveTaskToBack(true)
    }

    private fun launchVideoRecord() {
        val script = binding.scriptInput.text.toString()
        if (script.isBlank()) { toast("请先输入稿件"); return }
        startActivity(
            Intent(this, VideoRecordActivity::class.java).apply {
                putExtra(VideoRecordActivity.EXTRA_SCRIPT, script)
                putExtra(VideoRecordActivity.EXTRA_APP_ID, doubaoAppId)
                putExtra(VideoRecordActivity.EXTRA_ACCESS_TOKEN, doubaoAccessToken)
            }
        )
    }

    private fun pasteFromClipboard() {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cb.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (!text.isNullOrBlank()) {
            binding.scriptInput.setText(text)
            binding.scriptInput.setSelection(text.length)
        } else {
            toast("剪贴板没有文本内容")
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName"))
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private val DEMO_SCRIPT = """
大家好，欢迎来到今天的视频。今天我们要聊的主题，是一款能够跟随你说话速度自动滚动的智能提词器。

在过去，主持人和演讲者需要花费大量时间背稿子。传统提词器虽然可以显示文字，但只能按照预设的固定速度滚动，说话者必须迁就机器的节奏，稍有停顿就会跟丢位置，使用体验非常不友好。

现在，随着人工智能和语音识别技术的快速发展，一种全新的智能提词器出现了。它的核心原理是这样的：当你开口说话，麦克风实时采集声音，将音频数据通过网络发送到云端的语音识别服务器。服务器利用深度学习模型，在毫秒级别内将语音转换为文字，并把识别结果实时推送回你的设备。

设备收到识别文字之后，通过一套文本对齐算法，在原始稿件中快速定位你当前读到的位置，然后驱动屏幕平滑地滚动过去，让正在朗读的段落始终保持在屏幕中央。不管你说话快一点还是慢一点，临时停顿还是重复一句，提词器都会紧跟你的节奏，而不是让你跟着机器走。

这种技术带来的改变是非常直观的。录制视频时，你只需要自然地说话，不需要刻意记忆稿件，也不需要担心页面跟不上。整个表达过程更加从容，观众看到的画面也更加自然生动。

感谢大家收看本期视频，如果你觉得有帮助，欢迎点赞关注，我们下期再见！
        """.trimIndent()
    }
}
