package com.example.teleprompter

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.teleprompter.databinding.ActivitySyncSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 同步设置页：服务器地址 + 访问 token，支持「测试连接」。
 */
class SyncSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySyncSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySyncSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 手填优先；未配置时预填文档联调默认值，便于直接测试
        binding.inputBaseUrl.setText(
            SyncSettingsStore.getStoredBaseUrl(this)
                .ifEmpty { MobileSyncApiService.DEFAULT_BASE_URL }
        )
        binding.inputToken.setText(
            SyncSettingsStore.getStoredToken(this)
                .ifEmpty { MobileSyncApiService.DEFAULT_TOKEN }
        )

        binding.navBack.setOnClickListener { finish() }
        binding.btnTest.setOnClickListener { testConnection() }
        binding.btnSave.setOnClickListener { save() }
    }

    private fun currentBaseUrl() = binding.inputBaseUrl.text.toString().trim()
        .ifEmpty { MobileSyncApiService.DEFAULT_BASE_URL }

    private fun currentToken() = binding.inputToken.text.toString().trim()
        .ifEmpty { MobileSyncApiService.DEFAULT_TOKEN }

    private fun testConnection() {
        val baseUrl = currentBaseUrl()
        val token = currentToken()

        lifecycleScope.launch {
            setTesting(true)
            val result = withContext(Dispatchers.IO) {
                MobileSyncApiService.fetchSpeeches(baseUrl, token)
            }
            setTesting(false)

            result.fold(
                onSuccess = { speeches ->
                    showResult("连接成功，读到 ${speeches.size} 篇口语稿", isError = false)
                },
                onFailure = { error ->
                    showResult("连接失败：${error.message}", isError = true)
                }
            )
        }
    }

    private fun save() {
        val baseUrl = currentBaseUrl()
        val token = currentToken()
        if (baseUrl.isEmpty() || token.isEmpty()) {
            showResult("服务器地址与 token 都不能为空", isError = true)
            return
        }
        SyncSettingsStore.save(this, baseUrl, token)
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun setTesting(testing: Boolean) {
        binding.testProgress.visibility = if (testing) View.VISIBLE else View.GONE
        binding.testLabel.visibility = if (testing) View.INVISIBLE else View.VISIBLE
        binding.btnTest.isEnabled = !testing
        binding.btnSave.isEnabled = !testing
    }

    private fun showResult(message: String, isError: Boolean) {
        binding.resultText.visibility = View.VISIBLE
        binding.resultText.text = message
        binding.resultText.setTextColor(if (isError) 0xFFFF9F0A.toInt() else 0xFF34C759.toInt())
    }
}
