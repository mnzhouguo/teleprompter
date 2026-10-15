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
import com.example.teleprompter.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首页：口语稿列表（PC 创作工作台 mobile/speeches）。
 * 支持离线新增/编辑，联网后同步；冲突以云端为准。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: RemoteSpeechAdapter

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            loadSpeeches(showCenterSpinner = false)
        }
    }

    private val editLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // 离线保存后先立刻刷新本地列表，再尝试联网同步
            showCachedSpeeches()
            loadSpeeches(showCenterSpinner = false)
        }
    }

    private val recordLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 录制结束回首页
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupSwipeRefresh()
        setupFab()
        setupSettingsEntry()

        showCachedSpeeches()
        loadSpeeches(showCenterSpinner = true)
    }

    private fun setupSettingsEntry() {
        binding.headerSync.setOnClickListener {
            settingsLauncher.launch(Intent(this, SyncSettingsActivity::class.java))
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeColors(0xFFFF3B30.toInt())
        // 下拉只用 SwipeRefresh 自带圆圈，不再叠中间 ProgressBar
        binding.swipeRefresh.setOnRefreshListener {
            loadSpeeches(showCenterSpinner = false)
        }
    }

    private fun setupRecyclerView() {
        adapter = RemoteSpeechAdapter(
            speeches = mutableListOf(),
            onSpeechClick = { speech -> openForRecord(speech) },
            onEditClick = { speech -> openForEdit(speech) }
        )
        binding.scriptList.layoutManager = LinearLayoutManager(this)
        binding.scriptList.adapter = adapter
        updateEmptyState()
    }

    private fun setupFab() {
        binding.fabAdd.setOnClickListener { openForCreate() }
    }

    private fun openForCreate() {
        editLauncher.launch(
            Intent(this, MobileSpeechEditActivity::class.java).apply {
                putExtra(MobileSpeechEditActivity.EXTRA_IS_NEW, true)
            }
        )
    }

    private fun openForEdit(speech: MobileSyncApiService.RemoteSpeech) {
        val pending = PendingSpeechStore.loadPending(this)

        // 本地待同步新建
        if (PendingSpeechSyncLogic.isLocalPendingId(speech.id)) {
            val local = PendingSpeechSyncLogic.findByLocalDisplayId(pending, speech.id)
            if (local == null) {
                Toast.makeText(this, "本地草稿不存在", Toast.LENGTH_SHORT).show()
                return
            }
            launchEdit(
                isNew = true,
                speechId = 0L,
                localKey = local.localKey,
                title = local.title,
                content = local.content,
                baselineUpdatedAt = ""
            )
            return
        }

        // 本地待同步编辑
        val localUpdate = PendingSpeechSyncLogic.findUpdateByRemoteId(pending, speech.id)
        if (localUpdate != null) {
            launchEdit(
                isNew = false,
                speechId = speech.id,
                localKey = localUpdate.localKey,
                title = localUpdate.title,
                content = localUpdate.content,
                baselineUpdatedAt = localUpdate.baselineUpdatedAt
            )
            return
        }

        val cached = PendingSpeechStore.loadDetail(this, speech.id)

        // 离线：只用本地全文，绝不请求网络
        if (!NetworkReachability.isOnline(this)) {
            if (cached != null) {
                launchEdit(
                    isNew = false,
                    speechId = cached.id,
                    localKey = PendingSpeechStore.newLocalKey(),
                    title = cached.title,
                    content = cached.content,
                    baselineUpdatedAt = cached.updatedAt
                )
            } else {
                Toast.makeText(
                    this,
                    "离线暂无全文，请先联网同步一次后再编辑",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

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
                    PendingSpeechStore.saveDetail(this@MainActivity, detail)
                    launchEdit(
                        isNew = false,
                        speechId = detail.id,
                        localKey = PendingSpeechStore.newLocalKey(),
                        title = detail.title,
                        content = detail.content,
                        baselineUpdatedAt = detail.updatedAt
                    )
                },
                onFailure = { error ->
                    if (cached != null) {
                        launchEdit(
                            isNew = false,
                            speechId = cached.id,
                            localKey = PendingSpeechStore.newLocalKey(),
                            title = cached.title,
                            content = cached.content,
                            baselineUpdatedAt = cached.updatedAt
                        )
                        Toast.makeText(
                            this@MainActivity,
                            "已用本地缓存打开（联网后可同步）",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "打开编辑失败：${error.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
        }
    }

    private fun launchEdit(
        isNew: Boolean,
        speechId: Long,
        localKey: String,
        title: String,
        content: String,
        baselineUpdatedAt: String
    ) {
        editLauncher.launch(
            Intent(this, MobileSpeechEditActivity::class.java).apply {
                putExtra(MobileSpeechEditActivity.EXTRA_IS_NEW, isNew)
                putExtra(MobileSpeechEditActivity.EXTRA_SPEECH_ID, speechId)
                putExtra(MobileSpeechEditActivity.EXTRA_LOCAL_KEY, localKey)
                putExtra(MobileSpeechEditActivity.EXTRA_SPEECH_TITLE, title)
                putExtra(MobileSpeechEditActivity.EXTRA_SPEECH_CONTENT, content)
                putExtra(MobileSpeechEditActivity.EXTRA_BASELINE_UPDATED_AT, baselineUpdatedAt)
            }
        )
    }

    private fun showCachedSpeeches() {
        val merged = SpeechOfflineSync.mergeCachedListWithPending(this)
        val pending = PendingSpeechStore.loadPending(this)
        val pendingIds = PendingSpeechSyncLogic.pendingRemoteIds(pending)
        if (merged.isEmpty()) {
            updateCountBadge(0)
            updateEmptyState()
            showStatus("正在从创作工作台拉取文稿…", isError = false)
            return
        }
        adapter.submit(merged, pendingIds)
        updateCountBadge(merged.size)
        updateEmptyState()
        val pendingHint = if (pending.isNotEmpty()) "，待同步 ${pending.size} 条" else ""
        showStatus("已加载本地缓存 ${merged.size} 条$pendingHint，正在同步…", isError = false)
    }

    /**
     * @param showCenterSpinner 首次进入等场景用中间圈；下拉刷新只用 SwipeRefresh 圆圈，避免叠两个。
     */
    private fun loadSpeeches(showCenterSpinner: Boolean = false) {
        val baseUrl = SyncSettingsStore.getBaseUrl(this)
        val token = SyncSettingsStore.getToken(this)

        lifecycleScope.launch {
            if (showCenterSpinner) {
                binding.loadingIndicator.visibility = View.VISIBLE
            }

            val flushResult = withContext(Dispatchers.IO) {
                if (NetworkReachability.isOnline(this@MainActivity) &&
                    PendingSpeechStore.loadPending(this@MainActivity).isNotEmpty()
                ) {
                    SpeechOfflineSync.flush(this@MainActivity)
                } else {
                    SpeechOfflineSync.FlushResult(0, 0, 0)
                }
            }

            val result = withContext(Dispatchers.IO) {
                MobileSyncApiService.fetchSpeeches(baseUrl, token)
            }

            result.fold(
                onSuccess = { speeches ->
                    SyncSettingsStore.saveCachedSpeeches(this@MainActivity, speeches)
                    val pending = PendingSpeechStore.loadPending(this@MainActivity)
                    val merged = PendingSpeechSyncLogic.mergeForDisplay(speeches, pending)
                    val pendingIds = PendingSpeechSyncLogic.pendingRemoteIds(pending)
                    adapter.submit(merged, pendingIds)
                    updateCountBadge(merged.size)
                    updateEmptyState()

                    val parts = mutableListOf<String>()
                    if (flushResult.synced > 0) parts.add("已上传 ${flushResult.synced} 条本地稿")
                    if (flushResult.discardedByConflict > 0) {
                        parts.add("冲突 ${flushResult.discardedByConflict} 条已用云端覆盖")
                    }
                    if (flushResult.failed > 0) parts.add("${flushResult.failed} 条仍待同步")
                    if (pending.isNotEmpty() && flushResult.synced == 0 && flushResult.failed == 0) {
                        parts.add("待同步 ${pending.size} 条")
                    }
                    val flushHint = if (parts.isNotEmpty()) parts.joinToString("；") + "。" else ""

                    if (merged.isEmpty()) {
                        binding.loadingIndicator.visibility = View.GONE
                        binding.swipeRefresh.isRefreshing = false
                        showStatus("${flushHint}暂无文稿，可点下方 + 新建", isError = false)
                    } else {
                        showStatus(
                            "${flushHint}已同步 ${speeches.size} 篇，正在缓存全文以便离线编辑…",
                            isError = false
                        )
                        // 缓存阶段：下拉继续转 Swipe 圈；非下拉才用中间圈
                        if (showCenterSpinner) {
                            binding.loadingIndicator.visibility = View.VISIBLE
                        }
                        val cachedFull = withContext(Dispatchers.IO) {
                            cacheFullContentsForOffline(speeches)
                        }
                        binding.loadingIndicator.visibility = View.GONE
                        binding.swipeRefresh.isRefreshing = false
                        showStatus(
                            "${flushHint}已同步 ${speeches.size} 篇，已缓存全文 $cachedFull 篇，可离线编辑/录制",
                            isError = false
                        )
                    }
                },
                onFailure = { error ->
                    binding.loadingIndicator.visibility = View.GONE
                    binding.swipeRefresh.isRefreshing = false
                    Log.e("MainActivity", "同步失败: ${error.message}", error)
                    showCachedSpeeches()
                    showStatus(
                        "同步失败：${error.message}\n显示本地缓存（含待同步草稿）",
                        isError = true
                    )
                }
            )
        }
    }

    /**
     * 云端列表同步后，把每篇全文落到本地。
     * 有本地待同步编辑的条目跳过，避免冲掉未上传改动。
     * @return 本地已具备全文的篇数
     */
    private fun cacheFullContentsForOffline(
        speeches: List<MobileSyncApiService.RemoteSpeech>
    ): Int {
        val baseUrl = SyncSettingsStore.getBaseUrl(this)
        val token = SyncSettingsStore.getToken(this)
        val pending = PendingSpeechStore.loadPending(this)
        var ready = 0

        for (speech in speeches) {
            val hasPendingEdit = PendingSpeechSyncLogic.findUpdateByRemoteId(pending, speech.id) != null
            if (hasPendingEdit) {
                ready++
                continue
            }

            val cached = PendingSpeechStore.loadDetail(this, speech.id)
            val upToDate = cached != null &&
                cached.content.isNotBlank() &&
                (speech.updatedAt.isBlank() || cached.updatedAt == speech.updatedAt)

            if (upToDate) {
                ready++
                continue
            }

            val result = MobileSyncApiService.fetchSpeech(baseUrl, token, speech.id)
            if (result.isSuccess) {
                PendingSpeechStore.saveDetail(this, result.getOrThrow())
                ready++
            } else if (cached != null && cached.content.isNotBlank()) {
                // 拉取失败但旧缓存仍可用
                ready++
            }
        }
        return ready
    }

    /**
     * 解析本地可用正文：待同步草稿 > 详情缓存。
     * 离线录制只依赖这里，不再请求网络。
     */
    private fun resolveLocalRecordContent(speech: MobileSyncApiService.RemoteSpeech): String? {
        val pending = PendingSpeechStore.loadPending(this)

        if (PendingSpeechSyncLogic.isLocalPendingId(speech.id)) {
            val local = PendingSpeechSyncLogic.findByLocalDisplayId(pending, speech.id)
            return local?.content
        }

        PendingSpeechSyncLogic.findUpdateByRemoteId(pending, speech.id)?.let {
            return it.content
        }

        return PendingSpeechStore.loadDetail(this, speech.id)?.content
    }

    private fun openForRecord(speech: MobileSyncApiService.RemoteSpeech) {
        val localContent = resolveLocalRecordContent(speech)
        if (!localContent.isNullOrBlank()) {
            val content = ScriptContentFilter.forDisplay(
                ScriptContentFilter.stripMarkdown(localContent)
            )
            if (content.isBlank()) {
                Toast.makeText(this, "该文稿内容为空", Toast.LENGTH_SHORT).show()
                return
            }
            launchRecord(content)
            return
        }

        // 离线且无本地正文：无法录制（列表只有预览）
        if (!NetworkReachability.isOnline(this)) {
            Toast.makeText(
                this,
                "离线暂无全文，请先联网同步一次后再录制",
                Toast.LENGTH_LONG
            ).show()
            return
        }

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
                    PendingSpeechStore.saveDetail(this@MainActivity, detail)
                    val content = ScriptContentFilter
                        .forDisplay(ScriptContentFilter.stripMarkdown(detail.content))
                    if (content.isBlank()) {
                        Toast.makeText(this@MainActivity, "该文稿内容为空", Toast.LENGTH_SHORT).show()
                        return@fold
                    }
                    launchRecord(content)
                },
                onFailure = { error ->
                    Toast.makeText(
                        this@MainActivity,
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

    private fun updateCountBadge(count: Int) {
        binding.countText.text = "$count 个"
    }

    private fun updateEmptyState() {
        if (adapter.itemCount == 0) {
            binding.emptyState.visibility = View.VISIBLE
            binding.scriptList.visibility = View.GONE
        } else {
            binding.emptyState.visibility = View.GONE
            binding.scriptList.visibility = View.VISIBLE
        }
    }
}
