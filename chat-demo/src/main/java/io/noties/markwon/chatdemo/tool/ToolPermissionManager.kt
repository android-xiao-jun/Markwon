package io.noties.markwon.chatdemo.tool

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.noties.markwon.chatdemo.util.AppLog

/**
 * 工具运行时权限管理
 *
 * 需要运行时权限（联系人/存储/蓝牙连接等）的工具在执行前先经 [ensure] 检查：
 * - 已授权 → 直接放行；
 * - 未授权 → 通过前台 Activity 实现的 [Delegate] 弹出说明对话框，向用户说明
 *   「哪个工具、为什么需要该权限」，用户确认后拉起系统授权弹窗；
 * - 工具协程挂起等待用户选择，授权成功继续执行，拒绝则返回错误信息给模型。
 */
object ToolPermissionManager {

    private const val TAG = "ToolPermissionManager"

    /**
     * 权限请求委托：由持有弹窗环境的前台 Activity 实现（[io.noties.markwon.chatdemo.ui.ChatActivity]）。
     * 实现需自行处理：说明弹窗 → 系统授权 → 永久拒绝引导系统设置 → 挂起返回结果。
     */
    interface Delegate {
        /**
         * 弹出授权说明对话框并请求系统权限（主线程调用）。
         * 挂起直至用户在系统弹窗/设置页作出选择。
         * @return true=已授权；false=用户拒绝
         */
        suspend fun requestPermission(permission: String, toolName: String, reason: String): Boolean
    }

    @Volatile
    private var delegate: Delegate? = null

    /** Activity onCreate 时挂载 */
    fun attach(delegate: Delegate) {
        this.delegate = delegate
    }

    /** Activity onDestroy 时解除（未决请求由 Activity 自行恢复为拒绝） */
    fun detach() {
        delegate = null
    }

    /**
     * 确保权限已授权，未授权则弹窗向用户申请。
     *
     * @param permission 权限名（如 android.permission.READ_CONTACTS）
     * @param toolName   请求权限的工具名（弹窗展示用）
     * @param reason     权限用途说明（弹窗展示用）
     * @return true=已授权；false=拒绝 / 无前台 Activity 委托
     */
    suspend fun ensure(
        context: Context,
        permission: String,
        toolName: String,
        reason: String
    ): Boolean {
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        val d = delegate
        if (d == null) {
            AppLog.w(AppLog.TAG_PERM, "no delegate attached, permission $permission treated as denied")
            return false
        }
        return withContext(Dispatchers.Main) {
            try {
                d.requestPermission(permission, toolName, reason)
            } catch (e: Exception) {
                AppLog.e(AppLog.TAG_PERM, "requestPermission error: ${e.message}")
                false
            }
        }
    }
}
