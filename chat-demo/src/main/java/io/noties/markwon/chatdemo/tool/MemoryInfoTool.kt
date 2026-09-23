package io.noties.markwon.chatdemo.tool

import android.app.ActivityManager
import android.content.Context
import org.json.JSONObject

/** 内存信息查询：系统总内存/可用内存/低内存状态 */
class MemoryInfoTool : ChatTool {

    override val name: String = "query_memory_info"

    override val description: String = "查询设备系统内存情况，包括总内存、可用内存、是否处于低内存状态。当用户询问内存、RAM、运存时使用。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(context: Context, args: JSONObject): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return """
| 项目 | 值 |
| --- | --- |
| 总内存(RAM) | ${format(memInfo.totalMem)} |
| 可用内存 | ${format(memInfo.availMem)} |
| 低内存状态 | ${if (memInfo.lowMemory) "是" else "否"} |
| 阈值 | ${format(memInfo.threshold)} |
| 应用内存上限 | ${am.memoryClass} MB |
""".trimIndent()
    }

    private fun format(bytes: Long): String =
        if (bytes > 0) "%.1f".format(bytes / (1024.0 * 1024.0 * 1024.0)) + " GB" else "0"
}