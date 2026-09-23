package io.noties.markwon.chatdemo.tool

import android.content.Context
import android.os.Environment
import android.os.StatFs
import org.json.JSONObject

/** 存储空间查询：内部存储/外部存储总容量与可用空间 */
class StorageInfoTool : ChatTool {

    override val name: String = "query_storage_info"

    override val description: String = "查询设备的存储空间情况，包括内部存储和应用外置存储的总容量、可用空间。当用户询问存储空间、剩余空间、容量时使用。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(context: Context, args: JSONObject): String {
        val internal = statOf(Environment.getDataDirectory().absolutePath)
        val externalAvailable = try {
            Environment.getExternalStorageDirectory().let { statOf(it.absolutePath) }
        } catch (e: Exception) {
            null
        }
        val sb = StringBuilder()
        sb.append("| 存储 | 总容量 | 可用 |\n| --- | --- | --- |\n")
        sb.append("| 内部存储 | ${formatMb(internal.total)} | ${formatMb(internal.available)} |\n")
        externalAvailable?.let {
            sb.append("| 外置存储 | ${formatMb(it.total)} | ${formatMb(it.available)} |\n")
        }
        return sb.toString().trim()
    }

    private fun statOf(path: String): Storage {
        val statFs = StatFs(path)
        // totalBytes/availableBytes 需 API 18，低版本回退 blockSize 计算
        val total: Long
        val available: Long
        if (android.os.Build.VERSION.SDK_INT >= 18) {
            total = statFs.totalBytes
            available = statFs.availableBytes
        } else {
            @Suppress("DEPRECATION")
            total = statFs.blockCountLong * statFs.blockSizeLong
            @Suppress("DEPRECATION")
            available = statFs.availableBlocksLong * statFs.blockSizeLong
        }
        return Storage(total, available)
    }

    private fun formatMb(bytes: Long): String =
        if (bytes > 0) "%.1f".format(bytes / (1024.0 * 1024.0 * 1024.0)) + " GB" else "不可用"

    private data class Storage(val total: Long, val available: Long)
}