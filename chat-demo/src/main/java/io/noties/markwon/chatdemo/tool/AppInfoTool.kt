package io.noties.markwon.chatdemo.tool

import android.content.Context
import android.os.Debug
import org.json.JSONObject

/** 应用信息查询：包名/版本号/版本名/当前进程内存占用等 */
class AppInfoTool : ChatTool {

    override val name: String = "query_app_info"

    override val description: String = "查询本应用（chat-demo）的应用信息，包括包名、版本名、版本号、进程内存占用等。当用户询问应用信息、版本号、包名、内存占用时使用。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(context: Context, args: JSONObject): String {
        val pkgInfo = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: Exception) {
            null
        }
        val memInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memInfo)
        val heapMB = memInfo.getTotalPss() / 1024
        return """
| 项目 | 值 |
| --- | --- |
| 应用名称 | ${context.getApplicationInfo()?.loadLabel(context.packageManager)} |
| 包名 | ${context.packageName} |
| 版本名 | ${pkgInfo?.versionName ?: "未知"} |
| 版本号 | ${pkgInfo?.versionCode ?: "未知"} |
| 当前进程内存(PSS) | ${heapMB} MB |
""".trimIndent()
    }
}