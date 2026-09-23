package io.noties.markwon.chatdemo.bean

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.security.MessageDigest
import java.util.UUID

/**
 * AI聊天消息数据类
 *
 * 一条用户消息 = 文本内容(content) + N个附件(attachments)
 * 附件统一依附在文本消息中，不再拆分为独立的消息条目
 *
 * 移植自 doslas 的 AIChatMessage，裁剪了用户/服务器同步字段。
 */
@Parcelize
data class AIChatMessage(
    var id: String = "",
    var sessionId: String = "",
    var role: String = ROLE_USER,
    var type: String = TYPE_TEXT,
    /// 文本内容
    var content: String = "",
    /// 依附的附件列表（图片/文件，localPath 指向本地文件）
    var attachments: List<Attachment> = emptyList(),
    /// 单附件字段（保留兼容，仅用于旧消息加载）
    var attachmentUrl: String = "",
    var attachmentName: String = "",
    var attachmentType: String = "",
    var timestamp: Long = System.currentTimeMillis(),
    var status: Int = STATUS_SENDING,
    /// AI回复消息对应的用户消息ID（仅AI消息使用）
    var fatherMsgId: String = "",
    /** 内容是否被拦截（保留兼容字段） */
    var filtered: Boolean = false,
    /** 附件是否被拦截（保留兼容字段） */
    var fileFiltered: Boolean = false,
    /** 思考过程（reasoning_content，AI 消息；DB v2 持久化，重进会话可恢复展示） */
    var thinking: String = "",
    /** Agent 工具步骤 JSON（AgentStep 列表序列化；DB v2 持久化） */
    var agentStepsJson: String = "[]",
    /** 渲染轨迹 JSON（ChatTrailSegment 序列化；DB v3 持久化，重进会话恢复「思考/工具」交错顺序） */
    var trailJson: String = "",
) : Parcelable {

    init {
        // localMsgId 为空时自动生成 UUID
        if (id.isEmpty()) id = UUID.randomUUID().toString()
    }

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_AI = "assistant"

        const val TYPE_TEXT = "text"
        const val TYPE_IMAGE = "image"
        const val TYPE_FILE = "file"
        const val TYPE_VOICE = "voice"

        const val STATUS_SENDING = 0
        const val STATUS_SUCCESS = 1
        const val STATUS_FAILED = 2
    }

    fun isUser(): Boolean = role == ROLE_USER
    fun isAi(): Boolean = role == ROLE_AI
    fun isText(): Boolean = type == TYPE_TEXT
    fun isImage(): Boolean = type == TYPE_IMAGE
    fun isFile(): Boolean = type == TYPE_FILE

    /**
     * 计算确定性 ID：MD5(content+role+时间秒)
     */
    fun computeLocalId(): String {
        val timeSeconds = timestamp / 1000
        return md5("${content}_${role}_$timeSeconds")
    }

    /**
     * 生成字符串的 MD5 哈希（用于确定性ID生成）
     */
    private fun md5(input: String): String {
        return MessageDigest.getInstance("MD5")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}