package com.example.teleprompter

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.teleprompter.databinding.ActivityMainBinding

/**
 * 首页 - 提词文稿列表
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ScriptListAdapter

    // 演示数据（后续替换为数据库）
    private val demoScripts = mutableListOf(
        Script(
            id = 1,
            title = "产品介绍视频脚本",
            content = "大家好，欢迎来到我们的产品发布会。今天，我要向大家介绍一款革命性的智能设备——它将彻底改变您的视频创作体验。",
            createdAt = System.currentTimeMillis()
        ),
        Script(
            id = 2,
            title = "Vlog 日常分享",
            content = "嘿大家好，今天又是美好的一天。我想和大家分享一下我最近的生活，最近我在尝试一款新的智能提词器应用，效果非常好。",
            createdAt = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        ),
        Script(
            id = 3,
            title = "直播带货话术",
            content = "亲爱的家人们好！今天给大家带来的是我们精心挑选的优质商品，限时特价，不要错过这个机会哦！",
            createdAt = System.currentTimeMillis() - 3 * 24 * 60 * 60 * 1000
        )
    )

    // 编辑Activity结果回调
    private val editLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val id = data.getLongExtra(ScriptEditActivity.EXTRA_SCRIPT_ID, 0)
            val title = data.getStringExtra(ScriptEditActivity.EXTRA_SCRIPT_TITLE) ?: ""
            val content = data.getStringExtra(ScriptEditActivity.EXTRA_SCRIPT_CONTENT) ?: ""
            val isNew = data.getBooleanExtra(ScriptEditActivity.EXTRA_IS_NEW, true)

            val script = Script(id = id, title = title, content = content)

            if (isNew) {
                adapter.addScript(script)
                updateCountBadge()
            } else {
                adapter.updateScript(script)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupFab()
        updateCountBadge()
    }

    private fun setupRecyclerView() {
        adapter = ScriptListAdapter(
            scripts = demoScripts,
            onEditClick = { script -> launchEditActivity(script) },
            onDeleteClick = { script -> deleteScript(script) },
            onRecordClick = { script -> launchRecord(script) },
            onViewClick = { script -> launchViewActivity(script) }  // 双击查看
        )

        binding.scriptList.layoutManager = LinearLayoutManager(this)
        binding.scriptList.adapter = adapter

        // 空状态显示
        updateEmptyState()
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
            launchEditActivity(null) // 新增模式
        }
    }

    private fun launchEditActivity(script: Script?) {
        val intent = Intent(this, ScriptEditActivity::class.java).apply {
            if (script != null) {
                // 编辑模式
                putExtra(ScriptEditActivity.EXTRA_IS_NEW, false)
                putExtra(ScriptEditActivity.EXTRA_SCRIPT_ID, script.id)
                putExtra(ScriptEditActivity.EXTRA_SCRIPT_TITLE, script.title)
                putExtra(ScriptEditActivity.EXTRA_SCRIPT_CONTENT, script.content)
            } else {
                // 新增模式
                putExtra(ScriptEditActivity.EXTRA_IS_NEW, true)
            }
        }
        editLauncher.launch(intent)
    }

    private fun deleteScript(script: Script) {
        // 演示：直接删除（后续添加确认对话框和数据库操作）
        adapter.removeScript(script)
        updateCountBadge()
        updateEmptyState()
        Toast.makeText(this, "已删除: ${script.title}", Toast.LENGTH_SHORT).show()
    }

    private fun launchRecord(script: Script) {
        // 跳转到视频录制页面，传入文稿内容
        val intent = Intent(this, VideoRecordActivity::class.java).apply {
            putExtra(VideoRecordActivity.EXTRA_SCRIPT, script.content)
            // ASR credentials (从原MainActivity移过来)
            putExtra(VideoRecordActivity.EXTRA_APP_ID, "9578212060")
            putExtra(VideoRecordActivity.EXTRA_ACCESS_TOKEN, "BVI-b4p-L8ZP_q0iek85BCWDFbJ5-3hc")
        }
        startActivity(intent)
        Toast.makeText(this, "开始录制: ${script.title}", Toast.LENGTH_SHORT).show()
    }

    private fun updateCountBadge() {
        val count = adapter.itemCount
        binding.countText.text = "$count 个文稿"
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