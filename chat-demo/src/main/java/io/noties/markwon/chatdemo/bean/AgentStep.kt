package io.noties.markwon.chatdemo.bean

/**
 * Agent 工具调用步骤（仅 UI/运行态，不持久化）
 */
class AgentStep(
    val toolName: String,
    val arguments: String = "",
    var status: Int = STATUS_RUNNING
) {

    companion object {
        const val STATUS_RUNNING = 0
        const val STATUS_DONE = 1
        const val STATUS_FAILED = 2
    }

    var title: String = ""
        get() = if (field.isNotEmpty()) field else sanitize(toolName)

    /** 工具执行结果（截断用于 UI 展示） */
    var summary: String = ""

    /** 工具展示标签 */
    fun statusText(): String = when (status) {
        STATUS_RUNNING -> "执行中…"
        STATUS_DONE -> "已完成"
        STATUS_FAILED -> "失败"
        else -> ""
    }

    /** 是否失败 */
    fun isFailed(): Boolean = status == STATUS_FAILED

    private fun sanitize(name: String): String {
        val segment = name.substringAfterLast('_')
        if (segment.isEmpty()) return name
        return segment.substring(0, 1).toUpperCase() + segment.substring(1)
    }
}