package com.example.teleprompter

import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 浏览文稿页面 - 只读查看
 */
class ScriptViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_CONTENT = "content"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_script_view)

        // 获取传入的文稿数据
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "文稿"
        val content = intent.getStringExtra(EXTRA_CONTENT) ?: ""

        // 设置标题
        findViewById<TextView>(R.id.view_title).text = title

        // 设置内容
        findViewById<TextView>(R.id.view_content).text = content

        // 设置字数统计
        findViewById<TextView>(R.id.view_char_count).text = "${content.length}字"

        // 返回按钮
        findViewById<ImageView>(R.id.back_btn).setOnClickListener {
            finish()
        }
    }
}