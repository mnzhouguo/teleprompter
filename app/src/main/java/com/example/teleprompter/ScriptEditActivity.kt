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
 * 编辑/新增文稿页面（使用API）
 */
class ScriptEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SCRIPT_ID = "script_id"
        const val EXTRA_SCRIPT_TITLE = "script_title"
        const val EXTRA_SCRIPT_CONTENT = "script_content"
        const val EXTRA_IS_NEW = "is_new"
    }

    private lateinit var binding: ActivityScriptEditBinding
    private var isNewScript: Boolean = true
    private var scriptId: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScriptEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 获取传入参数
        isNewScript = intent.getBooleanExtra(EXTRA_IS_NEW, true)
        scriptId = intent.getLongExtra(EXTRA_SCRIPT_ID, 0)

        if (!isNewScript) {
            binding.navTitle.text = "编辑文稿"
            binding.titleInput.setText(intent.getStringExtra(EXTRA_SCRIPT_TITLE) ?: "")
            binding.contentInput.setText(intent.getStringExtra(EXTRA_SCRIPT_CONTENT) ?: "")
        } else {
            binding.navTitle.text = "新增文稿"
        }

        // 返回按钮
        binding.navBack.setOnClickListener {
            finish()
        }

        // 保存按钮
        binding.navSave.setOnClickListener {
            saveScript()
        }

        setupTextWatcher()
    }

    private fun setupTextWatcher() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateStats()
            }
        }

        binding.titleInput.addTextChangedListener(watcher)
        binding.contentInput.addTextChangedListener(watcher)
        updateStats()
    }

    private fun updateStats() {
        val content = binding.contentInput.text.toString()
        val charCount = content.length
        val minutes = if (charCount > 0) Math.ceil(charCount / 200.0).toInt() else 0

        binding.charCount.text = "$charCount 字"
        binding.timeEstimate.text = "~$minutes 分钟"
    }

    private fun saveScript() {
        val title = binding.titleInput.text.toString().trim()
        val content = binding.contentInput.text.toString().trim()

        if (title.isEmpty()) {
            Toast.makeText(this, "请输入标题", Toast.LENGTH_SHORT).show()
            return
        }

        if (content.isEmpty()) {
            Toast.makeText(this, "请输入内容", Toast.LENGTH_SHORT).show()
            return
        }

        // 调用API保存
        lifecycleScope.launch {
            binding.loadingOverlay.visibility = View.VISIBLE
            binding.navSave.isEnabled = false

            val result = withContext(Dispatchers.IO) {
                if (isNewScript) {
                    VideoScriptApiService.createScript(title, content)
                } else {
                    VideoScriptApiService.updateScript(scriptId, title, content)
                }
            }

            binding.loadingOverlay.visibility = View.GONE
            binding.navSave.isEnabled = true

            result.fold(
                onSuccess = { apiScript ->
                    setResult(Activity.RESULT_OK)
                    Toast.makeText(
                        this@ScriptEditActivity,
                        if (isNewScript) "文稿已创建" else "文稿已更新",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                },
                onFailure = { error ->
                    Toast.makeText(
                        this@ScriptEditActivity,
                        "保存失败: ${error.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }
}