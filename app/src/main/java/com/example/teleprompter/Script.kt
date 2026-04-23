package com.example.teleprompter

/**
 * 提词文稿数据模型
 */
data class Script(
    val id: Long = System.currentTimeMillis(),
    val title: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /**
     * 获取字数统计
     */
    val charCount: Int
        get() = content.length

    /**
     * 估算朗读时长（分钟），按200字/分钟计算
     */
    val estimatedMinutes: Int
        get() = if (charCount > 0) Math.ceil(charCount / 200.0).toInt() else 0

    /**
     * 获取内容预览（前50字）
     */
    val preview: String
        get() = if (content.length > 50) content.substring(0, 50) + "..." else content

    /**
     * 格式化时长显示
     */
    val durationText: String
        get() = "~${estimatedMinutes}分钟"

    /**
     * 格式化创建时间显示
     */
    val createdTimeText: String
        get() {
            val now = System.currentTimeMillis()
            val diff = now - createdAt
            val days = (diff / (24 * 60 * 60 * 1000)).toInt()
            return when (days) {
                0 -> "今天"
                1 -> "昨天"
                in 2..6 -> "${days}天前"
                else -> {
                    val date = java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault())
                        .format(java.util.Date(createdAt))
                    date
                }
            }
        }
}