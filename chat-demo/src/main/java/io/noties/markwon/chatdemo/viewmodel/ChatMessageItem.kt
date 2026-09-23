package io.noties.markwon.chatdemo.viewmodel

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import io.noties.markwon.chatdemo.bean.AgentStep
import io.noties.markwon.chatdemo.bean.AIChatMessage
import io.noties.markwon.chatdemo.util.AppLog

/**
 * 渲染轨迹段：一段思考 / 一组工具步骤，按模型输出顺序交替出现
 *
 * Agent 多轮中每轮产出「思考(reasoning) → 工具(tool_calls)」，为保持展示顺序与模型一致，
 * 不再把全部思考合并成一块、全部工具合并成一块，而是按轮次生成交替的 [Thinking] / [Tools] 段。
 */
sealed class ChatTrailSegment {
    /** 一段思考过程（reasoning_content 全文，流式追加） */
    class Thinking(var text: String = "") : ChatTrailSegment()

    /** 一组工具调用步骤（同轮内的工具执行，按调用顺序） */
    class Tools(val steps: MutableList<AgentStep> = mutableListOf()) : ChatTrailSegment()
}

/**
 * 消息列表项包装（手写 RecyclerView Adapter 直接使用）
 *
 * - [content] 文本内容：流式 chunk 到达时在 ViewModel 里累加
 * - [trail] 有序渲染轨迹：[ChatTrailSegment.Thinking]（思考段）与
 *   [ChatTrailSegment.Tools]（工具步骤段）交替，始终保持模型输出顺序
 * - [thinking] 全部思考拼接文本（聚合自 trail，供日志/长度校验/旧字段同步使用）
 * - [loading] 是否思考占位（AI 气泡显示 ThinkingDotsView）
 * - [streaming] 是否正在流式输出
 * - [streamedLength] 已推送到视图的累计字符长度，用于计算增量 appendMarkdown
 */
class ChatMessageItem(val message: AIChatMessage) {

    var content: String = message.content

    /** 有序渲染轨迹（思考段 / 工具段交替，唯一事实来源） */
    val trail = mutableListOf<ChatTrailSegment>()

    /** 全部思考拼接文本（= trail 中所有 Thinking 段文本按顺序拼接） */
    val thinking: String
        get() = trail.joinToString("") { seg -> (seg as? ChatTrailSegment.Thinking)?.text.orEmpty() }

    /** 全部工具步骤聚合（= trail 中所有 Tools 段步骤按顺序拼接，供日志统计） */
    val agentSteps: List<AgentStep>
        get() = trail.flatMap { seg -> (seg as? ChatTrailSegment.Tools)?.steps.orEmpty() }

    var loading: Boolean = false

    var streaming: Boolean = false

    var streamedLength: Int = content.length

    /** 是否 Agent 模式发起（展示标识） */
    var isAgent: Boolean = false

    init {
        restoreTrail()
    }

    // ==================== 轨迹操作（ViewModel 流式写入） ====================

    /**
     * 追加一段思考片段：trail 尾部若是 Thinking 段则直接追加，
     * 否则（尾部是 Tools 段/空）新建一个 Thinking 段——天然按轮次分段。
     */
    fun trailAppendThinking(chunk: String) {
        val last = trail.lastOrNull()
        val seg = last as? ChatTrailSegment.Thinking
            ?: ChatTrailSegment.Thinking().also { trail.add(it) }
        seg.text += chunk
    }

    /**
     * 获取（或新建）尾部 Tools 段，用于追加同一轮的工具步骤。
     * 思考段之后的首次调用会新建 Tools 段，从而形成「思考 → 工具」的顺序分组。
     */
    fun trailTools(): ChatTrailSegment.Tools {
        val last = trail.lastOrNull()
        return last as? ChatTrailSegment.Tools
            ?: ChatTrailSegment.Tools().also { trail.add(it) }
    }

    /** 清空轨迹（重新生成时重置，并同步回 bean） */
    fun clearTrail() {
        trail.clear()
        message.thinking = ""
        message.agentStepsJson = "[]"
        message.trailJson = ""
    }

    // ==================== 持久化 ====================

    /** 恢复轨迹：trailJson 优先；旧数据按 thinking + agentStepsJson 降级为单段形态 */
    private fun restoreTrail() {
        val parsed = message.trailJson.takeIf { it.isNotBlank() }
            ?.let { parseTrail(it) }
        if (!parsed.isNullOrEmpty()) {
            trail.addAll(parsed)
            return
        }
        if (message.thinking.isNotBlank()) {
            trail.add(ChatTrailSegment.Thinking(message.thinking))
        }
        val steps = parseAgentSteps(message.agentStepsJson)
        if (steps.isNotEmpty()) {
            trail.add(ChatTrailSegment.Tools(steps.toMutableList()))
        }
    }

    /** 入库前同步：trail 序列化进 trailJson，thinking/agentStepsJson 兼容字段同步 */
    fun syncTrailToJson() {
        if (trail.isEmpty()) {
            message.thinking = ""
            message.agentStepsJson = "[]"
            message.trailJson = ""
            return
        }
        message.thinking = thinking
        message.agentStepsJson = Gson().toJson(agentSteps)
        message.trailJson = serializeTrail()
    }

    private fun serializeTrail(): String {
        val gson = Gson()
        val arr = JsonArray()
        trail.forEach { seg ->
            val obj = JsonObject()
            when (seg) {
                is ChatTrailSegment.Thinking -> {
                    obj.addProperty("type", "thinking")
                    obj.addProperty("text", seg.text)
                }
                is ChatTrailSegment.Tools -> {
                    obj.addProperty("type", "tools")
                    obj.add("steps", gson.toJsonTree(seg.steps))
                }
            }
            arr.add(obj)
        }
        return arr.toString()
    }

    private fun parseTrail(json: String): List<ChatTrailSegment>? {
        return try {
            val gson = Gson()
            val arr = JsonParser().parse(json).asJsonArray
            val list = mutableListOf<ChatTrailSegment>()
            arr.forEach { el ->
                val obj = el.asJsonObject
                when (obj.get("type").asString) {
                    "thinking" -> list.add(ChatTrailSegment.Thinking(obj.get("text").asString))
                    "tools" -> {
                        val type = object : TypeToken<List<AgentStep>>() {}.type
                        val steps = gson.fromJson<List<AgentStep>>(obj.get("steps"), type)
                        list.add(ChatTrailSegment.Tools(steps?.toMutableList() ?: mutableListOf()))
                    }
                }
            }
            list
        } catch (e: Exception) {
            AppLog.w(AppLog.TAG_RENDER, "parse trailJson failed: ${e.message}")
            null
        }
    }

    private fun parseAgentSteps(json: String): List<AgentStep> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            val type = object : TypeToken<List<AgentStep>>() {}.type
            Gson().fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            AppLog.w(AppLog.TAG_RENDER, "restore agentSteps failed: ${e.message}")
            emptyList()
        }
    }
}