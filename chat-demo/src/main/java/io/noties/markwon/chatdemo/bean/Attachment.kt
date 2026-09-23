package io.noties.markwon.chatdemo.bean

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 附件数据类（图片/文件）
 *
 * 说明：chat-demo 不接入 COS 云存储，一律直接使用 [localPath] 指向的本地文件，
 * 发送时读取本地文件/图片内容传给模型；数据库仅保存 localPath 文件地址。
 */
@Parcelize
data class Attachment(
    var localPath: String? = "",
    var remoteUrl: String? = "",
    var fileName: String? = "",
    var mimeType: String? = "",
    var size: Long? = 0,
    var width: Int? = 0,
    var height: Int? = 0,
    var duration: Int? = 0
) : Parcelable {

    fun isImage(): Boolean = mimeType?.startsWith("image/") ?: false
    fun isFile(): Boolean = localPath != "add"

    /**
     * 用于展示的图片地址：本地优先（chat-demo 场景 localPath 始终存在）
     */
    val displayUrl: String?
        get() = localPath?.takeIf { it.isNotEmpty() } ?: remoteUrl

    /**
     * 用于 UI 展示的文件类型后缀（大写）
     */
    val displayType: String
        get() {
            val name = fileName ?: return "unknown"
            val ext = name.substringAfterLast('.', "")
            return if (ext.isBlank() || ext == name) {
                "unknown"
            } else {
                ext.toUpperCase()
            }
        }
}