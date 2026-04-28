package com.example.teleprompter

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.teleprompter.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首页 - 提词文稿列表（使用API）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ScriptListAdapter
    private val scripts = mutableListOf<Script>()

    // 编辑Activity结果回调
    private val editLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // 编辑完成，重新加载列表
            loadScriptsFromApi()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupSwipeRefresh()
        setupFab()
        loadScriptsFromApi()
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeColors(0xFF3B30.toInt())
        binding.swipeRefresh.setOnRefreshListener {
            loadScriptsFromApi()
        }
    }

    private fun setupRecyclerView() {
        adapter = ScriptListAdapter(
            scripts = scripts,
            onEditClick = { script -> launchEditActivity(script) },
            onDeleteClick = { script -> deleteScript(script) },
            onRecordClick = { script -> launchRecord(script) },
            onViewClick = { script -> launchViewActivity(script) }
        )

        binding.scriptList.layoutManager = LinearLayoutManager(this)
        binding.scriptList.adapter = adapter
        updateEmptyState()
    }

    /**
     * 从API加载文稿列表
     */
    private fun loadScriptsFromApi() {
        lifecycleScope.launch {
            binding.loadingIndicator?.visibility = View.VISIBLE

            val result = withContext(Dispatchers.IO) {
                VideoScriptApiService.fetchScripts()
            }

            binding.loadingIndicator?.visibility = View.GONE
            binding.swipeRefresh.isRefreshing = false

            result.fold(
                onSuccess = { response ->
                    Log.d("MainActivity", "API成功: ${response.scripts.size} 个文稿")
                    scripts.clear()
                    response.scripts.forEach { apiScript ->
                        scripts.add(VideoScriptApiService.toScript(apiScript))
                    }
                    adapter.notifyDataSetChanged()
                    updateCountBadge()
                    updateEmptyState()
                },
                onFailure = { error ->
                    Log.e("MainActivity", "API失败: ${error.message}", error)
                    Toast.makeText(this@MainActivity, "加载失败: ${error.message}", Toast.LENGTH_SHORT).show()
                    updateEmptyState()
                }
            )
        }
    }

    private fun launchViewActivity(script: Script) {
        val intent = Intent(this, ScriptViewActivity::class.java).apply {
            putExtra(ScriptViewActivity.EXTRA_TITLE, script.title)
            putExtra(ScriptViewActivity.EXTRA_CONTENT, script.content)
        }
        startActivity(intent)
    }

    private fun setupFab() {
        binding.fabAdd.setOnClickListener {
            launchEditActivity(null)
        }
    }

    private fun launchEditActivity(script: Script?) {
        val intent = Intent(this, ScriptEditActivity::class.java).apply {
            if (script != null) {
                putExtra(ScriptEditActivity.EXTRA_IS_NEW, false)
                putExtra(ScriptEditActivity.EXTRA_SCRIPT_ID, script.id)
                putExtra(ScriptEditActivity.EXTRA_SCRIPT_TITLE, script.title)
                putExtra(ScriptEditActivity.EXTRA_SCRIPT_CONTENT, script.content)
            } else {
                putExtra(ScriptEditActivity.EXTRA_IS_NEW, true)
            }
        }
        editLauncher.launch(intent)
    }

    private fun deleteScript(script: Script) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                VideoScriptApiService.deleteScript(script.id)
            }

            result.fold(
                onSuccess = { success ->
                    if (success) {
                        adapter.removeScript(script)
                        updateCountBadge()
                        updateEmptyState()
                        Toast.makeText(this@MainActivity, "已删除: ${script.title}", Toast.LENGTH_SHORT).show()
                    }
                },
                onFailure = { error ->
                    Toast.makeText(this@MainActivity, "删除失败: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun launchRecord(script: Script) {
        val intent = Intent(this, VideoRecordActivity::class.java).apply {
            putExtra(VideoRecordActivity.EXTRA_SCRIPT, script.content)
            putExtra(VideoRecordActivity.EXTRA_APP_ID, "9578212060")
            putExtra(VideoRecordActivity.EXTRA_ACCESS_TOKEN, "BVI-b4p-L8ZP_q0iek85BCWDFbJ5-3hc")
        }
        startActivity(intent)
    }

    private fun updateCountBadge() {
        val count = adapter.itemCount
        binding.countText.text = "$count 个"
        updateEmptyState()
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