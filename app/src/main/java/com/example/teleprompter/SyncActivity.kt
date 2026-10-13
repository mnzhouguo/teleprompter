package com.example.teleprompter

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.teleprompter.databinding.ActivitySyncBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 口语稿同步页：列表 / 创作 / 编辑 / 提词录制。
 */
class SyncActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySyncBinding
    private lateinit var adapter: RemoteSpeechAdapter

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            loadSpeeches()
        }
    }

    private val editLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            loadSpeeches()
        }
    }

    private val recordLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 录制结束返回同步页
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySyncBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupActions()

        showCachedSpeeches()
        loadSpeeches()
    }

    private fun setupRecyclerView() {
        adapter = RemoteSpeechAdapter(
            speeches = mutableListOf(),
            onSpeechClick = { speech -> openForRecord(speech) },
            onEditClick = { speech -> openForEdit(speech) }
        )
        binding.speechList.layoutManager = LinearLayoutManager(this)
        binding.speechList.adapter = adapter
    }

    private fun setupActions() {
        binding.navBack.setOnClickListener { finish() }
        binding.navCreate.setOnClickListener { openForCreate() }
        binding.navSettings.setOnClickListener {
            settingsLauncher.launch(Intent(this, SyncSettingsActivity::class.java))
        }
        binding.swipeRefresh.setColorSchemeColors(0xFFFF3B30.toInt())
        binding.swipeRefresh.setOnRefreshListener { loadSpeeches() }
    }

    private fun openForCreate() {
        editLauncher.launch(
            Intent(this, MobileSpeechEditActivity::class.java).apply {
                putExtra(MobileSpeechEditActivity.EXTRA_IS_NEW, true)
            }
        )
    }

    private fun openForEdit(speech: MobileSyncApiService.RemoteSpeech) {
        val baseUrl = SyncSettingsStore.getBaseUrl(this)
        val token = SyncSettingsStore.getToken(this)

        lifecycleScope.launch {
            binding.loadingIndicator.visibility = View.VISIBLE
            val result = withContext(Dispatchers.IO) {
                MobileSyncApiService.fetchSpeech(baseUrl, token, speech.id)
            }
            binding.loadingIndicator.visibility = View.GONE

            result.fold(
                onSuccess = { detail ->
                    editLauncher.launch(
                        Intent(this@SyncActivity, MobileSpeechEditActivity::class.java).apply {
                            putExtra(MobileSpeechEditActivity.EXTRA_IS_NEW, false)
                            putExtra(MobileSpeechEditActivity.EXTRA_SPEECH_ID, detail.id)
                            putExtra(MobileSpeechEditActivity.EXTRA_SPEECH_TITLE, detail.title)
                            putExtra(MobileSpeechEditActivity.EXTRA_SPEECH_CONTENT, detail.content)
                        }
                    )
                },
                onFailure = { error ->
                    Toast.makeText(
                        this@SyncActivity,
                        "打开编辑失败：${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun showCachedSpeeches() {
        val cached = SyncSettingsStore.loadCachedSpeeches(this)
        if (cached.isEmpty()) {
            updateEmptyState()
            showStatus("正在从创作工作台拉取口语稿…", isError = false)
            return
        }
        adapter.submit(cached)
        updateEmptyState()
        showStatus("已加载本地缓存 ${cached.size} 条，正在同步…", isError = false)
    }

    private fun loadSpeeches() {
        val baseUrl = SyncSettingsStore.getBaseUrl(this)
        val token = SyncSettingsStore.getToken(this)

        lifecycleScope.launch {
            binding.loadingIndicator.visibility = View.VISIBLE

            val result = withContext(Dispatchers.IO) {
                MobileSyncApiService.fetchSpeeches(baseUrl, token)
            }

            binding.loadingIndicator.visibility = View.GONE
            binding.swipeRefresh.isRefreshing = false

            result.fold(
                onSuccess = { speeches ->
                    SyncSettingsStore.saveCachedSpeeches(this@SyncActivity, speeches)
                    adapter.submit(speeches)
                    updateEmptyState()
                    if (speeches.isEmpty()) {
                        showStatus("暂无进行中口语稿，可点「创作」新建", isError = false)
                    } else {
                        showStatus("已同步 ${speeches.size} 篇口语稿（最新在前）", isError = false)
                    }
                },
                onFailure = { error ->
                    Log.e("SyncActivity", "同步失败: ${error.message}", error)
                    updateEmptyState()
                    showStatus(
                        "同步失败：${error.message}\n条目为上次同步的缓存（点击仍可尝试拉取）",
                        isError = true
                    )
                }
            )
        }
    }

    private fun openForRecord(speech: MobileSyncApiService.RemoteSpeech) {
        val baseUrl = SyncSettingsStore.getBaseUrl(this)
        val token = SyncSettingsStore.getToken(this)

        lifecycleScope.launch {
            binding.loadingIndicator.visibility = View.VISIBLE
            val result = withContext(Dispatchers.IO) {
                MobileSyncApiService.fetchSpeech(baseUrl, token, speech.id)
            }
            binding.loadingIndicator.visibility = View.GONE

            result.fold(
                onSuccess = { detail ->
                    val content = ScriptContentFilter
                        .forDisplay(ScriptContentFilter.stripMarkdown(detail.content))
                    if (content.isBlank()) {
                        Toast.makeText(this@SyncActivity, "该口语稿内容为空", Toast.LENGTH_SHORT).show()
                        return@fold
                    }
                    launchRecord(content)
                },
                onFailure = { error ->
                    Toast.makeText(
                        this@SyncActivity,
                        "拉取失败：${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun launchRecord(content: String) {
        val intent = Intent(this, VideoRecordActivity::class.java).apply {
            putExtra(VideoRecordActivity.EXTRA_SCRIPT, content)
            putExtra(VideoRecordActivity.EXTRA_SCRIPT_ID, 0L)
            putExtra(VideoRecordActivity.EXTRA_APP_ID, AsrCredentials.APP_ID)
            putExtra(VideoRecordActivity.EXTRA_ACCESS_TOKEN, AsrCredentials.ACCESS_TOKEN)
        }
        recordLauncher.launch(intent)
    }

    private fun showStatus(message: String, isError: Boolean) {
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = message
        binding.statusText.setTextColor(if (isError) 0xFFFF9F0A.toInt() else 0xFFA1A1A6.toInt())
    }

    private fun updateEmptyState() {
        val empty = adapter.itemCount == 0
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.speechList.visibility = if (empty) View.GONE else View.VISIBLE
        binding.emptyTitle.text = "还没有口语稿"
        binding.emptyHint.text = "点右上角「创作」新建，或下拉刷新"
    }
}
