package io.noties.markwon.chatdemo.service

import io.noties.markwon.chatdemo.service.IAIService.AIStreamEvent
import io.noties.markwon.chatdemo.util.AppLog
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SSE 流式请求客户端（请求过程）
 *
 * 单一职责：封装 OkHttp 请求生命周期——构造请求、发起、逐行读取、
 * 通过 [SSEStreamParser] 解析并回调事件、释放资源、支持取消。
 *
 * 不关心业务（消息/模型/工具），由调用方传入 [RequestBody]、baseUrl 与鉴权信息；
 * baseUrl 每次请求传入，支持运行时切换网关。
 *
 * 并发安全：活跃请求以列表登记（[activeCalls]），支持同时多路流式请求；
 * [cancel] 取消全部在途请求（幂等）。
 */
class OkHttpStreamClient(
    private val client: OkHttpClient
) {

    companion object {
        private const val CHAT_COMPLETIONS_PATH = "/chat/completions"
    }

    /** 在途请求登记（并发安全，完成后自动移除） */
    private val activeCalls = CopyOnWriteArrayList<Call>()

    /**
     * 发起流式请求
     *
     * @param baseUrl      网关 baseUrl（如 https://api.deepseek.com，运行时可变）
     * @param requestBody  已构造的 JSON 请求体
     * @param accessToken  Bearer Token（此层不读取配置，由门面传入）
     * @param onEvent      每解析出一条事件回调（非空）
     * @param onError      网络/HTTP 错误回调（传入可展示的错误文案）
     * @param onComplete   流结束后回调（无论成败都会触发，用于外层关闭 Flow）
     */
    fun start(
        baseUrl: String,
        requestBody: RequestBody,
        accessToken: String,
        onEvent: (AIStreamEvent) -> Unit,
        onError: (String) -> Unit,
        onComplete: () -> Unit
    ) {
        val url = baseUrl.trimEnd('/') + CHAT_COMPLETIONS_PATH
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(requestBody)
            .build()

        val call = client.newCall(request)
        activeCalls.add(call)
        AppLog.d(AppLog.TAG_STREAM, "request start: $url, inFlight=${activeCalls.size}")

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activeCalls.remove(call)
                AppLog.e(AppLog.TAG_STREAM, "network failure: ${e.message}", e)
                onError(e.message ?: "网络请求失败")
                onComplete()
            }

            override fun onResponse(call: Call, response: Response) {
                activeCalls.remove(call)
                // okhttp 3.9 中 Kotlin 属性语法访问不到 body/code（classpath 限制），显式调用方法
                if (!response.isSuccessful) {
                    val errorBody = response.body()?.string() ?: "未知错误"
                    AppLog.e(AppLog.TAG_STREAM, "HTTP ${response.code()}: $errorBody")
                    onError("HTTP ${response.code()}: $errorBody")
                    response.close()
                    onComplete()
                    return
                }

                AppLog.i(AppLog.TAG_STREAM, "response ok (HTTP ${response.code()}), reading stream...")
                onEvent(AIStreamEvent.Start())

                val startNs = System.nanoTime()
                var lineCount = 0
                var eventCount = 0
                val parser = SSEStreamParser()
                val source = response.body()!!.source()
                try {
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: continue
                        lineCount++
                        val events = parser.acceptLine(line)
                        eventCount += events.size
                        events.forEach { onEvent(it) }
                    }
                } catch (e: Exception) {
                    AppLog.e(AppLog.TAG_STREAM, "read stream error: ${e.message}", e)
                    onError(e.message ?: "读取流失败")
                } finally {
                    // 流结束：发出聚合完成的工具调用与 Done
                    val tail = parser.finish()
                    eventCount += tail.size
                    tail.forEach { onEvent(it) }
                    response.close()
                    AppLog.i(AppLog.TAG_STREAM, "stream end: $lineCount lines, $eventCount events, ${AppLog.elapsedMs(startNs)}")
                }
                onComplete()
            }
        })
    }

    /** 取消全部在途请求（幂等） */
    fun cancel() {
        val calls = activeCalls.toList()
        if (calls.isNotEmpty()) {
            AppLog.i(AppLog.TAG_STREAM, "cancel ${calls.size} in-flight call(s)")
        }
        calls.forEach { it.cancel() }
        activeCalls.clear()
    }
}