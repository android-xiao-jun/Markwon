package io.noties.markwon.chatdemo.room

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import io.noties.markwon.chatdemo.bean.SessionSummary

/**
 * 会话表 Entity
 */
@Entity(
    tableName = "ai_chat_session",
    indices = [Index(value = ["timestamp"])]
)
data class SessionEntity(
    @PrimaryKey
    var sessionId: String = "",
    var title: String = "",
    var lastMessage: String = "",
    var timestamp: Long = System.currentTimeMillis(),
    var messageCount: Int = 0,
) {

    @Ignore
    constructor() : this("", "", "", 0, 0)

    fun toSessionSummary(): SessionSummary {
        return SessionSummary(
            sessionId = sessionId,
            title = title,
            lastMessage = lastMessage,
            timestamp = timestamp,
            messageCount = messageCount,
        )
    }

    companion object {
        fun fromSessionSummary(summary: SessionSummary): SessionEntity {
            return SessionEntity(
                sessionId = summary.sessionId,
                title = summary.title,
                lastMessage = summary.lastMessage,
                timestamp = summary.timestamp,
                messageCount = summary.messageCount,
            )
        }
    }
}