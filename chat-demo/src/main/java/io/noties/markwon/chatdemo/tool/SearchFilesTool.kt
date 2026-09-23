package io.noties.markwon.chatdemo.tool

import android.Manifest
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 文件搜索：按文件名关键词递归搜索
 *
 * 默认搜索应用工作区（无需权限）；指定公共目录（如 /sdcard/Download）时
 * 先申请存储读取权限。keyword 为空时退化为列出目录第一层内容（当作列目录用）。
 */
class SearchFilesTool : ChatTool {

    override val name: String = "search_files"

    override val description: String =
        "按文件名关键词递归搜索手机文件（不区分大小写，匹配文件名包含关键词），返回匹配项的绝对路径与大小。默认搜索应用工作区（无需权限）；可通过 dir 参数指定其他目录（如 /sdcard/Download 或 /sdcard，需用户授权存储权限）。关键词留空时列出目录第一层内容。当用户要求找文件、搜文件、看某个文件夹里有什么时使用。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("keyword", JSONObject().apply {
                put("type", "string")
                put("description", "文件名关键词（不区分大小写），留空则列出目录第一层")
            })
            put("dir", JSONObject().apply {
                put("type", "string")
                put("description", "搜索起始目录，默认应用工作区；可传公共目录绝对路径如 /sdcard/Download")
            })
            put("max_results", JSONObject().apply {
                put("type", "integer")
                put("description", "最大返回条数，默认 50，上限 100")
            })
        })
        put("required", JSONArray())
    }

    /** 递归深度上限（避免误入超大目录树） */
    private val maxDepth = 8

    /** 跳过的系统目录（其他应用沙箱/系统目录，读取无意义且量大） */
    private val skipDirs = setOf("Android", ".thumbnails", ".trashed")

    override suspend fun execute(context: Context, args: JSONObject): String {
        val keyword = args.optString("keyword").trim().toLowerCase(Locale.US)
        val maxResults = args.optInt("max_results", 50).coerceIn(1, 100)

        val baseDir = if (args.optString("dir").isNotBlank()) {
            ToolPaths.resolve(context, args.optString("dir"))
        } else {
            ToolPaths.workspaceRoot(context)
        }

        // 沙箱外公共目录 → 先申请存储读取权限
        if (!ToolPaths.isSandbox(context, baseDir)) {
            if (!ToolPaths.legacyStorageUsable()) return ToolPaths.legacyStorageBlockedTip()
            val granted = ToolPermissionManager.ensure(
                context, Manifest.permission.READ_EXTERNAL_STORAGE, name,
                "搜索公共存储目录：${baseDir.absolutePath}（仅按文件名查找，不读取文件内容）"
            )
            if (!granted) return "错误：用户未授权存储读取权限，无法搜索 ${baseDir.absolutePath}。可改为搜索应用工作区。"
        }

        if (!baseDir.exists()) return "错误：目录不存在（${baseDir.absolutePath}）"
        if (!baseDir.isDirectory) return "错误：该路径不是目录（${baseDir.absolutePath}）"

        // 空关键词 → 列目录第一层（列目录能力）
        if (keyword.isEmpty()) {
            val children = baseDir.listFiles()?.sortedBy { it.name } ?: return "目录为空或不可读：${baseDir.absolutePath}"
            if (children.isEmpty()) return "目录为空：${baseDir.absolutePath}"
            val shown = children.take(maxResults)
            return buildString {
                append("目录 `").append(baseDir.absolutePath).append("` 第一层内容（")
                append(children.size).append(" 项")
                if (children.size > maxResults) append("，仅显示前 ").append(maxResults)
                append("）：\n\n")
                shown.forEach {
                    append(if (it.isDirectory) "📁 " else "📄 ")
                    append(it.name)
                    if (!it.isDirectory) append("（${it.length()} B）")
                    append("\n")
                }
            }
        }

        // 递归关键词搜索（达上限立即终止遍历，避免扫描超大目录树）
        val results = mutableListOf<File>()
        var truncated = false
        try {
            val sequence = baseDir.walkTopDown()
                .maxDepth(maxDepth)
                .onFail { _, _ -> /* 单目录不可读（权限/损坏）跳过 */ }
                .filter { file ->
                    // 跳过根目录自身与其他应用沙箱
                    if (file == baseDir) return@filter false
                    val rel = file.toRelativeString(baseDir)
                    if (rel.split(File.separatorChar).any { it in skipDirs }) return@filter false
                    file.name.toLowerCase(Locale.US).contains(keyword)
                }
            for (file in sequence) {
                if (results.size >= maxResults) {
                    truncated = true
                    break
                }
                results.add(file)
            }
        } catch (e: Exception) {
            return "错误：搜索过程异常（${e.message}）"
        }

        if (results.isEmpty()) {
            return "未找到文件名包含「${args.optString("keyword").trim()}」的文件（搜索目录：${baseDir.absolutePath}，深度上限 $maxDepth）"
        }
        return buildString {
            append("在 `").append(baseDir.absolutePath).append("` 中找到 ").append(results.size).append(" 项")
            if (truncated) append("（已达上限，可能还有更多）")
            append("：\n\n")
            results.forEach {
                append(if (it.isDirectory) "📁 " else "📄 ")
                append("`").append(it.absolutePath).append("`")
                if (!it.isDirectory) append("（${formatSize(it.length())}）")
                append("\n")
            }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
