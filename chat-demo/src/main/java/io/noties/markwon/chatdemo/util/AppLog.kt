package io.noties.markwon.chatdemo.util

import android.content.Context
import android.util.Log
import io.noties.markwon.chatdemo.BuildConfig

/**
 * 统一日志工具（全模块唯一日志出口）
 *
 * - 级别：[Level.V] ~ [Level.E]，全局最低级别运行时可调（设置弹窗 Spinner）并持久化；
 * - 分区 TAG：统一前缀 `ChatDemo-<分区>`，logcat 按链路过滤（如 `ChatDemo-Stream`）；
 * - 默认级别：debug 包 D，release 包 W；
 * - 打点原则：关键节点逐条打 I/D；高频流式 chunk 仅 V 级（默认不打），节点结束打汇总 I，
 *   避免刷屏；异常路径打 W/E。
 */
object AppLog {

    /** 日志级别（priority 与 android.util.Log 对齐） */
    enum class Level(val priority: Int) {
        V(Log.VERBOSE),
        D(Log.DEBUG),
        I(Log.INFO),
        W(Log.WARN),
        E(Log.ERROR);

        override fun toString(): String = name
    }

    // ==================== 分区 TAG ====================

    /** 启动 / 初始化 */
    const val TAG_INIT = "Init"
    /** 数据库 / 历史记录加载 */
    const val TAG_DB = "DB"
    /** 附件选择与本地化 */
    const val TAG_ATTACH = "Attach"
    /** 消息发送 */
    const val TAG_SEND = "Send"
    /** 请求参数构建 */
    const val TAG_BUILD = "Build"
    /** 网络流式响应 */
    const val TAG_STREAM = "Stream"
    /** 思考过程（reasoning） */
    const val TAG_THINK = "Think"
    /** SSE 数据解析 */
    const val TAG_PARSE = "Parse"
    /** UI 渲染 */
    const val TAG_RENDER = "Render"
    /** Agent 循环 */
    const val TAG_AGENT = "Agent"
    /** 工具执行 */
    const val TAG_TOOL = "Tool"
    /** 权限申请 */
    const val TAG_PERM = "Perm"

    private const val PREFIX = "ChatDemo-"
    private const val PREFS = "chat_demo_config"
    private const val KEY_LOG_LEVEL = "log_level"

    @Volatile
    var minLevel: Level = if (BuildConfig.DEBUG) Level.V else Level.W
        private set

    // ==================== 级别管理 ====================

    /** App 启动恢复持久化的日志级别 */
    fun init(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LOG_LEVEL, null)
        if (saved != null) {
            Level.values().firstOrNull { it.name == saved }?.let { minLevel = it }
        }
        i(TAG_INIT, "AppLog ready, minLevel=$minLevel (debug=${BuildConfig.DEBUG})")
    }

    /** 运行时切换级别（即时生效 + 持久化） */
    fun setLevel(context: Context, level: Level) {
        minLevel = level
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LOG_LEVEL, level.name).apply()
        i(TAG_INIT, "log level -> $level")
    }

    // ==================== 打印 ====================

    fun v(tag: String, msg: String) {
        if (Level.V.priority >= minLevel.priority) Log.v(PREFIX + tag, msg)
    }

    fun d(tag: String, msg: String) {
        if (Level.D.priority >= minLevel.priority) Log.d(PREFIX + tag, msg)
    }

    fun i(tag: String, msg: String) {
        if (Level.I.priority >= minLevel.priority) Log.i(PREFIX + tag, msg)
    }

    fun w(tag: String, msg: String) {
        if (Level.W.priority >= minLevel.priority) Log.w(PREFIX + tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (Level.E.priority >= minLevel.priority) Log.e(PREFIX + tag, msg, tr)
    }

    // ==================== 便捷工具 ====================

    /** 耗时测量（纳秒 → 可读文本） */
    fun elapsedMs(startNs: Long): String = "${(System.nanoTime() - startNs) / 1_000_000}ms"

    /** 字节数可读化 */
    fun sizeText(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.1fKB".format(bytes / 1024.0)
        else -> "${bytes}B"
    }
}