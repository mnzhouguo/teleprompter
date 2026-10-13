package com.example.teleprompter

/**
 * 去掉文稿正文中不需要在提词界面展示的内容。
 *
 * 提词录制约定：空行、Markdown 标题行不会口播，故不展示、不占行号。
 */
object ScriptContentFilter {

    private val DROP_LINE = Regex("""^\s*(讨论侧重|素材个数)\s*[：:].*$""")

    /** ATX 标题整行，如 `# 开场` / `## 要点` */
    private val MARKDOWN_HEADING_LINE = Regex("""^\s{0,3}#{1,6}(\s+.*)?$""")

    private val HEADING_PREFIX = Regex("""^\s{0,3}#{1,6}\s*""")
    private val LIST_MARKER = Regex("""^\s*(?:[-*+]|\d+\.)\s+""")
    private val QUOTE = Regex("""^\s{0,3}>\s?""")
    private val EMPHASIS = Regex("""\*\*|__|~~""")
    private val CODE_TICK = Regex("`")

    fun forDisplay(content: String): String {
        if (content.isEmpty()) return content
        return content.lineSequence()
            .filterNot { shouldDropLine(it) }
            .joinToString("\n")
            .trim()
    }

    /**
     * 去掉 Markdown 标记，并丢弃标题行与空行，得到适合提词展示的纯文本。
     */
    fun stripMarkdown(content: String): String {
        if (content.isEmpty()) return content
        return content.lineSequence()
            .filterNot { shouldDropLine(it) }
            .map { line ->
                line.replace(HEADING_PREFIX, "")
                    .replace(QUOTE, "")
                    .replace(LIST_MARKER, "")
                    .replace(EMPHASIS, "")
                    .replace(CODE_TICK, "")
            }
            .filterNot { it.isBlank() }
            .joinToString("\n")
            .trim()
    }

    /** 元数据行、空行、Markdown 标题行：提词时一律不展示 */
    fun shouldDropLine(line: String): Boolean {
        if (line.isBlank()) return true
        if (DROP_LINE.matches(line)) return true
        if (MARKDOWN_HEADING_LINE.matches(line)) return true
        return false
    }
}
