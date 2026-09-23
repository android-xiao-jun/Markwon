package io.noties.markwon.chatdemo.tool

import android.Manifest
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset

/**
 * 写文件：将文本内容写入手机文件（新建/覆盖/追加）
 *
 * 路径规则（见 [ToolPaths]）：
 * - 相对路径 → 应用工作区（推荐，无需权限）；
 * - 沙箱绝对路径 → 直接写入（无需权限）；
 * - 公共存储绝对路径 → 先申请存储写入权限。
 * 自动创建父目录；默认覆盖已有文件，append=true 时追加。
 */
class WriteFileTool : ChatTool {

    override val name: String = "write_file"

    override val description: String =
        "将文本内容写入手机文件，支持新建、覆盖、追加。相对路径写入应用工作区（无需权限，推荐，例如 notes/todo.md）；绝对路径可写公共存储（如 /sdcard/Download/xx.txt，需用户授权存储权限）。自动创建父目录。覆盖已有文件必须传 overwrite=true 显式确认（默认拒绝），追加写入不受限。当用户要求保存内容、记笔记、生成文件时使用。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().apply {
                put("type", "string")
                put("description", "目标文件路径：相对路径基于应用工作区；绝对路径如 /sdcard/Download/notes.txt")
            })
            put("content", JSONObject().apply {
                put("type", "string")
                put("description", "要写入的文本内容")
            })
            put("append", JSONObject().apply {
                put("type", "boolean")
                put("description", "默认 false 覆盖写入；true 时在文件末尾追加")
            })
            put("overwrite", JSONObject().apply {
                put("type", "boolean")
                put("description", "目标文件已存在且非追加模式时，必须传 true 才允许覆盖旧文件；默认 false 拒绝覆盖")
            })
        })
        put("required", JSONArray().put("path").put("content"))
    }

    /** 单次写入内容硬上限 */
    private val maxContentBytes = 256L * 1024

    override suspend fun execute(context: Context, args: JSONObject): String {
        val rawPath = args.optString("path").trim()
        if (rawPath.isEmpty()) return "错误：缺少参数 path（文件路径）"
        val content = args.optString("content")
        if (content.isEmpty()) return "错误：缺少参数 content（写入内容）"
        if (content.toByteArray().size > maxContentBytes) {
            return "错误：内容过大（上限 256KB），请分次写入"
        }
        val append = args.optBoolean("append", false)

        val file = ToolPaths.resolve(context, rawPath)

        // 沙箱外公共路径 → 先申请存储写入权限
        if (!ToolPaths.isSandbox(context, file)) {
            if (!ToolPaths.legacyStorageUsable()) return ToolPaths.legacyStorageBlockedTip()
            val granted = ToolPermissionManager.ensure(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE, name,
                "写入公共存储文件：$rawPath（仅写入该文件，不读取其他数据）"
            )
            if (!granted) return "错误：用户未授权存储写入权限，无法写入 $rawPath。可改为使用工作区相对路径（无需权限）。"
        }

        if (file.exists() && file.isDirectory) {
            return "错误：目标路径是已存在的目录（$file），请换一个文件名"
        }

        // 覆盖已有文件必须显式确认（append 模式不受限）
        val overwrite = args.optBoolean("overwrite", false)
        if (file.exists() && !append && !overwrite) {
            return "错误：目标文件已存在（$file，${file.length()} 字节）。如确认要覆盖旧文件，请携带 overwrite=true 重新调用；" +
                "或改用 append=true 追加写入。"
        }

        return try {
            file.parentFile?.takeIf { !it.exists() }?.mkdirs()
            if (append) {
                file.appendText(content, Charset.forName("UTF-8"))
            } else {
                file.writeText(content, Charset.forName("UTF-8"))
            }
            val mode = when {
                append -> "追加"
                overwrite -> "覆盖"
                else -> "写入"
            }
            // 注意 ${mode} 花括号：中文也是合法标识符字符，裸 $mode文件 会被解析为未定义变量 mode文件
            "已${mode}文件：`${file.absolutePath}`（本次写入 ${content.toByteArray().size} 字节，文件现共 ${file.length()} 字节）" +
                if (mode == "覆盖") "。注意：旧文件内容已被替换。" else ""
        } catch (e: Exception) {
            "错误：文件写入失败（${e.message}），可能是路径无权限或存储已满"
        }
    }
}
