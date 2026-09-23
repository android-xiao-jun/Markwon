package io.noties.markwon.chatdemo.bean

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 历史会话摘要
 */
@Parcelize
data class SessionSummary(
    var sessionId: String = "",
    var title: String = "",
    var lastMessage: String = "",
    var timestamp: Long = System.currentTimeMillis(),
    var messageCount: Int = 0,
    var isSelected: Boolean = false
) : Parcelable