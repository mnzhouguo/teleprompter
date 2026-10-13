package com.example.teleprompter

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.teleprompter.databinding.ActivityScriptEditBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 口语稿同步：新增 / 编辑页。
 * 新建 → POST /api/mobile/speeches；编辑 → PUT /api/mobile/speeches/{id}
 */
class MobileSpeechEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IS_NEW = "is_new"
        const val EXTRA_SPEECH_ID = "speech_id"
        const val EXTRA_SPEECH_TITLE = "speech_title"
        const val EXTRA_SPEECH_CONTENT = "speech_content"
    }

    private lateinit var binding: ActivityScriptEditBinding
    private var isNew: Boolean = true
    private var speechId: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScriptEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isNew = intent.getBooleanExtra(EXTRA_IS_NEW, true)
        speechId = intent.getLongExtra(EXTRA_SPEECH_ID, 0L)

        binding.navTitle.text = if (isNew) "创作口语稿" else "编辑口语稿"
        binding.titleInput.hint = "标题（可留空，由服务端生成）"
        binding.contentInput.hint = "在这里输入口语稿正文…"

        if (!isNew) {
            binding.titleInput.setText(intent.getStringExtra(EXTRA_SPEECH_TITLE).orEmpty())
            binding.contentInput.setText(intent.getStringExtra(EXTRA_SPEECH_CONTENT).orEmpty())
            if (binding.contentInput.text.isNullOrBlank() && speechId > 0) {
                loadDetail()
            }
        }

        binding.navBack.setOnClickListener { finish() }
        binding.navSave.setOnClickListener { save() }
        setupStatsWatcher()
    }

    private fun setupStatsWatcher() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateStats()
        }
        binding.titleInput.addTextChangedListener(watcher)
        binding.contentInput.addTextChangedListener(watcher)
        updateStats()
    }

    private fun updateStats() {
        val charCount = binding.contentInput.text?.length ?: 0
        val minutes = if (charCount > 0) Math.ceil(charCount / 200.0).toInt() else 0
        binding.charCount.text = "$charCount 字"
        binding.timeEstimate.text = "~$minutes 分钟"
    }

    private fun loadDetail() {
        val baseUrl = SyncSettingsStore.getBaseUrl(this)
        val token = SyncSettingsStore.getToken(this)
        lifecycleScope.launch {
            binding.loadingOverlay.visibility = View.VISIBLE
            val result = withContext(Dispatchers.IO) {
                MobileSyncApiService.fetchSpeech(baseUrl, token, speechId)
            }
            binding.loadingOverlay.visibility = View.GONE
            result.fold(
                onSuccess = { detail ->
                    binding.titleInput.setText(detail.title)
                    binding.contentInput.setText(detail.content)
                    updateStats()
                },
                onFailure = { error ->
                    Toast.makeText(
                        this@MobileSpeechEditActivity,
                        "加载失败：${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun save() {
        val title = binding.titleInput.text.toString().trim()
        val content = binding.contentInput.text.toString()

        if (content.trim().isEmpty()) {
            Toast.makeText(this, "请输入口语稿正文", Toast.LENGTH_SHORT).show()
            return
        }

        val baseUrl = SyncSettingsStore.getBaseUrl(this)
        val token = SyncSettingsStore.getToken(this)

        lifecycleScope.launch {
            binding.loadingOverlay.visibility = View.VISIBLE
            binding.navSave.isEnabled = false

            val result = withContext(Dispatchers.IO) {
                if (isNew) {
                    MobileSyncApiService.createSpeech(
                        baseUrl = baseUrl,
                        token = token,
                        content = content,
                        title = title.ifEmpty { null }
                    )
                } else {
                    MobileSyncApiService.updateSpeech(
                        baseUrl = baseUrl,
                        token = token,
                        id = speechId,
                        content = content,
                        title = title.ifEmpty { null }
                    )
                }
            }

            binding.loadingOverlay.visibility = View.GONE
            binding.navSave.isEnabled = true

            result.fold(
                onSuccess = {
                    setResult(Activity.RESULT_OK)
                    Toast.makeText(
                        this@MobileSpeechEditActivity,
                        if (isNew) "已创建口语稿" else "已保存修改",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                },
                onFailure = { error ->
                    Toast.makeText(
                        this@MobileSpeechEditActivity,
                        "保存失败：${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }
}
