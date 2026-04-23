package com.example.teleprompter

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.teleprompter.databinding.ActivityScriptEditBinding

/**
 * 编辑/新增文稿页面
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
        scriptId = intent.getLongExtra(EXTRA_SCRIPT_ID, System.currentTimeMillis())

        if (!isNewScript) {
            // 编辑模式：填充已有内容
            binding.navTitle.text = "编辑文稿"
            binding.titleInput.setText(intent.getStringExtra(EXTRA_SCRIPT_TITLE) ?: "")
            binding.contentInput.setText(intent.getStringExtra(EXTRA_SCRIPT_CONTENT) ?: "")
        } else {
            // 新增模式
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

        // 实时更新字数统计和时长预估
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

        // 初始统计
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

        // 创建/更新Script对象
        val script = Script(
            id = scriptId,
            title = title,
            content = content,
            createdAt = if (isNewScript) System.currentTimeMillis() else intent.getLongExtra(EXTRA_SCRIPT_ID, System.currentTimeMillis()),
            updatedAt = System.currentTimeMillis()
        )

        // 返回结果给MainActivity（后续会替换为数据库保存）
        setResult(RESULT_OK, intent.apply {
            putExtra(EXTRA_SCRIPT_ID, script.id)
            putExtra(EXTRA_SCRIPT_TITLE, script.title)
            putExtra(EXTRA_SCRIPT_CONTENT, script.content)
            putExtra(EXTRA_IS_NEW, isNewScript)
        })

        Toast.makeText(this, if (isNewScript) "文稿已创建" else "文稿已更新", Toast.LENGTH_SHORT).show()
        finish()
    }
}