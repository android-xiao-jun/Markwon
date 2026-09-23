package io.noties.markwon.chatdemo.service

import io.noties.markwon.chatdemo.bean.AIChatMessage
import io.noties.markwon.chatdemo.service.IAIService.AIStreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * DeepSeek AI 服务（门面：编排底层组件，无业务细节）
 *
 * 职责分层：
 * - [OpenAIMessageBuilder]：消息上下文 → 请求体（系统指令/历史/多模态/工具定义组装）
 * - [SSEStreamParser]：SSE 数据解析（思考过程 / 文本 / 工具调用增量 / finish_reason）
 * - [OkHttpStreamClient]：HTTP 请求过程（发起 / 逐行读取 / 取消 / 资源释放）
 *
 * 本类只做两件事：
 * 1. 按入口选择构建器（普通聊天 / Agent 多轮），生成请求体；
 * 2. 把 OkHttpStreamClient 的回调式输出桥接为 [Flow]，并转发取消。
 *
 * baseUrl / apiKey / model 每次请求实时读取 [AIConfig]，运行时切换即时生效。
 */
class DeepSeekAIService : IAIService {

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val streamClient: OkHttpStreamClient = OkHttpStreamClient(okHttpClient)

    // ==================== 普通聊天 ====================

    override fun sendMessageStream(messages: List<AIChatMessage>): Flow<AIStreamEvent> =
        stream { OpenAIMessageBuilder.build(messages, AIConfig.model, tools = null) }

    // ==================== Agent 流式（工具调用） ====================

    override fun agentStream(messages: List<AgentMessage>, tools: JSONArray?): Flow<AIStreamEvent> =
        stream { OpenAIMessageBuilder.buildAgent(messages, AIConfig.model, tools) }

    // ==================== 取消 ====================

    override fun cancelRequest() {
        streamClient.cancel()
    }

    // ==================== 内部：回调 → Flow ====================

    /**
     * 发起请求并把回调式事件桥接为 Flow。
     * 请求体构造（含附件 Base64/文件读取）延迟到 flowOn(IO) 线程执行，避免阻塞主线程。
     */
    private fun stream(bodyBuilder: () -> RequestBody): Flow<AIStreamEvent> =
        callbackFlow {
            val requestBody = try {
                bodyBuilder()
            } catch (e: Exception) {
                offer(AIStreamEvent.Error("请求构造失败：${e.message}"))
                close()
                awaitClose { }
                return@callbackFlow
            }
            streamClient.start(
                baseUrl = AIConfig.baseUrl,
                requestBody = requestBody,
                accessToken = AIConfig.apiKey,
                onEvent = { event ->
                    // kotlinx-coroutines 1.4.2 无 trySend（1.5+ API），用 offer
                    offer(event)
                },
                onError = { message ->
                    offer(AIStreamEvent.Error(message))
                    close()
                },
                onComplete = {
                    close()
                }
            )
            awaitClose {
                streamClient.cancel()
            }
        }
            // 无界缓冲：coroutines 1.4.2 的 offer 在默认缓冲(64)满时会静默丢事件，
            // 高频 SSE chunk 或丢失 Done/Error 事件会导致上层状态卡死，必须放大缓冲
            .buffer(Channel.UNLIMITED)
            .flowOn(Dispatchers.IO)
}