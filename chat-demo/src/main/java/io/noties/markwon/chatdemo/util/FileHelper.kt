package io.noties.markwon.chatdemo.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import io.noties.markwon.chatdemo.bean.Attachment
import java.io.File

/**
 * 消息附件选择与本地化处理（chat-demo 精简版）
 *
 * 移植自 doslas MsgFileHelper，裁剪了：
 * - CameraX 拍照 / Matisse 相册 SDK → 统一用系统 ACTION_GET_CONTENT 选图片
 * - 腾讯云 COS 上传 → 无云端，直接复制到应用缓存目录保证 localPath 稳定
 * - 权限申请（系统选择器无需存储权限）
 *
 * 结果：数据库只保存 localPath 文件地址；发送时由 DeepSeekAIService
 * 直接读取本地图片 Base64 / 文件文本内容传给模型。
 */
object FileHelper {

    const val REQUEST_CODE_IMAGE = 1101
    const val REQUEST_CODE_FILE = 1102

    /** 图片大小上限：10MB */
    const val MAX_IMAGE_SIZE = 10 * 1024 * 1024L

    /** 文件大小上限：10MB */
    const val MAX_FILE_SIZE = 10 * 1024 * 1024L

    // ==================== 发起选择器 ====================

    /** 打开系统图片选择器（本地图片多选） */
    fun pickImage(activity: Activity, maxSelectable: Int = 9) {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, maxSelectable > 1)
        }
        activity.startActivityForResult(intent, REQUEST_CODE_IMAGE)
    }

    /** 打开系统文件选择器 */
    fun pickFile(activity: Activity) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        activity.startActivityForResult(intent, REQUEST_CODE_FILE)
    }

    // ==================== 处理回调 ====================

    /**
     * 统一处理 onActivityResult，返回校验通过的 [Attachment] 列表。
     * 全链路异常兜底：任何系统 provider 异常都不冒泡（防闪退）
     */
    fun onActivityResult(
        context: Context,
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ): List<Attachment> {
        if (resultCode != Activity.RESULT_OK || data == null) return emptyList()
        return try {
            val result = when (requestCode) {
                REQUEST_CODE_IMAGE -> {
                    if (data.clipData != null) {
                        (0 until data.clipData!!.itemCount)
                            .mapNotNull { handleImage(context, data.clipData!!.getItemAt(it).uri) }
                    } else {
                        data.data?.let { listOfNotNull(handleImage(context, it)) } ?: emptyList()
                    }
                }
                REQUEST_CODE_FILE -> data.data?.let { listOfNotNull(handleFile(context, it)) } ?: emptyList()
                else -> emptyList()
            }
            AppLog.i(AppLog.TAG_ATTACH, "picker result: req=$requestCode, picked=${result.size} 个附件")
            result
        } catch (e: Exception) {
            AppLog.e(AppLog.TAG_ATTACH, "onActivityResult error", e)
            toast(context, "附件读取失败，请重试")
            emptyList()
        }
    }

    // ==================== 内部处理 ====================

    private fun handleImage(context: Context, uri: Uri): Attachment? = try {
        handleImageInner(context, uri)
    } catch (e: Exception) {
        AppLog.w(AppLog.TAG_ATTACH, "handleImage error: ${e.message}")
        toast(context, "图片读取失败")
        null
    }

    private fun handleImageInner(context: Context, uri: Uri): Attachment? {
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        if (!mime.startsWith("image/")) {
            toast(context, "不支持的图片格式")
            return null
        }
        val size = querySize(context, uri)
        if (size > MAX_IMAGE_SIZE) {
            toast(context, "图片不能超过 ${MAX_IMAGE_SIZE / (1024 * 1024)}MB")
            return null
        }
        val name = queryName(context, uri) ?: "image_${System.currentTimeMillis()}.jpg"
        val localPath = copyToCache(context, uri, name) ?: run {
            toast(context, "图片读取失败")
            return null
        }
        val (width, height) = readImageSize(localPath)
        return Attachment(
            localPath = localPath,
            fileName = name,
            mimeType = mime,
            size = size,
            width = width,
            height = height
        )
    }

    private fun handleFile(context: Context, uri: Uri): Attachment? = try {
        handleFileInner(context, uri)
    } catch (e: Exception) {
        AppLog.w(AppLog.TAG_ATTACH, "handleFile error: ${e.message}")
        toast(context, "文件读取失败")
        null
    }

    private fun handleFileInner(context: Context, uri: Uri): Attachment? {
        val name = queryName(context, uri)
        if (name.isNullOrBlank()) {
            toast(context, "无法读取文件信息")
            return null
        }
        val size = querySize(context, uri)
        if (size > MAX_FILE_SIZE) {
            toast(context, "文件不能超过 ${MAX_FILE_SIZE / (1024 * 1024)}MB")
            return null
        }
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val localPath = copyToCache(context, uri, name) ?: run {
            toast(context, "文件读取失败")
            return null
        }
        return Attachment(
            localPath = localPath,
            fileName = name,
            mimeType = mime,
            size = size
        )
    }

    /**
     * 把 Uri 内容复制到应用缓存目录（cacheDir/ai_chat_files/），
     * 保证数据库存储的 localPath 在下次启动后依然稳定可读
     */
    private fun copyToCache(context: Context, uri: Uri, name: String): String? {
        return try {
            val startNs = System.nanoTime()
            val dir = File(context.cacheDir, "ai_chat_files").apply { mkdirs() }
            val target = File(dir, "${System.currentTimeMillis()}_${File(name).name}")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            AppLog.d(AppLog.TAG_ATTACH, "copyToCache: $name -> ${target.name}, ${AppLog.sizeText(target.length())}, ${AppLog.elapsedMs(startNs)}")
            target.absolutePath
        } catch (e: Exception) {
            AppLog.w(AppLog.TAG_ATTACH, "copyToCache error: $name, ${e.message}")
            null
        }
    }

    // ==================== Uri 信息查询 ====================

    private fun queryName(context: Context, uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
        return name
    }

    private fun querySize(context: Context, uri: Uri): Long {
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !cursor.isNull(idx)) size = cursor.getLong(idx)
            }
        }
        return size
    }

    // ==================== 位图处理 ====================

    /** 读取图片宽高（bounds 模式，不加载像素） */
    fun readImageSize(path: String?): Pair<Int, Int> {
        if (path.isNullOrEmpty()) return 0 to 0
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            options.outWidth to options.outHeight
        } catch (e: Exception) {
            0 to 0
        }
    }

    /** 按最大边长采样解码缩略图（避免大图 OOM） */
    fun decodeThumb(path: String?, maxEdge: Int): Bitmap? {
        if (path.isNullOrEmpty()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxEdge || bounds.outHeight / (sample * 2) >= maxEdge) {
                sample *= 2
            }
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) {
            null
        }
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}