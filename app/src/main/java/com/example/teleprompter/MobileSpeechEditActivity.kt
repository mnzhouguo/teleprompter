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
 * 在线优先写服务端；失败或离线则落本地待同步队列，联网后由 [SpeechOfflineSync] 推送。
 */
class MobileSpeechEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IS_NEW = "is_new"
        const val EXTRA_SPEECH_ID = "speech_id"
        const val EXTRA_SPEECH_TITLE = "speech_title"
        const val EXTRA_SPEECH_CONTENT = "speech_content"
        const val EXTRA_LOCAL_KEY = "local_key"
        const val EXTRA_BASELINE_UPDATED_AT = "baseline_updated_at"
    }

    private lateinit var binding: ActivityScriptEditBinding
    private var isNew: Boolean = true
    private var speechId: Long = 0L
    private var localKey: String = ""
    private var baselineUpdatedAt: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScriptEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isNew = intent.getBooleanExtra(EXTRA_IS_NEW, true)
        speechId = intent.getLongExtra(EXTRA_SPEECH_ID, 0L)
        localKey = intent.getStringExtra(EXTRA_LOCAL_KEY).orEmpty()
            .ifEmpty { PendingSpeechStore.newLocalKey() }
        baselineUpdatedAt = intent.getStringExtra(EXTRA_BASELINE_UPDATED_AT).orEmpty()

        binding.navTitle.text = if (isNew) "新建文稿" else "编辑文稿"
        binding.titleInput.hint = "标题（可留空，由服务端生成）"
        binding.contentInput.hint = "在这里输入文稿正文…"

        val titleExtra = intent.getStringExtra(EXTRA_SPEECH_TITLE).orEmpty()
        val contentExtra = intent.getStringExtra(EXTRA_SPEECH_CONTENT).orEmpty()
        if (titleExtra.isNotEmpty() || contentExtra.isNotEmpty()) {
            binding.titleInput.setText(titleExtra)
            binding.contentInput.setText(contentExtra)
        }
        if (!isNew && binding.contentInput.text.isNullOrBlank() && speechId > 0) {
            loadDetail()
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
        val pending = PendingSpeechSyncLogic.findUpdateByRemoteId(
            PendingSpeechStore.loadPending(this),
            speechId
        )
        if (pending != null) {
            binding.titleInput.setText(pending.title)
            binding.contentInput.setText(pending.content)
            baselineUpdatedAt = pending.baselineUpdatedAt
            localKey = pending.localKey
            updateStats()
            return
        }

        val cached = PendingSpeechStore.loadDetail(this, speechId)
        if (cached != null) {
            // 离线或已有缓存时先展示本地，避免空白等待
            binding.titleInput.setText(cached.title)
            binding.contentInput.setText(cached.content)
            if (baselineUpdatedAt.isBlank()) baselineUpdatedAt = cached.updatedAt
            updateStats()
            if (!NetworkReachability.isOnline(this)) return
        } else if (!NetworkReachability.isOnline(this)) {
            Toast.makeText(this, "离线暂无全文，请先联网同步一次", Toast.LENGTH_LONG).show()
            return
        }

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
                    PendingSpeechStore.saveDetail(this@MobileSpeechEditActivity, detail)
                    binding.titleInput.setText(detail.title)
                    binding.contentInput.setText(detail.content)
                    baselineUpdatedAt = detail.updatedAt
                    updateStats()
                },
                onFailure = { error ->
                    if (cached == null) {
                        Toast.makeText(
                            this@MobileSpeechEditActivity,
                            "加载失败：${error.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
        }
    }

    private fun save() {
        val title = binding.titleInput.text.toString().trim()
        val content = binding.contentInput.text.toString()

        if (content.trim().isEmpty()) {
            Toast.makeText(this, "请输入文稿正文", Toast.LENGTH_SHORT).show()
            return
        }

        val baseUrl = SyncSettingsStore.getBaseUrl(this)
        val token = SyncSettingsStore.getToken(this)
        val online = NetworkReachability.isOnline(this)

        lifecycleScope.launch {
            binding.loadingOverlay.visibility = View.VISIBLE
            binding.navSave.isEnabled = false

            if (!online) {
                saveLocally(title, content)
                return@launch
            }

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

            result.fold(
                onSuccess = { detail ->
                    // 在线成功则清掉同条待同步
                    PendingSpeechStore.remove(this@MobileSpeechEditActivity, localKey)
                    if (!isNew) {
                        PendingSpeechStore.loadPending(this@MobileSpeechEditActivity)
                            .filter { it.kind == PendingSpeechSyncLogic.Kind.UPDATE && it.remoteId == speechId }
                            .forEach { PendingSpeechStore.remove(this@MobileSpeechEditActivity, it.localKey) }
                    }
                    PendingSpeechStore.saveDetail(this@MobileSpeechEditActivity, detail)
                    binding.loadingOverlay.visibility = View.GONE
                    binding.navSave.isEnabled = true
                    setResult(Activity.RESULT_OK)
                    Toast.makeText(
                        this@MobileSpeechEditActivity,
                        if (isNew) "已创建文稿" else "已保存修改",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                },
                onFailure = {
                    saveLocally(title, content)
                }
            )
        }
    }

    private fun saveLocally(title: String, content: String) {
        if (isNew) {
            PendingSpeechStore.upsertCreate(this, localKey, title, content)
        } else {
            PendingSpeechStore.upsertUpdate(
                context = this,
                localKey = localKey,
                remoteId = speechId,
                title = title,
                content = content,
                baselineUpdatedAt = baselineUpdatedAt
            )
            PendingSpeechStore.saveDetail(
                this,
                MobileSyncApiService.RemoteSpeechDetail(
                    id = speechId,
                    title = title.ifEmpty { "未命名草稿" },
                    content = content,
                    updatedAt = baselineUpdatedAt
                )
            )
        }

        binding.loadingOverlay.visibility = View.GONE
        binding.navSave.isEnabled = true
        setResult(Activity.RESULT_OK)
        Toast.makeText(
            this,
            "已保存到本地，联网后将自动同步",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }
}
