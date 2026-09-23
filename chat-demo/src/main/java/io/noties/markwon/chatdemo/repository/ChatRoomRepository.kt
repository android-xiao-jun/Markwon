package io.noties.markwon.chatdemo.repository

import io.noties.markwon.chatdemo.bean.AIChatMessage
import io.noties.markwon.chatdemo.bean.Attachment
import io.noties.markwon.chatdemo.bean.SessionSummary
import io.noties.markwon.chatdemo.room.IChatRepository
import io.noties.markwon.chatdemo.room.MessageDao
import io.noties.markwon.chatdemo.room.MessageEntity
import io.noties.markwon.chatdemo.room.SessionDao
import io.noties.markwon.chatdemo.room.SessionEntity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 基于 Room 的聊天数据仓库实现
 *
 * 实现 [IChatRepository] 接口。DAO 层方法为同步调用
 * （Room 2.2.6 + kapt 不支持 suspend DAO），本仓库通过
 * withContext(Dispatchers.IO) 将所有数据库操作切到 IO 线程。
 */
class ChatRoomRepository(
    private val messageDao: MessageDao,
    private val sessionDao: SessionDao,
) : IChatRepository {

    private val gson = Gson()

    // ==================== 消息操作 ====================

    override suspend fun saveMessage(message: AIChatMessage) = withContext(Dispatchers.IO) {
        val attachmentsJson = gson.toJson(message.attachments)
        messageDao.insert(MessageEntity.fromMessage(message, attachmentsJson))
    }

    override suspend fun loadMessages(sessionId: String): List<AIChatMessage> = withContext(Dispatchers.IO) {
        messageDao.getBySession(sessionId).map { entity ->
            entity.toMessage(parseAttachments(entity.attachmentsJson))
        }
    }

    override suspend fun deleteMessage(messageId: String) = withContext(Dispatchers.IO) {
        messageDao.deleteById(messageId)
    }

    override suspend fun clearSessionMessages(sessionId: String) = withContext(Dispatchers.IO) {
        messageDao.deleteBySession(sessionId)
    }

    override suspend fun deleteAssistantSending(sessionId: String) = withContext(Dispatchers.IO) {
        messageDao.deleteAssistantSending(sessionId)
    }

    override suspend fun getLastMessage(sessionId: String): AIChatMessage? = withContext(Dispatchers.IO) {
        messageDao.getLastMessage(sessionId)?.let { entity ->
            entity.toMessage(parseAttachments(entity.attachmentsJson))
        }
    }

    // ==================== 会话操作 ====================

    override suspend fun saveSession(session: SessionSummary) = withContext(Dispatchers.IO) {
        sessionDao.insert(SessionEntity.fromSessionSummary(session))
    }

    override suspend fun loadSessions(): List<SessionSummary> = withContext(Dispatchers.IO) {
        sessionDao.getAll().map { it.toSessionSummary() }
    }

    override suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        sessionDao.deleteById(sessionId)
        messageDao.deleteBySession(sessionId)
    }

    override suspend fun getSession(sessionId: String): SessionSummary? = withContext(Dispatchers.IO) {
        sessionDao.getById(sessionId)?.toSessionSummary()
    }

    // ==================== 内部工具 ====================

    private fun parseAttachments(json: String): List<Attachment> {
        if (json.isEmpty()) return emptyList()
        return try {
            val type = object : TypeToken<List<Attachment>>() {}.type
            gson.fromJson<List<Attachment>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}