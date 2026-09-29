package io.noties.markwon.chatdemo.tool

/**
 * 工具运行期会话状态（由 ChatViewModel 在发起 Agent 请求前同步）
 *
 * 用于「工具需要参考用户当前消息」的场景：例如 decode_qr_code 未指定图片路径时，
 * 兜底识别用户最近一条消息中发送的图片。状态不持久化，进程存活期内有效。
 */
object ToolSessionState {

    /** 用户最近一条带图片的消息中的图片本地路径（按选择顺序） */
    @Volatile
    var latestUserImagePaths: List<String> = emptyList()
}