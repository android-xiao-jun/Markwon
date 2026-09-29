package io.noties.markwon.chatdemo.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 图片保存到系统相册（Agent 工具产出的图片，如生成的二维码）
 *
 * - Android 10（Q）及以上：MediaStore 写入 `Pictures/chat-demo`，无需存储权限；
 * - Android 9 及以下：直接写入公共 `Pictures/chat-demo` 并通知媒体库扫描，
 *   需要 WRITE_EXTERNAL_STORAGE 权限（调用方负责先申请）。
 */
object GallerySaver {

    /** 相册内归类目录（Pictures/chat-demo） */
    private const val ALBUM_DIR = "chat-demo"

    /**
     * 保存图片文件到系统相册。
     * @return 成功返回相册展示路径（如 Pictures/chat-demo/xx.png）；图片不存在或写入失败返回 null
     */
    fun saveToGallery(context: Context, imagePath: String): String? {
        val src = File(imagePath)
        if (!src.isFile) return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, src)
            } else {
                saveViaPublicPictures(context, src)
            }
        } catch (e: Exception) {
            AppLog.w(AppLog.TAG_ATTACH, "saveToGallery failed: ${e.message}")
            null
        }
    }

    /** Android 10+：MediaStore 两阶段写入（IS_PENDING，避免相册读到半成品） */
    private fun saveViaMediaStore(context: Context, src: File): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, src.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + File.separator + ALBUM_DIR
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { output ->
                src.inputStream().use { input -> input.copyTo(output) }
            } ?: return null
        } catch (e: Exception) {
            AppLog.w(AppLog.TAG_ATTACH, "saveViaMediaStore failed: ${e.message}")
            // 写入失败清理占位记录，避免相册出现 0 字节条目
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return albumDisplayPath(src.name)
    }

    /** Android 9-：直写公共 Pictures 目录 + 媒体库扫描（重名自动加时间戳前缀） */
    private fun saveViaPublicPictures(context: Context, src: File): String? {
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            ALBUM_DIR
        )
        if (!dir.exists() && !dir.mkdirs()) return null
        var target = File(dir, src.name)
        if (target.exists()) {
            target = File(dir, "${System.currentTimeMillis()}_${src.name}")
        }
        src.copyTo(target, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("image/png"), null)
        return albumDisplayPath(target.name)
    }

    private fun albumDisplayPath(fileName: String): String =
        Environment.DIRECTORY_PICTURES + File.separator + ALBUM_DIR + File.separator + fileName
}