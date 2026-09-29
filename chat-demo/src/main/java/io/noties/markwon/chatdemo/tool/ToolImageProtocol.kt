package io.noties.markwon.chatdemo.tool

/**
 * 工具结果「内联图片」标记协议
 *
 * 工具在返回文本末尾附加一行 `[[image:图片绝对路径]]`，聊天页解析该标记后在
 * 工具步骤卡片内展示图片（如生成的二维码）+ 本地路径 + 「保存到相册」按钮。
 * 标记行会随工具结果一并回传给模型，不影响模型理解（模型也可引用该路径）。
 */
object ToolImageProtocol {

    private const val PREFIX = "[[image:"
    private const val SUFFIX = "]]"

    /** 生成标记文本（工具返回结果时追加） */
    fun mark(path: String): String = "$PREFIX$path$SUFFIX"

    /** 提取首个图片路径（无标记返回 null） */
    fun extract(result: String): String? {
        val start = result.indexOf(PREFIX)
        if (start < 0) return null
        val end = result.indexOf(SUFFIX, start + PREFIX.length)
        if (end < 0) return null
        return result.substring(start + PREFIX.length, end).trim().ifEmpty { null }
    }

    /** 移除标记后的文本（UI 摘要展示用） */
    fun stripMarker(result: String): String {
        val path = extract(result) ?: return result
        return result.replace(mark(path), "").trim()
    }
}