package io.noties.markwon.chatdemo.service

import io.noties.markwon.chatdemo.BuildConfig
import io.noties.markwon.chatdemo.bean.AIChatMessage
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray

/** 模型运行时可切换配置。 */
object AIConfig {

    /** OpenAI 兼容接口 baseUrl（默认取 BuildConfig，可被 local.properties 的 ai.baseUrl 覆盖） */
    var baseUrl: String = BuildConfig.AI_BASE_URL

    /** API Key（默认取 BuildConfig，可被 local.properties 的 ai.apiKey 覆盖；为空时需在设置弹窗填写） */
    var apiKey: String = BuildConfig.AI_API_KEY

    /** 当前模型名（默认取 BuildConfig，可被 local.properties 的 ai.model 覆盖） */
    var model: String = BuildConfig.AI_MODEL
}

/**
 * Agent 上下文消息（OpenAI 协议消息的最小模型）
 *
 * 用于 Agent 多轮工具调用循环：每轮由 model 可能产生 `tool_calls`，
 * 我们执行工具后追加 `role=tool` 消息，拼出符合协议的多轮上下文。
 *
 * [content] 为 Object：既可以是纯文本 [String]，也可以是多模态 JSONArray
 * （用户带图片/文件附件时）。构建请求体时由服务直接写入。
 */
data class AgentMessage(
    val role: String,                  // user / assistant / tool / system
    val content: Any = "",             // String 或 JSONArray
    val toolCallId: String? = null,    // role=tool 时必填
    val toolCalls: List<AgentToolCall>? = null   // role=assistant 请求工具时分派
)

/** 模型请求的工具调用（增量聚合完成的单条） */
data class AgentToolCall(
    val id: String,
    val name: String,
    val arguments: String             // JSON 字符串（参数对象）
)

/** 工具执行结果（写入 role=tool 消息） */
data class ToolResultMsg(
    val toolCallId: String,
    val name: String,
    val content: String
)

/**
 * AI服务接口
 *
 * 抽象 AI 网络请求层，支持：
 * - [sendMessageStream] 普通聊天（无工具）
 * - [agentStream] Agent 模式（携带 tools schema + 多轮 tool 消息）
 * - 流式解析 reasoning_content（思考过程）与 tool_calls 增量
 */
interface IAIService {

    /** 普通流式聊天 */
    fun sendMessageStream(messages: List<AIChatMessage>): Flow<AIStreamEvent>

    /**
     * Agent 流式请求：携带 tools schema，返回的流可能包含
     * [AIStreamEvent.ToolCall]（模型请求调用工具）。调用方执行工具后，
     * 组装下一轮 [messages]（追加 assistant tool_calls + tool 结果）再调用本方法，形成循环。
     *
     * @param messages Agent 多轮上下文（[AgentMessage] 列表）
     * @param tools    待注册给模型的工具 schema（JSONArray 或 null 关闭）
     */
    fun agentStream(messages: List<AgentMessage>, tools: JSONArray?): Flow<AIStreamEvent>

    /** 取消当前请求 */
    fun cancelRequest()

    /**
     * AI流式事件
     */
    sealed class AIStreamEvent {
        /** 开始响应 */
        data class Start(val userMessageId: String = "") : AIStreamEvent()

        /** 思考过程片段（reasoning_content，deepseek-reasoner 返回） */
        data class ThinkingChunk(val content: String) : AIStreamEvent()

        /** 流式文本片段 */
        data class Chunk(val content: String) : AIStreamEvent()

        /** 模型请求调用工具 */
        data class ToolCall(val toolCall: AgentToolCall) : AIStreamEvent()

        /** 完成。finishReason 取值如 tool_calls / stop，供 Agent 循环判断是否需要继续工具调用 */
        data class Done(val finishReason: String = "") : AIStreamEvent()

        /** 错误 */
        data class Error(val message: String, val errorCode: Int = -1) : AIStreamEvent()
    }
}