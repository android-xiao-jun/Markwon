package io.noties.markwon.chatdemo.tool

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 清空应用缓存（当前可操作的安全工具）
 *
 * 仅清理应用自己的缓存目录（cacheDir 与 externalCacheDir），
 * 不触碰用户数据（files/databases/shared_prefs），可安全反复执行。
 */
class ClearCacheTool : ChatTool {

    override val name: String = "clear_app_cache"

    override val description: String = "清空本应用的临时缓存文件（仅缓存目录，不影响聊天记录等用户数据），返回释放的空间大小。当用户要求清理缓存、释放空间时使用。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(context: Context, args: JSONObject): String {
        val cacheDir = context.cacheDir
        val externalCacheDir = try {
            context.externalCacheDir
        } catch (e: Exception) {
            null
        }

        var freed = 0L
        cacheDir?.let { dir ->
            if (dir.exists()) {
                freed += deleteRecursive(dir)
            }
        }
        externalCacheDir?.let { dir ->
            if (dir.exists()) {
                freed += deleteRecursive(dir)
            }
        }

        val freedText = if (freed > 0) {
            formatSize(freed)
        } else {
            "0 B"
        }
        return "已清空应用缓存，释放空间：$freedText。注意：附件缓存文件（图片/文件副本）也可能被清除。"
    }

    /** 递归删除目录下全部内容（保留目录本身），返回删除的字节数 */
    private fun deleteRecursive(file: File): Long {
        if (!file.exists()) return 0L
        return if (file.isDirectory) {
            var freed = 0L
            file.listFiles()?.forEach { child -> freed += deleteRecursive(child) }
            // 删除空目录本身
            file.delete()
            freed
        } else {
            val size = file.length()
            if (file.delete()) size else 0L
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var idx = 0
        while (value >= 1024 && idx < units.lastIndex) {
            value /= 1024
            idx++
        }
        return String.format(Locale.US, "%.1f %s", value, units[idx])
    }
}