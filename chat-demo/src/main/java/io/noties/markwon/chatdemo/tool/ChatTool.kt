package io.noties.markwon.chatdemo.tool

import android.content.Context
import org.json.JSONObject

/**
 * 手机端可执行工具（Agent 能力）
 *
 * 每个工具向模型暴露：
 * - [name] 唯一名称（模型调用时使用）
 * - [description] 用途描述（模型判断何时调用的依据）
 * - [parametersSchema] JSON Schema 参数定义（OpenAI tool 协议）
 *
 * 执行：接收从模型解析出的参数 JSON，返回给模型的字符串结果。
 * 所有工具必须是安全的只读/可逆操作。
 *
 * execute 为挂起函数：需要运行时权限的工具可通过 [ToolPermissionManager.ensure]
 * 挂起等待用户在弹窗中授权后再继续执行。
 */
interface ChatTool {

    val name: String

    val description: String

    val parametersSchema: JSONObject

    /** 执行工具（挂起），返回给模型的文本结果（建议 Markdown 结构化） */
    suspend fun execute(context: Context, args: JSONObject): String
}