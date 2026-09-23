package io.noties.markwon.chatdemo.service

import io.noties.markwon.chatdemo.service.IAIService.AIStreamEvent
import io.noties.markwon.chatdemo.util.AppLog
import org.json.JSONObject

/**
 * SSE 数据解析器（OpenAI /chat/completions 流）
 *
 * 单一职责：把 SSE 文本行解析为 [AIStreamEvent] 事件序列。
 * 无网络、无状态扩散（每次请求 new 一个实例）。
 *
 * 解析能力：
 * - `reasoning_content`（思考过程，deepseek-reasoner）→ [AIStreamEvent.ThinkingChunk]
 * - `content` → [AIStreamEvent.Chunk]
 * - `tool_calls` 增量（index/id/function.name/function.arguments 分片）→ 内部累积，
 *   流结束 [finish] 时聚合为 [AIStreamEvent.ToolCall]
 * - `finish_reason`（tool_calls/stop）→ 记录进 [AIStreamEvent.Done]
 */
class SSEStreamParser {

    companion object {
        private const val TAG = "SSEStreamParser"
        private const val PREFIX = "data: "
        private const val DONE = "[DONE]"
    }

    /** 工具调用增量聚合（index -> 累积器） */
    private val pendingToolCalls = HashMap<Int, ToolCallAccumulator>()

    /** 本轮 finish_reason（tool_calls / stop） */
    private var finishReason: String? = null

    // ==================== 解析统计（finish 汇总输出，避免高频刷屏） ====================

    private var chunkCount = 0
    private var chunkChars = 0
    private var thinkingCount = 0
    private var thinkingChars = 0
    private var parseErrors = 0

    /**
     * 解析一行 SSE；返回该行产生的事件列表（可能为空）。
     * 忽略非 `data:` 行、空行、心跳与 `[DONE]`（由调用方判断结束）。
     */
    fun acceptLine(line: String): List<AIStreamEvent> {
        if (!line.startsWith(PREFIX)) return emptyList()
        val payload = line.removePrefix(PREFIX).trim()
        if (payload.isEmpty() || payload == DONE) return emptyList()

        return try {
            val json = JSONObject(payload)
            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) return emptyList()
            val choice = choices.getJSONObject(0)

            if (!choice.isNull("finish_reason")) {
                finishReason = choice.optString("finish_reason")
                AppLog.d(AppLog.TAG_PARSE, "finish_reason=$finishReason")
            }

            val delta = choice.optJSONObject("delta") ?: return emptyList()
            val events = mutableListOf<AIStreamEvent>()

            // 1. 思考过程
            if (!delta.isNull("reasoning_content")) {
                val reasoning = delta.optString("reasoning_content", "")
                if (reasoning.isNotEmpty()) {
                    thinkingCount++
                    thinkingChars += reasoning.length
                    events.add(AIStreamEvent.ThinkingChunk(reasoning))
                }
            }

            // 2. 最终文本
            if (!delta.isNull("content")) {
                val content = delta.optString("content", "")
                if (content.isNotEmpty()) {
                    chunkCount++
                    chunkChars += content.length
                    events.add(AIStreamEvent.Chunk(content))
                }
            }

            // 3. 工具调用增量
            val toolCalls = delta.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                for (i in 0 until toolCalls.length()) {
                    accumulate(toolCalls.getJSONObject(i))
                }
            }

            events
        } catch (e: Exception) {
            parseErrors++
            AppLog.w(AppLog.TAG_PARSE, "Parse SSE chunk error: ${e.message}")
            emptyList()
        }
    }

    /** 累积一条 tool_calls 增量分片 */
    private fun accumulate(tc: JSONObject) {
        val index = tc.optInt("index")
        val acc = pendingToolCalls.getOrPut(index) { ToolCallAccumulator() }
        if (!tc.isNull("id")) acc.id += tc.optString("id", "")
        val fn = tc.optJSONObject("function")
        if (fn != null) {
            if (!fn.isNull("name")) acc.name += fn.optString("name", "")
            if (!fn.isNull("arguments")) acc.arguments += fn.optString("arguments", "")
        }
    }

    /**
     * 流结束收尾：把已聚合完成的工具调用逐条作为事件发出，
     * 并追加记录 finish_reason 的 [AIStreamEvent.Done]。
     */
    fun finish(): List<AIStreamEvent> {
        val events = mutableListOf<AIStreamEvent>()
        pendingToolCalls.values.forEach { acc ->
            if (acc.id.isNotEmpty() && acc.name.isNotEmpty()) {
                events.add(
                    AIStreamEvent.ToolCall(
                        AgentToolCall(
                            id = acc.id,
                            name = acc.name,
                            arguments = acc.arguments
                        )
                    )
                )
            }
        }
        events.add(AIStreamEvent.Done(finishReason ?: ""))
        AppLog.i(AppLog.TAG_PARSE,
            "parse summary: chunks=$chunkCount($chunkChars chars), thinking=$thinkingCount($thinkingChars chars), " +
                "toolCalls=${events.count { it is AIStreamEvent.ToolCall }}, parseErrors=$parseErrors, finish=${finishReason ?: "none"}")
        return events
    }

    /** 工具调用增量累积器 */
    private class ToolCallAccumulator {
        var id: String = ""
        var name: String = ""
        var arguments: String = ""
    }
}