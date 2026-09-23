package io.noties.markwon.chatdemo.room

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * 会话 DAO
 *
 * Room 2.2.6 + kapt 不支持 suspend，异步由 Repository 层 withContext(IO) 包装
 */
@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(session: SessionEntity)

    @Update
    fun update(session: SessionEntity)

    @Delete
    fun delete(session: SessionEntity)

    @Query("SELECT * FROM ai_chat_session ORDER BY timestamp DESC")
    fun getAll(): List<SessionEntity>

    @Query("SELECT * FROM ai_chat_session WHERE sessionId = :sessionId")
    fun getById(sessionId: String): SessionEntity?

    @Query("DELETE FROM ai_chat_session WHERE sessionId = :sessionId")
    fun deleteById(sessionId: String)
}