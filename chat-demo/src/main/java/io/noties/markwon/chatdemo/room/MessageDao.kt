package io.noties.markwon.chatdemo.room

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * 消息 DAO
 *
 * Room 2.2.6 + kapt 不支持 suspend，异步由 Repository 层 withContext(IO) 包装
 */
@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(message: MessageEntity)

    @Update
    fun update(message: MessageEntity)

    @Delete
    fun delete(message: MessageEntity)

    @Query("DELETE FROM ai_chat_message WHERE id = :messageId")
    fun deleteById(messageId: String)

    @Query("DELETE FROM ai_chat_message WHERE sessionId = :sessionId")
    fun deleteBySession(sessionId: String)

    /** 获取会话最新一条消息（按时间降序） */
    @Query("SELECT * FROM ai_chat_message WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT 1")
    fun getLastMessage(sessionId: String): MessageEntity?

    @Query("SELECT * FROM ai_chat_message WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getBySession(sessionId: String): List<MessageEntity>

    /** 删除指定会话中所有 status == 0 (SENDING) 且 role == assistant 的消息（清理未完成的AI回复） */
    @Query("DELETE FROM ai_chat_message WHERE sessionId = :sessionId AND status = 0 AND role = 'assistant'")
    fun deleteAssistantSending(sessionId: String)
}