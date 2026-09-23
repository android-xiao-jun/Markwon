package io.noties.markwon.chatdemo.tool

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.noties.markwon.chatdemo.util.AppLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具注册中心：收集全部手机工具，生成 OpenAI tools schema，并按名执行
 *
 * 需要运行时权限的工具（联系人/公共存储/蓝牙连接）在执行时经
 * [ToolPermissionManager] 弹窗向用户申请授权，挂起等待结果。
 */
object ToolRegistry {

    private const val TAG = "ToolRegistry"

    private val tools: List<ChatTool> by lazy {
        listOf(
            // ===== 设备/系统信息（无需权限）=====
            DeviceInfoTool(),
            AppInfoTool(),
            ScreenInfoTool(),
            StorageInfoTool(),
            MemoryInfoTool(),
            BatteryInfoTool(),
            ClearCacheTool(),
            // ===== 蓝牙设备列表（Android 12+ 需授权）=====
            BluetoothDevicesTool(),
            // ===== 文件读写/搜索（公共存储需授权）=====
            ReadFileTool(),
            WriteFileTool(),
            SearchFilesTool(),
            // ===== 闹钟/定时器（SET_ALARM 普通权限）=====
            SetAlarmTool(),
            SetTimerTool(),
            QueryNextAlarmTool(),
            // ===== 联系人（需授权）=====
            ContactsTool(),
            // ===== 剪贴板 / 拨号（无需权限）=====
            ReadClipboardTool(),
            WriteClipboardTool(),
            DialPhoneTool()
        )
    }

    /** 生成 OpenAI tools 参数列表（JSONArray），Agent 模式请求时附带 */
    fun buildToolsSchema(): JSONArray = JSONArray().apply {
        tools.forEach { tool ->
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parametersSchema)
                })
            })
        }
    }

    /** 是否存在该名称的工具 */
    fun has(name: String): Boolean = tools.any { it.name == name }

    /**
     * 执行工具（挂起）：参数解析失败/工具不存在返回错误提示（仍作为 tool 结果回传给模型）。
     * 文件/IO 类操作运行在 IO 线程；权限弹窗由工具内部经 ToolPermissionManager 切主线程处理。
     */
    suspend fun execute(context: Context, name: String, arguments: String): String {
        val tool = tools.firstOrNull { it.name == name }
        if (tool == null) {
            AppLog.w(AppLog.TAG_TOOL, "tool not found: $name")
            return "错误：工具 $name 不存在"
        }
        return try {
            val args = if (arguments.isBlank()) JSONObject() else JSONObject(arguments)
            withContext(Dispatchers.IO) {
                tool.execute(context, args)
            }
        } catch (e: Exception) {
            AppLog.e(AppLog.TAG_TOOL, "execute tool $name error", e)
            "工具执行异常：${e.message}"
        }
    }
}
