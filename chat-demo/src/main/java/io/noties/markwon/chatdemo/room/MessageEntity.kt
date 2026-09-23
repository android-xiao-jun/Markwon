package io.noties.markwon.chatdemo.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import io.noties.markwon.chatdemo.bean.AIChatMessage
import io.noties.markwon.chatdemo.bean.Attachment

/**
 * 消息表 Entity
 *
 * attachments 以 JSON 存储（[Converters] 负责 List<Attachment> ↔ String）
 */
@Entity(
    tableName = "ai_chat_message",
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["sessionId", "timestamp"])
    ]
)
data class MessageEntity(
    @PrimaryKey
    var id: String = "",
    var sessionId: String = "",
    var role: String = "",
    var type: String = "",
    var content: String = "",
    /** 附件列表 JSON，由 [Converters] 负责 List<Attachment> ↔ String 转换 */
    var attachmentsJson: String = "[]",
    @ColumnInfo(defaultValue = "")
    var attachmentUrl: String = "",
    @ColumnInfo(defaultValue = "")
    var attachmentName: String = "",
    @ColumnInfo(defaultValue = "")
    var attachmentType: String = "",
    var timestamp: Long = System.currentTimeMillis(),
    var status: Int = 0,
    @ColumnInfo(defaultValue = "")
    var fatherMsgId: String = "",
    /** 内容是否被拦截 */
    @ColumnInfo(defaultValue = "0")
    var filtered: Boolean = false,
    /** 附件是否被拦截 */
    @ColumnInfo(defaultValue = "0")
    var fileFiltered: Boolean = false,
    /** 思考过程（DB v2 新增，Migration 1→2） */
    @ColumnInfo(defaultValue = "")
    var thinking: String = "",
    /** Agent 工具步骤 JSON（DB v2 新增，Migration 1→2） */
    @ColumnInfo(defaultValue = "[]")
    var agentStepsJson: String = "[]",
    /** 渲染轨迹 JSON（DB v3 新增，Migration 2→3） */
    @ColumnInfo(defaultValue = "")
    var trailJson: String = "",
) {

    @Ignore
    constructor() : this("", "", "", "", "", "", "", "", "", 0, 0, "", false, false, "", "[]", "")

    fun toMessage(attachments: List<Attachment>): AIChatMessage {
        return AIChatMessage(
            id = id,
            sessionId = sessionId,
            role = role,
            type = type,
            content = content,
            attachments = attachments,
            attachmentUrl = attachmentUrl,
            attachmentName = attachmentName,
            attachmentType = attachmentType,
            timestamp = timestamp,
            status = status,
            fatherMsgId = fatherMsgId,
            filtered = filtered,
            fileFiltered = fileFiltered,
            thinking = thinking,
            agentStepsJson = agentStepsJson,
            trailJson = trailJson,
        )
    }

    companion object {
        fun fromMessage(message: AIChatMessage, attachmentsJson: String): MessageEntity {
            return MessageEntity(
                id = message.id,
                sessionId = message.sessionId,
                role = message.role,
                type = message.type,
                content = message.content,
                attachmentsJson = attachmentsJson,
                attachmentUrl = message.attachmentUrl,
                attachmentName = message.attachmentName,
                attachmentType = message.attachmentType,
                timestamp = message.timestamp,
                status = message.status,
                fatherMsgId = message.fatherMsgId,
                filtered = message.filtered,
                fileFiltered = message.fileFiltered,
                thinking = message.thinking,
                agentStepsJson = message.agentStepsJson,
                trailJson = message.trailJson,
            )
        }
    }
}