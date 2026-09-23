package io.noties.markwon.chatdemo.tool

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 剪贴板读取：读取系统剪贴板文本（前台读取，无需权限）
 *
 * Android 10+ 仅允许焦点应用读取剪贴板，工具执行时应用在前台，符合限制。
 * 剪贴板服务调用统一切主线程。
 */
class ReadClipboardTool : ChatTool {

    override val name: String = "read_clipboard"

    override val description: String =
        "读取系统剪贴板中的文本内容。当用户说「看看我复制的内容」「我刚才复制了什么」「帮我把这段发出去」时使用。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("max_chars", JSONObject().apply {
                put("type", "integer")
                put("description", "返回内容最大字符数，默认 2000，超出截断")
            })
        })
    }

    override suspend fun execute(context: Context, args: JSONObject): String {
        val text = withContext(Dispatchers.Main) {
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
            } catch (e: Exception) {
                null
            }
        }
        if (text.isNullOrBlank()) return "剪贴板为空（或内容不是文本）"
        val maxChars = args.optInt("max_chars", 2000).coerceIn(100, 10000)
        val truncated = text.length > maxChars
        val body = if (truncated) text.take(maxChars) else text
        return buildString {
            append("剪贴板内容（")
            append(text.length).append(" 字符")
            if (truncated) append("，已截断至 ").append(maxChars)
            append("）：\n\n```\n").append(body).append("\n```")
        }
    }
}

/**
 * 剪贴板写入：将文本复制到系统剪贴板（无需权限）
 */
class WriteClipboardTool : ChatTool {

    override val name: String = "write_clipboard"

    override val description: String =
        "将指定文本复制到系统剪贴板。当用户要求「帮我复制这段」「把这个号码复制一下」时使用。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("text", JSONObject().apply {
                put("type", "string")
                put("description", "要复制的文本内容")
            })
        })
        put("required", JSONArray().put("text"))
    }

    override suspend fun execute(context: Context, args: JSONObject): String {
        val text = args.optString("text")
        if (text.isEmpty()) return "错误：缺少参数 text（要复制的文本）"
        return withContext(Dispatchers.Main) {
            // Kotlin 1.4 下 setPrimaryClip 调用与属性赋值均报 Val cannot be reassigned，走 Java 兼容层
            val ok = io.noties.markwon.chatdemo.util.ClipboardCompat.setText(
                context, "chat_demo", text
            )
            if (ok) {
                "已复制到剪贴板（${text.length} 字符），用户可直接粘贴使用。"
            } else {
                "错误：写入剪贴板失败"
            }
        }
    }
}

/**
 * 拨号：打开系统拨号界面并填充号码（ACTION_DIAL 不自动拨出，无需通话权限）
 */
class DialPhoneTool : ChatTool {

    override val name: String = "dial_phone"

    override val description: String =
        "打开系统拨号界面并自动填入电话号码（仅打开拨号盘，不会自动拨出，用户需自己按拨号键确认）。当用户要求拨打/呼叫某号码时使用。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("phone", JSONObject().apply {
                put("type", "string")
                put("description", "电话号码，支持 +86、-、空格等常见格式")
            })
        })
        put("required", JSONArray().put("phone"))
    }

    override suspend fun execute(context: Context, args: JSONObject): String {
        val raw = args.optString("phone").trim()
        if (raw.isEmpty()) return "错误：缺少参数 phone（电话号码）"
        val normalized = raw.replace(" ", "").replace("-", "")
        if (!Regex("^\\+?\\d{3,15}$").matches(normalized)) {
            return "错误：电话号码格式不正确（$raw），请提供 3-15 位数字（可带 + 国际区号）"
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_DIAL).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            // setData 返回 Intent（builder 风格），Kotlin 无法合成 var 属性，必须显式调用
            setData(android.net.Uri.parse("tel:" + normalized))
        }
        return try {
            context.startActivity(intent)
            "已打开拨号界面并填入号码 $normalized，等待用户确认拨出。"
        } catch (e: android.content.ActivityNotFoundException) {
            "错误：设备未找到拨号应用"
        }
    }
}
