package io.noties.markwon.chatdemo.tool

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.IOException

/**
 * 文件类工具共享的路径规则
 *
 * 两类可访问区域：
 * 1. 应用沙箱（filesDir / cacheDir / externalFilesDir / externalCacheDir）：
 *    无需任何权限即可读写，模型给的相对路径默认落在工作区（externalFilesDir）；
 * 2. 公共存储（/sdcard、/storage/emulated/0 等绝对路径）：
 *    需运行时 READ/WRITE_EXTERNAL_STORAGE 权限（由工具经 ToolPermissionManager 申请）。
 */
object ToolPaths {

    /** 应用工作区根目录：模型相对路径的基准（优先外部，无外部存储回退内部） */
    fun workspaceRoot(context: Context): File =
        context.getExternalFilesDir(null) ?: context.filesDir

    /** 全部应用沙箱目录（canonical 路径包含判定） */
    fun sandboxDirs(context: Context): List<File> {
        val externalFilesRoot = context.getExternalFilesDir(null)?.parentFile
        return listOfNotNull(
            context.filesDir,
            context.cacheDir,
            externalFilesRoot,          // 覆盖 externalFilesDir 各子目录
            context.externalCacheDir
        )
    }

    /** 解析用户/模型给定路径：绝对路径原样，相对路径基于工作区 */
    fun resolve(context: Context, raw: String): File {
        val trimmed = raw.trim()
        val file = File(trimmed)
        return if (file.isAbsolute) file else File(workspaceRoot(context), trimmed)
    }

    /** 是否位于应用沙箱内（无需权限） */
    fun isSandbox(context: Context, file: File): Boolean = sandboxDirs(context).any { isUnder(file, it) }

    /** 是否位于公共存储（需存储权限） */
    fun isPublicStorage(file: File): Boolean {
        val externalRoot = Environment.getExternalStorageDirectory().absoluteFile
        return isUnder(file, externalRoot)
    }

    /** file 是否位于 dir 目录内（或等于 dir），canonical 化以消除 ../ 等路径穿越 */
    fun isUnder(file: File, dir: File): Boolean = try {
        val filePath = file.canonicalPath
        val dirPath = dir.canonicalPath
        filePath == dirPath || filePath.startsWith(dirPath + File.separator)
    } catch (e: IOException) {
        false
    }

    /**
     * 公共存储 File API 直读可用性检查（targetSdk 升级预留检查点）：
     * - targetSdk ≤ 29：requestLegacyExternalStorage 生效，授权后 File API 可用；
     * - targetSdk ≥ 30 + Android 11+：需 Environment.isExternalStorageLegacy() 为 true
     *   （本应用当前 targetSdk 29 返回 true）；未来升级 targetSdk 后此处会返回 false，
     *   文件工具应给出明确提示并引导迁移 SAF/MediaStore 实现。
     */
    fun legacyStorageUsable(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= 30) {
            @Suppress("DEPRECATION")
            Environment.isExternalStorageLegacy()
        } else {
            true
        }
    }

    /** 公共存储不可用时的统一提示文案（工具返回给模型） */
    fun legacyStorageBlockedTip(): String =
        "错误：当前应用不支持直接访问公共存储（targetSdk 升级后 File API 受限）。" +
            "请建议用户使用应用工作区（相对路径）方式，或由用户通过系统文件选择器手动操作。"
}
