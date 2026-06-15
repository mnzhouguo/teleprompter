package com.example.teleprompter

/**
 * 去掉文稿正文中不需要在提词界面展示的元数据行。
 */
object ScriptContentFilter {

    private val DROP_LINE = Regex("""^\s*(讨论侧重|素材个数)\s*[：:].*$""")

    fun forDisplay(content: String): String {
        if (content.isEmpty()) return content
        return content.lineSequence()
            .filterNot { DROP_LINE.matches(it) }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
