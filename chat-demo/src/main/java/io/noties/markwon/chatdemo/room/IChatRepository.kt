package io.noties.markwon.chatdemo.room

import io.noties.markwon.chatdemo.bean.AIChatMessage
import io.noties.markwon.chatdemo.bean.SessionSummary

/**
 * AI聊天数据仓库接口
 *
 * 接口隔离设计，方便替换底层数据库实现（如 Room → Realm / SQLite 原生 / 远端同步）
 */
interface IChatRepository {

    /** 保存/更新消息（同一 id 覆盖写入） */
    suspend fun saveMessage(message: AIChatMessage)

    /** 加载会话的全部消息（按时间升序） */
    suspend fun loadMessages(sessionId: String): List<AIChatMessage>

    /** 删除单条消息 */
    suspend fun deleteMessage(messageId: String)

    /** 清空会话消息 */
    suspend fun clearSessionMessages(sessionId: String)

    /** 删除会话中所有 status==SENDING 且 role==assistant 的消息（清理未完成的AI回复） */
    suspend fun deleteAssistantSending(sessionId: String)

    /** 获取会话最新一条消息 */
    suspend fun getLastMessage(sessionId: String): AIChatMessage?

    /** 保存/更新会话摘要 */
    suspend fun saveSession(session: SessionSummary)

    /** 加载所有会话（按时间降序） */
    suspend fun loadSessions(): List<SessionSummary>

    /** 删除会话（同时删除其消息） */
    suspend fun deleteSession(sessionId: String)

    /** 根据 ID 获取会话 */
    suspend fun getSession(sessionId: String): SessionSummary?
}