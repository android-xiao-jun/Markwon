package io.noties.markwon.chatdemo.tool

import android.Manifest
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset

/**
 * 读文件：读取手机上的文本文件内容
 *
 * 路径规则（见 [ToolPaths]）：
 * - 相对路径 → 应用工作区（无需权限）；
 * - 沙箱绝对路径 → 直接读取（无需权限）；
 * - 公共存储绝对路径（如 /sdcard/Download/a.txt）→ 先申请存储读取权限。
 */
class ReadFileTool : ChatTool {

    override val name: String = "read_file"

    override val description: String =
        "读取手机上的文本文件内容并返回。支持相对路径（基于应用工作区，无需权限）和绝对路径（公共存储如 /sdcard/Download/xx.txt 需用户授权存储权限）。大文件会自动截断。当用户要求查看、读取某个文件内容时使用。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().apply {
                put("type", "string")
                put("description", "文件路径：相对路径基于应用工作区；绝对路径如 /sdcard/Download/notes.txt")
            })
            put("max_chars", JSONObject().apply {
                put("type", "integer")
                put("description", "返回内容最大字符数，默认 8000，上限 20000，超出部分截断")
            })
        })
        put("required", org.json.JSONArray().put("path"))
    }

    /** 单次读取的文件大小硬上限（过大直接拒绝，避免内存与 token 爆炸） */
    private val maxFileBytes = 1L * 1024 * 1024

    override suspend fun execute(context: Context, args: JSONObject): String {
        val rawPath = args.optString("path").trim()
        if (rawPath.isEmpty()) return "错误：缺少参数 path（文件路径）"

        val file = ToolPaths.resolve(context, rawPath)

        // 沙箱外公共路径 → 先申请存储读取权限
        if (!ToolPaths.isSandbox(context, file)) {
            if (!ToolPaths.legacyStorageUsable()) return ToolPaths.legacyStorageBlockedTip()
            val granted = ToolPermissionManager.ensure(
                context, Manifest.permission.READ_EXTERNAL_STORAGE, name,
                "读取公共存储中的文件：$rawPath（仅读取该文件内容，不修改）"
            )
            if (!granted) return "错误：用户未授权存储读取权限，无法读取 $rawPath。请向用户说明用途后重试。"
        }

        if (!file.exists()) return "错误：文件不存在（$file）"
        if (file.isDirectory) return "错误：该路径是目录不是文件（$file），如需查看目录内容可用 search_files"
        val size = file.length()
        if (size > maxFileBytes) {
            return "错误：文件过大（${size / 1024} KB，上限 1MB），请提示用户指定更小的文本文件"
        }

        val content = try {
            file.readText(Charset.forName("UTF-8"))
        } catch (e: Exception) {
            return "错误：文件读取失败（${e.message}），可能是编码不支持或无权限"
        }
        if (content.contains('\u0000')) {
            return "错误：该文件疑似二进制文件（非文本），无法以文本形式返回"
        }

        val maxChars = args.optInt("max_chars", 8000).coerceIn(100, 20000)
        val truncated = content.length > maxChars
        val body = if (truncated) content.take(maxChars) else content

        return buildString {
            append("文件：`").append(file.absolutePath).append("`（")
            append(size).append(" 字节")
            if (truncated) append("，已截断至 ").append(maxChars).append(" 字符，共 ").append(content.length)
            append("）\n\n```\n").append(body).append("\n```")
        }
    }
}
