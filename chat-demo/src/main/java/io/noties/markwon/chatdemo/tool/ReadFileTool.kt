package io.noties.markwon.chatdemo.tool

import android.Manifest
import android.content.Context
import io.noties.markwon.chatdemo.util.AppLog
import io.noties.markwon.chatdemo.util.FileHelper
import io.noties.markwon.chatdemo.util.FileTextExtractor
import org.json.JSONArray
import org.json.JSONObject

/**
 * 读文件：读取手机上的文件内容并返回
 *
 * 按格式自动分派（见 [FileTextExtractor]）：
 * - 文本类（txt/md/json/html/csv/代码等）→ UTF-8 直接读取（非法序列回退 GB18030）
 * - PDF → 提取文本层（PDFBox-Android，最多前 20 页）
 * - Word/Excel/PPT（.docx/.xlsx/.pptx）→ OOXML 解析出文本 / 表格
 * - zip/jar/apk/epub → 条目清单 + 内嵌小型文本条目内容
 * - 图片 → 说明无法以文本读取（图片走聊天附件 Base64 多模态通道）
 * - 旧版 Office（.doc/.xls/.ppt）等二进制 → 明确的不支持说明与替代建议
 *
 * 路径规则（见 [ToolPaths]）：
 * - 相对路径 → 应用工作区（无需权限）；
 * - 沙箱绝对路径 → 直接读取（无需权限）；
 * - 公共存储绝对路径（如 /sdcard/Download/a.txt）→ 先申请存储读取权限。
 */
class ReadFileTool : ChatTool {

    override val name: String = "read_file"

    override val description: String =
        "读取手机上的文件内容并返回。文本类文件（txt/md/json/xml/html/csv/代码等）直接读取；" +
            "PDF 提取文本（前 20 页）；Word/Excel/PPT（.docx/.xlsx/.pptx）与压缩包（zip/jar/epub 等）自动解析内容，返回文本或条目清单；" +
            "图片与旧版 Office（.doc/.xls/.ppt）无法解析，会返回说明。支持相对路径（基于应用工作区，无需权限）和绝对路径（公共存储如 /sdcard/Download/xx.txt 需用户授权存储权限）。大文件与内容会自动截断。当用户要求查看、读取某个文件内容时使用。"

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
        put("required", JSONArray().put("path"))
    }

    /** 文本类文件大小硬上限（全文读入内存） */
    private val maxTextFileBytes = 1L * 1024 * 1024

    /** 容器类文件（PDF/Office/压缩包）大小上限（流式解析，可放宽） */
    private val maxContainerFileBytes = 10L * 1024 * 1024

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

        val ext = FileTextExtractor.extOf(file.name)
        val size = file.length()

        // 图片：无法以文本形式返回（多模态 Base64 通道见 OpenAIMessageBuilder）
        if (ext in FileTextExtractor.IMAGE_EXTS) {
            val (w, h) = FileHelper.readImageSize(file.absolutePath)
            val dim = if (w > 0 && h > 0) "${w}x${h}，" else ""
            return "文件：`${file.absolutePath}`（图片 ${dim}${size} 字节）。图片无法以文本形式读取：" +
                "请提示用户通过聊天框以图片附件方式发送（由支持视觉的模型查看）"
        }

        // 大小上限：文本类 1MB；容器类（待解析格式）放宽到 10MB
        val isText = FileTextExtractor.isTextExt(ext)
        val limit = if (isText) maxTextFileBytes else maxContainerFileBytes
        if (size > limit) {
            return "错误：文件过大（${size / 1024} KB，上限 ${limit / 1024} KB），请提示用户指定更小的文件"
        }

        val maxChars = args.optInt("max_chars", 8000).coerceIn(100, 20000)
        val startNs = System.nanoTime()
        val result = FileTextExtractor.extract(file, maxChars)
        AppLog.d(
            AppLog.TAG_TOOL,
            "read_file: ${file.name}, ext=$ext, size=$size, result=${result::class.java.simpleName}, ${AppLog.elapsedMs(startNs)}"
        )

        return when (result) {
            is FileTextExtractor.Result.Text -> buildString {
                append("文件：`").append(file.absolutePath).append("`（").append(size).append(" 字节")
                if (result.truncated) append("，已截断至 ").append(maxChars).append(" 字符")
                append("）\n\n```\n").append(result.content).append("\n```")
            }
            is FileTextExtractor.Result.Unsupported -> "文件：`${file.absolutePath}`（$size 字节）：${result.reason}"
            is FileTextExtractor.Result.Failed -> "文件：`${file.absolutePath}` 读取失败：${result.reason}"
        }
    }
}